package dev.lumen.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.ChunkSender
import dev.lumen.protocol.PictureAnswer
import dev.lumen.protocol.PictureOps
import dev.lumen.protocol.PictureRequest
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Sends the glasses the notification picture they ask for ([PictureOps]): read and encoded off
 * the main thread ([NotificationForwarder.picture]), then the header and the chunks, acked as an
 * audio file's are. One transfer at a time: Rokid's link is slow and in order, and a newer
 * request (another notification opened) means the old one isn't wanted. Main thread.
 */
object PictureTransfer {
    private const val TAG = "NbPicture"
    private const val PUMP_MS = 1_000L

    private class Outgoing(val request: PictureRequest, val header: JSONObject, val sender: ChunkSender)

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor()
    private var current: Outgoing? = null
    /** Being read and encoded: a repeat of it waits for the header. */
    private var preparing: PictureRequest? = null

    /** A request from the glasses (they repeat it until the header comes). */
    fun onRequest(context: Context, request: PictureRequest) {
        current?.takeIf { it.request.id == request.id }?.let {
            // The header went missing on the way: again (the chunks follow their own acks).
            CompanionService.sendPicture(it.header)
            return
        }
        if (preparing?.id == request.id) return
        drop("replaced")
        preparing = request
        val app = context.applicationContext
        work.execute {
            val started = System.currentTimeMillis()
            val result = runCatching { NotificationForwarder.picture(app, request) }
            main.post {
                if (preparing !== request) return@post
                preparing = null
                result.onSuccess { picture ->
                    val header = PictureAnswer.of(request, picture.bytes, picture.width, picture.height).toJson()
                    Log.d(TAG, "picture ${request.id}: ${picture.width}x${picture.height}, ${picture.bytes.size} B in ${System.currentTimeMillis() - started} ms")
                    val out = Outgoing(request, header, ChunkSender(picture.bytes))
                    current = out
                    CompanionService.sendPicture(header)
                    pump()
                }.onFailure { e ->
                    val reason = (e as? PictureFailure)?.reason ?: PictureOps.REASON_UNREADABLE
                    Log.w(TAG, "picture ${request.id} not sent ($reason): ${e.message}")
                    CompanionService.sendPicture(PictureAnswer.failed(request, reason).toJson())
                }
            }
        }
    }

    /** {op: ack|cancel} on [dev.lumen.protocol.Link.PICTURE]. */
    fun onMessage(json: JSONObject) {
        val id = json.optString("id")
        when (json.optString("op")) {
            PictureOps.ACK -> current?.takeIf { it.request.id == id }?.let {
                it.sender.ack(json.optInt("seq", -1))
                pump()
            }
            PictureOps.CANCEL -> if (current?.request?.id == id || preparing?.id == id) drop("cancelled by the glasses")
        }
    }

    /** The link went away: nothing in flight survives it. */
    fun reset() = drop("link down")

    private fun drop(why: String) {
        current?.let { Log.d(TAG, "picture ${it.request.id} dropped: $why") }
        current = null
        preparing = null
        main.removeCallbacks(pumpLater)
    }

    private fun pump() {
        val out = current ?: return
        out.sender.pump(System.currentTimeMillis()) { seq, piece -> CompanionService.sendPicture(PictureOps.chunk(out.request.id, seq), piece) }
        when {
            out.sender.done -> {
                current = null
                Log.d(TAG, "picture ${out.request.id} delivered")
            }
            out.sender.failed -> {
                current = null
                Log.w(TAG, "picture ${out.request.id}: the glasses stopped answering")
            }
            else -> {
                main.removeCallbacks(pumpLater)
                main.postDelayed(pumpLater, PUMP_MS)
            }
        }
    }

    private val pumpLater = Runnable { pump() }
}
