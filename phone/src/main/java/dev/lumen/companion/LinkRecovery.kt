package dev.lumen.companion

/**
 * When the link is stuck where only the home screen's Reconnect gets it back, the service
 * reconnects by itself. Seen on Levi's phone: Hi Rokid reports the glasses app's session
 * unavailable with the glasses near and Lumen open, and nothing brings it back until the link
 * is configured again, which is what Reconnect does.
 *
 * A stuck state waits a little first (the SDK may recover alone), then each new attempt waits
 * longer; a working session starts over. Glasses away (Bluetooth off or out of range) are not
 * stuck: reconnecting can't bring them back, and Hi Rokid reports them when they return.
 */
internal class LinkRecovery {
    private var attempt = 0

    /** The wait before reconnecting, now that the link is in [state]; null for none. */
    fun delayFor(state: LinkState): Long? = when (state) {
        in WORKING -> {
            attempt = 0
            null
        }
        in STUCK -> DELAYS_MS[minOf(attempt, DELAYS_MS.lastIndex)]
        else -> null
    }

    /** Whether a reconnect due now still has a reason to happen. Counts it. */
    fun shouldReconnect(state: LinkState): Boolean {
        if (state !in STUCK) return false
        attempt++
        return true
    }

    /** Attempts since the session last worked. */
    val attempts: Int get() = attempt

    companion object {
        private val WORKING = setOf(LinkState.SESSION_READY, LinkState.SESSION_ACTIVE, LinkState.DICTATING)
        private val STUCK = setOf(LinkState.SESSION_UNAVAILABLE, LinkState.HI_ROKID_DISCONNECTED, LinkState.REFUSED)
        /** 10 s, 30 s, 1 min, 2 min, then every 5 min. */
        private val DELAYS_MS = longArrayOf(10_000, 30_000, 60_000, 120_000, 300_000)
    }
}
