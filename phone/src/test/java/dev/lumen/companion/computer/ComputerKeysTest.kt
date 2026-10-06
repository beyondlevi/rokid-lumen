package dev.lumen.companion.computer

import dev.lumen.companion.computer.ComputerKeys.Layout
import dev.lumen.companion.computer.ComputerKeys.Output
import dev.lumen.companion.computer.ComputerKeys.Stroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ComputerKeysTest {
    @Test
    fun `letters, digits and space type the same on both layouts`() {
        for (layout in Layout.entries) {
            assertEquals(listOf(Stroke(0x04), Stroke(0x1D)), ComputerKeys.type("az", layout))
            assertEquals(listOf(Stroke(0x0F, ComputerKeys.SHIFT)), ComputerKeys.type("L", layout))
            assertEquals(listOf(Stroke(0x1E), Stroke(0x26), Stroke(0x27)), ComputerKeys.type("190", layout))
            assertEquals(listOf(Stroke(ComputerKeys.SPACE), Stroke(ComputerKeys.ENTER)), ComputerKeys.type(" \n", layout))
        }
    }

    @Test
    fun `punctuation follows the layout`() {
        assertEquals(listOf(Stroke(0x38)), ComputerKeys.type('/', Layout.US))
        assertEquals(listOf(Stroke(0x87)), ComputerKeys.type('/', Layout.ABNT2))
        assertEquals(listOf(Stroke(0x38, ComputerKeys.SHIFT)), ComputerKeys.type('?', Layout.US))
        assertEquals(listOf(Stroke(0x87, ComputerKeys.SHIFT)), ComputerKeys.type('?', Layout.ABNT2))
        assertEquals(listOf(Stroke(0x33, ComputerKeys.SHIFT)), ComputerKeys.type(':', Layout.US))
        assertEquals(listOf(Stroke(0x38, ComputerKeys.SHIFT)), ComputerKeys.type(':', Layout.ABNT2))
        assertEquals(listOf(Stroke(0x34, ComputerKeys.SHIFT)), ComputerKeys.type('"', Layout.US))
        assertEquals(listOf(Stroke(0x35, ComputerKeys.SHIFT)), ComputerKeys.type('"', Layout.ABNT2))
    }

    @Test
    fun `an ABNT2 accent is its dead key and a space`() {
        assertEquals(listOf(Stroke(0x34), Stroke(ComputerKeys.SPACE)), ComputerKeys.type('~', Layout.ABNT2))
        assertEquals(listOf(Stroke(0x34, ComputerKeys.SHIFT), Stroke(ComputerKeys.SPACE)), ComputerKeys.type('^', Layout.ABNT2))
        assertEquals(listOf(Stroke(0x35, ComputerKeys.SHIFT)), ComputerKeys.type('~', Layout.US))
    }

    @Test
    fun `every character the band can write has a key on both layouts`() {
        val written = ('a'..'z') + ('A'..'Z') + ('0'..'9') + "!\"#\$%&'()*+,-./:;<=>?@[\\]^_`{|}~ \n".toList()
        for (layout in Layout.entries) {
            val missing = written.filter { ComputerKeys.type(it, layout) == null }
            assertEquals("$layout misses $missing", emptyList<Char>(), missing)
        }
    }

    @Test
    fun `characters it can't type are left out`() {
        assertNull(ComputerKeys.type('é', Layout.US))
        assertEquals(listOf(Stroke(0x04)), ComputerKeys.type("éa", Layout.US))
    }

    @Test
    fun `actions are macOS keys, media keys and the wheel`() {
        assertEquals(Output.Keys(listOf(Stroke(ComputerKeys.RIGHT, ComputerKeys.CTRL))), ComputerKeys.action("pc.desktop.next"))
        assertEquals(Output.Keys(listOf(Stroke(ComputerKeys.UP, ComputerKeys.CTRL))), ComputerKeys.action("pc.mission_control"))
        assertEquals(Output.Keys(listOf(Stroke(ComputerKeys.TAB, ComputerKeys.GUI))), ComputerKeys.action("pc.app_switch"))
        assertEquals(Output.Consumer(0xE9), ComputerKeys.action("pc.volume.up"))
        assertEquals(Output.Consumer(0x6F), ComputerKeys.action("pc.brightness.up"))
        assertNull(ComputerKeys.action(ComputerKeys.WRITE))
        assertNull(ComputerKeys.action("none"))
    }

    @Test
    fun `scrolling up is wheel steps toward the person, as far as set, reversed on request`() {
        assertEquals(Output.Wheel(-6), ComputerKeys.action("pc.scroll.up"))
        assertEquals(Output.Wheel(6), ComputerKeys.action("pc.scroll.down"))
        assertEquals(Output.Wheel(6), ComputerKeys.action("pc.scroll.up", invertScroll = true))
        assertEquals(Output.Wheel(-12), ComputerKeys.action("pc.scroll.up", scrollSteps = 12))
        assertEquals(Output.Wheel(-ComputerKeys.SCROLL_MAX), ComputerKeys.action("pc.scroll.up", scrollSteps = 500))
        assertEquals(Output.Wheel(-1), ComputerKeys.action("pc.wheel.up"))
    }

    @Test
    fun `a mouse move splits into reports of at most 127 counts and keeps its total`() {
        assertEquals(emptyList<Pair<Int, Int>>(), ComputerKeys.mouseMoves(0, 0))
        assertEquals(listOf(5 to -3), ComputerKeys.mouseMoves(5, -3))
        val moves = ComputerKeys.mouseMoves(300, -130)
        assertEquals(300, moves.sumOf { it.first })
        assertEquals(-130, moves.sumOf { it.second })
        assertTrue(moves.all { Math.abs(it.first) <= 127 && Math.abs(it.second) <= 127 })
        assertNull(ComputerKeys.action(ComputerKeys.POINTER))
    }
}
