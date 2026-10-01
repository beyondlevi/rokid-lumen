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
 * Icons for the grid: an offline app brings its own (copied at install); an online app's comes
 * from its web manifest, or its apple-touch-icon, fetched once in the background.
 */
object WebAppIcons {
    private const val TAG = "BandWebAppIcons"
    private val worker = Executors.newSingleThreadExecutor()
    private val inFlight = mutableSetOf<String>()

    @JvmStatic
    fun load(app: WebApp): Bitmap? = app.icon?.let { path -> runCatching { BitmapFactory.decodeFile(path) }.getOrNull() }

    /** Fetches a missing online icon, then calls [done] on the worker thread if one was saved. */
    @JvmStatic
    fun fetchMissing(context: Context, apps: List<WebApp>, done: Runnable) {
        val missing = apps.filter { !it.offline && it.icon == null && synchronized(inFlight) { inFlight.add(it.id) } }
        if (missing.isEmpty()) return
        val appContext = context.applicationContext
        worker.execute {
            var saved = false
            missing.forEach { app ->
                runCatching { fetch(appContext, app) }
                    .onSuccess { if (it) saved = true }
                    .onFailure { Log.d(TAG, "No icon for ${app.remoteUrl}: ${it.message}") }
            }
            if (saved) done.run()
        }
    }

    private fun fetch(context: Context, app: WebApp): Boolean {
        val page = URL(app.remoteUrl)
        val html = String(get(page, 512 * 1024) ?: return false, Charsets.UTF_8)
        val candidates = mutableListOf<URL>()
        linkHref(html, "manifest")?.let { href ->
            val manifestUrl = URL(page, href)
            get(manifestUrl, 256 * 1024)?.let { body ->
                val icons = JSONObject(String(body, Charsets.UTF_8)).optJSONArray("icons")
                if (icons != null) {
                    (0 until icons.length()).map { icons.getJSONObject(it) }
                        .filter { it.optString("type").ifEmpty { "image/png" } == "image/png" }
                        .sortedByDescending { it.optString("sizes").substringBefore('x').toIntOrNull() ?: 0 }
                        .forEach { candidates += URL(manifestUrl, it.getString("src")) }
                }
            }
        }
        linkHref(html, "apple-touch-icon")?.let { candidates += URL(page, it) }
        for (candidate in candidates) {
            val bytes = get(candidate, 1024 * 1024) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: continue
            val file = File(File(context.filesDir, "webapp-icons").apply { mkdirs() }, "${app.id}.png")
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            WebAppLibrary.setIcon(context, app.id, file.absolutePath)
            return true
        }
        return false
    }

    /** The href of the first `<link rel="…">` naming [rel] (attributes in any order). */
    @JvmStatic
    fun linkHref(html: String, rel: String): String? {
        Regex("<link\\b[^>]*>", RegexOption.IGNORE_CASE).findAll(html).forEach { match ->
            val tag = match.value
            val rels = Regex("rel\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
                ?.lowercase()?.split(Regex("\\s+")) ?: return@forEach
            if (rel !in rels) return@forEach
            return Regex("href\\s*=\\s*[\"']([^\"']*)[\"']", RegexOption.IGNORE_CASE).find(tag)?.groupValues?.get(1)
        }
        return null
    }

    private fun get(url: URL, limit: Int): ByteArray? {
        if (url.protocol != "https") return null
        val connection = url.openConnection() as HttpURLConnection
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
}
