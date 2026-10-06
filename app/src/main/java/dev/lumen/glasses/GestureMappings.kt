package dev.lumen.glasses

import android.content.Context
import android.content.SharedPreferences

/**
 * How the band's gestures drive the glasses. The navigation gestures are fixed, as the R08 ring's
 * are in R08 Access Bridge: a swipe moves the launcher or the focus one step (right and down
 * forward, left and up back), an index tap selects, a middle tap is Back. The middle double tap
 * turns the screen off and on, as on Meta's glasses ([BandCommand.SCREEN]). The index double tap
 * and pinch and turn can be mapped; the index double ships unmapped (the dial is volume) so that
 * an index tap never waits to rule out a double. The middle hold stays the controls toggle.
 */
enum class MappableGesture(val key: String, val title: String) {
    INDEX_DOUBLE("index_double", "Index double tap"),
}

/** What pinch and turn does: one action per step each way. */
/**
 * What pinch and turn does while nothing plays: with audio playing it's always the volume
 * ([resolve]). [up] and [down] are the commands for a turn each way.
 */
enum class DialMode(val key: String, val title: String, val detail: String, val up: String, val down: String) {
    VOLUME("volume", "Volume", "Turn to raise or lower the volume", BandCommand.VOLUME_UP, BandCommand.VOLUME_DOWN),
    NAVIGATION("navigation", "Navigation", "Turn to move through lists and the launcher", BandCommand.FORWARD, BandCommand.BACKWARD),
    BRIGHTNESS("brightness", "Brightness", "Turn to brighten or dim the display", BandCommand.BRIGHTNESS_UP, BandCommand.BRIGHTNESS_DOWN),
    NONE("none", "No action", "Pinch and turn does nothing without audio", "", "");

    /** The command for one turn step: the volume while audio [playing], else this mode's (null: nothing). */
    fun resolve(up: Boolean, playing: Boolean): String? = when {
        playing -> if (up) BandCommand.VOLUME_UP else BandCommand.VOLUME_DOWN
        else -> (if (up) this.up else down).ifEmpty { null }
    }

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: VOLUME
    }
}

/** The action names the bridge hands back (see [BandMapping]). */
object BandCommand {
    /** One step on the glasses' single axis (the launcher, lists): pinch and turn's navigation. */
    const val FORWARD = "nav.forward"
    const val BACKWARD = "nav.backward"

    /** The four swipes stay apart so a web app gets real arrow keys (see [WebAppActivity]). */
    const val RIGHT = "nav.right"
    const val DOWN = "nav.down"
    const val LEFT = "nav.left"
    const val UP = "nav.up"
    const val ACTIVATE = "nav.activate"
    const val BACK = "nav.back"
    const val VOLUME_UP = "volume.up"
    const val VOLUME_DOWN = "volume.down"
    const val BRIGHTNESS_UP = "brightness.up"
    const val BRIGHTNESS_DOWN = "brightness.down"
    /** Pinch and turn's steps, resolved on the glasses ([DialMode.resolve]): audio playing or not. */
    const val DIAL_UP = "dial.up"
    const val DIAL_DOWN = "dial.down"

    /**
     * The middle double tap: screen off when it's on, on when it's off. With the screen off it's
     * the only gesture that does anything (as on Meta's glasses); the others are ignored.
     */
    const val SCREEN = "screen.toggle"

    /** A mapped [GlassesAction], by id. */
    const val GLASSES_PREFIX = "glasses."

    /** Launch this package (the [GlassesAction.LAUNCH_APP] mapping). */
    const val APP_PREFIX = "app:"

    /**
     * The one-axis form of a command, as R08 Access Bridge navigates: right and down move
     * forward, left and up back. Anything else is returned as it is.
     */
    @JvmStatic
    fun axis(command: String): String = when (command) {
        RIGHT, DOWN -> FORWARD
        LEFT, UP -> BACKWARD
        else -> command
    }
}

/** The `gesture=action;…` string the bridge and the simulated band resolve gestures with. */
object BandMapping {
    fun build(indexDouble: String, dial: DialMode, hand: String): String = listOf(
        "swipe_right" to BandCommand.RIGHT,
        "swipe_down" to BandCommand.DOWN,
        "swipe_left" to BandCommand.LEFT,
        "swipe_up" to BandCommand.UP,
        "index_tap" to BandCommand.ACTIVATE,
        "middle_tap" to BandCommand.BACK,
        "index_double" to indexDouble,
        "middle_double" to BandCommand.SCREEN,
        // Pinch and turn depends on what's playing, which only the glasses know at the time.
        "dial_up" to BandCommand.DIAL_UP,
        "dial_down" to BandCommand.DIAL_DOWN,
        "hand" to hand,
    ).joinToString(";") { (key, value) -> "$key=$value" }

    /** The action name for a mapped gesture: empty for none, `app:<package>` for an app. */
    fun command(action: GlassesAction, launchPackage: String?): String = when (action) {
        GlassesAction.NONE -> ""
        GlassesAction.LAUNCH_APP -> launchPackage?.trim()?.takeIf { it.isNotEmpty() }?.let { BandCommand.APP_PREFIX + it } ?: ""
        else -> BandCommand.GLASSES_PREFIX + action.id()
    }
}

/** The app's settings: the gesture mapping, the band's wrist and controls state, the launcher mode. */
object GestureMappings {
    private const val PREFS = "neuralband_mapping"
    private const val RECORD_REQUEST_TTL_MS = 2L * 60L * 60L * 1000L

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun action(context: Context, gesture: MappableGesture): GlassesAction =
        GlassesAction.fromId(prefs(context).getString("${gesture.key}_action", null), GlassesAction.NONE)

