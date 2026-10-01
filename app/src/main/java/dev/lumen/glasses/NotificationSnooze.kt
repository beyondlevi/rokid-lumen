package dev.lumen.glasses

import android.content.Context
import dev.lumen.protocol.Link
import dev.lumen.protocol.NotifyCommand

/**
 * Snooze: no banners for [DURATION_MS]. It starts from two swipes down on a banner, the toggle
 * at the top of the inbox or the companion, and ends by itself or from those toggles.
 * Notifications still reach the inbox. Kept in preferences as a wall-clock end, so an app
 * restart doesn't cut it short; every change goes to the phone ([NotifyCommand.snooze]).
 */
object NotificationSnooze {
    const val DURATION_MS = 15 * 60_000L
    private const val PREFS = "lumen_notifications"
    private const val KEY_UNTIL = "snoozed_until"

    /** Called on the main thread when the snooze starts or is ended (not when it runs out). */
    val listeners = LinkedHashSet<() -> Unit>()

    @JvmStatic
    fun start(context: Context, now: Long = System.currentTimeMillis()): Long {
        val until = now + DURATION_MS
        save(context, until)
        return until
    }

    @JvmStatic
    fun stop(context: Context) = save(context, 0)

    @JvmStatic
    fun set(context: Context, on: Boolean) {
        if (on) start(context) else stop(context)
    }

    /** The snooze's end, or 0 when it isn't on. */
    @JvmStatic
    fun until(context: Context, now: Long = System.currentTimeMillis()): Long =
        prefs(context).getLong(KEY_UNTIL, 0).takeIf { isActive(it, now) } ?: 0

    @JvmStatic
    fun isActive(context: Context, now: Long = System.currentTimeMillis()): Boolean = until(context, now) > 0

    /** Snoozed while [now] is before [until] (and not absurdly far from it: a clock set back). */
    @JvmStatic
    fun isActive(until: Long, now: Long): Boolean = now < until && until - now <= DURATION_MS

    /** Tells the phone how the snooze stands (at start, and after every change). */
    @JvmStatic
    fun publish(context: Context) {
        PhoneLink.send(Link.NOTIFY, NotifyCommand.snooze(until(context)))
    }

    private fun save(context: Context, until: Long) {
        prefs(context).edit().putLong(KEY_UNTIL, until).apply()
        publish(context)
        listeners.toList().forEach { it() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
