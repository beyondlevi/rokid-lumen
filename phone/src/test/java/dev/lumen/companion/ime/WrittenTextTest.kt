package dev.lumen.companion.ime

import org.junit.Assert.assertEquals
import org.junit.Test

class WrittenTextTest {
    @Test
    fun `letters append and a deletion removes before the cursor`() {
        val text = WrittenText()
        assertEquals(WrittenText.Edit(0, "o"), text.update("o"))
        assertEquals(WrittenText.Edit(0, "i"), text.update("oi"))
        assertEquals(WrittenText.Edit(1, ""), text.update("o"))
        assertEquals(WrittenText.Edit(0, "la"), text.update("ola"))
        assertEquals("ola", text.sent)
    }

    @Test
    fun `a capital that replaces a letter deletes and inserts`() {
        val text = WrittenText()
        text.update("ab")
        assertEquals(WrittenText.Edit(1, "B"), text.update("aB"))
    }

    @Test
    fun `after a reset the band's text starts from the cursor`() {
        val text = WrittenText()
        text.update("hello")
        text.reset()
        assertEquals(WrittenText.Edit(0, "x"), text.update("x"))
    }

    @Test
    fun `nothing new is an empty edit`() {
        val text = WrittenText()
        text.update("a b")
        assertEquals(WrittenText.Edit(0, ""), text.update("a b"))
    }
}