    @JvmStatic
    fun launchPackage(context: Context, gesture: MappableGesture): String? =
        prefs(context).getString("${gesture.key}_launch_package", null)

    @JvmStatic
    fun setAction(context: Context, gesture: MappableGesture, action: GlassesAction, launchPackage: String?) {
        prefs(context).edit()
            .putString("${gesture.key}_action", action.id())
            .putString("${gesture.key}_launch_package", if (action == GlassesAction.LAUNCH_APP) launchPackage else null)
            .apply()
    }

    @JvmStatic
    fun dial(context: Context): DialMode = DialMode.of(prefs(context).getString("dial", null))

    @JvmStatic
    fun setDial(context: Context, mode: DialMode) = prefs(context).edit().putString("dial", mode.key).apply()

    /** "left" or "right" once chosen (the band is set to it on every connection), else "band". */
    @JvmStatic
    fun hand(context: Context): String = prefs(context).getString("hand", "band") ?: "band"

    @JvmStatic
    fun setHand(context: Context, hand: String) = prefs(context).edit().putString("hand", hand).apply()

    /** The middle hold turned the controls off (the band keeps its link, gestures do nothing). */
    @JvmStatic
    fun isPaused(context: Context): Boolean = prefs(context).getBoolean("paused", false)

    @JvmStatic
    fun setPaused(context: Context, paused: Boolean) = prefs(context).edit().putBoolean("paused", paused).apply()

    /** The band is with the phone companion: the glasses don't connect to it ([BandSettings]). */
    @JvmStatic
    fun isBandOnPhone(context: Context): Boolean = prefs(context).getBoolean("band_on_phone", false)

    @JvmStatic
    fun setBandOnPhone(context: Context, onPhone: Boolean) =
        prefs(context).edit().putBoolean("band_on_phone", onPhone).apply()

    /** Gestures come from [SimulatedBand] (adb broadcasts in a debug build), not Bluetooth. */
    @JvmStatic
    fun isSimulated(context: Context): Boolean = prefs(context).getBoolean("simulated", false)

    @JvmStatic
    fun setSimulated(context: Context, simulated: Boolean) =
        prefs(context).edit().putBoolean("simulated", simulated).apply()

    /** The band's battery on the Rokid launcher's status row ([BandBatteryOverlay]); on by default. */
    @JvmStatic
    fun showsLauncherBattery(context: Context): Boolean = prefs(context).getBoolean("launcher_battery", true)

    /**
     * The band's power saving: with the screen off it stops everything (no gesture, no
     * vibration) until the glasses' button turns the screen on. Off, the middle double tap
     * still turns the screen on.
     */
    @JvmStatic
    fun isPowerSaving(context: Context): Boolean = prefs(context).getBoolean("power_saving", false)

    @JvmStatic
    fun setPowerSaving(context: Context, on: Boolean) = prefs(context).edit().putBoolean("power_saving", on).apply()

    /** Seconds without input before the display goes off ([ScreenTimeout]); 0 is never. */
    @JvmStatic
    fun screenTimeout(context: Context): Int =
        prefs(context).getInt("screen_timeout", ScreenTimeout.DEFAULT_SECONDS)

    @JvmStatic
    fun setScreenTimeout(context: Context, seconds: Int) =
        prefs(context).edit().putInt("screen_timeout", seconds).apply()

    @JvmStatic
    fun setShowsLauncherBattery(context: Context, show: Boolean) =
        prefs(context).edit().putBoolean("launcher_battery", show).apply()

    /** Fast mode: repeated swipes on the launcher move two apps at a time (R08's Fast mode). */
    @JvmStatic
    fun isFastNavigation(context: Context): Boolean = prefs(context).getBoolean("fast_navigation", false)

    @JvmStatic
    fun setFastNavigation(context: Context, fast: Boolean) =
        prefs(context).edit().putBoolean("fast_navigation", fast).apply()

    /** The mapping string for the bridge, from the current settings. */
    @JvmStatic
    fun mapping(context: Context): String = BandMapping.build(
        command(context, MappableGesture.INDEX_DOUBLE),
        dial(context),
        hand(context),
    )

    private fun command(context: Context, gesture: MappableGesture) =
        BandMapping.command(action(context, gesture), launchPackage(context, gesture))

    @JvmStatic
    fun isVideoRecordingRequested(context: Context) = isRecordingRequested(context, "video_recording")

    @JvmStatic
    fun isArRecordingRequested(context: Context) = isRecordingRequested(context, "ar_recording")

    @JvmStatic
    fun setVideoRecordingRequested(context: Context, recording: Boolean) =
        setRecordingRequested(context, "video_recording", recording)

    @JvmStatic
    fun setArRecordingRequested(context: Context, recording: Boolean) =
        setRecordingRequested(context, "ar_recording", recording)

    /** A start request older than two hours is forgotten, as in R08: the recording surely ended. */
    private fun isRecordingRequested(context: Context, key: String): Boolean {
        val prefs = prefs(context)
        if (!prefs.getBoolean(key, false)) return false
        val age = System.currentTimeMillis() - prefs.getLong("${key}_at", 0L)
        if (age < 0L || age > RECORD_REQUEST_TTL_MS) {
            setRecordingRequested(context, key, false)
            return false
        }
        return true
    }

    private fun setRecordingRequested(context: Context, key: String, recording: Boolean) {
        prefs(context).edit()
            .putBoolean(key, recording)
            .putLong("${key}_at", if (recording) System.currentTimeMillis() else 0L)
            .apply()
    }
}
