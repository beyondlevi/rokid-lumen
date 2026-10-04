package dev.lumen.glasses

import dev.lumen.band.Identity
import dev.lumen.band.Phase
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
    const val KEY_NAVIGATION = "navigation"
    const val KEY_DIAL = "dial"
    const val KEY_HAND = "hand"
    const val KEY_PAUSED = "paused"
    const val KEY_LAUNCHER_BATTERY = "launcher_battery"
    const val KEY_POWER_SAVING = "power_saving"
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
    private fun pushStatusNow() {
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
            SettingsOps.DESCRIBE -> PhoneLink.send(Link.SETTINGS_EVENT, schema(ctx).toJson(request))
            SettingsOps.SET -> {
                val key = request.optString("key")
                val error = set(ctx, key, request.optString("value"))
                PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Result(error == null, key, error.orEmpty()).toJson(request))
                if (error == null) PhoneLink.send(Link.SETTINGS_EVENT, schema(ctx).toJson())
            }
            SettingsOps.ACTION -> {
                val name = request.optString("name")
                val error = action(ctx, name)
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
    )

    @JvmStatic
    fun schema(context: Context): SettingsEvent.Schema {
        val apps = launchableApps(context)
        val settings = mutableListOf<Setting>()
        MappableGesture.entries.forEach { gesture ->
            val action = GestureMappings.action(context, gesture)
            settings += Setting(
                gesture.key, Setting.Kind.CHOICE, gesture.title, action.id(),
                GlassesAction.entries.map { SettingOption(it.id(), it.title()) }, SECTION_GESTURES,
            )
            settings += Setting(
                gesture.key + APP_SUFFIX, Setting.Kind.CHOICE, "App to open", GestureMappings.launchPackage(context, gesture).orEmpty(),
                apps, SECTION_GESTURES, visibleWhen = gesture.key to GlassesAction.LAUNCH_APP.id(),
            )
        }
        settings += Setting(
            KEY_DIAL, Setting.Kind.CHOICE, "Pinch and turn", GestureMappings.dial(context).key,
            DialMode.entries.map { SettingOption(it.key, it.title) }, SECTION_GESTURES,
        )
        settings += Setting(
            KEY_NAVIGATION, Setting.Kind.CHOICE, "Navigation", if (GestureMappings.isFastNavigation(context)) "fast" else "stable",
            listOf(SettingOption("stable", "Stable"), SettingOption("fast", "Fast")), SECTION_GESTURES,
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
        val actions = listOf(
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
                val action = GlassesAction.entries.firstOrNull { it.id() == value } ?: return "unknown action $value"
                GestureMappings.setAction(context, gesture, action, GestureMappings.launchPackage(context, gesture))
            }
            gesture != null -> {
                if (launchableApps(context).none { it.id == value }) return "app not installed: $value"
                GestureMappings.setAction(context, gesture, GlassesAction.LAUNCH_APP, value)
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
        // Reconnecting here takes the band back from the phone too.
        ACTION_RECONNECT -> {
            GestureMappings.setBandOnPhone(context, false)
            BandRuntime.restart(context).also { pushStatusNow() }
        }
        SettingsOps.ACTION_TO_GLASSES -> {
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
