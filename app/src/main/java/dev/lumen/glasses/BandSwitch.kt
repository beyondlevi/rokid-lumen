package dev.lumen.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.band.Phase
import dev.lumen.protocol.BandDevices
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.Link
import dev.lumen.protocol.SettingsEvent
import dev.lumen.protocol.SettingsOps

/**
 * Where the band is and where it can go, from the glasses: the phone's last [BandDevices] (its
 * profiles, the computers it has been a keyboard for; kept across restarts, since Rokid's link
 * can hold the next one for minutes) and the moves the Controls make ([BandDeviceActivity]).
 *
 * A move shows its steps in a progress toast ([MetaToast.progress]): one away (the band going to
 * the phone or a computer) follows the phone's reports of its link there, which can come late; one
 * back here follows the band's own link. Either says so when it takes long, and still toasts the
 * arrival when it comes. A move the phone or the companion made toasts once the phone reports it,
 * and so does every pause or resume. Main thread.
 */
object BandSwitch {
    private const val TAG = "BandSwitch"
    private const val PREFS = "band_devices"
    private const val KEY = "devices"

    /** A move still on its way then: the toast says it takes long (and it goes on). */
    private const val SLOW_MS = 30_000L

    /** A move not done by then is left alone (no toast when it ends). */
    private const val GIVE_UP_MS = 180_000L

    @Volatile var devices: BandDevices? = null
        private set

    val listeners = LinkedHashSet<() -> Unit>()

    /** The glasses sent the band away ([send]) and wait for the phone's word that it works there. */
    private class Away(val target: String, val computer: String, val profile: String, val at: Long) {
        var slow = false
    }

    private var away: Away? = null

    /** When the band started coming back here (elapsed realtime), 0 when it isn't. */
    private var arriving = 0L
    private var arrivingSlow = false

    /** When the glasses last sent the band away (wall clock), against a late report from before. */
    private var movedAt = 0L

    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null

    private val slowCheck = Runnable { onSlow() }

    @JvmStatic
    fun load(context: Context) {
        appContext = context.applicationContext
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

    /** The phone's report: kept, the move away followed, and a toast for what changed there that the glasses didn't do. */
    @JvmStatic
    fun onPhone(context: Context, now: BandDevices) {
        val before = devices
        devices = now
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, now.toJson().toString()).apply()
        // The phone let the band go for the glasses (its Use the band on the glasses, or the
        // companion's switch): take it now rather than when Rokid's link delivers the request.
        if (now.where == BandDevices.GLASSES && GestureMappings.isBandOnPhone(context) && now.since > 0 &&
            now.since > movedAt && now.since > (before?.since ?: 0L) &&
            // Later than that, the phone has taken the band back (SettingsOps.HAND_OVER_MS).
            System.currentTimeMillis() - now.since <= SettingsOps.HAND_OVER_MS
        ) {
            Log.d(TAG, "the phone let the band go for the glasses")
            toGlasses(context)
        }
        val move = away
        if (move != null) {
            if (matches(move, now)) {
                // A phone too old to report its link says only where the band went.
                if (now.phase.isEmpty() || now.arrived) arrivedAway(context, now) else if (!move.slow) awayProgress(context, move, now)
            }
        } else if (now.where != BandDevices.GLASSES && GestureMappings.isBandOnPhone(context) && arriving == 0L) {
            val moved = before == null || state(before) != state(now)
            if (moved) toastPlace(context, now)
        }
        if (before != null && before.paused != now.paused && before.where == now.where && now.where != BandDevices.GLASSES &&
            GestureMappings.isBandOnPhone(context)
        ) {
            toastPause(context, now.paused, now.pauseGesture)
        }
        listeners.toList().forEach { it() }
    }

    private fun state(devices: BandDevices) = Triple(devices.where, devices.computer, profileOf(devices))

    private fun profileOf(devices: BandDevices) = if (devices.where == BandDevices.COMPUTER) devices.computerProfile else devices.phoneProfile

