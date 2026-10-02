package dev.lumen.glasses

import android.os.SystemClock
import dev.lumen.protocol.PhoneEvent

/**
 * The phone's battery as the companion last reported it ([PhoneEvent], on the phone link), for
 * the Controls tab. Main thread only. The companion repeats it every five minutes; after
 * [STALE_MS] without news it's unknown again (the link is down).
 */
object PhoneBattery {
    private const val STALE_MS = 15 * 60_000L

    private var last: PhoneEvent? = null
    private var at = 0L

    /** Called on the main thread when a report arrives. */
    val listeners = LinkedHashSet<() -> Unit>()

    @JvmStatic
    fun onEvent(event: PhoneEvent) {
        last = event
        at = SystemClock.elapsedRealtime()
        listeners.toList().forEach { it() }
    }

    /** The last report, or null when none came recently. */
    @JvmStatic
    fun current(): PhoneEvent? = last?.takeIf { SystemClock.elapsedRealtime() - at <= STALE_MS }
}
