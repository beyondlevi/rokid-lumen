package dev.lumen.glasses

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebAppNotificationsTest {
    private fun notification(shortcut: String = "5511999990000@s.whatsapp.net", title: String = "Ana") =
        PhoneNotification("k", "WhatsApp", "com.whatsapp", title, "oi", 0L, false, null, shortcut = shortcut)

    @Test
    fun theManifestDeclaresWhichNotificationsItOpens() {
        val manifest = JSONObject("""{"lumen_notifications":[{"packages":["com.whatsapp","com.whatsapp.w4b",""],"open":"/chat/{shortcut}"},{"packages":[]}]}""")
        val intents = WebAppNotifications.parse(manifest)
        assertEquals(1, intents.size)
        assertEquals(setOf("com.whatsapp", "com.whatsapp.w4b"), intents[0].packages)
        assertTrue(WebAppNotifications.parse(JSONObject("{}")).isEmpty())
    }

    @Test
    fun thePathIsFilledAndStaysInTheApp() {
        assertEquals("/chat/5511999990000%40s.whatsapp.net", WebAppNotifications.path("/chat/{shortcut}", notification()))
        // A placeholder without a value: the start page.
        assertEquals("/", WebAppNotifications.path("/chat/{shortcut}", notification(shortcut = "")))
        // Never another host, never a relative path.
        assertEquals("/", WebAppNotifications.path("//evil.example/{title}", notification()))
        assertEquals("/", WebAppNotifications.path("https://evil.example/", notification()))
        assertEquals("/", WebAppNotifications.path("", notification()))
        assertEquals("/search?q=Ana%20Lu", WebAppNotifications.path("/search?q={title}", notification(title = "Ana Lu")))
    }

    @Test
    fun theTagComesFromThePhonesKey() {
        assertEquals("agg:t2_abc:t3_1xyz:3", WebAppNotifications.tag("0|com.reddit.frontpage|0|agg:t2_abc:t3_1xyz:3|10392"))
        // A tag with "|" in it (a group summary's), no tag, and a key that isn't the phone's.
        assertEquals("0|com.example|g:Aggregate", WebAppNotifications.tag("0|com.example|0|0|com.example|g:Aggregate|10392"))
        assertEquals("", WebAppNotifications.tag("0|org.telegram.messenger.web|-1194773307|null|10385"))
        assertEquals("", WebAppNotifications.tag("debug|Maya Chen"))
        val reddit = PhoneNotification("0|com.reddit.frontpage|0|agg:t2_abc:t3_1xyz:3|10392", "Reddit", "com.reddit.frontpage", "t", "x", 0L, false, null)
        assertEquals("/notification/agg%3At2_abc%3At3_1xyz%3A3", WebAppNotifications.path("/notification/{tag}", reddit))
    }
}
