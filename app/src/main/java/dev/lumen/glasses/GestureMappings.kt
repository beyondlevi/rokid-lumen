package dev.lumen.glasses

import android.content.Context
import android.content.SharedPreferences
import dev.lumen.band.ScreenPointer

/**
 * How the band's gestures drive the glasses. Each gesture can be mapped ([GestureChoices]); by
 * default they navigate as the R08 ring does in R08 Access Bridge: a swipe moves the launcher or
 * the focus one step (right and down forward, left and up back), an index tap selects, a middle
 * tap is Back, and the middle double tap turns the screen off and on, as on Meta's glasses
 * ([BandCommand.SCREEN]), the middle hold pauses the controls ([GestureChoices.PAUSE]) and the
 * index double tap opens the band's device chooser ([GestureChoices.DEVICES]; an index tap waits
 * a moment to rule out the double, as with any mapped double).
 */
enum class MappableGesture(val key: String, val title: String, val default: String) {
    SWIPE_RIGHT("swipe_right", "Swipe right", BandCommand.RIGHT),
    SWIPE_LEFT("swipe_left", "Swipe left", BandCommand.LEFT),
    SWIPE_DOWN("swipe_down", "Swipe down", BandCommand.DOWN),
    SWIPE_UP("swipe_up", "Swipe up", BandCommand.UP),
    INDEX_TAP("index_tap", "Index tap", BandCommand.ACTIVATE),
    MIDDLE_TAP("middle_tap", "Middle tap", BandCommand.BACK),
    INDEX_DOUBLE("index_double", "Index double tap", GestureChoices.DEVICES),
    MIDDLE_DOUBLE("middle_double", "Middle double tap", BandCommand.SCREEN),
    /** Held and let go without turning (an index pinch held is also how pinch and turn starts). */
    INDEX_HOLD("index_hold", "Index hold", GestureChoices.NONE),
    MIDDLE_HOLD("middle_hold", "Middle hold", GestureChoices.PAUSE),
    ;

    /** A hold can't be the air mouse's switch: while it runs, the pinches are its clicks. */
    val isHold get() = this == INDEX_HOLD || this == MIDDLE_HOLD
}

/**
 * What a glasses gesture can do, by choice id: a band command (the navigation, the screen, the
 * volume, the air mouse) or a [GlassesAction] id. The ids are what's stored and what the phone
 * shows; [command] turns one into the bridge's action.
 */
object GestureChoices {
    const val NONE = "none"

    /** The air mouse ([GlassesPointer]): the bridge's toggle id (rust/bridge `POINTER_TOGGLE`). */
    const val POINTER = ScreenPointer.TOGGLE

    /**
     * Pauses the controls and resumes them (rust/bridge `PAUSE_TOGGLE`, handled by the bridge):
     * only the gesture mapped to it works while they're paused.
     */
    const val PAUSE = "band.pause"

    /** Opens the band's device chooser ([BandDeviceActivity]): the glasses, the phone or a computer. */
    const val DEVICES = "band.devices"

    const val NAVIGATION = "navigation"
    const val MOUSE = "mouse"
    const val GLASSES = "glasses"
    const val SCREEN = "screen"
    const val BAND = "band"
    const val OTHER = "other"

    data class Choice(val id: String, val title: String, val group: String)

    /** Every choice, in the order the phone lists them, under their groups. */
    val ALL: List<Choice> = listOf(
        Choice(DEVICES, "Choose the band's device", BAND),
        Choice(PAUSE, "Pause or resume the band", BAND),
        Choice(GlassesAction.TO_PHONE.id(), GlassesAction.TO_PHONE.title(), BAND),
        Choice(BandCommand.RIGHT, "Move right", NAVIGATION),
        Choice(BandCommand.LEFT, "Move left", NAVIGATION),
        Choice(BandCommand.DOWN, "Move down", NAVIGATION),
        Choice(BandCommand.UP, "Move up", NAVIGATION),
        Choice(BandCommand.ACTIVATE, "Select", NAVIGATION),
        Choice(BandCommand.BACK, "Back", NAVIGATION),
        Choice(POINTER, "Air Mouse", MOUSE),
    ) + listOf(
        GlassesAction.HOME, GlassesAction.OPEN_APPS_GRID, GlassesAction.PLAY_PAUSE, GlassesAction.AI_ASSIST,
        GlassesAction.HI_ROKID_SHORTCUT, GlassesAction.TAKE_PHOTO, GlassesAction.VIDEO_RECORD_TOGGLE,
        GlassesAction.AR_SCREENSHOT, GlassesAction.AR_RECORD_TOGGLE,
    ).map { Choice(it.id(), it.title(), GLASSES) } + listOf(
        Choice(BandCommand.SCREEN, "Screen on and off", SCREEN),
        Choice(BandCommand.VOLUME_UP, "Volume up", SCREEN),
        Choice(BandCommand.VOLUME_DOWN, "Volume down", SCREEN),
        Choice(BandCommand.BRIGHTNESS_UP, "Brightness up", SCREEN),
        Choice(BandCommand.BRIGHTNESS_DOWN, "Brightness down", SCREEN),
        Choice(GlassesAction.LAUNCH_APP.id(), GlassesAction.LAUNCH_APP.title(), OTHER),
        Choice(NONE, GlassesAction.NONE.title(), OTHER),
    )

