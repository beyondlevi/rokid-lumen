package dev.lumen.companion

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridOps
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * Offline packages picked on this phone for the glasses: each is copied into the app's cache
 * under a random token and served by [WebProxy] at [GridOps.PACKAGE_PATH] + token while the
 * glasses download it (over the phone's hotspot, which only they join). An offer is dropped
 * once the glasses answer, or after [TTL_MS] without an answer.
 */
object PackageShare {
    /** The glasses refuse bigger packages anyway (their limit is on the unpacked size). */
    const val MAX_BYTES = 200L * 1024 * 1024
    private const val TTL_MS = 15 * 60_000L

    class Offer(val token: String, val file: File, val name: String, val size: Long, val sha256: String, val replace: String, val expiresAt: Long)

    class TooBig : IOException("package too big")

    /** Where the package being handed over is: copied here, sent, then the glasses' answer. */
    enum class Phase { COPYING, SENDING, DONE, FAILED }

    /** The latest package handed over; [detail] is the glasses' (or this phone's) refusal. */
    data class Transfer(val name: String, val replace: String, val phase: Phase, val detail: String = "", val token: String = "") {
        val busy: Boolean get() = phase == Phase.COPYING || phase == Phase.SENDING
    }

    /** Rokid's link loses messages: the request goes again until the glasses answer. */
    private const val RESEND_MS = 15_000L

    @Volatile var transfer: Transfer? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-package").apply { isDaemon = true } }
    private val offers = java.util.concurrent.ConcurrentHashMap<String, Offer>()
    private val random = SecureRandom()

    /**
     * Hands the picked [uri] over to the glasses: copies it, asks them to fetch it, and repeats
     * the ask until they answer ([onResult]). [replace]: the grid id of the app it updates, or
     * empty. One at a time. Main thread.
     */
    @JvmStatic
    fun send(context: Context, uri: Uri, replace: String = "") {
        if (transfer?.busy == true) return
        val appContext = context.applicationContext
        update(Transfer("", replace, Phase.COPYING))
        io.execute {
            val result = runCatching { offer(appContext, uri, replace) }
            main.post {
                result.fold(
                    { offer ->
                        update(Transfer(offer.name, replace, Phase.SENDING, token = offer.token))
                        ask(appContext, offer)
                    },
                    { error ->
                        val text = if (error is TooBig) appContext.getString(R.string.apps_file_too_big) else error.message.orEmpty()
                        update(Transfer("", replace, Phase.FAILED, text))
                    },
                )
            }
        }
    }

    private fun ask(context: Context, offer: Offer) {
        val current = transfer
        if (current?.token != offer.token || current.phase != Phase.SENDING) return
        if (System.currentTimeMillis() > offer.expiresAt) {
            finish(offer.token)
            return update(current.copy(phase = Phase.FAILED, detail = context.getString(R.string.apps_file_no_answer)))
        }
        CompanionService.requestGrid(GridOps.installFile(offer.token, offer.name, offer.size, offer.sha256, offer.replace))
        main.postDelayed({ ask(context, offer) }, RESEND_MS)
    }

    /** The glasses' answer, if it's about the package being handed over (its subject is the token). */
    @JvmStatic
    fun onResult(result: GridEvent.Result): Boolean {
        val current = transfer ?: return false
        if (result.subject.isEmpty() || result.subject != current.token) return false
        finish(current.token)
        if (current.phase == Phase.SENDING) {
            update(current.copy(phase = if (result.ok) Phase.DONE else Phase.FAILED, detail = result.error))
        }
        return true
    }

    /** Clears a finished transfer from the screen. */
    @JvmStatic
    fun dismiss() {
        if (transfer?.busy == false) update(null)
    }

    private fun update(next: Transfer?) {
        transfer = next
        GridCache.listeners.toList().forEach { it() }
    }

    /** Copies [uri] (a picked .zip) into the cache and offers it; call off the main thread. */
    private fun offer(context: Context, uri: Uri, replace: String): Offer {
        prune(context)
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val file = File(folder(context), "$token.zip")
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        try {
            val input = context.contentResolver.openInputStream(uri) ?: throw IOException("can't read the file")
            input.use { source ->
                file.outputStream().use { sink ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = source.read(buffer)
                        if (read < 0) break
                        size += read
                        if (size > MAX_BYTES) throw TooBig()
                        digest.update(buffer, 0, read)
                        sink.write(buffer, 0, read)
                    }
                }
            }
        } catch (e: Throwable) {
            file.delete()
            throw e
        }
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        val offer = Offer(token, file, displayName(context, uri), size, sha, replace, System.currentTimeMillis() + TTL_MS)
        offers[token] = offer
        return offer
    }

    /** The file for a request path ([GridOps.PACKAGE_PATH] + token), while its offer lasts. */
    @JvmStatic
    fun file(path: String): File? {
        if (!path.startsWith(GridOps.PACKAGE_PATH)) return null
        val offer = offers[path.removePrefix(GridOps.PACKAGE_PATH)] ?: return null
        return offer.file.takeIf { System.currentTimeMillis() < offer.expiresAt }
    }

    @JvmStatic
    fun find(token: String): Offer? = offers[token]

    /** The glasses answered (installed or not): the copy goes. */
    @JvmStatic
    fun finish(token: String) {
        offers.remove(token)?.file?.delete()
    }

    private fun prune(context: Context) {
        val now = System.currentTimeMillis()
        offers.values.filter { it.expiresAt < now }.forEach { finish(it.token) }
        val live = offers.values.map { it.file.name }.toSet()
        folder(context).listFiles().orEmpty().filter { it.name !in live }.forEach { it.delete() }
    }

    private fun folder(context: Context) = File(context.cacheDir, "packages").apply { mkdirs() }

    private fun displayName(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "package.zip"
}
