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
    fun theDialIsResolvedOnTheGlasses() {
        for (mode in DialMode.entries) {
            val mapping = BandMapping.build("", mode, "band")
            assertEquals(BandCommand.DIAL_UP, resolve(mapping, SimulatedBand.DIAL_UP))
            assertEquals(BandCommand.DIAL_DOWN, resolve(mapping, SimulatedBand.DIAL_DOWN))
        }
    }

    @Test
    fun theDialIsTheVolumeWhileAudioPlaysAndItsModeOtherwise() {
        for (mode in DialMode.entries) {
            assertEquals(BandCommand.VOLUME_UP, mode.resolve(up = true, playing = true))
            assertEquals(BandCommand.VOLUME_DOWN, mode.resolve(up = false, playing = true))
        }
        assertEquals(BandCommand.VOLUME_UP, DialMode.VOLUME.resolve(up = true, playing = false))
        assertEquals(BandCommand.FORWARD, DialMode.NAVIGATION.resolve(up = true, playing = false))
        assertEquals(BandCommand.BACKWARD, DialMode.NAVIGATION.resolve(up = false, playing = false))
        assertEquals(BandCommand.BRIGHTNESS_DOWN, DialMode.BRIGHTNESS.resolve(up = false, playing = false))
        assertNull(DialMode.NONE.resolve(up = true, playing = false))
    }

    @Test
    fun pausedControlsRunNothing() {
        val mapping = BandMapping.build("", DialMode.VOLUME, "left")
        assertNull(resolve(mapping, "index_tap", paused = true))
        assertEquals("left", SimulatedBand.parse(mapping)["hand"])
    }

    @Test
    fun everyGestureCanBeMappedAndKeepsItsDefaultOtherwise() {
        val mapping = BandMapping.build(
            mapOf("swipe_right" to GestureChoices.command(GestureChoices.POINTER, null), "middle_tap" to ""),
            DialMode.VOLUME, "band",
        )
        assertEquals("pc.pointer", resolve(mapping, "swipe_right"))
        assertNull(resolve(mapping, "middle_tap"))
        // The others are as they always were.
        assertEquals(BandCommand.LEFT, resolve(mapping, "swipe_left"))
        assertEquals(BandCommand.ACTIVATE, resolve(mapping, "index_tap"))
        assertEquals(BandCommand.SCREEN, resolve(mapping, "middle_double"))
    }

    @Test
    fun theMiddleHoldPausesByDefaultAndAHoldCantBeTheAirMouse() {
        val mapping = BandMapping.build(emptyMap(), DialMode.VOLUME, "band")
        assertEquals(GestureChoices.PAUSE, resolve(mapping, "middle_hold"))
        assertNull(resolve(mapping, "index_hold"))
        assertEquals(false, GestureChoices.choicesFor(MappableGesture.INDEX_HOLD).any { it.id == GestureChoices.POINTER })
        assertEquals(true, GestureChoices.choicesFor(MappableGesture.SWIPE_LEFT).any { it.id == GestureChoices.POINTER })
        assertEquals(true, GestureChoices.choicesFor(MappableGesture.MIDDLE_HOLD).any { it.id == GestureChoices.PAUSE })
    }

    @Test
    fun choicesBecomeCommands() {
        assertEquals(BandCommand.BACK, GestureChoices.command(BandCommand.BACK, null))
        assertEquals("glasses.home", GestureChoices.command(GlassesAction.HOME.id(), null))
        assertEquals("app:com.example.reader", GestureChoices.command(GlassesAction.LAUNCH_APP.id(), "com.example.reader"))
        assertEquals("", GestureChoices.command(GestureChoices.NONE, null))
        assertEquals("pc.pointer", GestureChoices.command(GestureChoices.POINTER, null))
        // The index double tap's stored ids from before still read, Back as the navigation's.
        assertEquals(GlassesAction.AI_ASSIST.id(), GestureChoices.of(GlassesAction.AI_ASSIST.id(), GestureChoices.NONE))
        assertEquals(BandCommand.BACK, GestureChoices.of(GlassesAction.BACK.id(), GestureChoices.NONE))
        assertEquals(BandCommand.ACTIVATE, GestureChoices.of("nonsense", BandCommand.ACTIVATE))
        assertEquals(BandCommand.SCREEN, GestureChoices.of(null, BandCommand.SCREEN))
        // Every gesture's default is a choice the phone can show.
        MappableGesture.entries.forEach { assertEquals(true, GestureChoices.isChoice(it.default)) }
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
