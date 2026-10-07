package dev.lumen.glasses

import android.content.Context
import android.util.Log
import dev.lumen.protocol.BandDevices
import dev.lumen.protocol.Link
import dev.lumen.protocol.SettingsEvent
import dev.lumen.protocol.SettingsOps

/**
 * Where the band is and where it can go, from the glasses: the phone's last [BandDevices] (its
 * profiles, the computers it has been a keyboard for; kept across restarts, since Rokid's link
 * can hold the next one for minutes) and the moves the Controls make ([BandDeviceActivity]).
 * Every move and every pause or resume shows a toast ([MetaToast]); one the phone reports late
 * still shows, unless the glasses made it themselves. Main thread.
 */
object BandSwitch {
    private const val TAG = "BandSwitch"
    private const val PREFS = "band_devices"
    private const val KEY = "devices"

    @Volatile var devices: BandDevices? = null
        private set

    val listeners = LinkedHashSet<() -> Unit>()

    /** The state the glasses moved the band to, so the phone's report of it doesn't toast twice. */
    private var expected: Triple<String, String, String>? = null

    /** The band came back here: say so once it connects ([BandRuntime]). */
    @Volatile var arrivalPending = false

    /** When the glasses last sent the band away (wall clock), against a late report from before. */
    private var movedAt = 0L

    @JvmStatic
    fun load(context: Context) {
        if (devices != null) return
        val text = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return
        devices = runCatching { BandDevices.from(Link.parse(text)) }.getOrNull()
    }

    /** Where the band is: the glasses, or the phone's word for where it went. */
    @JvmStatic
    fun where(context: Context): String {
        if (!GestureMappings.isBandOnPhone(context)) return BandDevices.GLASSES
        return devices?.where?.takeIf { it != BandDevices.GLASSES } ?: BandDevices.PHONE
    }

    /** The phone's report: kept, and a toast for what changed there that the glasses didn't do. */
    @JvmStatic
    fun onPhone(context: Context, now: BandDevices) {
        val before = devices
        devices = now
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, now.toJson().toString()).apply()
        // The phone let the band go for the glasses (its Use the band on the glasses, or the
        // companion's switch): take it now rather than when Rokid's link delivers the request.
        if (now.where == BandDevices.GLASSES && GestureMappings.isBandOnPhone(context) && now.since > 0 &&
            now.since > movedAt && now.since > (before?.since ?: 0L)
        ) {
            Log.d(TAG, "the phone let the band go for the glasses")
            toGlasses(context)
        }
        if (now.where != BandDevices.GLASSES && GestureMappings.isBandOnPhone(context)) {
            val state = state(now)
            val moved = before == null || state(before) != state
            if (moved && state != expected) toastPlace(context, now)
            if (before != null && before.paused != now.paused && before.where == now.where) toastPause(context, now.paused, now.pauseGesture)
            expected = null
        }
        listeners.toList().forEach { it() }
    }

    private fun state(devices: BandDevices) = Triple(devices.where, devices.computer, profileOf(devices))

    private fun profileOf(devices: BandDevices) = if (devices.where == BandDevices.COMPUTER) devices.computerProfile else devices.phoneProfile

    /** The band back to the glasses (they take it; the phone sees their status and lets go). */
    @JvmStatic
    fun toGlasses(context: Context) {
        if (!GestureMappings.isBandOnPhone(context)) return
        BandSettings.action(context, SettingsOps.ACTION_TO_GLASSES)
        Log.d(TAG, "the band comes back to the glasses")
        listeners.toList().forEach { it() }
    }

    /** The band to the phone ([BandDevices.PHONE]) or to a computer through it, with [profile] there. */
    @JvmStatic
    fun send(context: Context, target: String, computer: String, profile: String) {
        PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.BandTarget(target, computer, profile).toJson())
        if (!GestureMappings.isBandOnPhone(context)) {
            // As the Use the band on the phone action: let it go and stay off it.
            BandSettings.action(context, SettingsOps.ACTION_TO_PHONE)
        }
        expected = Triple(target, if (target == BandDevices.COMPUTER) computer else "", profile)
        movedAt = System.currentTimeMillis()
        devices = devices?.let {
            if (target == BandDevices.COMPUTER) it.copy(where = target, computer = computer, computerProfile = profile)
            else it.copy(where = target, computer = "", phoneProfile = profile)
        }
        Log.d(TAG, "the band goes to the $target")
        devices?.let { toastPlace(context, it) } ?: MetaToast.show(R.drawable.ic_phone, context.getString(R.string.toast_band_phone))
        listeners.toList().forEach { it() }
    }

    /** "Band on the phone · Media", "Band on <computer> · Notebook". */
    private fun toastPlace(context: Context, devices: BandDevices) {
        when (devices.where) {
            BandDevices.COMPUTER -> {
                val name = devices.computers.firstOrNull { it.address == devices.computer }?.name ?: context.getString(R.string.band_switch_computer)
                val profile = devices.computerProfiles.firstOrNull { it.id == devices.computerProfile }?.name.orEmpty()
                MetaToast.show(R.drawable.ic_laptop, context.getString(R.string.toast_band_computer, name), profile)
            }
            else -> {
                val profile = devices.phoneProfiles.firstOrNull { it.id == devices.phoneProfile }?.name.orEmpty()
                MetaToast.show(R.drawable.ic_phone, context.getString(R.string.toast_band_phone), profile)
            }
        }
    }

    /** "Band paused · Middle hold resumes", or "Band resumed". */
    @JvmStatic
    fun toastPause(context: Context, paused: Boolean, resumeGesture: String) {
        if (!paused) {
            MetaToast.show(R.drawable.ic_band, context.getString(R.string.toast_band_resumed))
            return
        }
        val gesture = GestureLabels.of(context, resumeGesture)
        MetaToast.show(R.drawable.ic_pause, context.getString(R.string.toast_band_paused), gesture?.let { context.getString(R.string.toast_band_resume_with, it) }.orEmpty())
    }

    /** The band connected here after coming back. */
    @JvmStatic
    fun onArrived(context: Context) {
        if (!arrivalPending) return
        arrivalPending = false
        MetaToast.show(R.drawable.ic_glasses, context.getString(R.string.toast_band_glasses))
    }
}

/** The gestures' names, by mapping key, in the glasses' language. */
object GestureLabels {
    private val labels = mapOf(
        "swipe_right" to R.string.gesture_swipe_right,
        "swipe_left" to R.string.gesture_swipe_left,
        "swipe_down" to R.string.gesture_swipe_down,
        "swipe_up" to R.string.gesture_swipe_up,
        "index_tap" to R.string.gesture_index_tap,
        "middle_tap" to R.string.gesture_middle_tap,
        "index_double" to R.string.gesture_index_double,
        "middle_double" to R.string.gesture_middle_double,
        "index_hold" to R.string.gesture_index_hold,
        "middle_hold" to R.string.gesture_middle_hold,
    )

    @JvmStatic
    fun of(context: Context, key: String): String? = labels[key]?.let { context.getString(it) }
}
