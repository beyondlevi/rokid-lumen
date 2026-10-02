package dev.lumen.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class OggOpusWriterTest {
    /** libopus's 20 ms CELT silence packet. */
    private val silence = byteArrayOf(0xf8.toByte(), 0xff.toByte(), 0xfe.toByte())

    private data class Page(val flags: Int, val granule: Long, val sequence: Int, val packets: Int, val crcOk: Boolean)

    private fun pages(file: ByteArray): List<Page> {
        val out = mutableListOf<Page>()
        var at = 0
        while (at < file.size) {
            assertEquals("OggS", String(file, at, 4, Charsets.US_ASCII))
            val segments = file[at + 26].toInt() and 0xff
            val lacing = (0 until segments).map { file[at + 27 + it].toInt() and 0xff }
            val size = 27 + segments + lacing.sum()
            val page = file.copyOfRange(at, at + size)
            val stored = (0 until 4).fold(0) { acc, i -> acc or ((page[22 + i].toInt() and 0xff) shl (8 * i)) }
            for (i in 22 until 26) page[i] = 0
            var granule = 0L
            for (i in 7 downTo 0) granule = (granule shl 8) or (file[at + 6 + i].toLong() and 0xff)
            val sequence = (0 until 4).fold(0) { acc, i -> acc or ((file[at + 18 + i].toInt() and 0xff) shl (8 * i)) }
            out += Page(file[at + 5].toInt(), granule, sequence, lacing.count { it < 255 }, OggOpusWriter.crc(page) == stored)
            at += size
        }
        return out
    }

    @Test
    fun `granules count each page's end, never a negative start`() {
        val writer = OggOpusWriter(preSkip = 312, inputRate = 16_000)
        repeat(130) { writer.packet(silence) } // 2.6 s
        val file = writer.finish()
        File(System.getProperty("java.io.tmpdir"), "lumen-ogg-opus-test.ogg").writeBytes(file)
        val pages = pages(file)
        assertTrue(pages.all { it.crcOk })
        assertEquals(listOf(0x02, 0, 0, 0, 0x04), pages.map { it.flags })
        assertEquals((0 until pages.size).toList(), pages.map { it.sequence })
        // Head and tags at 0; each audio page ends where its audio ends (50 packets a page).
        assertEquals(listOf(0L, 0L, 48_000L, 96_000L, 124_800L), pages.map { it.granule })
        assertEquals(listOf(1, 1, 50, 50, 30), pages.map { it.packets })
        assertEquals(960, OggOpusWriter.samples(silence))
        assertEquals(1920, OggOpusWriter.samples(byteArrayOf(0x09, 0))) // SILK 40 ms
        assertEquals(2880, OggOpusWriter.samples(byteArrayOf(0xfb.toByte(), 0x03))) // CELT 20 ms × 3
    }
}
