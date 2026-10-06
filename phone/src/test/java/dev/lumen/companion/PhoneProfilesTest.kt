package dev.lumen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneProfilesTest {
    private fun parse(mapping: String) = mapping.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `untouched defaults become Media and Navigation, switched by the middle double tap`() {
        val state = PhoneProfiles.migrate(PhoneSettings.DEFAULTS, emptyMap(), "volume")
        assertEquals(listOf("media", "navigation"), state.profiles.map { it.id })
        assertEquals("media", state.active)
        assertEquals("middle_double", state.switchGesture)
        val media = state.current
        assertEquals("volume.mute", media.action("middle_tap"))
        assertEquals(PhoneSettings.NONE, media.action("middle_double"))
        assertTrue(media.whenLocked)
        // The old always-volume dial is contextual now: brightness without audio.
        assertEquals("brightness", media.dial)
        assertFalse(state.profiles[1].whenLocked)
    }

    @Test
    fun `a changed map becomes My layout and the switch takes a free gesture`() {
        val old = mapOf(
            "swipe_up" to "screen.swipe_up", "swipe_down" to "screen.swipe_down", "swipe_left" to "screen.swipe_left",
            "swipe_right" to "screen.swipe_right", "index_tap" to "key.enter", "index_double" to PhoneSettings.NONE,
            "middle_tap" to "screen.back", "middle_double" to "volume.mute",
        )
        val state = PhoneProfiles.migrate(old, emptyMap(), "volume")
        assertEquals(listOf("mine", "media", "navigation"), state.profiles.map { it.id })
        assertEquals("mine", state.active)
        assertEquals("index_double", state.switchGesture)
        assertEquals("volume.mute", state.current.action("middle_double"))
        assertEquals("screen.back", state.current.action("middle_tap"))
        assertTrue(state.current.whenLocked)
    }

    @Test
    fun `a map using every gesture leaves the switch unset`() {
        val old = PhoneSettings.GESTURES.associateWith { "volume.up" }
        assertEquals(PhoneSettings.NONE, PhoneProfiles.migrate(old, emptyMap(), "none").switchGesture)
    }

    @Test
    fun `the mapping has the switch gesture, the contextual dial and the wrist`() {
        val profile = PhoneProfiles.navigation()
        val mapping = parse(PhoneProfiles.mapping(profile, "middle_double", "left"))
        assertEquals("key.dpad_up", mapping["swipe_up"])
        assertEquals("screen.back", mapping["middle_tap"])
        assertEquals(PhoneProfiles.NEXT, mapping["middle_double"])
        assertEquals("", mapping["index_double"])
        assertEquals(PhoneProfiles.DIAL_UP, mapping["dial_up"])
        assertEquals(PhoneProfiles.DIAL_DOWN, mapping["dial_down"])
        assertEquals("left", mapping["hand"])
    }

    @Test
    fun `an open-app gesture maps to its package, or to nothing without one`() {
        val profile = PhoneProfiles.Profile("p", "Mine", "custom", mapOf("index_double" to PhoneSettings.OPEN_APP, "swipe_up" to PhoneSettings.OPEN_APP), mapOf("index_double" to "com.example.music"))
        val mapping = parse(PhoneProfiles.mapping(profile, PhoneSettings.NONE, "band"))
        assertEquals("app:com.example.music", mapping["index_double"])
        assertEquals("", mapping["swipe_up"])
    }

    @Test
    fun `switching goes around the list both ways`() {
        val three = PhoneProfiles.State(
            listOf(PhoneProfiles.media(), PhoneProfiles.navigation(), PhoneProfiles.Profile("p", "Mine", "custom", emptyMap())),
            "media", "middle_double",
        )
        assertEquals("navigation", PhoneProfiles.step(three, +1))
        assertEquals("p", PhoneProfiles.step(three, -1))
        assertEquals("media", PhoneProfiles.step(three.copy(active = "p"), +1))
    }

    @Test
    fun `profiles survive their JSON`() {
        val profile = PhoneProfiles.Profile("p", "Mine", "custom", mapOf("swipe_up" to PhoneProfiles.GO_PREFIX + "media"), mapOf("x" to "y"), "arrows", true)
        assertEquals(profile, PhoneProfiles.Profile.fromJson(profile.toJson()))
    }
}
