package dev.lumen.companion.ui

import androidx.annotation.StringRes
import dev.lumen.companion.R

/**
 * The companion's translations for the band settings the glasses describe, by key. A key or
 * option the glasses add later isn't here: the screen shows the glasses' English label then.
 */
object BandLabels {
    private val settings = mapOf(
        "index_double" to R.string.band_setting_index_double,
        "middle_double" to R.string.band_setting_middle_double,
        "index_double_app" to R.string.band_setting_app,
        "middle_double_app" to R.string.band_setting_app,
        "dial" to R.string.band_setting_dial,
        "navigation" to R.string.band_setting_navigation,
        "hand" to R.string.band_setting_hand,
        "paused" to R.string.band_setting_paused,
        "launcher_battery" to R.string.band_setting_launcher_battery,
        "power_saving" to R.string.band_setting_power_saving,
        "screen_timeout" to R.string.band_setting_screen_timeout,
        // The phone's own (PhoneSettings).
        "phone.swipe_up" to R.string.phone_gesture_swipe_up,
        "phone.swipe_down" to R.string.phone_gesture_swipe_down,
        "phone.swipe_left" to R.string.phone_gesture_swipe_left,
        "phone.swipe_right" to R.string.phone_gesture_swipe_right,
        "phone.index_tap" to R.string.phone_gesture_index_tap,
        "phone.index_double" to R.string.band_setting_index_double,
        "phone.middle_tap" to R.string.phone_gesture_middle_tap,
        "phone.middle_double" to R.string.band_setting_middle_double,
        "phone.dial" to R.string.band_setting_dial,
        "phone.hand" to R.string.band_setting_hand,
    ).let { base -> base + listOf("swipe_up", "swipe_down", "swipe_left", "swipe_right", "index_tap", "index_double", "middle_tap", "middle_double")
        .associate { "phone.${it}_app" to R.string.band_setting_app } }

    /** Options by id; the gesture actions, the dial, the navigation and the wrist. */
    private val options = mapOf(
        "none" to R.string.band_option_none,
        "back" to R.string.band_option_back,
        "home" to R.string.band_option_home,
        "play_pause" to R.string.band_option_play_pause,
        "launch_app" to R.string.band_option_launch_app,
        "open_apps_grid" to R.string.band_option_open_apps_grid,
        "ai_assist" to R.string.band_option_ai_assist,
        "hi_rokid_shortcut" to R.string.band_option_hi_rokid_shortcut,
        "take_photo" to R.string.band_option_take_photo,
        "toggle_video" to R.string.band_option_toggle_video,
        "ar_screenshot" to R.string.band_option_ar_screenshot,
        "toggle_ar_record" to R.string.band_option_toggle_ar_record,
        "volume" to R.string.band_option_volume,
        "navigation" to R.string.band_option_navigation,
        "stable" to R.string.band_option_stable,
        "fast" to R.string.band_option_fast,
        "band" to R.string.band_option_hand_band,
        "left" to R.string.band_option_left,
        "right" to R.string.band_option_right,
        "to_phone" to R.string.band_option_to_phone,
        "screen_off.30" to R.string.band_option_screen_off_30,
        "screen_off.60" to R.string.band_option_screen_off_60,
        "screen_off.120" to R.string.band_option_screen_off_120,
        "screen_off.300" to R.string.band_option_screen_off_300,
        "screen_off.600" to R.string.band_option_screen_off_600,
        "screen_off.never" to R.string.band_option_screen_off_never,
        // The phone's actions (PhoneSettings.ACTIONS) and pinch and turn.
        "media.play_pause" to R.string.band_option_play_pause,
        "media.next" to R.string.phone_action_next,
        "media.previous" to R.string.phone_action_previous,
        "volume.up" to R.string.phone_action_volume_up,
        "volume.down" to R.string.phone_action_volume_down,
        "volume.mute" to R.string.phone_action_mute,
        "screen.swipe_up" to R.string.phone_action_swipe_up,
        "screen.swipe_down" to R.string.phone_action_swipe_down,
        "screen.swipe_left" to R.string.phone_action_swipe_left,
        "screen.swipe_right" to R.string.phone_action_swipe_right,
        "screen.back" to R.string.band_option_back,
        "screen.home" to R.string.phone_action_home,
        "screen.recents" to R.string.phone_action_recents,
        "key.dpad_up" to R.string.phone_action_arrow_up,
        "key.dpad_down" to R.string.phone_action_arrow_down,
        "key.dpad_left" to R.string.phone_action_arrow_left,
        "key.dpad_right" to R.string.phone_action_arrow_right,
        "key.enter" to R.string.phone_action_enter,
        "brightness.up" to R.string.phone_action_brightness_up,
        "brightness.down" to R.string.phone_action_brightness_down,
        "torch.toggle" to R.string.phone_action_torch,
        "open_app" to R.string.band_option_launch_app,
        "device.glasses" to R.string.phone_action_to_glasses,
        "seek" to R.string.phone_dial_seek,
        "brightness" to R.string.phone_dial_brightness,
    )

    private val sections = mapOf(
        "gestures" to R.string.band_section_gestures,
        "phone" to R.string.band_section_phone,
        "band" to R.string.band_section_band,
        "glasses" to R.string.band_section_glasses,
    )

    private val actions = mapOf(
        "reconnect" to R.string.band_action_reconnect,
        "forget" to R.string.band_action_forget,
    )

    private val phases = mapOf(
        "stopped" to R.string.band_phase_stopped,
        "searching" to R.string.band_phase_searching,
        "connecting" to R.string.band_phase_connecting,
        "connected" to R.string.band_phase_connected,
    )

    @StringRes fun setting(key: String): Int? = settings[key]

    /** App options (package names) keep the glasses' label: it's the app's own name. */
    @StringRes fun option(settingKey: String, id: String): Int? = if (settingKey.endsWith("_app")) null else options[id]

    @StringRes fun section(key: String): Int? = sections[key]

    @StringRes fun action(name: String): Int? = actions[name]

    @StringRes fun phase(phase: String): Int? = phases[phase]
}
