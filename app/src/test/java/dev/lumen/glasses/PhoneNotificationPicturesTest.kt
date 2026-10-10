package dev.lumen.glasses

import dev.lumen.protocol.NotificationPicture
import dev.lumen.protocol.NotifyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PhoneNotificationPicturesTest {
    private fun post(redacted: Boolean, pictures: List<NotificationPicture>) = NotifyEvent.putPictures(
        NotifyEvent.post("0|com.whatsapp|1|chat").put("app", "WhatsApp").put("pkg", "com.whatsapp").put("title", "Ana")
            .put("text", "Ana: Look at this view!").put("when", 5L).put("redacted", redacted).put("reply", true),
        pictures,
    )

    @Test
    fun aPostNamesItsPictures() {
        val pictures = listOf(NotificationPicture(4L, ""), NotificationPicture(5L, "Look at this view!"))
        val notification = PhoneLink.parse(post(redacted = false, pictures))!!
        assertEquals(pictures, notification.pictures)
        assertTrue(notification.replyable)
    }

    @Test
    fun aHiddenOneHasNone() {
        // The phone never sends them for it; the glasses wouldn't show them anyway.
        val notification = PhoneLink.parse(post(redacted = true, listOf(NotificationPicture(5L, "x"))))!!
        assertEquals(emptyList<NotificationPicture>(), notification.pictures)
        assertEquals(emptyList<NotificationPicture>(), PhoneLink.parse(post(redacted = false, emptyList()))!!.pictures)
    }
}
