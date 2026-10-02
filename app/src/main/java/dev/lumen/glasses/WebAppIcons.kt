package dev.lumen.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * Icons for the grid, from the app itself: its web manifest's icons, its page's
 * apple-touch-icon and `<link rel="icon">` (the favicon), then `/favicon.ico`; the biggest one
 * the platform can decode wins (PNG, WebP, JPEG, ICO; not SVG). An offline app's comes from its
 * package's files at install and at every update; an online app's is fetched in the background
 * when it has none and again, at most every [REFRESH_MS], when the app opens with internet.
 */
object WebAppIcons {
    private const val TAG = "BandWebAppIcons"
    /** An online app's icon is looked up again after this, when the app opens. */
    private const val REFRESH_MS = 6 * 60 * 60_000L
    /** Smaller than this is a 16 px favicon at best on the HUD, still better than a letter. */
    private const val MIN_PX = 16
    /** The address an offline package's files are resolved against (never fetched). */
    private const val PACKAGE_BASE = "https://package.lumen.invalid/"

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-icons").apply { isDaemon = true } }
    private val inFlight = mutableSetOf<String>()
    /** Online apps whose missing icon was looked for in this process (the grid refreshes often). */
    private val triedMissing = mutableSetOf<String>()

    /** One place an icon may be: its address and the size it claims (0 when unknown). */
    data class Candidate(val url: String, val size: Int)

