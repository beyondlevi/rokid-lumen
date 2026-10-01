package dev.lumen.glasses

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationInboxTest {
    private fun item(key: String, time: Long) = PhoneNotification(key, "App", "pkg", key, "text", time, false, null)

    @After
    fun tearDown() = NotificationInbox.clear()

    @Test
    fun `items stay in the order of what they say, not of their arrival`() {
        NotificationInbox.put(item("new", 3_000), live = true)
        NotificationInbox.put(item("old", 1_000), live = false)
        NotificationInbox.put(item("mid", 2_000), live = false)
        assertEquals(listOf("new", "mid", "old"), NotificationInbox.all().map { it.key })
        // An old chat re-posted (same key, same content time) keeps its place and isn't unread.
        NotificationInbox.put(item("old", 1_000), live = false)
        assertEquals(listOf("new", "mid", "old"), NotificationInbox.all().map { it.key })
        assertEquals(1, NotificationInbox.unread())
    }
}
