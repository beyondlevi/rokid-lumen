package dev.lumen.glasses

import dev.lumen.glasses.NotificationDetail.Step
import dev.lumen.protocol.NotificationPicture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationDetailTest {
    @Test
    fun `a photo takes its caption's line, as the canvas shows it`() {
        val bubbles = NotificationDetail.bubbles(listOf("Ana: Hi", "Ana: Look at this view!"), listOf(NotificationPicture(2, "Look at this view!")))
        assertEquals(listOf(DetailBubble.Text("Ana: Hi"), DetailBubble.Photo(0, "Ana: Look at this view!")), bubbles)
        // The caption alone, as a 1:1 chat without a sender writes it.
        assertEquals(listOf(DetailBubble.Photo(0, "Wow")), NotificationDetail.bubbles(listOf("Wow"), listOf(NotificationPicture(1, "Wow"))))
    }

    @Test
    fun `photos match from the newest, and the ones not in the text come first`() {
        val lines = listOf("Ana: 📷 Photo", "Ana: ok", "Ana: 📷 Photo")
        val pictures = listOf(NotificationPicture(1, "📷 Photo"), NotificationPicture(2, "📷 Photo"), NotificationPicture(3, "📷 Photo"))
        // The text keeps the newest messages: the oldest photo's line is gone, so it leads.
        assertEquals(
            listOf(DetailBubble.Photo(0, "📷 Photo"), DetailBubble.Photo(1, "Ana: 📷 Photo"), DetailBubble.Text("Ana: ok"), DetailBubble.Photo(2, "Ana: 📷 Photo")),
            NotificationDetail.bubbles(lines, pictures),
        )
        // A big picture has no line: first, with its own caption (or none).
        assertEquals(
            listOf(DetailBubble.Photo(0, ""), DetailBubble.Text("Breaking news")),
            NotificationDetail.bubbles(listOf("Breaking news"), listOf(NotificationPicture(9))),
        )
        // A line that merely contains the caption isn't its line.
        assertEquals(
            listOf(DetailBubble.Photo(0, "ok"), DetailBubble.Text("Ana: ok then")),
            NotificationDetail.bubbles(listOf("Ana: ok then"), listOf(NotificationPicture(1, "ok"))),
        )
        assertEquals(listOf(DetailBubble.Text("a")), NotificationDetail.bubbles(listOf("a"), emptyList()))
    }

    @Test
    fun `the focus starts on the pictures, else on the bar`() {
        val all = NotificationDetail.zones(photos = true, replyRow = true, bar = true)
        assertEquals(listOf(DetailZone.CONTENT, DetailZone.REPLY, DetailZone.BAR), all)
        assertEquals(DetailZone.CONTENT, NotificationDetail.initial(all))
        assertEquals(DetailZone.BAR, NotificationDetail.initial(NotificationDetail.zones(photos = false, replyRow = true, bar = true)))
        assertEquals(DetailZone.REPLY, NotificationDetail.initial(NotificationDetail.zones(photos = false, replyRow = true, bar = false)))
        assertNull(NotificationDetail.initial(NotificationDetail.zones(photos = false, replyRow = false, bar = false)))
    }

    @Test
    fun `down scrolls the content to its end, then goes to the reply row and the bar`() {
        val zones = listOf(DetailZone.CONTENT, DetailZone.REPLY, DetailZone.BAR)
        assertEquals(Step.Scroll(1), NotificationDetail.vertical(zones, DetailZone.CONTENT, down = true, canScrollDown = true))
        assertEquals(Step.Zone(DetailZone.REPLY), NotificationDetail.vertical(zones, DetailZone.CONTENT, down = true, canScrollDown = false))
        assertEquals(Step.Zone(DetailZone.BAR), NotificationDetail.vertical(zones, DetailZone.REPLY, down = true, canScrollDown = false))
        // The bottom zone's down scrolls the text on, as the bar's did.
        assertEquals(Step.Scroll(1), NotificationDetail.vertical(zones, DetailZone.BAR, down = true, canScrollDown = true))
        // Up goes back the same way, and the content's up scrolls back.
        assertEquals(Step.Zone(DetailZone.REPLY), NotificationDetail.vertical(zones, DetailZone.BAR, down = false, canScrollDown = false))
        assertEquals(Step.Zone(DetailZone.CONTENT), NotificationDetail.vertical(zones, DetailZone.REPLY, down = false, canScrollDown = false))
        assertEquals(Step.Scroll(-1), NotificationDetail.vertical(zones, DetailZone.CONTENT, down = false, canScrollDown = false))
        // A text-only notification: the reply row's up scrolls the text; nothing below the content alone.
        assertEquals(Step.Scroll(-1), NotificationDetail.vertical(listOf(DetailZone.REPLY, DetailZone.BAR), DetailZone.REPLY, down = false, canScrollDown = false))
        assertEquals(Step.Stay, NotificationDetail.vertical(listOf(DetailZone.CONTENT), DetailZone.CONTENT, down = true, canScrollDown = false))
        assertEquals(Step.Scroll(1), NotificationDetail.vertical(emptyList(), null, down = true, canScrollDown = true))
        assertEquals(ReplyPart.SEND, NotificationDetail.side(1))
        assertEquals(ReplyPart.FIELD, NotificationDetail.side(-1))
    }

    @Test
    fun `send needs text and nothing in flight`() {
        val empty = ReplyState()
        assertFalse(empty.canSend)
        assertFalse(empty.edited("   ").canSend)
        val typed = empty.edited("Que lindo!")
        assertTrue(typed.canSend)
        val sending = typed.sent()
        assertFalse(sending.canSend)
        assertTrue(sending.sending)
    }

    @Test
    fun `a reply that went clears the field, one that didn't keeps the text to try again`() {
        val sending = ReplyState().edited("Where is it?").sent()
        assertEquals(ReplyState(), sending.replied(ok = true))
        val failed = sending.replied(ok = false)
        assertEquals("Where is it?", failed.text)
        assertTrue(failed.failed)
        assertTrue(failed.canSend)
        assertEquals(ReplyHint.RETRY, failed.hint(ReplyPart.SEND, typing = false))
        // Editing it is a new reply: no longer a retry.
        val edited = failed.edited("Where is it? :)")
        assertFalse(edited.failed)
        assertEquals(ReplyHint.SEND, edited.hint(ReplyPart.SEND, typing = false))
        assertEquals(failed, failed.edited("Where is it?"))
    }

    @Test
    fun `the hint follows the band and goes while typing`() {
        assertEquals(ReplyHint.FIELD, ReplyState().hint(ReplyPart.FIELD, typing = false))
        assertNull(ReplyState().hint(ReplyPart.SEND, typing = false))
        assertNull(ReplyState().edited("x").hint(ReplyPart.FIELD, typing = true))
        assertNull(ReplyState().edited("x").sent().hint(ReplyPart.SEND, typing = false))
    }

    @Test
    fun `a picture fits its box, keeping its shape`() {
        assertEquals(380 to 285, NotificationDetail.fitInto(480, 360, 380, 300))
        assertEquals(117 to 260, NotificationDetail.fitInto(216, 480, 380, 260))
        assertEquals(100 to 50, NotificationDetail.fitInto(100, 50, 380, 260))
        assertEquals(380 to 1, NotificationDetail.fitInto(48_000, 10, 380, 260))
        assertEquals(1 to 1, NotificationDetail.fitInto(0, 0, 380, 260))
    }
}
