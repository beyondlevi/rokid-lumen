package dev.lumen.companion

import android.content.Context
import android.content.Intent
import dev.lumen.band.BandLink
import dev.lumen.band.ScreenPointer
import dev.lumen.companion.computer.ComputerProfiles
import dev.lumen.protocol.Setting
import dev.lumen.protocol.SettingOption

/**
 * What each gesture does on the phone, from the original phone app's catalogue ([PhoneActions]):
 * one action per gesture, pinch and turn's pair, and the wrist. Described as [Setting]s, so the
 * Band tab draws them as it draws the glasses'. Labels here are English fallbacks: the tab shows
 * its translations ([dev.lumen.companion.ui.BandLabels]). The middle hold stays the
 * band's pause.
 */
object PhoneSettings {
    const val SECTION = "phone"
    const val PREFIX = "phone."
    const val DIAL = PREFIX + "dial"
    const val HAND = PREFIX + "hand"
    private const val APP_SUFFIX = "_app"

    /** Opens the app chosen in the gesture's `_app` setting (stored as `app:<package>` for the bridge). */
    const val OPEN_APP = "open_app"
    const val NONE = "none"

    /** Hands the band back to the glasses ([PhoneBand.useOnGlasses]). */
    const val SWITCH_TO_GLASSES = "device.glasses"
    const val APP_PREFIX = "app:"

    val GESTURES = listOf(
        "swipe_up", "swipe_down", "swipe_left", "swipe_right",
        "index_tap", "index_double", "index_hold", "middle_tap", "middle_double", "middle_hold",
    )

    /** The holds: held and let go (the index), the band's own long press (the middle). */
    val HOLDS = setOf("index_hold", "middle_hold")

    /**
     * Pauses the band's controls and resumes them (the bridge's `PAUSE_TOGGLE`): only a gesture
     * mapped to it works while they're paused. The middle hold's unless a profile says otherwise.
     */
    const val PAUSE = "band.pause"

    /** Writing with the band in the focused field ([HandwritingSwitch]). */
    const val WRITE = "phone.write"

    /** What a gesture a profile never set does: the middle hold pauses, as it always did. */
    val GESTURE_DEFAULTS = mapOf("middle_hold" to PAUSE)

    /** The original app's media layout, the defaults. */
    val DEFAULTS = mapOf(
        "swipe_up" to "media.previous",
        "swipe_down" to "media.next",
        "index_tap" to "media.play_pause",
        "middle_double" to "volume.mute",
    )

    /** Action id to its English label. */
    val ACTIONS = linkedMapOf(
        NONE to "Nothing",
        "media.play_pause" to "Play or pause",
        "media.next" to "Next track",
        "media.previous" to "Previous track",
        "volume.up" to "Volume up",
        "volume.down" to "Volume down",
        "volume.mute" to "Mute",
        "screen.swipe_up" to "Swipe up on screen",
        "screen.swipe_down" to "Swipe down on screen",
        "screen.swipe_left" to "Swipe left on screen",
        "screen.swipe_right" to "Swipe right on screen",
        "screen.back" to "Back",
        "screen.home" to "Home",
        "screen.recents" to "Recent apps",
        "key.dpad_up" to "Arrow up",
        "key.dpad_down" to "Arrow down",
        "key.dpad_left" to "Arrow left",
        "key.dpad_right" to "Arrow right",
        "key.enter" to "Enter (select)",
        "brightness.up" to "Brightness up",
        "brightness.down" to "Brightness down",
        "torch.toggle" to "Flashlight on or off",
        OPEN_APP to "Open an app",
        SWITCH_TO_GLASSES to "Use the band on the glasses",
        ScreenPointer.TOGGLE to "Air Mouse",
        WRITE to "Write",
        PAUSE to "Pause or resume the band",
        PhoneProfiles.NEXT to "Next profile",
        PhoneProfiles.PREVIOUS to "Previous profile",
    )

    /** Pinch and turn: one action per step each way. */
    val DIALS = linkedMapOf(
        "volume" to ("volume.up" to "volume.down"),
        "seek" to ("dial.seek_forward" to "dial.seek_back"),
        "brightness" to ("brightness.up" to "brightness.down"),
        NONE to ("" to ""),
    )
    private val DIAL_LABELS = mapOf("volume" to "Volume", "seek" to "Playback position", "brightness" to "Brightness", NONE to "Nothing")

    private val HANDS = linkedMapOf("band" to "As on the band", "left" to "Left", "right" to "Right")

    /** Actions that need the accessibility service ([PhoneTouchService]); the air mouse draws and touches through it. */
    fun needsTouch(action: String) =
        action.startsWith("screen.") || action.startsWith("key.") || action == OPEN_APP || action == ScreenPointer.TOGGLE ||
            action == WRITE

    // ---- The air mouse on this phone ([PhonePointer]), set like a computer's ----

