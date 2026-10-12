package dev.lumen.companion

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbViaPhoneTest {
    private val hour = 3_600_000L

    @Test
    fun `wireless debugging switches itself off after its hours, never with Never`() {
        val since = 1_000_000L
        assertFalse(AutoOff.due(since, 4, since + 4 * hour - 1))
        assertTrue(AutoOff.due(since, 4, since + 4 * hour))
        assertTrue(AutoOff.due(since, 1, since + hour))
        assertFalse(AutoOff.due(since, AutoOff.NEVER, since + 100 * hour))
        // Off (no start time): nothing to switch off.
        assertFalse(AutoOff.due(0, 1, 100 * hour))
        assertEquals(listOf(1, 4, AutoOff.NEVER), AutoOff.CHOICES)
    }

    @Test
    fun `pairing codes are six digits, leading zeros kept`() {
        repeat(200) {
            val code = PairingCode.next()
            assertTrue(code, dev.lumen.protocol.SettingsOps.isPairingCode(code))
        }
    }

    @Test
    fun `the Tailscale address is the one in 100_64_0_0 slash 10`() {
        assertTrue(TailnetAddress.isTailnet("100.117.10.107"))
        assertTrue(TailnetAddress.isTailnet("100.64.0.1"))
        assertTrue(TailnetAddress.isTailnet("100.127.255.254"))
        assertFalse(TailnetAddress.isTailnet("100.128.0.1"))
        assertFalse(TailnetAddress.isTailnet("100.63.255.255"))
        assertFalse(TailnetAddress.isTailnet("192.168.68.62"))
        assertFalse(TailnetAddress.isTailnet("fd7a:115c:a1e0::1"))
    }

    @Test
    fun `an H264 stream splits into units at its start codes, whatever the chunks`() {
        val sps = byteArrayOf(0, 0, 0, 1, 0x67, 0x42, 0x00, 0x1f)
        val pps = byteArrayOf(0, 0, 0, 1, 0x68, 0xce.toByte(), 0x3c, 0x80.toByte())
        val idr = byteArrayOf(0, 0, 1, 0x65, 0x88.toByte(), 0x84.toByte(), 0x00, 0x33)
        val slice = byteArrayOf(0, 0, 0, 1, 0x41, 0x9a.toByte(), 0x02)
        val stream = sps + pps + idr + slice
        // Byte by byte, as a slow network might deliver it.
        val splitter = AnnexB()
        val units = stream.flatMap { splitter.feed(byteArrayOf(it), 1) }
        assertEquals(3, units.size)
        assertArrayEquals(sps, units[0])
        assertArrayEquals(pps, units[1])
        assertArrayEquals(idr, units[2])
        // The last unit waits for the next start code, or the end.
        assertArrayEquals(slice, splitter.flush())
        assertNull(splitter.flush())
        assertEquals(AnnexB.SPS, AnnexB.type(units[0]))
        assertEquals(AnnexB.PPS, AnnexB.type(units[1]))
        assertEquals(5, AnnexB.type(units[2]))
        assertEquals(1, AnnexB.type(slice))
        // All at once: the same units.
        val whole = AnnexB().feed(stream)
        assertEquals(3, whole.size)
        assertArrayEquals(idr, whole[2])
    }
}
