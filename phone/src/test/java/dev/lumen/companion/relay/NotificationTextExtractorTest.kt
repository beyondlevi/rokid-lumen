// Ported from Rokid Nexus's Relay plugin (https://github.com/Anezium/Rokid-Nexus,
// plugins/relay), Copyright Anezium, Apache License 2.0 (see NOTICE). Modified for the
// Rokid Lumen Companion: package, settings and limits.
package dev.lumen.companion.relay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextExtractorTest {
    @Test
    fun `messaging style keeps the newest messages in timestamp order`() {
        val input = NotificationTextInput(
            messages = listOf(
                message("one", "A", 100L),
                message("two", "B", 500L),
                message("three", "C", 200L),
                message("four", "D", 400L),
                message("five", "E", 300L),
                message("six", "F", 600L),
            ),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "E: five\nD: four\nB: two\nF: six",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `genuinely richer expanded lines beat messaging style`() {
        val input = NotificationTextInput(
            messages = listOf(message("Brief")),
            expandedLines = listOf("Brief with enough additional detail to win"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "Brief with enough additional detail to win",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `expanded lines do not replace a richer messaging style body`() {
        val input = NotificationTextInput(
            messages = listOf(message("Primary message")),
            expandedLines = listOf("Shorter"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "Primary message",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `expanded lines are used when messaging style is empty`() {
        val input = NotificationTextInput(
            expandedLines = listOf("first line", null, "second line"),
            bigText = "big fallback",
            text = "plain fallback",
        )

        assertEquals(
            "first line\nsecond line",
            NotificationTextExtractor.extract(input, messageLimit = 4),
        )
    }

    @Test
    fun `big text is used after messages and expanded lines`() {
        val input = NotificationTextInput(
            messages = listOf(message("   ")),
            expandedLines = listOf("   ", null),
            bigText = "big text",
            text = "plain fallback",
        )

        assertEquals("big text", NotificationTextExtractor.extract(input, messageLimit = 4))
    }

    @Test
    fun `plain text is the final nonempty fallback`() {
        val input = NotificationTextInput(bigText = "   ", text = "plain text")

        assertEquals("plain text", NotificationTextExtractor.extract(input, messageLimit = 4))
    }

    @Test
    fun `empty text input produces an empty body`() {
        assertEquals(
            "",
            NotificationTextExtractor.extract(NotificationTextInput(), messageLimit = 4),
        )
    }

    @Test
    fun `a collapsed message summary always loses to expanded lines`() {
        assertTrue(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = "3 new messages",
                lines = "A\nB",
            ),
        )
    }

    @Test
    fun `expanded detail must exceed the normalized margin`() {
        val primary = "a\nb"
        val exactlyTwentyFourMore = "a\n${"x".repeat(25)}"
        val twentyFiveMore = "a\n${"x".repeat(26)}"

        assertFalse(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = primary,
                lines = exactlyTwentyFourMore,
            ),
        )
        assertTrue(
            NotificationTextExtractor.shouldPreferExpandedLines(
                primary = primary,
                lines = twentyFiveMore,
            ),
        )
    }

    @Test
    fun `top trimming leaves text within the cap and preserves the newest end`() {
        assertEquals("short", NotificationTextExtractor.trimFromTop("short", maxChars = 8))
        assertEquals("def", NotificationTextExtractor.trimFromTop("abcdef", maxChars = 3))

        val trimmed = NotificationTextExtractor.trimFromTop(
            "oldest line\nmiddle line\nnewest line",
            maxChars = 11,
        )

        assertEquals("newest line", trimmed)
        assertTrue(trimmed.length <= 11)
    }

    @Test
    fun `top trimming handles zero cap and empty input`() {
        assertEquals("", NotificationTextExtractor.trimFromTop("text", maxChars = 0))
        assertEquals("", NotificationTextExtractor.trimFromTop("", maxChars = 8))
    }

    private fun message(text: String?, sender: String? = null, timestamp: Long = 0L) =
        ExtractedMessage(text = text, sender = sender, timestamp = timestamp)
}
