package dev.lumen.companion.computer

import dev.lumen.companion.PhoneProfiles
import dev.lumen.companion.PhoneSettings
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerProfilesTest {
    private fun parse(mapping: String) = mapping.split(';').associate { it.substringBefore('=') to it.substringAfter('=') }

    @Test
    fun `Notebook writes on the double tap the switch leaves free`() {
        val withMiddle = ComputerProfiles.notebook("Notebook", "middle_double")
        assertEquals(ComputerKeys.WRITE, withMiddle.action("index_double"))
        assertEquals(PhoneSettings.NONE, withMiddle.action("middle_double"))
        val withIndex = ComputerProfiles.notebook("Notebook", "index_double")
        assertEquals(ComputerKeys.WRITE, withIndex.action("middle_double"))
        assertEquals("pc.key.escape", withIndex.action("middle_tap"))
        assertEquals("pc.scroll.up", withIndex.action("swipe_up"))
    }

    @Test
    fun `the mapping puts the shared switch on its gesture and pinch and turn on dial actions`() {
        val state = ComputerProfiles.defaults(ComputerProfiles.Names(), "index_double")
        val map = parse(ComputerProfiles.mapping(state.current, "index_double", "right"))
        assertEquals(PhoneProfiles.NEXT, map["index_double"])
        assertEquals(ComputerKeys.WRITE, map["middle_double"])
        assertEquals("pc.desktop.next", map["swipe_left"])
        assertEquals("pc.scroll.up", map["swipe_up"])
        assertEquals(PhoneProfiles.DIAL_UP, map["dial_up"])
        assertEquals("right", map["hand"])
        assertTrue(PhoneSettings.GESTURES.all { it in map })
    }

    @Test
    fun `pinch and turn follows the profile's choice`() {
        assertEquals("pc.wheel.up", ComputerProfiles.dialAction("scroll", up = true))
        assertEquals("pc.volume.down", ComputerProfiles.dialAction("volume", up = false))
        assertEquals("pc.key.up", ComputerProfiles.dialAction("arrows", up = true))
        assertNull(ComputerProfiles.dialAction("none", up = true))
    }

    @Test
    fun `stored profiles keep only computer actions`() {
        val json = JSONObject().put("id", "c1").put("name", "Mine").put("dial", "sideways")
            .put("actions", JSONObject().put("swipe_up", "pc.key.up").put("index_tap", "screen.home").put("middle_tap", PhoneProfiles.GO_PREFIX + "notebook"))
        val profile = ComputerProfiles.Profile.fromJson(json)
        assertEquals("pc.key.up", profile.action("swipe_up"))
        assertEquals(PhoneSettings.NONE, profile.action("index_tap"))
        assertEquals(PhoneProfiles.GO_PREFIX + "notebook", profile.action("middle_tap"))
        assertEquals("scroll", profile.dial)
        assertEquals(profile, ComputerProfiles.Profile.fromJson(profile.toJson()))
    }

    @Test
    fun `stepping goes around the list`() {
        val state = ComputerProfiles.defaults(ComputerProfiles.Names(), "middle_double")
        assertEquals("presentation", ComputerProfiles.step(state, +1))
        assertEquals("presentation", ComputerProfiles.step(state, -1))
        assertEquals("notebook", ComputerProfiles.step(state.copy(active = "presentation"), +1))
    }
}
