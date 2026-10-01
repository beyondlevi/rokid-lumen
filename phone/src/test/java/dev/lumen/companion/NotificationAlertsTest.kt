package dev.lumen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationAlertsTest {
    private val now = 1_790_000_000_000L
    private val minute = 60_000L

    @Test
    fun `the content time is the newest message, then when, then the post time`() {
        assertEquals(now - 5 * minute, NotificationAlerts.contentTime(listOf(now - 9 * minute, now - 5 * minute), now - minute, now, now))
        assertEquals(now - 2 * minute, NotificationAlerts.contentTime(emptyList(), now - 2 * minute, now, now))
        // An app's `when` of 0 (or in the future) isn't a time: the post time is.
        assertEquals(now, NotificationAlerts.contentTime(emptyList(), 0L, now, now))
        assertEquals(now, NotificationAlerts.contentTime(emptyList(), now + 10 * minute, now, now))
    }

    @Test
    fun `a new message alerts once, a re-post of it doesn't`() {
        assertTrue(NotificationAlerts.decide(now - 10_000, last = null, now = now))
        assertFalse(NotificationAlerts.decide(now - 10_000, last = now - 10_000, now = now))
        assertTrue(NotificationAlerts.decide(now, last = now - 10_000, now = now))
    }

    @Test
    fun `an old chat re-posted by its app is not news`() {
        // WhatsApp re-posts yesterday's chat with a fresh postTime: its newest message is old.
        assertFalse(NotificationAlerts.decide(now - 24 * 60 * minute, last = null, now = now))
        assertFalse(NotificationAlerts.decide(now - 4 * minute, last = null, now = now))
    }
}
