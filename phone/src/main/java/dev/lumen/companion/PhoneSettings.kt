package dev.lumen.companion

import android.content.Context
import android.content.Intent
import dev.lumen.band.BandLink
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

    val GESTURES = listOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "index_tap", "index_double", "middle_tap", "middle_double")

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

    /** Actions that need the accessibility service ([PhoneTouchService]). */
    fun needsTouch(action: String) = action.startsWith("screen.") || action.startsWith("key.") || action == OPEN_APP

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

    /** The bridge's `gesture=action;…` string: the active profile's ([PhoneProfiles]). */
    fun mapping(context: Context): String {
        val profiles = PhoneProfiles.state(context)
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
