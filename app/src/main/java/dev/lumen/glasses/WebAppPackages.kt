package dev.lumen.glasses

import android.content.Context
import org.json.JSONObject
import dev.lumen.protocol.AppConfigField
import dev.lumen.protocol.GridOps
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline web apps: a `.mrbd.zip` is a Vite `dist/` (index.html at the root, or under one
 * top-level folder) with a `manifest.webmanifest`. It's extracted in [dir] and served by
 * [LocalAppServer]. A package without index.html whose manifest's `start_url` is an `https://`
 * address is an online app instead: it opens that address, and its folder holds the manifest,
 * the icon and the site scripts ([SiteScripts]) it brings. Packages arrive by `adb push` into
 * [dropFolder] (picked up when the grid opens, like the band key), from the phone ([GridApi]), or by download from an HTTPS URL
 * confirmed on the glasses ([InstallConfirmActivity], which [stage]s the package first to show
 * what it is, then [commit]s or [discard]s it).
 *
 * A package's settings are bound to its manifest id, which any package can claim. So an update
 * that doesn't come from the owner's channels (adb, the phone) and comes from another origin
 * than the installed app's ([WebApp.source]) forgets the saved secrets; the confirmation says so.
 */
object WebAppPackages {
    const val SUFFIX = ".mrbd.zip"
    /** Beyond this, a package is refused (the glasses' storage is small). */
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024
    private const val MAX_ENTRIES = 5_000
    private val MANIFEST_NAMES = listOf("manifest.webmanifest", "manifest.json")
    private const val STAGING_PREFIX = ".staging-"
    /** A staging folder older than this was left by a screen that died: deleted. */
    private const val STAGING_MAX_AGE_MS = 6 * 60 * 60_000L

    /**
     * A package unpacked in a staging folder and not installed yet: what it is ([id], [name],
     * what its manifest asks for) before the user decides. [commit] installs it, [discard]
     * deletes it.
     */
    class StagedPackage internal constructor(
        internal val folder: File,
        /** The folder holding index.html, or an online package's manifest (the staging folder or its only subfolder). */
        val base: File,
        val id: String,
        val name: String,
        val version: String,
        val configFields: List<AppConfigField>,
        /** The manifest's `lumen_internet`. */
        val internet: Boolean,
        internal val manifest: JSONObject?,
        /** The origin it was downloaded from; empty for a package handed over locally. */
        val source: String = "",
        /** An online app's package: its manifest's `start_url` is an HTTPS address ([startUrl]). */
        val online: Boolean = false,
        val startUrl: String = "",
        /** The sites its scripts change ([SiteScripts.hosts]), for the confirmation. */
        val scriptHosts: List<String> = emptyList(),
    ) {
        /** The package's own icon (its manifest's or its page's), or null; beside the staging folder. */
        val icon: File? by lazy { WebAppIcons.packageIcon(base, manifest, iconFile(folder)) }

        /** The same package under another app's id (an update for an app whose package has no id). */
        internal fun withId(id: String) = StagedPackage(folder, base, id, name, version, configFields, internet, manifest, source, online, startUrl, scriptHosts)

        /** Whether its manifest names the app (`id`); without one, its id comes from the file name. */
        internal val hasManifestId: Boolean get() = !manifest?.optString("id").isNullOrEmpty()
    }

