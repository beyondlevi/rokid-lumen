package dev.lumen.band

/** The Rust band-core session and gesture controller (rust/bridge). Not thread-safe: callers serialise. */
object Bridge {
    init {
        System.loadLibrary("lumen_band")
    }

    /** A connection for the desktop's owner.key and band.json scheme guess, with the app's settings. */
    @JvmStatic external fun open(ownerKey: ByteArray, schemeGuess: Int, paused: Boolean, dial: String, mapping: String): Long
    /** The first bytes to write once the L2CAP channel is open. */
    @JvmStatic external fun request(handle: Long): ByteArray
    /** Bytes read from the band; returns bytes to write. */
    @JvmStatic external fun feed(handle: Long, bytes: ByteArray, now: Double): ByteArray
    /** Call every ~50 ms; returns bytes to write. */
    @JvmStatic external fun tick(handle: Long, now: Double): ByteArray
    /** Action names to run since the last call, '\n'-joined ("media.next", "dial.changed.brightness", …). */
    @JvmStatic external fun actions(handle: Long): String
    /** Log lines since the last call, '\n'-joined. */
    @JvmStatic external fun log(handle: Long): String
    /** Status snapshot JSON: connected, paused, battery, charging, hand, dial, last_gesture, last_action, gestures. */
    @JvmStatic external fun status(handle: Long): String
    @JvmStatic external fun setPaused(handle: Long, paused: Boolean, now: Double)
    /** The band's motion streams (gyro, orientation; pinch and turn) on or off; sent with the next tick. */
    @JvmStatic external fun setMotion(handle: Long, enabled: Boolean)
    /** The band's gesture stream on or off (off with motion off: the band's power saving); sent with the next tick. */
    @JvmStatic external fun setGestures(handle: Long, enabled: Boolean)
    @JvmStatic external fun setDial(handle: Long, dial: String)
    /** `gesture=action;…` (see [BandLink.Config.mapping]). */
    @JvmStatic external fun setMapping(handle: Long, mapping: String)
    @JvmStatic external fun close(handle: Long)
}
