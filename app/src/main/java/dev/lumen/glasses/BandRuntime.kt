package dev.lumen.glasses

import dev.lumen.band.BandLink
import dev.lumen.band.GestureDevice
import dev.lumen.band.Identity
import dev.lumen.band.Phase
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject

/**
 * The band link while the accessibility service runs. On the glasses there's no foreground
 * service: notifications don't reach the HUD, so the link lives in the accessibility service,
 * which Android keeps bound (and the self-arm watchdog re-enables after the firmware strips it),
 * as R08 Access Bridge keeps its ring. Everything here runs on the main thread.
 */
object BandRuntime {
    private const val TAG = "BandRuntime"
    private const val LOG_LINES = 40

    fun interface ActionSink {
        fun onBandAction(action: String)
    }

    /** The activity follows the state to redraw its status line. */
    fun interface StateListener {
        fun onBandStateChanged()
    }

    private val main = Handler(Looper.getMainLooper())
    private var device: GestureDevice? = null
    private var sink: ActionSink? = null
    private var appContext: Context? = null
    private val listeners = LinkedHashSet<StateListener>()
    private val log = ArrayDeque<String>()

    @JvmStatic var phase = Phase.STOPPED
        private set

    @JvmStatic var bandName: String? = null
        private set

    @JvmStatic var status: JSONObject = JSONObject()
        private set

    @JvmStatic
    fun isRunning() = device != null

    /** Start the link (or the simulated band); returns why it can't, or null. */
    @JvmStatic
    fun start(context: Context, sink: ActionSink): String? {
        this.sink = sink
        if (device != null) return null
        val app = context.applicationContext
        val simulated = GestureMappings.isSimulated(app)
        appContext = app
        if (!simulated && GestureMappings.isBandOnPhone(app)) {
            note("the band is with the phone: Reconnect here or hand it back from the phone")
            return app.getString(R.string.runtime_band_on_phone)
        }
        if (!simulated && !Identity.present(app)) {
            note("no owner key yet: import the band's key first")
            return app.getString(R.string.runtime_import_first)
        }
        // Scanning or connecting without them throws, and would take the service down.
        if (!simulated && !bluetoothGranted(app)) {
            note("Bluetooth permission missing: open the app to grant it")
            return app.getString(R.string.runtime_bluetooth)
        }
        val listener = object : GestureDevice.Listener {
            override fun onPhase(phase: Phase, band: String?) {
                main.post { setPhase(phase, band) }
            }

            override fun onStatus(status: JSONObject) {
                main.post { setStatus(app, status) }
            }

            override fun onActions(actions: List<String>) {
                main.post { actions.forEach { this@BandRuntime.sink?.onBandAction(it) } }
            }

            override fun onPointer(records: DoubleArray) {
                main.post { GlassesPointer.onBand(records) }
            }

            override fun onLog(line: String) {
                main.post { note(line) }
            }

            override fun onHandwriting(event: JSONObject) {
                main.post { onHandwritingEvent(app, event) }
            }
        }
        motion = wantsMotion(app)
        device = (if (simulated) SimulatedBand(listener, app) else BandLink(app, listener, config(app))).also {
            it.setMotion(motion)
            it.setGestures(gestures)
            it.start()
        }
        return null
    }

    private fun config(context: Context) = object : BandLink.Config {
        override fun paused() = GestureMappings.isPaused(context)
        override fun mapping() = GestureMappings.mapping(context)
    }

    /** Drop the link; [restart] brings it back while the service runs. */
    @JvmStatic
    fun stop() {
        device?.stop()
        device = null
        setPhase(Phase.STOPPED, null)
    }

    /** The service is going away: nothing can run gestures any more. */
    @JvmStatic
    fun shutdown() {
        stop()
        sink = null
    }

    /** Drop the link and start a fresh one (after an import, or from Pair / Reconnect). */
    @JvmStatic
    fun restart(context: Context): String? {
        val current = sink ?: return context.getString(R.string.runtime_enable_service)
        device?.stop()
        device = null
        return start(context, current)
    }

    /** Who hears the band's handwriting (Lumen's keyboard while it writes, [LumenKeyboard]). */
    fun interface HandwritingListener {
        fun onHandwriting(event: JSONObject)
    }

    private val handwritingListeners = LinkedHashSet<HandwritingListener>()

    @JvmStatic
    fun addHandwritingListener(listener: HandwritingListener) {
        handwritingListeners += listener
    }

    @JvmStatic
    fun removeHandwritingListener(listener: HandwritingListener) {
        handwritingListeners -= listener
    }

    /** The band's handwriting model on or off; false when the band can't start it now. */
    @JvmStatic
    fun setHandwriting(enabled: Boolean): Boolean = device?.setHandwriting(enabled) ?: false

    /** Start the written text over from [text], what the field holds. */
    @JvmStatic
    fun resetHandwritingText(text: String) {
        device?.resetHandwritingText(text)
    }

    private fun onHandwritingEvent(context: Context, event: JSONObject) {
        if (event.optString("type") == "state") {
            Log.d(HANDWRITING_TAG, "state ${event.optString("phase")}: ${event.optString("message")} " +
                "problem=${event.optString("problem")} verified=${event.optBoolean("verified")} " +
                "ids=${event.optInt("collection_id")}/${event.optInt("model_id")}")
        } else if (context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // What someone wrote: only a debug build logs it, for testing the classes on the band.
            Log.d(HANDWRITING_TAG, "text \"${event.optString("text")}\" raw \"${event.optString("raw")}\" class ${event.optInt("class")}")
        }
        handwritingListeners.toList().forEach { it.onHandwriting(event) }
    }

