package dev.lumen.companion

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridOps
import org.json.JSONObject

/**
 * The phone's changes to the glasses' grid ([GridOps]) until the glasses answer them. Rokid's link
 * is a queue in Hi Rokid that delivers in order but can hold a message for minutes behind Hi Rokid's
 * own traffic (measured 2026-10-09: a remove took ~10 min, a describe 3, while Hi Rokid sent the
 * phonebook every 5 s). So the screen doesn't wait for it: [GridCache] shows the change as made,
 * marked as sending. A repeat joins the back of the same queue, so the change is sent again only
 * now and then ([RESEND_MS]), and whenever the link comes back ([flush]), in case the queue was
 * lost; the glasses do each request id once and repeat the answer to a repeat. Unanswered after
 * [GIVE_UP_MS], the change is dropped and [failed] says which. `describe` goes the same way, until
 * a state answers it. Main thread.
 */
object GridRequests {
    private const val TAG = "NbGridSend"

    /** The waits before each new try (then the last one, over and over). */
    private val RESEND_MS = longArrayOf(30_000, 120_000)

    /** Older than this, a change still on its way is shown as slow. */
    const val SLOW_MS = 5_000L

    const val GIVE_UP_MS = 15 * 60_000L

    private const val TICK_MS = 1_000L

    class Pending(val json: JSONObject, val startedAt: Long) {
        val id: Long get() = GridOps.requestId(json)
        val op: String get() = json.optString("op")
        /** The grid item it changes ("" for the grid as a whole). */
        val item: String get() = GridOps.item(json)
        var sentAt = 0L
        var tries = 0
        /** Answered: kept until the glasses' next state shows the change, so it doesn't blink back. */
        var answered = false

        fun slow(now: Long) = !answered && now - startedAt >= SLOW_MS
    }

    private val main = Handler(Looper.getMainLooper())

    /** The changes on their way, oldest first. */
    val pending = LinkedHashMap<Long, Pending>()

    /** The last change the glasses never answered (its request), until the next change or a dismiss. */
    @Volatile var failed: JSONObject? = null
        private set

    private var ticking = false
    private var wasSlow = false

    /** Sends [json] now if the link is up, and again until the glasses answer it. */
    fun send(json: JSONObject) {
        val op = json.optString("op")
        if (op == GridOps.ICONS || op == GridOps.INSTALL_FILE) {
            // Icons are asked for again with every state; a package has its own resends (PackageShare).
            CompanionService.transmitGrid(json)
            return
        }
        if (op == GridOps.DESCRIBE) pending.values.removeAll { it.op == GridOps.DESCRIBE && !it.answered }
        val entry = Pending(json, SystemClock.elapsedRealtime())
        pending[entry.id] = entry
        if (op != GridOps.DESCRIBE) failed = null
        transmit(entry)
        tick()
        changed()
    }

    /** The link came (back) up: everything still waiting goes again now. */
    fun flush() {
        pending.values.filter { !it.answered }.forEach { transmit(it) }
    }

    /** An answer or a state from the glasses; [GridCache] calls this before it updates. */
    fun onEvent(event: GridEvent) {
        when (event) {
            is GridEvent.Result -> {
                val entry = pending[event.re] ?: return
                if (event.ok) entry.answered = true else pending.remove(event.re)
                Log.d(TAG, "${entry.op} ${entry.id} answered after ${entry.tries} tries${if (event.ok) "" else ": refused"}")
            }
            is GridEvent.State -> {
                // The state after an answer shows the change: the overlay can go.
                pending.values.removeAll { it.answered || (event.re != 0L && it.id == event.re) }
            }
            is GridEvent.Icon -> Unit
        }
    }

    fun dismissFailure() {
        failed = null
        changed()
    }

    /** Some change has been on its way for a while. */
    fun slow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return pending.values.any { it.op != GridOps.DESCRIBE && it.slow(now) }
    }

    private fun transmit(entry: Pending) {
        if (!CompanionService.transmitGrid(entry.json)) return
        entry.sentAt = SystemClock.elapsedRealtime()
        entry.tries++
    }

    private fun tick() {
        if (ticking) return
        ticking = true
        main.postDelayed(::onTick, TICK_MS)
    }

    private fun onTick() {
        ticking = false
        val now = SystemClock.elapsedRealtime()
        var dirty = false
        val iterator = pending.values.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (entry.answered) continue
            if (now - entry.startedAt >= GIVE_UP_MS) {
                Log.d(TAG, "${entry.op} ${entry.id}: no answer after ${entry.tries} tries, given up")
                iterator.remove()
                if (entry.op != GridOps.DESCRIBE) failed = entry.json
                dirty = true
                continue
            }
            val wait = RESEND_MS[minOf(maxOf(entry.tries - 1, 0), RESEND_MS.lastIndex)]
            if (entry.sentAt == 0L || now - entry.sentAt >= wait) {
                Log.d(TAG, "${entry.op} ${entry.id}: no answer yet, sending again")
                transmit(entry)
            }
        }
        val slow = slow()
        if (slow != wasSlow) {
            wasSlow = slow
            dirty = true
        }
        if (dirty) changed()
        if (pending.values.any { !it.answered }) tick()
    }

    private fun changed() = GridCache.listeners.toList().forEach { it() }
}