    private const val POINTER_SPEED = "pointer_speed"
    private const val POINTER_STEADINESS = "pointer_steadiness"
    private const val POINTER_BOOST = "pointer_boost"

    fun pointerTuning(context: Context): ScreenPointer.Tuning {
        val prefs = prefs(context)
        return ScreenPointer.Tuning(
            prefs.getInt(POINTER_SPEED, ScreenPointer.SPEED_DEFAULT).coerceIn(ScreenPointer.SPEEDS),
            prefs.getFloat(POINTER_STEADINESS, ScreenPointer.STEADINESS_DEFAULT).coerceIn(0f, 1f),
            prefs.getFloat(POINTER_BOOST, ScreenPointer.BOOST_DEFAULT).coerceIn(ScreenPointer.BOOSTS),
        )
    }

    fun setPointerSpeed(context: Context, speed: Int) =
        prefs(context).edit().putInt(POINTER_SPEED, speed.coerceIn(ScreenPointer.SPEEDS)).apply()

    fun setPointerSteadiness(context: Context, steadiness: Float) =
        prefs(context).edit().putFloat(POINTER_STEADINESS, steadiness.coerceIn(0f, 1f)).apply()

    fun setPointerBoost(context: Context, boost: Float) =
        prefs(context).edit().putFloat(POINTER_BOOST, boost.coerceIn(ScreenPointer.BOOSTS)).apply()

    fun action(context: Context, gesture: String): String =
        prefs(context).getString(PREFIX + gesture, null) ?: DEFAULTS[gesture] ?: NONE

    fun app(context: Context, gesture: String): String = prefs(context).getString(PREFIX + gesture + APP_SUFFIX, null).orEmpty()

    fun dial(context: Context): String = prefs(context).getString(DIAL, null)?.takeIf { it in DIALS } ?: "volume"

    fun hand(context: Context): String = prefs(context).getString(HAND, null)?.takeIf { it in HANDS } ?: "band"

    /** Every setting, for the Band tab. */
    fun schema(context: Context): List<Setting> {
        val apps = launchableApps(context)
        val out = mutableListOf<Setting>()
        GESTURES.forEach { gesture ->
            out += Setting(PREFIX + gesture, Setting.Kind.CHOICE, gesture, action(context, gesture),
                ACTIONS.map { (id, label) -> SettingOption(id, label) }, SECTION)
            out += Setting(PREFIX + gesture + APP_SUFFIX, Setting.Kind.CHOICE, "App to open", app(context, gesture),
                apps, SECTION, visibleWhen = PREFIX + gesture to OPEN_APP)
        }
        out += Setting(DIAL, Setting.Kind.CHOICE, "Pinch and turn", dial(context), DIALS.keys.map { SettingOption(it, DIAL_LABELS.getValue(it)) }, SECTION)
        out += Setting(HAND, Setting.Kind.CHOICE, "Wrist", hand(context), HANDS.map { (id, label) -> SettingOption(id, label) }, SECTION)
        return out
    }

    /** Saves a setting from the Band tab; false for an unknown key or value. */
    fun set(context: Context, key: String, value: String): Boolean {
        val valid = when {
            key == DIAL -> value in DIALS
            key == HAND -> value in HANDS
            key.endsWith(APP_SUFFIX) -> value.isNotEmpty()
            key.removePrefix(PREFIX) in GESTURES -> value in ACTIONS
            else -> false
        }
        if (valid) prefs(context).edit().putString(key, value).apply()
        return valid
    }

    /**
     * The bridge's `gesture=action;…` string: the active profile's ([PhoneProfiles]), or the
     * active computer profile's while the band works for a computer.
     */
    fun mapping(context: Context): String {
        val profiles = PhoneProfiles.state(context)
        if (CompanionPrefs.bandOnComputer(context)) {
            return ComputerProfiles.mapping(ComputerProfiles.state(context).current, profiles.switchGesture, hand(context))
        }
        return PhoneProfiles.mapping(profiles.current, profiles.switchGesture, hand(context))
    }

    fun mapping(actions: Map<String, String>, apps: Map<String, String>, dial: String, hand: String): String {
        val (up, down) = DIALS[dial] ?: DIALS.getValue("volume")
        return GESTURES.joinToString(";") { gesture ->
            val action = when (val id = actions[gesture] ?: NONE) {
                NONE -> ""
                OPEN_APP -> apps[gesture]?.takeIf { it.isNotEmpty() }?.let { APP_PREFIX + it } ?: ""
                else -> id
            }
            "$gesture=$action"
        } + ";dial_up=$up;dial_down=$down;hand=$hand"
    }

    fun config(context: Context): BandLink.Config = object : BandLink.Config {
        override fun paused(): Boolean = false
        override fun mapping(): String = mapping(context)
    }

    /** The phone's launchable apps (not this one), for "open an app". */
    fun launchableApps(context: Context): List<SettingOption> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { SettingOption(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("phone_band", Context.MODE_PRIVATE)
}
