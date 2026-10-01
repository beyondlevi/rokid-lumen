package dev.lumen.companion

import android.app.Notification
import org.junit.Assert.assertEquals
import org.junit.Test

class NotificationPrivacyTest {
    private val android14 = 34
    private val android15 = 35

    private fun decide(
        visibility: Int,
        sdk: Int = android14,
        sensitive: Boolean = false,
        hideAll: Boolean = false,
        publicTitle: String? = null,
        publicText: String? = null,
    ) = NotificationPrivacy.decide(sdk, visibility, sensitive, hideAll, publicTitle, publicText)

    @Test
    fun `a public notification goes as it is`() {
        assertEquals(NotificationContent.Full, decide(Notification.VISIBILITY_PUBLIC))
        assertEquals(NotificationContent.Full, decide(Notification.VISIBILITY_PUBLIC, publicTitle = "x", publicText = "y"))
    }

    @Test
    fun `a private notification shows its public version up to Android 14`() {
        assertEquals(
            NotificationContent.Public("Chat", "2 new messages"),
            decide(Notification.VISIBILITY_PRIVATE, publicTitle = " Chat ", publicText = "2 new messages"),
        )
        assertEquals(NotificationContent.Public("", "New message"), decide(Notification.VISIBILITY_PRIVATE, publicText = "New message"))
        assertEquals(NotificationContent.Public("Bank", ""), decide(Notification.VISIBILITY_PRIVATE, sdk = 29, publicTitle = "Bank"))
    }

    @Test
    fun `a private notification without a public version is redacted`() {
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_PRIVATE))
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_PRIVATE, publicTitle = "  ", publicText = ""))
    }

    @Test
    fun `from Android 15 on a private notification goes as it is`() {
        assertEquals(NotificationContent.Full, decide(Notification.VISIBILITY_PRIVATE, sdk = android15, publicText = "New message"))
        assertEquals(NotificationContent.Full, decide(Notification.VISIBILITY_PRIVATE, sdk = android15))
    }

    @Test
    fun `secret, sensitive and hide-all are redacted whatever the public version`() {
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_SECRET, publicText = "x"))
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_SECRET, sdk = android15))
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_PUBLIC, sensitive = true))
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_PRIVATE, hideAll = true, publicText = "x"))
        assertEquals(NotificationContent.Redacted, decide(Notification.VISIBILITY_PUBLIC, sdk = android15, hideAll = true))
    }
}
