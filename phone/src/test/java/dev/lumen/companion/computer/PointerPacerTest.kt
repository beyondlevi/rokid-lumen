package dev.lumen.companion.computer

import dev.lumen.band.PointerCounts
import dev.lumen.band.PointerPacer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ported from kinesis' pacer tests (AirCursorTests.swift). */
class PointerPacerTest {
    @Test
    fun `the pacer plays batches back evenly at any refresh rate`() {
        for (refresh in listOf(60.0, 82.0, 100.0, 144.0, 125.0)) {
            // The arm moves 1 count per 128 Hz sample; two samples arrive together every
            // 15 ms, and every fifth batch after 30 ms.
            val pacer = PointerPacer()
            var sample = 0
            var arrival = 0.0
            var batches = 0
            val steps = mutableListOf<Double>()
            var frame = 0.0
            while (frame < 2) {
                frame += 1 / refresh
                while (arrival <= frame) {
                    while (sample / 128.0 <= arrival) {
                        pacer.add(1.0, 0.0, sample / 128.0)
                        sample++
                    }
                    batches++
                    arrival += if (batches % 5 == 0) 0.030 else 0.015
                }
                steps += pacer.take(frame)?.get(0) ?: 0.0
            }
            val expected = 128 / refresh
            val settled = steps.drop(10)
            val even = settled.count { Math.abs(it - expected) < expected * 0.05 }
            assertTrue("$refresh Hz", even > settled.size * 0.85)
            val close = settled.count { Math.abs(it - expected) < expected * 0.5 }
            assertTrue("$refresh Hz", close > settled.size * 0.95)
            assertTrue(Math.abs(steps.sum() - sample) < 8)
        }
    }

    @Test
    fun `the pacer drops what is waiting on a click`() {
        val pacer = PointerPacer()
        pacer.add(5.0, 0.0, 1.0)
        pacer.clear()
        assertNull(pacer.take(2.0))
        assertTrue(pacer.isEmpty)
    }

    @Test
    fun `with no playback delay it posts everything, and keeps a tiny step for later`() {
        val pacer = PointerPacer(seconds = 0.0)
        pacer.add(4.0, -2.0, 1.0)
        assertArrayEquals(doubleArrayOf(4.0, -2.0), pacer.take(1.0), 1e-9)
        pacer.add(0.01, 0.0, 1.01)
        assertNull(pacer.take(1.01))
    }

    @Test
    fun `counts carry their fractions, so slow moves add up`() {
        val counts = PointerCounts()
        var total = 0
        repeat(12) { total += counts.add(0.25, 0.0)[0] }
        assertEquals(3, total)
        val back = counts.add(-1.7, 2.6)
        assertEquals(-1, back[0])
        assertEquals(2, back[1])
        counts.reset()
        assertEquals(0, counts.add(0.9, 0.9)[0])
    }
}