    /** Why a package was refused: the text in the device's language, and English for the log. */
    enum class Problem(@androidx.annotation.StringRes val text: Int, val log: String) {
        HTTPS_ONLY(R.string.package_https_only, "packages only over HTTPS"),
        DOWNLOAD_FAILED(R.string.package_download_failed, "download failed, HTTP"),
        NO_INDEX(R.string.package_no_index, "no index.html in the package"),
        WRITE_FAILED(R.string.package_write_failed, "couldn't save the package"),
        TOO_MANY_FILES(R.string.package_too_many, "too many files in the package"),
        BAD_PATH(R.string.package_bad_path, "invalid path in the package:"),
        TOO_BIG(R.string.package_too_big, "package too big"),
        EMPTY(R.string.package_empty, "empty package, or not a zip"),
        DAMAGED(R.string.package_damaged, "the package doesn't match what the phone sent"),
        OTHER_APP(R.string.package_other_app, "the package is another app:"),
        NOT_OFFLINE(R.string.package_not_offline, "not an app from a package:"),
        START_URL(R.string.package_start_url, "an online package's start_url must be https:"),
        SCRIPTS_INVALID(R.string.package_scripts_invalid, "invalid lumen_scripts:"),
        SCRIPT_MATCH(R.string.package_script_match, "a site script's page isn't https://<host>/…:"),
        SCRIPT_PATH(R.string.package_script_path, "site script missing or outside the package:"),
        SCRIPTS_TOO_BIG(R.string.package_scripts_too_big, "site scripts over 1 MiB"),
    }

    class InvalidPackage(val problem: Problem, val detail: String = "") : IOException("${problem.log} $detail".trim())

    /** What to show for a failed install: the refusal in the device's language, or the error. */
    @JvmStatic
    fun describe(context: Context, error: Throwable): String =
        (error as? InvalidPackage)?.let { context.getString(it.problem.text, it.detail) } ?: error.message.orEmpty()

    @JvmStatic
    fun root(context: Context) = File(context.filesDir, "webapps")

    @JvmStatic
    fun dir(context: Context, id: String) = File(root(context), id)

    /** Where `adb push x.mrbd.zip /sdcard/Android/data/dev.lumen.glasses/files/webapps/` leaves packages. */
    @JvmStatic
    fun dropFolder(context: Context): File? = context.getExternalFilesDir("webapps")

    /** Installs every package in [dropFolder] and deletes it there. Returns one line per package. */
    @JvmStatic
    fun importFromDropFolder(context: Context): List<String> {
        val folder = dropFolder(context) ?: return emptyList()
        val files = folder.listFiles { f -> f.isFile && f.name.endsWith(SUFFIX) }.orEmpty().sortedBy { it.name }
        val lines = files.map { file ->
            val line = runCatching { file.inputStream().use { install(context, it, file.name) } }
                .fold({ context.getString(R.string.launcher_installed, it.name) }, { context.getString(R.string.package_line, file.name, describe(context, it)) })
            file.delete()
            line
        }
        // The phone's Apps tab lists the new app now, not at its next look at the grid.
        if (files.isNotEmpty()) GridApi.pushStateSoon()
        return lines
    }

    /**
     * Downloads a package over HTTPS and installs it (call off the main thread); through
     * [proxy] (`host:port`, the phone's internet) when the glasses have none of their own.
     */
    @JvmStatic
    @JvmOverloads
    fun installFromUrl(context: Context, url: String, proxy: String? = null): WebApp =
        // Asked for on the phone: the owner's channel.
        commit(context, download(context, url, proxy), trusted = true)