    /** The report is about the move: the band where it was sent, with the profile it was sent with. */
    private fun matches(move: Away, report: BandDevices) = report.where == move.target &&
        (move.target != BandDevices.COMPUTER || move.computer.isEmpty() || report.computer == move.computer) &&
        (move.profile.isEmpty() || profileOf(report) == move.profile)

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
        appContext = context.applicationContext
        movedAt = System.currentTimeMillis()
        stopArriving()
        val move = Away(target, if (target == BandDevices.COMPUTER) computer else "", profile, SystemClock.elapsedRealtime())
        away = move
        devices = devices?.let {
            val moved = it.copy(phase = "", computerConnected = false)
            if (target == BandDevices.COMPUTER) moved.copy(where = target, computer = computer, computerProfile = profile)
            else moved.copy(where = target, computer = "", phoneProfile = profile)
        }
        Log.d(TAG, "the band goes to the $target")
        awayProgress(context, move, null)
        scheduleSlow()
        listeners.toList().forEach { it() }
    }

    /** "Band going to the phone · waiting for the phone", then the phone's steps. */
    private fun awayProgress(context: Context, move: Away, report: BandDevices?) {
        val step = when {
            report == null -> R.string.move_waiting_phone
            report.phase == BandStatus.PHASE_CONNECTED -> R.string.move_computer_connecting
            report.phase == BandStatus.PHASE_CONNECTING -> R.string.move_phone_connecting
            else -> R.string.move_phone_searching
        }
        MetaToast.progress(destination(context, move), context.getString(step))
    }

    private fun destination(context: Context, move: Away): String = when (move.target) {
        BandDevices.COMPUTER -> context.getString(R.string.move_to_computer, computerName(context, move.computer))
        else -> context.getString(R.string.move_to_phone)
    }

    private fun computerName(context: Context, address: String) =
        devices?.computers?.firstOrNull { it.address == address }?.name ?: context.getString(R.string.band_switch_computer)

    private fun arrivedAway(context: Context, report: BandDevices) {
        away = null
        main.removeCallbacks(slowCheck)
        Log.d(TAG, "the band works on the ${report.where}")
        toastPlace(context, report)
    }

    /** The band is coming back here (the phone's request, the Controls, a Reconnect): its steps follow. */
    @JvmStatic
    fun arriving(context: Context) {
        val app = context.applicationContext
        appContext = app
        main.post {
            if (arriving != 0L) return@post
            away = null
            arriving = SystemClock.elapsedRealtime()
            arrivingSlow = false
            arrivingProgress(app, BandRuntime.phase)
            scheduleSlow()
        }
    }

    /** The band's link here changed: the arrival's next step, or its end. */
    @JvmStatic
    fun onPhase(context: Context, phase: Phase) {
        if (arriving == 0L) return
        if (phase == Phase.CONNECTED) {
            stopArriving()
            MetaToast.settle(R.drawable.ic_glasses, context.getString(R.string.toast_band_glasses))
        } else if (!arrivingSlow) {
            arrivingProgress(context, phase)
        }
    }

    private fun arrivingProgress(context: Context, phase: Phase) {
        val step = when (phase) {
            Phase.SEARCHING -> context.getString(R.string.move_searching)
            Phase.CONNECTING -> context.getString(R.string.move_connecting)
            else -> ""
        }
        MetaToast.progress(context.getString(R.string.move_to_glasses), step)
    }

    private fun stopArriving() {
        arriving = 0L
        arrivingSlow = false
        main.removeCallbacks(slowCheck)
    }

    private fun scheduleSlow() {
        main.removeCallbacks(slowCheck)
        main.postDelayed(slowCheck, SLOW_MS)
    }

    /** Still on its way: say so (the toast settles), keep following it until [GIVE_UP_MS]. */
    private fun onSlow() {
        val context = appContext ?: return
        val now = SystemClock.elapsedRealtime()
        val move = away
        if (move != null) {
            if (now - move.at >= GIVE_UP_MS) {
                away = null
                return
            }
            if (!move.slow) {
                move.slow = true
                MetaToast.settle(R.drawable.ic_phone, context.getString(R.string.move_slow_phone), context.getString(R.string.move_slow_phone_detail))
            }
            main.postDelayed(slowCheck, GIVE_UP_MS - (now - move.at))
            return
        }
        if (arriving != 0L) {
            if (now - arriving >= GIVE_UP_MS) {
                stopArriving()
                return
            }
            if (!arrivingSlow) {
                arrivingSlow = true
                MetaToast.settle(R.drawable.ic_band, context.getString(R.string.move_slow_band), context.getString(R.string.move_slow_band_detail))
            }
            main.postDelayed(slowCheck, GIVE_UP_MS - (now - arriving))
        }
    }

    /** "Band on the phone · Media", "Band on <computer> · Notebook". */
    private fun toastPlace(context: Context, devices: BandDevices) {
        when (devices.where) {
            BandDevices.COMPUTER -> {
                val name = devices.computers.firstOrNull { it.address == devices.computer }?.name ?: context.getString(R.string.band_switch_computer)
                val profile = devices.computerProfiles.firstOrNull { it.id == devices.computerProfile }?.name.orEmpty()
                MetaToast.settle(R.drawable.ic_laptop, context.getString(R.string.toast_band_computer, name), profile)
            }
            else -> {
                val profile = devices.phoneProfiles.firstOrNull { it.id == devices.phoneProfile }?.name.orEmpty()
                MetaToast.settle(R.drawable.ic_phone, context.getString(R.string.toast_band_phone), profile)
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
