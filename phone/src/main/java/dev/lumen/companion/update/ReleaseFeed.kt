package dev.lumen.companion.update

import android.content.Context
import android.util.Log
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The repository's releases from GitHub's API (`GET /repos/<repo>/releases`), cached in the
 * app's files with their ETag: a check that finds nothing new is a 304 and costs no quota, and
 * with no network the last list is used. Blocking: call it off the main thread.
 */
object ReleaseFeed {
    private const val TAG = "NbUpdate"
    const val REPOSITORY = "beyondlevi/rokid-lumen"
    private const val URL_RELEASES = "https://api.github.com/repos/$REPOSITORY/releases?per_page=30"

    private fun dir(context: Context) = File(context.filesDir, "updates").apply { mkdirs() }
    private fun body(context: Context) = File(dir(context), "releases.json")
    private fun etag(context: Context) = File(dir(context), "releases.etag")

    /** The releases, newest first: fresh from GitHub, or the cache when that fails. */
    @JvmStatic
    fun fetch(context: Context): Result<List<Release>> {
        val cached = body(context).takeIf { it.isFile }?.readText()
        val connection = (URL(URL_RELEASES).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "RokidLumenCompanion")
            if (cached != null) etag(context).takeIf { it.isFile }?.readText()?.let { setRequestProperty("If-None-Match", it) }
        }
        return try {
            when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_NOT_MODIFIED -> Result.success(Release.parseList(cached.orEmpty()))
                HttpURLConnection.HTTP_OK -> {
                    val text = connection.inputStream.bufferedReader().use { it.readText() }
                    write(body(context), text)
                    connection.getHeaderField("ETag")?.let { write(etag(context), it) }
                    Result.success(Release.parseList(text))
                }
                else -> {
                    Log.w(TAG, "releases: HTTP $code")
                    cached?.let { Result.success(Release.parseList(it)) } ?: Result.failure(IllegalStateException("HTTP $code"))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "releases: ${e.message}")
            cached?.let { Result.success(Release.parseList(it)) } ?: Result.failure(e)
        } finally {
            connection.disconnect()
        }
    }

    /** The cached list, without the network (the release notes' history). */
    @JvmStatic
    fun cached(context: Context): List<Release> = body(context).takeIf { it.isFile }?.readText()?.let(Release::parseList).orEmpty()

    private fun write(file: File, text: String) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        tmp.renameTo(file)
    }
}
