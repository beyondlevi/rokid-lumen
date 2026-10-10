package dev.lumen.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class PicturesTest {
    @Test
    fun `a picture request crosses the link and back`() {
        val sent = NotifyCommand.picture("p1", "0|com.whatsapp|1|chat", 2, 1_700_000_000_000L)
        val read = NotifyCommand.pictureOf(Link.parse(sent.toString()))
        assertEquals(PictureRequest("p1", "0|com.whatsapp|1|chat", 2, 1_700_000_000_000L), read)
        assertEquals(NotifyCommand.PICTURE, sent.getString("action"))
        // Another action, an incomplete request or an index past the limit is no request.
        assertNull(NotifyCommand.pictureOf(NotifyCommand.reply("k", "hi")))
        assertNull(NotifyCommand.pictureOf(Link.message().put("action", "picture").put("key", "k").put("index", 0)))
        assertNull(NotifyCommand.pictureOf(NotifyCommand.picture("p", "k", PictureOps.MAX_PICTURES, 0)))
        assertNull(NotifyCommand.replyOf(sent))
    }

    @Test
    fun `answers carry the header or the reason`() {
        val request = PictureRequest("p1", "k", 0, 5L)
        val bytes = Random(3).nextBytes(Chunks.SIZE * 2 + 10)
        val ok = PictureAnswer.of(request, bytes, 480, 360)
        val read = PictureAnswer.from(Link.parse(ok.toJson().toString()))!!
        assertEquals(ok, read)
        assertTrue(read.ok)
        assertEquals(3, read.chunks)
        assertEquals(PictureOps.MIME, ok.toJson().getString("mime"))
        val receiver = ChunkReceiver(read.size, read.sha256, read.chunks)
        (0 until read.chunks).forEach { receiver.put(it, Chunks.piece(bytes, it)) }
        assertArrayEquals(bytes, receiver.bytes())

        val failed = PictureAnswer.from(PictureAnswer.failed(request, PictureOps.REASON_DENIED).toJson())!!
        assertFalse(failed.ok)
        assertEquals(PictureOps.REASON_DENIED, failed.reason)
        assertEquals("k", failed.key)
        assertEquals(0, failed.index)
        // A chunk is not an answer.
        assertNull(PictureAnswer.from(PictureOps.chunk("p1", 0)))
    }

    @Test
    fun `a post names its newest pictures, captions cut`() {
        val pictures = (1..6).map { NotificationPicture(it.toLong(), if (it == 6) "x".repeat(500) else "caption $it") }
        val post = NotifyEvent.putPictures(NotifyEvent.post("k"), pictures)
        val read = NotifyEvent.picturesOf(Link.parse(post.toString()))
        assertEquals(PictureOps.MAX_PICTURES, read.size)
        assertEquals(listOf(3L, 4L, 5L, 6L), read.map { it.at })
        assertEquals("caption 3", read.first().caption)
        assertEquals(PictureOps.MAX_CAPTION, read.last().caption.length)
        // None: no field at all, and an old companion's post reads as none.
        assertFalse(NotifyEvent.putPictures(NotifyEvent.post("k"), emptyList()).has("pictures"))
        assertEquals(emptyList<NotificationPicture>(), NotifyEvent.picturesOf(NotifyEvent.post("k")))
    }

    @Test
    fun `the picture channels go one way each`() {
        assertTrue(Link.PICTURE_EVENT in Link.TO_GLASSES)
        assertTrue(Link.PICTURE in Link.TO_PHONE)
        assertEquals(PictureOps.ACK, PictureOps.message(PictureOps.ACK, "p").getString("op"))
    }
}