    private const val HANDWRITING_TAG = "BandHandwriting"

    /** Push the current mapping to the band (after a change in the app). */
    @JvmStatic
    fun applyMapping(context: Context) {
        device?.setMapping(GestureMappings.mapping(context))
    }

    /** The glasses' screen, as last reported ([setScreenOn]). */
    private var screenOn = true
    /** Motion streams wanted ([wantsMotion]). */
    private var motion = true
    /** Gesture stream wanted: off while the screen is off in power saving. */
    private var gestures = true

    /**
     * The screen went on or off. Off, the band stops its motion streams, and with power saving
     * ([GestureMappings.isPowerSaving]) its gestures too: it then sends nothing and doesn't
     * vibrate, and only the glasses' button brings the screen (and the band) back.
     */
    @JvmStatic
    fun setScreenOn(context: Context, on: Boolean) {
        screenOn = on
        // No cursor over a dark display (and no motion for it).
        if (!on) GlassesPointer.stop()
        applyStreams(context)
    }

    /** The air mouse on the band ([GlassesPointer]); false when the band isn't connected or can't run it. */
    @JvmStatic
    fun setPointer(on: Boolean, tuning: String): Boolean {
        val device = device ?: return false
        if (on && phase != Phase.CONNECTED) return false
        return device.setPointer(on, tuning)
    }

    /**
     * The motion streams (gyro, orientation) feed pinch and turn only: on while the screen is on,
     * the controls aren't paused and the dial does something. Before, a paused band or a dial set to
     * No action still streamed them (the radio and a thread wakeup per sample, ~190 a second).
     */
    private fun wantsMotion(context: Context) =
        // Pinch and turn always has something to do: the volume while audio plays.
        screenOn && !GestureMappings.isPaused(context)

    /** The streams for the screen, the pause, the dial and power saving now; kept for new links. */
    @JvmStatic
    fun applyStreams(context: Context) {
        val wantGestures = screenOn || !GestureMappings.isPowerSaving(context)
        val wantMotion = wantsMotion(context)
        if (motion != wantMotion) {
            motion = wantMotion
            device?.setMotion(wantMotion)
        }
        if (gestures != wantGestures) {
            gestures = wantGestures
            device?.setGestures(wantGestures)
        }
    }

    @JvmStatic
    fun setPaused(context: Context, paused: Boolean) {
        GestureMappings.setPaused(context, paused)
        device?.setPaused(paused)
        applyStreams(context)
        notifyListeners()
    }

    /** A gesture key from `adb shell am broadcast` in a debug build (see [SimulatedBand]). */
    @JvmStatic
    fun simulate(key: String): Boolean {
        val simulated = device as? SimulatedBand ?: return false
        simulated.perform(key)
        return true
    }

    /** Text the simulated band writes, letter by letter (see [SimulatedBand.write]). */
    @JvmStatic
    fun simulateWriting(text: String): Boolean = (device as? SimulatedBand)?.write(text) ?: false

    @JvmStatic
    fun addListener(listener: StateListener) {
        listeners += listener
    }

    @JvmStatic
    fun removeListener(listener: StateListener) {
        listeners -= listener
    }

    @JvmStatic
    fun recentLog(): List<String> = log.toList()

    @JvmStatic
    fun isPaused(): Boolean = status.optBoolean("paused", false)

    /** The band's charge, or -1 before it reports one. */
    @JvmStatic
    fun battery(): Int = if (status.isNull("battery")) -1 else status.optInt("battery", -1)

    @JvmStatic
    fun bluetoothGranted(context: Context): Boolean {
        val needed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
    }

    private fun setPhase(phase: Phase, band: String?) {
        this.phase = phase
        // The air mouse ends with the connection.
        if (phase != Phase.CONNECTED) GlassesPointer.stop()
        appContext?.let { BandSwitch.onPhase(it, phase) }
        if (band != null) bandName = band
        if (phase == Phase.STOPPED) status = JSONObject()
        notifyListeners()
    }

    private fun setStatus(context: Context, status: JSONObject) {
        val before = this.status
        this.status = status
        // Paused or resumed (a gesture, or the phone's switch): a toast says so.
        if (before.has("paused") && status.has("paused") && before.optBoolean("paused") != status.optBoolean("paused")) {
            val resume = MappableGesture.entries.firstOrNull { GestureMappings.choice(context, it) == GestureChoices.PAUSE }?.key.orEmpty()
            BandSwitch.toastPause(context, status.optBoolean("paused"), resume)
        }
        // The middle hold toggles the controls on the band itself: remember it for the next link.
        if (status.has("paused") && status.optBoolean("paused") != GestureMappings.isPaused(context)) {
            GestureMappings.setPaused(context, status.optBoolean("paused"))
            applyStreams(context)
        }
        notifyListeners()
    }

    private fun note(line: String) {
        Log.d(TAG, line)
        log.addLast(line)
        while (log.size > LOG_LINES) log.removeFirst()
        notifyListeners()
    }

    private fun notifyListeners() = listeners.toList().forEach { it.onBandStateChanged() }

    /** A setting that shows the band's state changed (the launcher battery): redraw. */
    @JvmStatic
    fun notifyChanged() = notifyListeners()
}
