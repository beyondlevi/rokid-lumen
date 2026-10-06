package dev.lumen.companion.computer

import dev.lumen.band.ScreenPointer

/**
 * What the band does on a computer, in the terms of a Bluetooth keyboard and mouse: key strokes
 * (a HID usage with its modifiers), media keys (consumer usages) and the mouse wheel.
 *
 * A keyboard sends where a key is, not the character: the computer's own layout turns it into
 * text. Typing the band's handwriting goes through [Layout], the one the computer uses. The
 * shortcuts are macOS' (desktops, Mission Control, the app switcher).
 */
object ComputerKeys {
    // Modifier bits of the keyboard report.
    const val CTRL = 0x01
    const val SHIFT = 0x02
    const val ALT = 0x04
    const val GUI = 0x08

    const val ENTER = 0x28
    const val ESCAPE = 0x29
    const val BACKSPACE = 0x2A
    const val TAB = 0x2B
    const val SPACE = 0x2C
    const val RIGHT = 0x4F
    const val LEFT = 0x50
    const val DOWN = 0x51
    const val UP = 0x52

    /** One key press and release: [usage] with [modifiers] held. */
    data class Stroke(val usage: Int, val modifiers: Int = 0)

    sealed interface Output {
        data class Keys(val strokes: List<Stroke>) : Output
        /** A media key (consumer page usage). */
        data class Consumer(val usage: Int) : Output
        /** Mouse wheel steps, as the report carries them: positive is the wheel turned away from the person. */
        data class Wheel(val steps: Int) : Output
    }

    /** Writing with the band ([ComputerWriter]): not a key, the phone handles it. */
    const val WRITE = "pc.write"

    /**
     * The air mouse ([ComputerPointer]): the gesture mapped to it turns it on, and the band turns
     * it off with the same gesture. Not a key either.
     */
    const val POINTER = ScreenPointer.TOGGLE

