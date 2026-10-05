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
    /**
     * A connection that claims a band in pairing mode (the ownership ceremony with a fresh key)
     * instead of signing in with a stored one; it carries on as a normal connection once claimed.
     */
    @JvmStatic external fun openClaim(schemeGuess: Int, paused: Boolean, dial: String, mapping: String): Long
    /**
     * The ceremony's events since the last call, '\n'-joined JSON objects: `stage` (text),
     * `pair_request` (device_cert, serial, secondary_cert, nonce, app_pubkey; bytes in hex),
     * `pair` (receipt, signature), `completed` (owner_key, hex). Never log them.
     */
    @JvmStatic external fun claimEvents(handle: Long): String
    /** After `pair_request`: the server's signature and pending receipt; returns bytes to write. */
    @JvmStatic external fun claimPairRequestCompleted(handle: Long, signature: ByteArray, receipt: String): ByteArray
    /** After `pair`; [deviceKey] may be null. Persist [claimPendingKey] BEFORE writing the bytes returned. */
    @JvmStatic external fun claimPairCompleted(handle: Long, signature: ByteArray, receipt: String, deviceKey: ByteArray?): ByteArray
    /** The owner key the band is about to commit, null before [claimPairCompleted]. */
    @JvmStatic external fun claimPendingKey(handle: Long): ByteArray?
    @JvmStatic external fun close(handle: Long)
}
