package dev.lumen.glasses

import android.os.Handler
import android.os.Looper
import dev.lumen.protocol.Link
import dev.lumen.protocol.NotifyCommand

/**
 * Quick replies to a phone notification: sent to the companion, which answers through the
 * notification's own reply action (a reaction is its emoji, as text). Main thread only. The
 * outcome comes back as [onReplied]; with no answer in [TIMEOUT_MS] it counts as failed.
 */
object NotificationReplies {
    private const val TIMEOUT_MS = 20_000L

    /** How a reply went: [ok], or not (the phone said so, or never answered). */
    fun interface Listener {
        fun onReplied(key: String, ok: Boolean)
    }

    val listeners = LinkedHashSet<Listener>()
    private val pending = HashMap<String, Runnable>()
    private val main = Handler(Looper.getMainLooper())

    /** Sends [text] as the answer to [key]; false when the phone link is down. */
    @JvmStatic
    fun send(key: String, text: String): Boolean {
        if (!PhoneLink.send(Link.NOTIFY, NotifyCommand.reply(key, text))) return false
        pending.remove(key)?.let(main::removeCallbacks)
        val timeout = Runnable { finish(key, false) }
        pending[key] = timeout
        main.postDelayed(timeout, TIMEOUT_MS)
        return true
    }

    @JvmStatic
    fun onReplied(key: String, ok: Boolean) = finish(key, ok)

    private fun finish(key: String, ok: Boolean) {
        val timeout = pending.remove(key) ?: return
        main.removeCallbacks(timeout)
        listeners.toList().forEach { it.onReplied(key, ok) }
    }
}
