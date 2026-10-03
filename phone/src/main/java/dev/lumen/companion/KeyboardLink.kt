package dev.lumen.companion

import android.os.Handler
import android.os.Looper
import dev.lumen.protocol.KeyboardCommand
import dev.lumen.protocol.KeyboardField
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * The companion's keyboard for the glasses' web apps: the field focused there ([field], from the
 * glasses) and the text typed here, sent live as the field's whole value (a lost message heals
 * with the next). Typing bursts go out at most every [THROTTLE_MS], the last text always.
 * Main thread.
 */
object KeyboardLink {
    private const val THROTTLE_MS = 80L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    /** Grows across openings, so the glasses never take a late text over a newer one. */
    private val seq = AtomicLong(System.currentTimeMillis())
    private var pending: String? = null
    private var lastSent = 0L

    /** The glasses' focused field; not focused when none, or nothing heard yet. */
    var field = KeyboardField(false)
        private set

    val listeners = mutableSetOf<() -> Unit>()

    fun onGlassesField(json: JSONObject) {
        field = KeyboardField.from(json)
        listeners.toList().forEach { it() }
    }

    /** The keyboard's screen shows (and again every [KeyboardCommand.HEARTBEAT_MS] while it does). */
    fun open() = send(KeyboardCommand(KeyboardCommand.OPEN))

    fun close() {
        flush()
        send(KeyboardCommand(KeyboardCommand.CLOSE))
    }

    fun text(text: String) {
        pending = text
        main.removeCallbacks(flushRunnable)
        val wait = lastSent + THROTTLE_MS - android.os.SystemClock.uptimeMillis()
        if (wait <= 0) flush() else main.postDelayed(flushRunnable, wait)
    }

    /** Enter after the text it follows. */
    fun enter() {
        flush()
        send(KeyboardCommand(KeyboardCommand.ENTER))
    }

    private val flushRunnable = Runnable { flush() }

    private fun flush() {
        main.removeCallbacks(flushRunnable)
        val text = pending ?: return
        pending = null
        lastSent = android.os.SystemClock.uptimeMillis()
        send(KeyboardCommand(KeyboardCommand.TEXT, text, seq.incrementAndGet()))
    }

    private fun send(command: KeyboardCommand) {
        CompanionService.requestKeyboard(command.toJson())
    }
}
