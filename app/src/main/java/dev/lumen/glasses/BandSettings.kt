package dev.lumen.glasses

import dev.lumen.band.Identity
import dev.lumen.band.Phase
import dev.lumen.band.ScreenPointer
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.Link
import dev.lumen.protocol.Setting
import dev.lumen.protocol.SettingOption
import dev.lumen.protocol.SettingsAction
import dev.lumen.protocol.SettingsEvent
import dev.lumen.protocol.SettingsOps
import org.json.JSONObject

/**
 * The band's settings as the phone edits them ([Link.SETTINGS]): a schema built from
 * [GestureMappings], changes applied the way the glasses' own screens did (then
 * [BandRuntime.applyMapping]), and the band's status pushed whenever it changes. Labels are
 * English; the companion shows its translation for the keys it knows.
 */
object BandSettings {
    private const val TAG = "BandSettings"
    private const val STATUS_DEBOUNCE_MS = 500L

    const val SECTION_GESTURES = "gestures"
    const val SECTION_BAND = "band"
    const val SECTION_GLASSES = "glasses"
    const val SECTION_POINTER = "pointer"
    const val KEY_POINTER_SPEED = "pointer_speed"
    const val KEY_POINTER_STEADINESS = "pointer_steadiness"
    const val KEY_POINTER_BOOST = "pointer_boost"
    const val ACTION_RESET_GESTURES = "reset_gestures"
    const val KEY_NAVIGATION = "navigation"
    const val KEY_DIAL = "dial"
    const val KEY_HAND = "hand"
    const val KEY_PAUSED = "paused"
    const val KEY_LAUNCHER_BATTERY = "launcher_battery"
    const val KEY_POWER_SAVING = "power_saving"
    const val KEY_SCREEN_TIMEOUT = "screen_timeout"
    private const val SCREEN_TIMEOUT_PREFIX = "screen_off."
    const val ACTION_RECONNECT = "reconnect"
    const val ACTION_FORGET = "forget"
    private const val APP_SUFFIX = "_app"

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var context: Context? = null
    private var lastStatus: BandStatus? = null

