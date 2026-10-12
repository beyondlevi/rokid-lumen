package dev.lumen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkRecoveryTest {
    @Test
    fun `an unavailable session reconnects, waiting longer each time`() {
        val recovery = LinkRecovery()
        val waits = (1..6).map {
            val wait = recovery.delayFor(LinkState.SESSION_UNAVAILABLE)
            assertTrue(recovery.shouldReconnect(LinkState.SESSION_UNAVAILABLE))
            wait
        }
        assertEquals(listOf(10_000L, 30_000L, 60_000L, 120_000L, 300_000L, 300_000L), waits)
    }

    @Test
    fun `a working session starts over`() {
        val recovery = LinkRecovery()
        recovery.delayFor(LinkState.SESSION_UNAVAILABLE)
        recovery.shouldReconnect(LinkState.SESSION_UNAVAILABLE)
        recovery.delayFor(LinkState.SESSION_UNAVAILABLE)
        recovery.shouldReconnect(LinkState.SESSION_UNAVAILABLE)
        assertNull(recovery.delayFor(LinkState.SESSION_READY))
        assertEquals(0, recovery.attempts)
        assertEquals(10_000L, recovery.delayFor(LinkState.SESSION_UNAVAILABLE))
    }

    @Test
    fun `glasses away are not stuck`() {
        val recovery = LinkRecovery()
        assertNull(recovery.delayFor(LinkState.GLASSES_DISCONNECTED))
        assertNull(recovery.delayFor(LinkState.NOT_AUTHORIZED))
        assertNull(recovery.delayFor(LinkState.CONNECTING))
    }

    @Test
    fun `a reconnect that comes due after the link recovered does nothing`() {
        val recovery = LinkRecovery()
        recovery.delayFor(LinkState.SESSION_UNAVAILABLE)
        // The session came back alone before the wait ended.
        recovery.delayFor(LinkState.SESSION_ACTIVE)
        assertFalse(recovery.shouldReconnect(LinkState.SESSION_ACTIVE))
        assertEquals(0, recovery.attempts)
    }

    @Test
    fun `Hi Rokid gone and a refused connect are stuck too`() {
        assertEquals(10_000L, LinkRecovery().delayFor(LinkState.HI_ROKID_DISCONNECTED))
        assertEquals(10_000L, LinkRecovery().delayFor(LinkState.REFUSED))
    }
}
