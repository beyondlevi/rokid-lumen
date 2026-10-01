package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BandMappingTest {
    private fun resolve(mapping: String, key: String, paused: Boolean = false) =
        SimulatedBand.resolve(SimulatedBand.parse(mapping), key, paused)

    @Test
    fun swipesMoveOneAxisTapsSelectAndGoBack() {
        val mapping = BandMapping.build("", DialMode.VOLUME, "band")
        assertEquals(BandCommand.FORWARD, BandCommand.axis(resolve(mapping, "swipe_right")!!))
        assertEquals(BandCommand.FORWARD, BandCommand.axis(resolve(mapping, "swipe_down")!!))
        assertEquals(BandCommand.BACKWARD, BandCommand.axis(resolve(mapping, "swipe_left")!!))
        assertEquals(BandCommand.BACKWARD, BandCommand.axis(resolve(mapping, "swipe_up")!!))
        // A web app still tells the four directions apart.
        assertEquals(BandCommand.UP, resolve(mapping, "swipe_up"))
        assertEquals(BandCommand.RIGHT, resolve(mapping, "swipe_right"))
        assertEquals(BandCommand.ACTIVATE, resolve(mapping, "index_tap"))
        assertEquals(BandCommand.BACK, resolve(mapping, "middle_tap"))
    }

    @Test
    fun theIndexDoubleShipsUnmappedAndTheMiddleDoubleIsTheScreen() {
        val mapping = BandMapping.build("", DialMode.VOLUME, "band")
        // The bridge only holds a single tap back when its double is assigned.
        assertEquals("index_double=", mapping.split(';').first { it.startsWith("index_double=") })
        assertNull(resolve(mapping, "index_double"))
        // As on Meta's glasses: the middle double tap turns the screen off and on.
        assertEquals(BandCommand.SCREEN, resolve(mapping, "middle_double"))
    }

    @Test
    fun mappedActionsCarryTheirIdOrPackage() {
        val ai = BandMapping.command(GlassesAction.AI_ASSIST, null)
        val app = BandMapping.command(GlassesAction.LAUNCH_APP, " com.example.reader ")
        assertEquals("glasses.ai_assist", resolve(BandMapping.build(ai, DialMode.VOLUME, "band"), "index_double"))
        assertEquals("app:com.example.reader", resolve(BandMapping.build(app, DialMode.VOLUME, "band"), "index_double"))
    }

    @Test
    fun launchAppWithoutAPackageIsNoAction() {
        assertEquals("", BandMapping.command(GlassesAction.LAUNCH_APP, null))
        assertEquals("", BandMapping.command(GlassesAction.LAUNCH_APP, "  "))
        assertEquals("", BandMapping.command(GlassesAction.NONE, "com.example"))
    }

    @Test
    fun theDialFollowsItsMode() {
        val volume = BandMapping.build("", DialMode.VOLUME, "band")
        assertEquals(BandCommand.VOLUME_UP, resolve(volume, SimulatedBand.DIAL_UP))
        assertEquals(BandCommand.VOLUME_DOWN, resolve(volume, SimulatedBand.DIAL_DOWN))
        val navigation = BandMapping.build("", DialMode.NAVIGATION, "band")
        assertEquals(BandCommand.FORWARD, resolve(navigation, SimulatedBand.DIAL_UP))
        assertEquals(BandCommand.BACKWARD, resolve(navigation, SimulatedBand.DIAL_DOWN))
        val none = BandMapping.build("", DialMode.NONE, "band")
        assertNull(resolve(none, SimulatedBand.DIAL_UP))
    }

    @Test
    fun pausedControlsRunNothing() {
        val mapping = BandMapping.build("", DialMode.VOLUME, "left")
        assertNull(resolve(mapping, "index_tap", paused = true))
        assertEquals("left", SimulatedBand.parse(mapping)["hand"])
    }

    @Test
    fun everyGlassesActionRoundTripsThroughItsId() {
        for (action in GlassesAction.entries) {
            assertEquals(action, GlassesAction.fromId(action.id(), GlassesAction.NONE))
        }
        assertEquals(GlassesAction.NONE, GlassesAction.fromId("nexus_launcher", GlassesAction.NONE))
        assertEquals(DialMode.VOLUME, DialMode.of("unknown"))
    }
}
