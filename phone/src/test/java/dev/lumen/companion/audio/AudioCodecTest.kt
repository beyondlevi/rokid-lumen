package dev.lumen.companion.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class AudioCodecTest {
    private fun samples(bytes: ByteArray) = ShortArray(bytes.size / 2) { i ->
        ((bytes[2 * i].toInt() and 0xff) or (bytes[2 * i + 1].toInt() shl 8)).toShort()
    }

    @Test
    fun `48 kHz in pieces becomes 16 kHz, a third as long, the tone kept`() {
        val tone = ShortArray(48_000) { (8_000 * sin(2 * PI * 440 * it / 48_000.0)).toInt().toShort() }
        val resampler = AudioCodec.Resampler()
        // Odd-sized pieces, as a decoder hands them out.
        val out = (0 until tone.size step 1_001).flatMap { at ->
            samples(resampler.feed(tone.copyOfRange(at, minOf(tone.size, at + 1_001)), 48_000)).toList()
        }
        assertTrue(abs(out.size - 16_000) <= 2)
        val peak = out.maxOf { abs(it.toInt()) }
        assertTrue("peak $peak", peak in 7_000..8_100)
    }

    @Test
    fun `16 kHz passes through and stereo is averaged`() {
        val input = shortArrayOf(1, -2, 300, 4)
        assertEquals(input.toList(), samples(AudioCodec.Resampler().feed(input, 16_000)).toList())
        assertEquals(listOf<Short>(2, 6), AudioCodec.mono(shortArrayOf(1, 3, 5, 7), 2).toList())
    }
}
