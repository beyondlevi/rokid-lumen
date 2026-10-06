package dev.lumen.band

import org.json.JSONObject

/**
 * Where gestures come from, as each app's runtime sees it. Each device turns the
 * gestures it recognises into action ids itself, from the app's mapping string
 * ([BandLink.Config.mapping]), and reports them to its [Listener].
 *
 * [BandLink] is the real band, over the protocol kinesis worked out (Meta
 * publishes no Android API for the band's gestures; see NEURALBAND.md).
 * [SimulatedBand] stands in for it, so the mapping and the profiles can be
 * tried without the hardware. An official SDK, should Meta publish one, would
 * be a third implementation.
 */
interface GestureDevice {
    /** Called from any thread; the service moves to the main thread. */
    interface Listener {
        fun onPhase(phase: Phase, band: String?)

        /** Keys: paused, battery, charging, hand, dial, last_gesture, gestures (see [Bridge.status]). */
        fun onStatus(status: JSONObject)
        fun onActions(actions: List<String>)
        fun onLog(line: String)

        /** A handwriting event (see [Bridge.handwritingEvents]); the text is never to be logged. */
        fun onHandwriting(event: JSONObject) = Unit

        /** The air mouse's records (see [Bridge.pointer]), after the drain's actions. */
        fun onPointer(records: DoubleArray) = Unit
    }

    fun start()
    fun stop()
    fun setPaused(paused: Boolean)

    /** The band's motion streams (pinch and turn) on or off, to save its power; on by default. */
    fun setMotion(enabled: Boolean) = Unit

    /**
     * The band's gesture stream on or off; on by default. Off together with motion is the band's
     * power saving: it recognizes nothing and doesn't vibrate, so no gesture reaches this device.
     */
    fun setGestures(enabled: Boolean) = Unit

    /** `gesture=action;…` (see [BandLink.Config.mapping]). */
    fun setMapping(mapping: String)

    /**
     * The band's handwriting model on or off; false when it can't start now (no band connected).
     * Progress and text come to [Listener.onHandwriting]; off always puts the band back.
     */
    fun setHandwriting(enabled: Boolean): Boolean = false

    /** Start the written text over from [text] (what the field holds). */
    fun resetHandwritingText(text: String) = Unit

    /**
     * The air mouse on or off (see [Bridge.setPointer]); false when it can't run here. Its
     * movement and buttons come to [Listener.onPointer]. It ends with the connection.
     */
    fun setPointer(enabled: Boolean, tuning: String): Boolean = false
}
