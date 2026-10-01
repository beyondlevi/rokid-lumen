package dev.lumen.companion

import dev.lumen.protocol.NotifyCommand

/**
 * The glasses' banner snooze as they last told it ([NotifyCommand.snooze]): the glasses own it,
 * the companion shows it and asks for changes.
 */
object PhoneSnooze {
    /** Wall-clock end, or 0 when off. */
    @Volatile var until = 0L
        private set

    val listeners = mutableSetOf<() -> Unit>()

    fun isActive(now: Long = System.currentTimeMillis()) = until > now

    fun onState(until: Long) {
        this.until = until
        listeners.toList().forEach { it() }
    }
}
