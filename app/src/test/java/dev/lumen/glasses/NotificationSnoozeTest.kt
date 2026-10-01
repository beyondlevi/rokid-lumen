package dev.lumen.glasses

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationSnoozeTest {
    private val now = 1_000_000_000L

    @Test
    fun `snoozed for fifteen minutes`() {
        val until = now + NotificationSnooze.DURATION_MS
        assertTrue(NotificationSnooze.isActive(until, now))
        assertTrue(NotificationSnooze.isActive(until, until - 1))
        assertFalse(NotificationSnooze.isActive(until, until))
    }

    @Test
    fun `never snoozed, or a clock set back`() {
        assertFalse(NotificationSnooze.isActive(0, now))
        assertFalse(NotificationSnooze.isActive(now + NotificationSnooze.DURATION_MS, now - 60_000))
    }
}
