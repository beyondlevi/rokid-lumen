package dev.lumen.glasses

import android.content.Context
import org.json.JSONObject
import dev.lumen.protocol.AppConfigField
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream

/**
 * Offline web apps: a `.mrbd.zip` is a Vite `dist/` (index.html at the root, or under one
 * top-level folder) with a `manifest.webmanifest`. It's extracted in [dir] and served by
 * [LocalAppServer]. Packages arrive by `adb push` into [dropFolder] (picked up when the grid
 * opens, like the band key), from the phone ([GridApi]), or by download from an HTTPS URL
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
        /** The folder holding index.html (the staging folder or its only subfolder). */
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
    ) {
        /** The package's own icon (its manifest's largest PNG), or null. */
        val icon: File? get() = manifest?.let { iconFile(base, it) }
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
        return files.map { file ->
            val line = runCatching { file.inputStream().use { install(context, it, file.name) } }
                .fold({ context.getString(R.string.launcher_installed, it.name) }, { context.getString(R.string.package_line, file.name, describe(context, it)) })
            file.delete()
            line
        }
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
            val base = contentRoot(staging) ?: throw InvalidPackage(Problem.NO_INDEX)
            val manifest = readManifest(base)
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
     * Installs a [stage]d package, replacing an earlier version of the same app (keeping its port).
     * Unless [trusted], an update from another origin forgets the app's secrets ([clearsSecrets]).
     */
    @JvmStatic
    @JvmOverloads
    fun commit(context: Context, staged: StagedPackage, trusted: Boolean = false): WebApp {
        try {
            val existing = WebAppLibrary.find(context, staged.id)
            if (existing != null && clearsSecrets(context, staged, trusted)) WebAppConfig.clearSecrets(context, existing)
            val port = existing?.port ?: WebAppLibrary.allocatePort(context)
            val target = dir(context, staged.id)
            target.deleteRecursively()
            if (!staged.base.renameTo(target)) throw InvalidPackage(Problem.WRITE_FAILED)
            val icon = staged.manifest?.let { copyIcon(context, target, it, staged.id) } ?: existing?.icon
            val app = WebApp(
                id = staged.id,
                name = staged.name,
                offline = true,
                remoteUrl = "",
                port = port,
                engine = existing?.engine ?: WebEngineKind.GECKO,
                icon = icon,
                version = staged.version,
                configFields = staged.configFields,
                internet = staged.internet,
                // A local package from the owner keeps the origin the app was downloaded from.
                source = if (trusted && staged.source.isEmpty()) existing?.source.orEmpty() else staged.source,
            )
            WebAppLibrary.put(context, app)
            return app
        } finally {
            discard(staged)
        }
    }

    /** Deletes a [stage]d package that won't be installed. */
    @JvmStatic
    fun discard(staged: StagedPackage) {
        staged.folder.deleteRecursively()
    }

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

    @JvmStatic
    fun readManifest(base: File): JSONObject? = MANIFEST_NAMES.map { File(base, it) }.firstOrNull { it.isFile }
        ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }

    /** The largest PNG icon of the manifest, inside [base]; null when there's none. */
    private fun iconFile(base: File, manifest: JSONObject): File? {
        val icons = manifest.optJSONArray("icons") ?: return null
        val best = (0 until icons.length()).mapNotNull { icons.optJSONObject(it) }
            .filter { it.optString("src").isNotEmpty() && (it.optString("type").ifEmpty { "image/png" } == "image/png") }
            .maxByOrNull { it.optString("sizes").substringBefore('x').toIntOrNull() ?: 0 } ?: return null
        val src = best.getString("src").substringBefore('?').removePrefix("./").removePrefix("/")
        val file = File(base, src)
        if (!file.isFile || !file.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        return file
    }

    /** The largest PNG icon of the manifest, copied next to the library (the package may be replaced). */
    private fun copyIcon(context: Context, base: File, manifest: JSONObject, id: String): String? {
        val file = iconFile(base, manifest) ?: return null
        val iconsDir = File(context.filesDir, "webapp-icons").apply { mkdirs() }
        val copy = File(iconsDir, "$id.png")
        file.copyTo(copy, overwrite = true)
        return copy.absolutePath
    }
}
