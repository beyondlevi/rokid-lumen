package dev.lumen.companion

import dev.lumen.protocol.NotificationPicture
import dev.lumen.protocol.PictureOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PictureSizingTest {
    private fun photo(at: Long, text: String? = null, mime: String? = "image/jpeg", uri: String? = "content://com.whatsapp.provider.media/item/$at") =
        MessageData(mime, uri, text, at)

    @Test
    fun `a chat's photos are its image messages, newest last, with their text as the caption`() {
        val messages = listOf(
            photo(1, mime = null, uri = null, text = "Hi"),
            photo(2, "📷 Photo"),
            photo(3, "Look at\nthis view!  "),
            // A voice note and an image without a URI aren't pictures.
            photo(4, "🎤 0:03", mime = "audio/ogg"),
            photo(5, "x", uri = null),
        )
        val sources = PictureSizing.select(messages, big = null)
        assertEquals(listOf(NotificationPicture(2, "📷 Photo"), NotificationPicture(3, "this view!")), sources.map { it.picture })
        assertEquals("content://com.whatsapp.provider.media/item/3", (sources.last() as PictureSource.Message).uri)
    }

    @Test
    fun `the big picture comes last, and only the newest few are named`() {
        val messages = (1..6L).map { photo(it) }
        val sources = PictureSizing.select(messages, big = NotificationPicture(99, "Summary"))
        assertEquals(PictureOps.MAX_PICTURES, sources.size)
        assertEquals(listOf(4L, 5L, 6L, 99L), sources.map { it.picture.at })
        assertTrue(sources.last() is PictureSource.Big)
        assertEquals(listOf<PictureSource>(PictureSource.Big(NotificationPicture(7))), PictureSizing.select(emptyList(), NotificationPicture(7)))
        assertEquals("", PictureSizing.caption(null))
        assertEquals("", PictureSizing.caption("  \n "))
    }

    @Test
    fun `a request finds its picture by index, or by time once the notification moved on`() {
        val sources = PictureSizing.select((1..3L).map { photo(it) }, big = null)
        assertEquals(2L, PictureSizing.resolve(sources, 1, 2)?.picture?.at)
        // A new photo pushed the old ones along: the time still names the right one.
        val later = PictureSizing.select((2..5L).map { photo(it) }, big = null)
        assertEquals(2L, PictureSizing.resolve(later, 1, 2)?.picture?.at)
        assertNull(PictureSizing.resolve(later, 0, 1))
        assertNull(PictureSizing.resolve(emptyList(), 0, 0))
    }

    @Test
    fun `decoding samples down by powers of two without going under the HUD's side`() {
        assertEquals(8, PictureSizing.sampleSize(4000, 3000, 480))
        assertEquals(2, PictureSizing.sampleSize(960, 640, 480))
        assertEquals(1, PictureSizing.sampleSize(959, 640, 480))
        assertEquals(1, PictureSizing.sampleSize(300, 200, 480))
        assertEquals(4, PictureSizing.sampleSize(1080, 2400, 480))
        assertEquals(1, PictureSizing.sampleSize(0, 0, 480))
        // Whatever comes out still has the HUD's side to scale from.
        listOf(4000 to 3000, 1080 to 2400, 961 to 100, 5000 to 5000).forEach { (w, h) ->
            val sample = PictureSizing.sampleSize(w, h, 480)
            assertTrue(maxOf(w, h) / sample >= 480)
            assertTrue(maxOf(w, h) / (sample * 2) < 480)
        }
    }

    @Test
    fun `fitting keeps the shape, never enlarges, and keeps a pixel`() {
        assertEquals(480 to 360, PictureSizing.fit(4000, 3000, 480))
        assertEquals(216 to 480, PictureSizing.fit(1080, 2400, 480))
        assertEquals(300 to 200, PictureSizing.fit(300, 200, 480))
        assertEquals(480 to 1, PictureSizing.fit(10_000, 10, 480))
        assertEquals(1 to 1, PictureSizing.fit(0, 0, 480))
    }

    @Test
    fun `the JPEG tries lower quality before a smaller size, starting at the HUD's side`() {
        assertEquals(PictureOps.MAX_SIDE, PictureSizing.ATTEMPTS.first().first)
        val sides = PictureSizing.ATTEMPTS.map { it.first }
        assertEquals(sides.sortedDescending(), sides)
        assertTrue(PictureSizing.ATTEMPTS.all { (_, quality) -> quality in 30..80 })
    }
}