    @JvmStatic
    fun load(app: WebApp): Bitmap? = app.icon?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }

    /**
     * Where a page's icons are, biggest first: [manifest]'s icons (resolved against
     * [manifestUrl]), the page's apple-touch-icon and icon links (against [pageUrl]), and
     * `/favicon.ico`. Monochrome-only and SVG icons are left out (the platform can't draw SVG).
     */
    @JvmStatic
    fun candidates(pageUrl: String, html: String?, manifest: JSONObject?, manifestUrl: String?): List<Candidate> {
        val out = mutableListOf<Candidate>()
        val icons = manifest?.optJSONArray("icons")
        if (icons != null && manifestUrl != null) {
            (0 until icons.length()).mapNotNull { icons.optJSONObject(it) }.forEach { icon ->
                val src = icon.optString("src")
                val purpose = icon.optString("purpose").lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
                if (src.isEmpty() || isSvg(src, icon.optString("type")) || (purpose.isNotEmpty() && purpose.all { it == "monochrome" })) return@forEach
                resolve(manifestUrl, src)?.let { out += Candidate(it, largest(icon.optString("sizes"))) }
            }
        }
        if (html != null) {
            links(html).forEach { (rels, attrs) ->
                val href = attrs["href"].orEmpty()
                if (href.isEmpty() || isSvg(href, attrs["type"].orEmpty())) return@forEach
                val size = largest(attrs["sizes"].orEmpty())
                when {
                    "apple-touch-icon" in rels || "apple-touch-icon-precomposed" in rels ->
                        resolve(pageUrl, href)?.let { out += Candidate(it, if (size > 0) size else 180) }
                    "icon" in rels -> resolve(pageUrl, href)?.let { out += Candidate(it, if (size > 0) size else 32) }
                }
            }
        }
        resolve(pageUrl, "/favicon.ico")?.let { out += Candidate(it, 16) }
        // Biggest first; on a tie, the order above (manifest, touch icon, favicon).
        return out.withIndex().sortedWith(compareByDescending<IndexedValue<Candidate>> { it.value.size }.thenBy { it.index })
            .map { it.value }.distinctBy { it.url }
    }

    /** The first candidate whose bytes [read] gets and decode to an image of at least [MIN_PX]. */
    private fun firstDecodable(candidates: List<Candidate>, read: (String) -> ByteArray?): Bitmap? {
        for (candidate in candidates) {
            val bytes = runCatching { read(candidate.url) }.getOrNull() ?: continue
            val bitmap = runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull() ?: continue
            if (bitmap.width >= MIN_PX && bitmap.height >= MIN_PX) return bitmap
        }
        return null
    }

    // ---- Offline packages ----

    /** An offline package's icon, from its files ([base] holds index.html), written to [into]; null when it has none. */
    @JvmStatic
    fun packageIcon(base: File, manifest: JSONObject?, into: File): File? = packageBitmap(base, manifest)?.let { bitmap ->
        into.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        into
    }

    /** Saves an installed package's icon next to the library (the package may be replaced); its path. */
    @JvmStatic
    fun savePackageIcon(context: Context, base: File, manifest: JSONObject?, id: String): String? {
        val bitmap = packageBitmap(base, manifest) ?: return null
        return save(context, id, bitmap)
    }

    private fun packageBitmap(base: File, manifest: JSONObject?): Bitmap? {
        val html = File(base, "index.html").takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }
        val manifestRef = html?.let { linkHref(it, "manifest") }
        // The manifest the page links to; else the one beside it, which the package reader found.
        val linked = manifestRef?.let { resolve(PACKAGE_BASE, it) }?.let { url -> packageFile(base, url) }
            ?.let { runCatching { JSONObject(it.readText()) }.getOrNull() }
        val manifestJson = linked ?: manifest
        val manifestUrl = if (linked != null) resolve(PACKAGE_BASE, manifestRef) else PACKAGE_BASE + "manifest.webmanifest"
        val candidates = candidates(PACKAGE_BASE, html, manifestJson, manifestUrl)
        return firstDecodable(candidates) { url -> packageFile(base, url)?.readBytes() }
    }

    /** The file of the package behind [url] (an address under [PACKAGE_BASE]), never outside [base]. */
    private fun packageFile(base: File, url: String): File? {
        if (!url.startsWith(PACKAGE_BASE)) return null
        val path = java.net.URLDecoder.decode(url.removePrefix(PACKAGE_BASE).substringBefore('?').substringBefore('#'), "UTF-8")
        val file = File(base, path)
        if (!file.isFile || !file.canonicalPath.startsWith(base.canonicalPath + File.separator)) return null
        return file
    }

    // ---- Online apps ----

    /** Fetches a missing online icon, then calls [done] on the worker thread if one was saved. */
    @JvmStatic
    fun fetchMissing(context: Context, apps: List<WebApp>, done: Runnable) {
        val missing = apps.filter { !it.offline && it.icon == null && synchronized(triedMissing) { triedMissing.add(it.id) } }
        if (missing.isEmpty()) return
        fetch(context, missing, done)
    }

    /**
     * An online app opened with internet ([proxy], `host:port`, or null for direct): its icon is
     * looked up again when it has none or the last look is older than [REFRESH_MS]. [done] runs
     * on the worker thread when the icon changed.
     */
    @JvmStatic
    fun refreshOnOpen(context: Context, app: WebApp, done: Runnable) {
        if (app.offline) return
        val age = app.icon?.let { System.currentTimeMillis() - File(it).lastModified() } ?: Long.MAX_VALUE
        if (age < REFRESH_MS) return
        fetch(context, listOf(app), done)
    }

    private fun fetch(context: Context, apps: List<WebApp>, done: Runnable) {
        val todo = apps.filter { synchronized(inFlight) { inFlight.add(it.id) } }
        if (todo.isEmpty()) return
        val appContext = context.applicationContext
        worker.execute {
            var saved = false
            todo.forEach { app ->
                runCatching { fetchOnline(appContext, app) }
                    .onSuccess { if (it) saved = true }
                    .onFailure { Log.d(TAG, "No icon for ${app.remoteUrl}: ${it.message}") }
                synchronized(inFlight) { inFlight.remove(app.id) }
            }
            if (saved) done.run()
        }
    }

    private fun fetchOnline(context: Context, app: WebApp): Boolean {
        val html = get(app.remoteUrl, 512 * 1024)?.let { String(it, Charsets.UTF_8) }
        val manifestUrl = html?.let { linkHref(it, "manifest") }?.let { resolve(app.remoteUrl, it) }
        val manifest = manifestUrl?.let { get(it, 256 * 1024) }?.let { runCatching { JSONObject(String(it, Charsets.UTF_8)) }.getOrNull() }
        val bitmap = firstDecodable(candidates(app.remoteUrl, html, manifest, manifestUrl)) { get(it, 1024 * 1024) }
        if (bitmap == null) {
            // Looked at now, nothing found: keep the old icon, but don't look again on every open.
            app.icon?.let { File(it).setLastModified(System.currentTimeMillis()) }
            return false
        }
        WebAppLibrary.setIcon(context, app.id, save(context, app.id, bitmap))
        return true
    }

    /** Over HTTPS only, through the phone's proxy while the glasses use it; null on any failure. */
    private fun get(address: String, limit: Int): ByteArray? {
        val url = URL(address)
        if (url.protocol != "https") return null
        val proxy = PhoneInternet.proxy?.let {
            java.net.Proxy(java.net.Proxy.Type.HTTP, java.net.InetSocketAddress(it.substringBeforeLast(':'), it.substringAfterLast(':').toInt()))
        } ?: java.net.Proxy.NO_PROXY
        val connection = url.openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = 8_000
        connection.readTimeout = 8_000
        return try {
            if (connection.responseCode != 200) null
            else connection.inputStream.use { input ->
                // InputStream.readNBytes is API 33; the glasses run 31.
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > limit) return null
                }
                out.toByteArray()
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun save(context: Context, id: String, bitmap: Bitmap): String {
        val file = File(File(context.filesDir, "webapp-icons").apply { mkdirs() }, "$id.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.absolutePath
    }

    // ---- Parsing ----

    /** The href of the first `<link rel="…">` naming [rel] (attributes in any order). */
    @JvmStatic
    fun linkHref(html: String, rel: String): String? = links(html).firstOrNull { rel in it.first }?.second?.get("href")

    /** Every `<link>` of [html]: its rel values (lowercase) and its attributes. */
    @JvmStatic
    fun links(html: String): List<Pair<List<String>, Map<String, String>>> =
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).map { match ->
            val attrs = Regex("([a-zA-Z-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)'|([^\\s>]+))").findAll(match.value)
                .associate { it.groupValues[1].lowercase() to (it.groupValues[2].ifEmpty { it.groupValues[3] }.ifEmpty { it.groupValues[4] }) }
            attrs["rel"].orEmpty().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() } to attrs
        }.toList()

    /** The largest side in a `sizes` value ("16x16 32x32", "any"); 0 when there's none. */
    @JvmStatic
    fun largest(sizes: String): Int = sizes.lowercase().split(Regex("\\s+"))
        .mapNotNull { it.substringBefore('x').toIntOrNull() }.maxOrNull() ?: 0

    private fun isSvg(href: String, type: String) =
        type.lowercase().contains("svg") || href.substringBefore('?').substringBefore('#').lowercase().endsWith(".svg")

    private fun resolve(base: String, ref: String): String? = runCatching {
        val url = URL(URL(base), ref.trim())
        if (url.protocol != "https") null else url.toString()
    }.getOrNull()
}