    /**
     * Downloads a package over HTTPS into a staging folder, not installed yet (call off the main
     * thread): [commit] or [discard] it. Through [proxy] as [installFromUrl].
     */
    @JvmStatic
    @JvmOverloads
    fun download(context: Context, url: String, proxy: String? = null): StagedPackage {
        if (!WebAppLibrary.isAcceptable(url)) throw InvalidPackage(Problem.HTTPS_ONLY)
        val via = proxy?.let {
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(it.substringBefore(':'), it.substringAfter(':').toInt()))
        } ?: java.net.Proxy.NO_PROXY
        val connection = URL(url).openConnection(via) as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode != 200) throw InvalidPackage(Problem.DOWNLOAD_FAILED, connection.responseCode.toString())
            return connection.inputStream.use { stage(context, it, fileName(url), WebOrigin.of(url)) }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Downloads a package the phone serves on its own network ([GridOps.installFile]) from
     * [phone] (`host:port`, its proxy), checks it is what the phone sent ([size], [sha256]) and
     * installs it. With [replace] (a web app id) it only updates that app, offline or a packaged
     * online one: a package of another app is refused, one without a manifest id takes that
     * app's. The owner's channel, so settings and storage stay. Off the main thread.
     */
    @JvmStatic
    fun installFromPhone(context: Context, phone: String, token: String, size: Long, sha256: String, fileName: String, replace: String?): WebApp {
        val target = replace?.let { id ->
            WebAppLibrary.find(context, id)?.takeIf { it.hasPackage } ?: throw InvalidPackage(Problem.NOT_OFFLINE, id)
        }
        val download = File(context.cacheDir, "phone-package-$token.zip")
        try {
            fetchFromPhone(phone, GridOps.PACKAGE_PATH + token, download)
            if (download.length() != size || sha256Of(download) != sha256.lowercase()) throw InvalidPackage(Problem.DAMAGED)
            var staged = download.inputStream().use { stage(context, it, fileName) }
            if (target != null && staged.id != target.id) {
                if (staged.hasManifestId) {
                    discard(staged)
                    throw InvalidPackage(Problem.OTHER_APP, staged.name)
                }
                staged = staged.withId(target.id)
            }
            return commit(context, staged, trusted = true)
        } finally {
            download.delete()
        }
    }

    /**
     * A plain HTTP GET on the phone's hotspot, over a socket: the platform refuses cleartext to
     * HttpURLConnection (targetSdk 34), and this is a link to the phone, not the web.
     */
    private fun fetchFromPhone(phone: String, path: String, into: File) {
        val host = phone.substringBeforeLast(':')
        val port = phone.substringAfterLast(':').toInt()
        java.net.Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), 10_000)
            socket.soTimeout = 30_000
            socket.getOutputStream().apply {
                write("GET $path HTTP/1.1\r\nHost: $phone\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                flush()
            }
            val input = socket.getInputStream().buffered()
            val status = readLine(input)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            var length = -1L
            while (true) {
                val line = readLine(input)
                if (line.isEmpty()) break
                if (line.substringBefore(':').trim().equals("content-length", ignoreCase = true)) {
                    length = line.substringAfter(':').trim().toLongOrNull() ?: -1L
                }
            }
            if (code != 200) throw InvalidPackage(Problem.DOWNLOAD_FAILED, code.toString())
            if (length > MAX_TOTAL_BYTES) throw InvalidPackage(Problem.TOO_BIG)
            into.outputStream().use { sink ->
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > MAX_TOTAL_BYTES) throw InvalidPackage(Problem.TOO_BIG)
                    sink.write(buffer, 0, read)
                }
            }
        }
    }

    /** One header line, without its CRLF; empty at the blank line (or the end). */
    private fun readLine(input: InputStream): String {
        val out = StringBuilder()
        while (out.length < 8 * 1024) {
            val b = input.read()
            if (b < 0 || b == '\n'.code) break
            if (b != '\r'.code) out.append(b.toChar())
        }
        return out.toString()
    }

    private fun sha256Of(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /** The package's file name in [url], for an app with no manifest id. */
    @JvmStatic
    fun fileName(url: String): String = url.substringAfterLast('/')

    /**
     * Extracts the package into a staging folder, reads its manifest, then swaps it in place of
     * an earlier version of the same app (same manifest id, or same file name), keeping its port.
     */
    @JvmStatic
    fun install(context: Context, input: InputStream, fileName: String): WebApp =
        // From adb's drop folder: the owner's channel.
        commit(context, stage(context, input, fileName), trusted = true)

    /**
     * Extracts the package into a staging folder and reads what it is; nothing is installed.
     * [source] is the origin it was downloaded from, empty for a local package.
     */
    @JvmStatic
    @JvmOverloads
    fun stage(context: Context, input: InputStream, fileName: String, source: String = ""): StagedPackage {
        cleanStaging(context)
        val staging = File(root(context), STAGING_PREFIX + System.nanoTime())
        try {
            extract(input, staging)
            val index = contentRoot(staging)
            val folder = index ?: manifestRoot(staging)
            val manifest = folder?.let { readManifest(it) }
            // A package with its own page stays an offline app whatever its start_url says (an
            // MRBD app's manifest may name where it's also hosted); only one without index.html
            // is an online app.
            val startUrl = if (index == null) onlineStart(manifest) else null
            val declaredStart = manifest?.optString("start_url").orEmpty()
            val base = when {
                folder != null && (startUrl != null || folder == index) -> folder
                // An address that isn't HTTPS: meant as an online package, which must be.
                declaredStart.contains("://") -> throw InvalidPackage(Problem.START_URL, declaredStart)
                else -> throw InvalidPackage(Problem.NO_INDEX)
            }
            // Only an online app's site scripts run (an offline app never leaves its origin).
            val scripts = if (startUrl != null) SiteScripts.declared(manifest).also { SiteScripts.check(base, it) } else emptyList()
            val fallbackName = fileName.removeSuffix(SUFFIX).removeSuffix(".zip")
            val key = manifest?.optString("id")?.ifEmpty { null }
                ?: fallbackName
            val name = manifest?.optString("short_name")?.ifEmpty { null }
                ?: manifest?.optString("name")?.ifEmpty { null }
                ?: fallbackName
            return StagedPackage(
                folder = staging,
                base = base,
                id = "pkg-" + WebAppLibrary.idForUrl("package:$key"),
                name = name,
                version = manifest?.optString("version").orEmpty(),
                // The package declares what it needs; values set earlier stay (WebAppConfig).
                configFields = AppConfigField.list(manifest?.optJSONArray("lumen_config"))
                    .map { AppConfigField(it.key, it.label.ifEmpty { it.key }, it.type, optional = it.optional) },
                internet = manifest?.optBoolean("lumen_internet") ?: false,
                manifest = manifest,
                source = source,
                online = startUrl != null,
                startUrl = startUrl.orEmpty(),
                scriptHosts = SiteScripts.hosts(scripts),
            )
        } catch (e: Throwable) {
            staging.deleteRecursively()
            throw e
        }
    }

    /**
     * Whether installing [staged] forgets the saved secrets of the app it replaces: it's an update
     * from another origin than that app's, and some secret has a value. [trusted] is the owner's
     * channels (adb, the phone), which keep them.
     */
    @JvmStatic
    @JvmOverloads
    fun clearsSecrets(context: Context, staged: StagedPackage, trusted: Boolean = false): Boolean {
        if (trusted) return false
        val existing = WebAppLibrary.find(context, staged.id) ?: return false
        return existing.source != staged.source && WebAppConfig.hasSecrets(context, existing)
    }

    /**
     * Installs a [stage]d package, replacing an earlier version of the same app (an offline one
     * keeps its port). An online package becomes an online app ([WebApp.packaged]) whose folder
     * keeps the manifest, icon and site scripts. Unless [trusted], an update from another origin
     * forgets the app's secrets ([clearsSecrets]).
     */
    @JvmStatic
    @JvmOverloads
    fun commit(context: Context, staged: StagedPackage, trusted: Boolean = false): WebApp {
        try {
            val existing = WebAppLibrary.find(context, staged.id)
            if (existing != null && clearsSecrets(context, staged, trusted)) WebAppConfig.clearSecrets(context, existing)
            val port = when {
                staged.online -> 0
                existing != null && existing.offline -> existing.port
                else -> WebAppLibrary.allocatePort(context)
            }
            // An offline app that became an online one: nothing serves its files any more.
            if (staged.online && existing?.offline == true) LocalAppServer.stop(existing.port)
            val target = dir(context, staged.id)
            target.deleteRecursively()
            if (!staged.base.renameTo(target)) throw InvalidPackage(Problem.WRITE_FAILED)
            val icon = WebAppIcons.savePackageIcon(context, target, staged.manifest, staged.id) ?: existing?.icon
            // The app added by address for the same site, which this package takes over.
            val adopted = if (staged.online) adoptable(WebAppLibrary.all(context), staged.startUrl, staged.scriptHosts, staged.id) else null
            // A name given by hand stays (this app's, or the one taken over).
            val named = existing?.takeIf { it.renamed } ?: adopted?.takeIf { it.renamed }
            val app = WebApp(
                id = staged.id,
                name = named?.name ?: staged.name,
                renamed = named != null,
                offline = !staged.online,
                remoteUrl = staged.startUrl.takeIf { staged.online }.orEmpty(),
                port = port,
                engine = existing?.engine ?: adopted?.engine ?: WebEngineKind.GECKO,
                icon = icon,
                version = staged.version,
                configFields = staged.configFields,
                internet = staged.internet,
                // A local package from the owner keeps the origin the app was downloaded from.
                source = if (trusted && staged.source.isEmpty()) existing?.source.orEmpty() else staged.source,
                packaged = staged.online,
                scriptHosts = staged.scriptHosts,
                // Its sign-ins: the context taken over now, or the one it took over before.
                contextOf = adopted?.contextKey ?: existing?.contextOf.orEmpty(),
            )
            // The context this app had on its own until now (a fresh install's) isn't used any more.
            if (adopted != null && existing != null && existing.contextKey != app.contextKey) WebAppContexts.clear(context, existing.contextKey)
            WebAppLibrary.put(context, app)
            if (adopted != null) {
                WebAppLibrary.handOver(context, adopted.id, app.id)
                android.util.Log.i("BandPackages", "${app.name} took over ${adopted.name} (its sign-ins and its place in the grid)")
            }
            updateCopies(context, app, target)
            return app
        } finally {
            discard(staged)
        }
    }

    /**
     * The copies of [app] ([WebAppLibrary.copy]) get its new package: the files, the version and
     * what the manifest asks for, an online one's address and site scripts; each keeps its name,
     * port, settings and data.
     */
    private fun updateCopies(context: Context, app: WebApp, files: File) {
        WebAppLibrary.copiesOf(context, app.id).forEach { copy ->
            runCatching {
                val dir = dir(context, copy.id)
                dir.deleteRecursively()
                check(files.copyRecursively(dir))
                val icon = app.icon?.let { path -> File(path).copyTo(File(File(path).parentFile, "${copy.id}.png"), overwrite = true).absolutePath } ?: copy.icon
                val port = if (!app.offline) 0 else copy.port.takeIf { copy.offline && it > 0 } ?: WebAppLibrary.allocatePort(context)
                if (!app.offline && copy.offline) LocalAppServer.stop(copy.port)
                WebAppLibrary.put(context, copy.copy(
                    version = app.version, configFields = app.configFields, internet = app.internet, icon = icon, source = app.source,
                    offline = app.offline, remoteUrl = app.remoteUrl, port = port, packaged = app.packaged, scriptHosts = app.scriptHosts,
                ))
            }.onFailure { android.util.Log.w("BandPackages", "couldn't update the copy ${copy.id}", it) }
        }
    }

    /** Deletes a [stage]d package that won't be installed. */
    @JvmStatic
    fun discard(staged: StagedPackage) {
        staged.folder.deleteRecursively()
        iconFile(staged.folder).delete()
    }

    private fun iconFile(staging: File) = File(staging.parentFile, staging.name + ".icon.png")

    /** Staging folders left behind by a process that died before committing or discarding. */
    private fun cleanStaging(context: Context) {
        val cutoff = System.currentTimeMillis() - STAGING_MAX_AGE_MS
        root(context).listFiles { f -> f.name.startsWith(STAGING_PREFIX) && f.lastModified() < cutoff }
            .orEmpty().forEach { it.deleteRecursively() }
    }

    /** Unzips with the usual guards: no path outside [into] (zip slip), bounded size and count. */
    @JvmStatic
    fun extract(input: InputStream, into: File) {
        into.mkdirs()
        val canonicalRoot = into.canonicalPath + File.separator
        var total = 0L
        var count = 0
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++count > MAX_ENTRIES) throw InvalidPackage(Problem.TOO_MANY_FILES)
                val out = File(into, entry.name)
                if (!out.canonicalPath.startsWith(canonicalRoot)) throw InvalidPackage(Problem.BAD_PATH, entry.name)
                if (entry.isDirectory) {
                    out.mkdirs()
                    continue
                }
                out.parentFile?.mkdirs()
                out.outputStream().use { sink ->
                    val buffer = ByteArray(16 * 1024)
                    while (true) {
                        val read = zip.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_TOTAL_BYTES) throw InvalidPackage(Problem.TOO_BIG)
                        sink.write(buffer, 0, read)
                    }
                }
            }
        }
        if (count == 0) throw InvalidPackage(Problem.EMPTY)
    }

    /** The folder holding index.html: the root, or its only subfolder (a zipped `dist/`). */
    @JvmStatic
    fun contentRoot(extracted: File): File? {
        if (File(extracted, "index.html").isFile) return extracted
        val children = extracted.listFiles().orEmpty().filter { !it.name.startsWith("__MACOSX") && !it.name.startsWith(".") }
        val only = children.singleOrNull()?.takeIf { it.isDirectory } ?: return null
        return if (File(only, "index.html").isFile) only else null
    }

    /**
     * The app an online package installed at [startUrl] takes over: one added by address (not
     * packaged, not a copy, not [id] itself) for the same site: the start page's host or one the
     * package's scripts change ([scriptHosts], `*.` covering the domain and its subdomains), a
     * leading `www.` or `m.` aside (an app added as `instagram.com` is the site of a package for
     * `www.instagram.com`). The package then keeps that app's sign-ins ([WebApp.contextOf]) and
     * its place in the grid, instead of a second app that starts signed out. Null when there's none.
     */
    @JvmStatic
    fun adoptable(apps: List<WebApp>, startUrl: String, scriptHosts: List<String>, id: String): WebApp? {
        val start = hostOf(startUrl) ?: return null
        fun site(host: String) = host.removePrefix("www.").removePrefix("m.")
        val sites = (listOf(start) + scriptHosts.map { it.removePrefix("*.") }).map(::site).toSet()
        val domains = scriptHosts.filter { it.startsWith("*.") }.map { it.removePrefix("*.") }
        return apps.firstOrNull { app ->
            val host = hostOf(app.remoteUrl)
            !app.offline && !app.packaged && app.copyOf.isEmpty() && app.id != id && host != null &&
                (site(host) in sites || domains.any { host == it || host.endsWith(".$it") })
        }
    }

    private fun hostOf(url: String): String? = runCatching { java.net.URI(url).host?.lowercase(java.util.Locale.ROOT) }.getOrNull()

    /** The folder holding the manifest: the root, or its only subfolder (an online package has no index.html). */
    @JvmStatic
    fun manifestRoot(extracted: File): File? {
        if (MANIFEST_NAMES.any { File(extracted, it).isFile }) return extracted
        val children = extracted.listFiles().orEmpty().filter { !it.name.startsWith("__MACOSX") && !it.name.startsWith(".") }
        val only = children.singleOrNull()?.takeIf { it.isDirectory } ?: return null
        return if (MANIFEST_NAMES.any { File(only, it).isFile }) only else null
    }

    /** An online package's address: its manifest's `start_url` when that's an absolute HTTPS URL, else null. */
    @JvmStatic
    fun onlineStart(manifest: JSONObject?): String? =
        manifest?.optString("start_url")?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) && WebAppLibrary.isAcceptable(it) }

    @JvmStatic
    fun readManifest(base: File): JSONObject? = MANIFEST_NAMES.map { File(base, it) }.firstOrNull { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
}
