package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationGroupTest {
    private fun item(key: String, pkg: String, time: Long) = PhoneNotification(key, pkg.uppercase(), pkg, key, "text", time, false, null)

    @Test
    fun `groups by app, the app with the newest first, newest first inside`() {
        val groups = NotificationGroup.of(listOf(item("a1", "chat", 10), item("m1", "mail", 30), item("a2", "chat", 20)))
        assertEquals(listOf("mail", "chat"), groups.map { it.packageName })
        assertEquals(listOf("a2", "a1"), groups[1].keys)
        assertEquals("a2", groups[1].newest.key)
    }
}

class NotificationPreviewTest {
    @Test
    fun `the preview is the last lines, oldest first`() {
        assertEquals("b\nc", PhoneNotification.lastLines("a\n\nb\n c ", 2))
        assertEquals("only", PhoneNotification.lastLines("only", 2))
    }
}
