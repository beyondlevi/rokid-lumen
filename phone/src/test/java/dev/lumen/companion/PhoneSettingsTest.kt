package dev.lumen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneSettingsTest {
    private fun pairs(mapping: String) = mapping.split(";").associate { it.substringBefore("=") to it.substringAfter("=") }

    @Test
    fun `the defaults are the original app's media layout`() {
        val pairs = pairs(PhoneSettings.mapping(PhoneSettings.DEFAULTS, emptyMap(), "volume", "band"))
        assertEquals("media.play_pause", pairs["index_tap"])
        assertEquals("media.next", pairs["swipe_down"])
        assertEquals("", pairs["swipe_left"])
        assertEquals("volume.up", pairs["dial_up"])
        assertEquals("band", pairs["hand"])
        assertTrue(PhoneSettings.GESTURES.all { it in pairs })
    }

    @Test
    fun `open an app, the switch and the dial`() {
        val actions = mapOf("index_double" to PhoneSettings.OPEN_APP, "middle_tap" to PhoneSettings.OPEN_APP, "middle_double" to PhoneSettings.SWITCH_TO_GLASSES)
        val pairs = pairs(PhoneSettings.mapping(actions, mapOf("index_double" to "com.example"), "brightness", "left"))
        assertEquals("app:com.example", pairs["index_double"])
        // Open an app with no app chosen does nothing.
        assertEquals("", pairs["middle_tap"])
        assertEquals(PhoneSettings.SWITCH_TO_GLASSES, pairs["middle_double"])
        assertEquals("brightness.down", pairs["dial_down"])
        assertEquals("left", pairs["hand"])
        assertTrue(PhoneSettings.needsTouch("screen.back") && PhoneSettings.needsTouch(PhoneSettings.OPEN_APP))
    }
}
