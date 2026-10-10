package dev.lumen.glasses

import dev.lumen.band.BandLink
import dev.lumen.band.GestureDevice
import dev.lumen.band.Phase
import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject

/**
 * A band that isn't there: in a debug build,
 * `adb shell am broadcast -a dev.lumen.glasses.SIMULATE --es gesture swipe_right`
 * stands in for what the band recognises. It resolves the gesture with the same
 * mapping string the bridge gets, so the navigation and the mapped actions run
 * exactly as they would from the band. It doesn't model the bridge's timing
 * (a single tap waiting out its double), only the mapping.
 *
 * It also writes: with its handwriting on, `--es handwriting_text "<text>"` arrives one letter at
 * a time, as the band's model sends it (for screenshots and demos).
 */
class SimulatedBand(private val listener: GestureDevice.Listener, private val context: Context) : GestureDevice {
    private var running = false
    private var paused = false
    private var mapping = emptyMap<String, String>()
    private var gestures = 0L
    private var lastGesture: String? = null
    private val main = Handler(Looper.getMainLooper())
    private var writing = false
    private var written = ""

    override fun start() {
        if (running) return
        running = true
        // As BandLink opens its session: the saved controls state and the active profile's map.
        paused = GestureMappings.isPaused(context)
        mapping = parse(GestureMappings.mapping(context))
        listener.onLog("simulated band: gestures come from the app, not Bluetooth")
        listener.onPhase(Phase.CONNECTED, NAME)
        publish()
    }

    override fun stop() {
        setHandwriting(false)
        running = false
        listener.onPhase(Phase.STOPPED, null)
    }

    override fun setHandwriting(enabled: Boolean): Boolean {
        if (enabled && !running) return false
        if (enabled == writing) return true
        writing = enabled
        main.removeCallbacksAndMessages(null)
        if (enabled) {
            main.postDelayed({ handwritingState("ready") }, READY_MS)
        } else {
            handwritingState("finished")
        }
        return true
    }

    override fun resetHandwritingText(text: String) {
        written = text
    }

    /** As if the band's model read [text], a letter every [LETTER_MS]; false when it isn't writing. */
    fun write(text: String): Boolean {
        if (!writing) return false
        text.indices.forEach { index ->
            main.postDelayed({
                if (!writing) return@postDelayed
                written += text[index]
                listener.onHandwriting(JSONObject().put("type", "text").put("text", written))
            }, LETTER_MS * (index + 1))
        }
        return true
    }

    private fun handwritingState(phase: String) {
        listener.onHandwriting(
            JSONObject().put("type", "state").put("phase", phase).put("verified", phase == "finished"),
        )
    }

    override fun setPaused(paused: Boolean) {
        this.paused = paused
        publish()
    }

    override fun setMapping(mapping: String) {
        this.mapping = parse(mapping)
        publish()
    }

    /** As if the band recognised [key]: one of [KEYS]. */
    fun perform(key: String) {
        if (!running) return
        if (key !in KEYS) {
            listener.onLog("simulated band: no gesture $key")
            return
        }
        if (mapping[key] == PAUSE) {
            // The pause: the one action that works while the controls are off.
            paused = !paused
            publish()
            return
        }
        gestures++
        lastGesture = label(key)
        resolve(mapping, key, paused)?.let { listener.onActions(listOf(it)) }
        publish()
    }

    private fun publish() {
        if (!running) return
        val hand = mapping["hand"]?.takeIf { it == "left" || it == "right" }
        listener.onStatus(
            JSONObject()
                .put("connected", true)
                .put("paused", paused)
                .put("battery", JSONObject.NULL)
                .put("charging", false)
                .put("hand", hand ?: JSONObject.NULL)
                .put("dial", "volume")
                .put("last_gesture", lastGesture ?: JSONObject.NULL)
                .put("gestures", gestures),
        )
    }

    companion object {
        const val NAME = "Simulated band"
        const val MIDDLE_HOLD = "middle_hold"
        const val INDEX_HOLD = "index_hold"

        /** The bridge's pause (rust/bridge `PAUSE_TOGGLE`). */
        const val PAUSE = "band.pause"
        const val DIAL_UP = "dial_up"
        const val DIAL_DOWN = "dial_down"
        private const val READY_MS = 800L
        private const val LETTER_MS = 650L

        /** What the simulator can do: the mappable gestures and a dial step each way. */
        val KEYS = listOf(
            "swipe_up", "swipe_down", "swipe_left", "swipe_right",
            "index_tap", "index_double", "middle_tap", "middle_double",
            DIAL_UP, DIAL_DOWN, INDEX_HOLD, MIDDLE_HOLD,
        )

        /** `key=value;…` into a map; later pairs win, as in the bridge. */
        fun parse(mapping: String): Map<String, String> = mapping.split(';')
            .mapNotNull { pair -> pair.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .toMap()

        /** The action [key] runs under [mapping], or null (unassigned, or the controls are off). */
        fun resolve(mapping: Map<String, String>, key: String, paused: Boolean): String? =
            mapping[key]?.takeIf { !paused && it.isNotBlank() }

        fun label(key: String) = when (key) {
            DIAL_UP -> "pinch and turn up"
            DIAL_DOWN -> "pinch and turn down"
            MIDDLE_HOLD -> "middle hold"
            INDEX_HOLD -> "index hold"
            else -> key.replace('_', ' ')
        }
    }
}