    /** Starts pushing the band's status to the phone (once per process). */
    @JvmStatic
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        BandRuntime.addListener { main.removeCallbacks(pushStatus); main.postDelayed(pushStatus, STATUS_DEBOUNCE_MS) }
    }

    /** The status right away (where the band is changed: the phone waits on it). */
    @JvmStatic
    fun pushStatusNow() {
        main.removeCallbacks(pushStatus)
        main.post(pushStatus)
    }

    private val pushStatus = Runnable {
        val now = status()
        if (now == lastStatus) return@Runnable
        lastStatus = now
        PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Status(now).toJson())
    }

    /** A request from the phone; answers on [Link.SETTINGS_EVENT]. Main thread. */
    fun onPhoneRequest(request: JSONObject) {
        val ctx = context ?: return
        when (request.optString("op")) {
            SettingsOps.DESCRIBE -> {
                PhoneLink.send(Link.SETTINGS_EVENT, schema(ctx).toJson(request))
                LocalSelfArmStatus.sendToPhone(ctx)
            }
            SettingsOps.SET -> {
                val key = request.optString("key")
                val error = set(ctx, key, request.optString("value"))
                PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Result(error == null, key, error.orEmpty()).toJson(request))
                if (error == null) PhoneLink.send(Link.SETTINGS_EVENT, schema(ctx).toJson())
            }
            SettingsOps.ACTION -> {
                val name = request.optString("name")
                // The phone took the band back when the glasses didn't answer in time: too late now.
                val late = SettingsOps.isLate(request)
                if (late) Log.d(TAG, "$name came too late: the phone kept the band")
                val error = if (late) "too late" else action(ctx, name)
                PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Result(error == null, name, error.orEmpty()).toJson(request))
            }
        }
    }

    @JvmStatic
    fun status(): BandStatus = BandStatus(
        phase = BandRuntime.phase.name.lowercase(),
        name = BandRuntime.bandName.orEmpty(),
        battery = BandRuntime.battery(),
        charging = BandRuntime.status.optBoolean("charging"),
        paused = BandRuntime.isPaused(),
        onPhone = context?.let { GestureMappings.isBandOnPhone(it) } ?: false,
        hasKey = context?.let { Identity.present(it) },
    )

    @JvmStatic
    fun schema(context: Context): SettingsEvent.Schema {
        val apps = launchableApps(context)
        val settings = mutableListOf<Setting>()
        MappableGesture.entries.forEach { gesture ->
            val choices = GestureChoices.choicesFor(gesture).map { SettingOption(it.id, it.title, it.group) }
            settings += Setting(gesture.key, Setting.Kind.CHOICE, gesture.title, GestureMappings.choice(context, gesture), choices, SECTION_GESTURES)
            settings += Setting(
                gesture.key + APP_SUFFIX, Setting.Kind.CHOICE, "App to open", GestureMappings.launchPackage(context, gesture).orEmpty(),
                apps, SECTION_GESTURES, visibleWhen = gesture.key to GlassesAction.LAUNCH_APP.id(),
            )
        }
        settings += Setting(
            KEY_DIAL, Setting.Kind.CHOICE, "Pinch and turn without audio", GestureMappings.dial(context).key,
            DialMode.entries.map { SettingOption(it.key, it.title) }, SECTION_GESTURES,
        )
        settings += Setting(
            KEY_NAVIGATION, Setting.Kind.CHOICE, "Navigation", if (GestureMappings.isFastNavigation(context)) "fast" else "stable",
            listOf(SettingOption("stable", "Stable"), SettingOption("fast", "Fast")), SECTION_GESTURES,
        )
        val tuning = GestureMappings.pointerTuning(context)
        settings += Setting(
            KEY_POINTER_SPEED, Setting.Kind.RANGE, "Cursor speed", tuning.speed.toString(), section = SECTION_POINTER,
            min = ScreenPointer.SPEEDS.first.toDouble(), max = ScreenPointer.SPEEDS.last.toDouble(), step = 5.0,
        )
        settings += Setting(
            KEY_POINTER_STEADINESS, Setting.Kind.RANGE, "Steadiness", tuning.steadiness.toString(), section = SECTION_POINTER,
            min = 0.0, max = 1.0, step = 0.1,
        )
        settings += Setting(
            KEY_POINTER_BOOST, Setting.Kind.RANGE, "Fast movement boost", tuning.boost.toString(), section = SECTION_POINTER,
            min = ScreenPointer.BOOSTS.start.toDouble(), max = ScreenPointer.BOOSTS.endInclusive.toDouble(), step = 0.1,
        )
        settings += Setting(
            KEY_HAND, Setting.Kind.CHOICE, "Wrist", GestureMappings.hand(context),
            listOf(SettingOption("band", "As on the band"), SettingOption("left", "Left"), SettingOption("right", "Right")), SECTION_BAND,
        )
        settings += Setting(KEY_PAUSED, Setting.Kind.TOGGLE, "Pause the band", GestureMappings.isPaused(context).toString(), section = SECTION_BAND)
        settings += Setting(
            KEY_POWER_SAVING, Setting.Kind.TOGGLE, "Band power saving",
            GestureMappings.isPowerSaving(context).toString(), section = SECTION_BAND,
        )
        settings += Setting(
            KEY_LAUNCHER_BATTERY, Setting.Kind.TOGGLE, "Battery on the Rokid launcher",
            GestureMappings.showsLauncherBattery(context).toString(), section = SECTION_BAND,
        )
        settings += Setting(
            KEY_SCREEN_TIMEOUT, Setting.Kind.CHOICE, "Screen off after", screenTimeoutId(GestureMappings.screenTimeout(context)),
            ScreenTimeout.CHOICES.map { SettingOption(screenTimeoutId(it), screenTimeoutTitle(it)) }, SECTION_GLASSES,
        )
        val actions = listOf(
            SettingsAction(ACTION_RESET_GESTURES, "Restore the default gestures"),
            SettingsAction(ACTION_RECONNECT, "Reconnect"),
            SettingsAction(ACTION_FORGET, "Forget the band", destructive = true),
        )
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()
        return SettingsEvent.Schema(settings, actions, status(), WirelessDebug.current, version)
    }

    /** Null when applied, else why not (in English, for the log and the phone's fallback). */
    @JvmStatic
    fun set(context: Context, key: String, value: String): String? {
        val gesture = MappableGesture.entries.firstOrNull { it.key == key || it.key + APP_SUFFIX == key }
        when {
            gesture != null && key == gesture.key -> {
                if (GestureChoices.choicesFor(gesture).none { it.id == value }) return "unknown action $value"
                GestureMappings.setChoice(context, gesture, value, GestureMappings.launchPackage(context, gesture))
            }
            gesture != null -> {
                if (launchableApps(context).none { it.id == value }) return "app not installed: $value"
                GestureMappings.setChoice(context, gesture, GlassesAction.LAUNCH_APP.id(), value)
            }
            key == KEY_POINTER_SPEED || key == KEY_POINTER_STEADINESS || key == KEY_POINTER_BOOST -> {
                val number = value.toFloatOrNull() ?: return "not a number: $value"
                val tuning = GestureMappings.pointerTuning(context)
                GestureMappings.setPointerTuning(context, when (key) {
                    KEY_POINTER_SPEED -> tuning.copy(speed = Math.round(number))
                    KEY_POINTER_STEADINESS -> tuning.copy(steadiness = number)
                    else -> tuning.copy(boost = number)
                })
                GlassesPointer.retune(context)
                return null
            }
            key == KEY_DIAL -> {
                val mode = DialMode.entries.firstOrNull { it.key == value } ?: return "unknown dial mode $value"
                GestureMappings.setDial(context, mode)
                BandRuntime.applyStreams(context)
            }
            key == KEY_NAVIGATION -> {
                if (value != "stable" && value != "fast") return "unknown navigation $value"
                GestureMappings.setFastNavigation(context, value == "fast")
                return null
            }
            key == KEY_HAND -> {
                if (value !in listOf("band", "left", "right")) return "unknown wrist $value"
                GestureMappings.setHand(context, value)
            }
            key == KEY_LAUNCHER_BATTERY -> {
                GestureMappings.setShowsLauncherBattery(context, value == "true")
                BandRuntime.notifyChanged()
                return null
            }
            key == KEY_POWER_SAVING -> {
                setPowerSaving(context, value == "true")
                return null
            }
            key == KEY_SCREEN_TIMEOUT -> {
                val seconds = ScreenTimeout.CHOICES.firstOrNull { screenTimeoutId(it) == value } ?: return "unknown screen timeout $value"
                GestureMappings.setScreenTimeout(context, seconds)
                ScreenTimeout.apply(context)
                return null
            }
            key == SettingsOps.KEY_WIRELESS_DEBUG -> {
                WirelessDebug.setEnabled(context, value == "true")
                return null
            }
            key == KEY_PAUSED -> {
                BandRuntime.setPaused(context, value == "true")
                return null
            }
            else -> return "unknown setting $key"
        }
        BandRuntime.applyMapping(context)
        Log.d(TAG, "set $key=$value")
        return null
    }

    private fun screenTimeoutId(seconds: Int) =
        SCREEN_TIMEOUT_PREFIX + if (seconds == ScreenTimeout.NEVER) "never" else seconds.toString()

    private fun screenTimeoutTitle(seconds: Int) = when {
        seconds == ScreenTimeout.NEVER -> "Never"
        seconds < 60 -> "$seconds seconds"
        seconds == 60 -> "1 minute"
        else -> "${seconds / 60} minutes"
    }

    /** The settings to the phone, after a change made on the glasses. */
    @JvmStatic
    fun pushSchema(context: Context) {
        PhoneLink.send(Link.SETTINGS_EVENT, schema(context).toJson())
    }

    /** Power saving on or off (glasses screen or phone), applied right away. */
    @JvmStatic
    fun setPowerSaving(context: Context, on: Boolean) {
        GestureMappings.setPowerSaving(context, on)
        BandRuntime.applyStreams(context)
        Log.d(TAG, "power saving $on")
    }

    @JvmStatic
    fun action(context: Context, name: String): String? = when (name) {
        ACTION_RESET_GESTURES -> {
            GestureMappings.resetGestures(context)
            BandRuntime.applyMapping(context)
            pushSchema(context)
            null
        }
        // Reconnecting here takes the band back from the phone too.
        ACTION_RECONNECT -> {
            if (GestureMappings.isBandOnPhone(context)) BandSwitch.arriving(context)
            GestureMappings.setBandOnPhone(context, false)
            BandRuntime.restart(context).also { pushStatusNow() }
        }
        SettingsOps.ACTION_TO_GLASSES -> {
            // Back from the phone or a computer: its steps in a toast until it connects here.
            if (GestureMappings.isBandOnPhone(context)) BandSwitch.arriving(context)
            GestureMappings.setBandOnPhone(context, false)
            (if (BandRuntime.phase == Phase.CONNECTED) null else BandRuntime.restart(context)).also { pushStatusNow() }
        }
        SettingsOps.ACTION_TO_PHONE -> {
            GestureMappings.setBandOnPhone(context, true)
            BandRuntime.stop()
            Log.d(TAG, "the band goes to the phone")
            // The glasses stay off the band until to_glasses or a Reconnect here: trying in the
            // background while the phone holds it would wake the band's radio for nothing.
            pushStatusNow()
            null
        }
        // The self-arm, as the glasses' own Settings row starts it; its progress goes to the phone.
        SettingsOps.ACTION_SELF_ARM -> if (BandAccessibilityService.requestLocalSelfArm(context)) null else LocalSelfArmStatus.state(context).ifEmpty { "not started" }
        ACTION_FORGET -> {
            BandRuntime.stop()
            Identity.forget(context)
            null
        }
        else -> "unknown action $name"
    }

    /** The apps a gesture can open: the glasses' launcher apps, this one aside. */
    private fun launchableApps(context: Context): List<SettingOption> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .map { SettingOption(it.packageName, it.loadLabel(pm).toString()) }
            .sortedBy { it.label.lowercase() }
    }
}