    private val ids = ALL.map { it.id }.toSet()

    /**
     * A stored choice, or [default] when there's none or it's unknown. The index double tap's
     * Android Back of old (`back`) is the Back of the navigation now, which does that and more.
     */
    fun of(stored: String?, default: String): String = when (stored) {
        null -> default
        GlassesAction.BACK.id() -> BandCommand.BACK
        in ids -> stored
        else -> default
    }

    fun isChoice(id: String) = id in ids

    /** The choices [gesture] can have: all of them, the air mouse aside for a hold. */
    fun choicesFor(gesture: MappableGesture): List<Choice> = if (gesture.isHold) ALL.filter { it.id != POINTER } else ALL

    /** The bridge's action for [choice]: a command as it is, `glasses.<id>`, `app:<package>`, or none. */
    fun command(choice: String, launchPackage: String?): String = when {
        choice == NONE || choice.isEmpty() -> ""
        choice == GlassesAction.LAUNCH_APP.id() -> BandMapping.command(GlassesAction.LAUNCH_APP, launchPackage)
        choice.contains('.') -> choice
        else -> BandMapping.command(GlassesAction.fromId(choice, GlassesAction.NONE), launchPackage)
    }
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
    /** [commands] by gesture key; a gesture left out keeps its default ([MappableGesture.default]). */
    fun build(commands: Map<String, String>, dial: DialMode, hand: String): String = (
        MappableGesture.entries.map { it.key to (commands[it.key] ?: GestureChoices.command(it.default, null)) } + listOf(
            // Pinch and turn depends on what's playing, which only the glasses know at the time.
            "dial_up" to BandCommand.DIAL_UP,
            "dial_down" to BandCommand.DIAL_DOWN,
            "hand" to hand,
        )
    ).joinToString(";") { (key, value) -> "$key=$value" }

    /** The defaults with the index double tap mapped to [indexDouble]. */
    fun build(indexDouble: String, dial: DialMode, hand: String): String =
        build(mapOf(MappableGesture.INDEX_DOUBLE.key to indexDouble), dial, hand)

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

    /** What [gesture] does ([GestureChoices]); the index double tap's keys are kept from before. */
    @JvmStatic
    fun choice(context: Context, gesture: MappableGesture): String =
        GestureChoices.of(prefs(context).getString("${gesture.key}_action", null), gesture.default)

    @JvmStatic
    fun launchPackage(context: Context, gesture: MappableGesture): String? =
        prefs(context).getString("${gesture.key}_launch_package", null)

    @JvmStatic
    fun setChoice(context: Context, gesture: MappableGesture, choice: String, launchPackage: String?) {
        prefs(context).edit()
            .putString("${gesture.key}_action", choice)
            .putString("${gesture.key}_launch_package", if (choice == GlassesAction.LAUNCH_APP.id()) launchPackage else null)
            .apply()
    }

    /** Every gesture back to what it does out of the box. */
    @JvmStatic
    fun resetGestures(context: Context) {
        val edit = prefs(context).edit()
        MappableGesture.entries.forEach { edit.remove("${it.key}_action").remove("${it.key}_launch_package") }
        edit.apply()
    }

    /** The air mouse on the glasses ([GlassesPointer]), set from the phone. */
    @JvmStatic
    fun pointerTuning(context: Context): ScreenPointer.Tuning {
        val prefs = prefs(context)
        return ScreenPointer.Tuning(
            prefs.getInt("pointer_speed", ScreenPointer.SPEED_DEFAULT).coerceIn(ScreenPointer.SPEEDS),
            prefs.getFloat("pointer_steadiness", ScreenPointer.STEADINESS_DEFAULT).coerceIn(0f, 1f),
            prefs.getFloat("pointer_boost", ScreenPointer.BOOST_DEFAULT).coerceIn(ScreenPointer.BOOSTS),
        )
    }

    @JvmStatic
    fun setPointerTuning(context: Context, tuning: ScreenPointer.Tuning) {
        prefs(context).edit()
            .putInt("pointer_speed", tuning.speed.coerceIn(ScreenPointer.SPEEDS))
            .putFloat("pointer_steadiness", tuning.steadiness.coerceIn(0f, 1f))
            .putFloat("pointer_boost", tuning.boost.coerceIn(ScreenPointer.BOOSTS))
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
        MappableGesture.entries.associate { it.key to command(context, it) },
        dial(context),
        hand(context),
    )

    private fun command(context: Context, gesture: MappableGesture) =
        GestureChoices.command(choice(context, gesture), launchPackage(context, gesture))

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