    /** A mouse move as reports of at most ±127 counts each way, keeping the total. */
    fun mouseMoves(dx: Int, dy: Int): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var x = dx
        var y = dy
        while (x != 0 || y != 0) {
            val stepX = x.coerceIn(-127, 127)
            val stepY = y.coerceIn(-127, 127)
            out += stepX to stepY
            x -= stepX
            y -= stepY
        }
        return out
    }

    /**
     * How far one scroll gesture goes, in wheel steps (the person sets it, between [SCROLL_MIN]
     * and [SCROLL_MAX]). [ComputerLink] sends them as a quick run of small steps, which the
     * computer's scrolling acceleration turns into distance; pinch and turn moves one step per notch.
     */
    const val SCROLL_DEFAULT = 6
    const val SCROLL_MIN = 2
    const val SCROLL_MAX = 16

    /**
     * An action id's output, or null when it isn't one (nothing, a profile switch, writing).
     * [invertScroll] turns the wheel around (the computer's "natural" scrolling setting decides
     * which way the page moves).
     */
    fun action(id: String, invertScroll: Boolean = false, scrollSteps: Int = SCROLL_DEFAULT): Output? {
        val wheel = if (invertScroll) -1 else 1
        val scroll = scrollSteps.coerceIn(SCROLL_MIN, SCROLL_MAX)
        return when (id) {
            "pc.key.up" -> keys(UP)
            "pc.key.down" -> keys(DOWN)
            "pc.key.left" -> keys(LEFT)
            "pc.key.right" -> keys(RIGHT)
            "pc.key.enter" -> keys(ENTER)
            "pc.key.escape" -> keys(ESCAPE)
            "pc.key.tab" -> keys(TAB)
            "pc.key.space" -> keys(SPACE)
            "pc.key.backspace" -> keys(BACKSPACE)
            // macOS: Control and the arrows move between desktops and open Mission Control.
            "pc.desktop.next" -> keys(RIGHT, CTRL)
            "pc.desktop.previous" -> keys(LEFT, CTRL)
            "pc.mission_control" -> keys(UP, CTRL)
            "pc.app_switch" -> keys(TAB, GUI)
            // Up is toward the top of the page. macOS comes with natural scrolling, where a wheel
            // step toward the person (negative) goes up; [invertScroll] is for the other way.
            "pc.scroll.up" -> Output.Wheel(-scroll * wheel)
            "pc.scroll.down" -> Output.Wheel(scroll * wheel)
            "pc.wheel.up" -> Output.Wheel(-wheel)
            "pc.wheel.down" -> Output.Wheel(wheel)
            "pc.media.play_pause" -> Output.Consumer(0xCD)
            "pc.media.next" -> Output.Consumer(0xB5)
            "pc.media.previous" -> Output.Consumer(0xB6)
            "pc.volume.up" -> Output.Consumer(0xE9)
            "pc.volume.down" -> Output.Consumer(0xEA)
            "pc.volume.mute" -> Output.Consumer(0xE2)
            "pc.brightness.up" -> Output.Consumer(0x6F)
            "pc.brightness.down" -> Output.Consumer(0x70)
            else -> null
        }
    }

    private fun keys(usage: Int, modifiers: Int = 0) = Output.Keys(listOf(Stroke(usage, modifiers)))

    /** The computer's keyboard layout, for typing text. */
    enum class Layout(val id: String) {
        /** US positions: macOS' ABC and U.S., Windows' US. */
        US("us"),
        /** The Brazilian ABNT2 keyboard (C-cedilla, dead accents, the extra / ? key). */
        ABNT2("abnt2"),
        ;

        companion object {
            fun of(id: String?) = entries.firstOrNull { it.id == id } ?: US
        }
    }

    /** The strokes that type [text] on [layout]; characters it can't type are left out. */
    fun type(text: String, layout: Layout): List<Stroke> = text.flatMap { type(it, layout).orEmpty() }

    /** The strokes that type [char] on [layout], or null when the layout can't. */
    fun type(char: Char, layout: Layout): List<Stroke>? {
        when (char) {
            in 'a'..'z' -> return listOf(Stroke(0x04 + (char - 'a')))
            in 'A'..'Z' -> return listOf(Stroke(0x04 + (char - 'A'), SHIFT))
            in '1'..'9' -> return listOf(Stroke(0x1E + (char - '1')))
            '0' -> return listOf(Stroke(0x27))
            ' ' -> return listOf(Stroke(SPACE))
            '\n' -> return listOf(Stroke(ENTER))
            '\t' -> return listOf(Stroke(TAB))
        }
        val table = if (layout == Layout.ABNT2) ABNT2 else US_KEYS
        return table[char]
    }

    private fun key(usage: Int, shift: Boolean = false) = listOf(Stroke(usage, if (shift) SHIFT else 0))

    /** A dead key followed by a space: the accent itself. */
    private fun dead(usage: Int, shift: Boolean = false) = key(usage, shift) + Stroke(SPACE)

    private val US_KEYS: Map<Char, List<Stroke>> = mapOf(
        '!' to key(0x1E, true), '@' to key(0x1F, true), '#' to key(0x20, true), '$' to key(0x21, true),
        '%' to key(0x22, true), '^' to key(0x23, true), '&' to key(0x24, true), '*' to key(0x25, true),
        '(' to key(0x26, true), ')' to key(0x27, true),
        '-' to key(0x2D), '_' to key(0x2D, true), '=' to key(0x2E), '+' to key(0x2E, true),
        '[' to key(0x2F), '{' to key(0x2F, true), ']' to key(0x30), '}' to key(0x30, true),
        '\\' to key(0x31), '|' to key(0x31, true), ';' to key(0x33), ':' to key(0x33, true),
        '\'' to key(0x34), '"' to key(0x34, true), '`' to key(0x35), '~' to key(0x35, true),
        ',' to key(0x36), '<' to key(0x36, true), '.' to key(0x37), '>' to key(0x37, true),
        '/' to key(0x38), '?' to key(0x38, true),
    )

    private val ABNT2: Map<Char, List<Stroke>> = mapOf(
        '!' to key(0x1E, true), '@' to key(0x1F, true), '#' to key(0x20, true), '$' to key(0x21, true),
        '%' to key(0x22, true), '&' to key(0x24, true), '*' to key(0x25, true),
        '(' to key(0x26, true), ')' to key(0x27, true),
        '-' to key(0x2D), '_' to key(0x2D, true), '=' to key(0x2E), '+' to key(0x2E, true),
        // The acute and grave accents are dead keys; the bracket keys sit to their right.
        '`' to dead(0x2F, true), '[' to key(0x30), '{' to key(0x30, true),
        ']' to key(0x31), '}' to key(0x31, true),
        // The C-cedilla row: the tilde and circumflex are dead keys.
        '~' to dead(0x34), '^' to dead(0x34, true),
        '\'' to key(0x35), '"' to key(0x35, true),
        ',' to key(0x36), '<' to key(0x36, true), '.' to key(0x37), '>' to key(0x37, true),
        ';' to key(0x38), ':' to key(0x38, true),
        '\\' to key(0x64), '|' to key(0x64, true),
        '/' to key(0x87), '?' to key(0x87, true),
    )
}
