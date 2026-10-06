package dev.lumen.companion

import android.Manifest
import android.app.KeyguardManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.PowerManager
import android.os.Looper
import android.util.Log
import dev.lumen.band.BandLink
import dev.lumen.band.GestureDevice
import dev.lumen.band.Identity
import dev.lumen.band.Phase
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.SettingsOps
import org.json.JSONObject

/**
 * The band on the phone: the same link the glasses run ([BandLink], from :band), with the
 * gestures turned into phone actions ([PhoneActions]). The band talks to one device at a time,
 * so it's handed over: [useHere] asks the glasses to let go (they stay off it while their status
 * says [BandStatus.onPhone]) and connects here (autoConnect waits until the band is free);
 * [useOnGlasses] lets go here first, then asks the glasses to take it. The glasses' status says
 * where the band is: when it turns to the phone (the switch gesture on the glasses) the phone
 * connects, and when it turns back (the glasses took it: Reconnect there) the phone lets go. The
 * switch gesture here ([PhoneSettings.SWITCH_TO_GLASSES]) is [useOnGlasses].
 * Runs inside [CompanionService]; main thread.
 */
object PhoneBand {
    private const val TAG = "NbBand"

    private val main = Handler(Looper.getMainLooper())
    private var link: BandLink? = null
    private var actions: PhoneActions? = null
    /** What the glasses said last about where the band is, to act on a change only. */
    private var glassesSaidPhone: Boolean? = null

    @Volatile var phase: Phase = Phase.STOPPED
        private set

    @Volatile var status: JSONObject = JSONObject()
        private set

    @Volatile var lastLog: String = ""
        private set

    val listeners = mutableSetOf<() -> Unit>()

    val isRunning get() = link != null

    fun battery(): Int = if (status.has("battery")) status.optInt("battery", -1) else -1

    /** Starts the link if the band belongs here and it can run (the service calls this). */
    fun resume(context: Context) {
        if (CompanionPrefs.bandOnPhone(context)) start(context)
    }

    /** The companion's "Use on the phone": the glasses let go, the phone connects. */
    fun useHere(context: Context) {
        CompanionPrefs.setBandOnPhone(context, true)
        // Until the glasses see the request they still say "not the phone's": no change then.
        glassesSaidPhone = false
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_PHONE))
        start(context)
    }

    /** The companion's "Use on the glasses": the phone lets go, then the glasses take it. */
    fun useOnGlasses(context: Context) {
        CompanionPrefs.setBandOnPhone(context, false)
        stop()
        glassesSaidPhone = true
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_GLASSES))
    }

    /**
     * The glasses' status, acted on when it changes (a status sent before the glasses saw a
     * request says what it said before, and changes nothing): the band given to the phone from
     * the glasses connects here; the band taken back by the glasses stops here.
     */
    fun onGlassesStatus(context: Context, glasses: BandStatus) {
        val before = glassesSaidPhone
        glassesSaidPhone = glasses.onPhone
        if (before == glasses.onPhone) return
        // A claim asked the glasses to let go for its own link: this one stays off the band.
        if (BandClaim.state is BandClaim.State.Running) return
        val here = CompanionPrefs.bandOnPhone(context)
        if (glasses.onPhone && !here) {
            Log.d(TAG, "the glasses handed the band over")
            CompanionPrefs.setBandOnPhone(context, true)
            start(context)
        } else if (!glasses.onPhone && here) {
            Log.d(TAG, "the glasses took the band back")
            CompanionPrefs.setBandOnPhone(context, false)
            stop()
        }
    }

    /** The gesture settings changed in the Band tab: the band gets the new mapping. */
    fun applyMapping(context: Context) {
        link?.setMapping(if (handwriting != null) writingMapping(context) else PhoneSettings.mapping(context))
    }

    /** Who receives the band's handwriting: the handwriting keyboard while it writes. */
    interface HandwritingSink {
        /** A handwriting event (see `Bridge.handwritingEvents`); its text is never to be logged. */
        fun onHandwriting(event: JSONObject)

        /** The middle tap: the writing is over. */
        fun onExit()
    }

    /** The action the middle tap runs while the band writes. */
    const val HANDWRITING_EXIT = "handwriting.exit"

    private var handwriting: HandwritingSink? = null

    /** Whether the band is connected here, so it can write. */
    val canWrite get() = link != null && phase == Phase.CONNECTED

    /**
     * Switch the band's handwriting on for [sink]; false when the band isn't connected here. While
     * it writes the bridge lets only the middle tap through, and here it ends the writing
     * ([HandwritingSink.onExit]), whatever the Band tab maps it to.
     */
    fun startHandwriting(context: Context, sink: HandwritingSink): Boolean {
        val link = link ?: return false
        if (phase != Phase.CONNECTED) return false
        handwriting = sink
        link.setMapping(writingMapping(context))
        if (!link.setHandwriting(true)) {
            handwriting = null
            link.setMapping(PhoneSettings.mapping(context))
            return false
        }
        return true
    }

    /** Start the written text over from [text]. */
    fun resetHandwritingText(text: String) {
        link?.resetHandwritingText(text)
    }

    /** End the writing: the band goes back to normal and to its usual gestures. */
    fun stopHandwriting(context: Context, sink: HandwritingSink) {
        if (handwriting !== sink) return
        handwriting = null
        link?.setHandwriting(false)
        link?.setMapping(PhoneSettings.mapping(context))
    }

    /**
     * Debug builds: [text] reaches the keyboard while it writes, a letter at a time, as if the band
     * wrote it (for screenshots and demos). False when nothing is writing.
     */
    fun simulateWriting(text: String): Boolean {
        if (handwriting == null) return false
        text.indices.forEach { index ->
            main.postDelayed({
                handwriting?.onHandwriting(JSONObject().put("type", "text").put("text", text.take(index + 1)))
            }, SIMULATED_LETTER_MS * (index + 1))
        }
        return true
    }

    private const val SIMULATED_LETTER_MS = 650L

    /** The usual mapping, with the middle tap ending the writing at once (no double tap). */
    private fun writingMapping(context: Context) =
        PhoneSettings.mapping(context) + ";middle_tap=$HANDWRITING_EXIT;middle_double="

    /** Why the link can't run here, or null. */
    fun problem(context: Context): Problem? = when {
        !Identity.present(context) -> Problem.NO_KEY
        listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            .any { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED } -> Problem.NO_BLUETOOTH
        else -> null
    }

    enum class Problem { NO_KEY, NO_BLUETOOTH }

    fun start(context: Context) {
        if (link != null) return
        val app = context.applicationContext
        problem(app)?.let {
            Log.d(TAG, "not starting: $it")
            changed()
            return
        }
        val runner = PhoneActions(app).also { actions = it }
        val listener = object : GestureDevice.Listener {
            override fun onPhase(phase: Phase, band: String?) {
                main.post {
                    this@PhoneBand.phase = phase
                    changed()
                }
            }

            override fun onStatus(status: JSONObject) {
                main.post {
                    this@PhoneBand.status = status
                    changed()
                }
            }

            override fun onActions(actions: List<String>) {
                main.post {
                    actions.forEach { action ->
                        Log.d(TAG, "action $action")
                        when {
                            action == HANDWRITING_EXIT -> handwriting?.onExit()
                            // Locked, and this profile doesn't count then (the band is told to
                            // stop sending too; this covers what was already on its way).
                            !listening -> Log.d(TAG, "$action ignored: the phone is locked")
                            action == PhoneSettings.SWITCH_TO_GLASSES -> useOnGlasses(app)
                            action == PhoneProfiles.DIAL_UP || action == PhoneProfiles.DIAL_DOWN ->
                                dial(app, runner, action == PhoneProfiles.DIAL_UP)
                            action == PhoneProfiles.NEXT -> switchProfile(app, PhoneProfiles.step(PhoneProfiles.state(app), +1))
                            action == PhoneProfiles.PREVIOUS -> switchProfile(app, PhoneProfiles.step(PhoneProfiles.state(app), -1))
                            action.startsWith(PhoneProfiles.GO_PREFIX) -> switchProfile(app, action.removePrefix(PhoneProfiles.GO_PREFIX))
                            else -> runner.run(action)?.let { Log.d(TAG, "$action: $it") }
                        }
                    }
                }
            }

            override fun onLog(line: String) {
                main.post {
                    Log.d(TAG, line)
                    lastLog = line
                    changed()
                }
            }

            override fun onHandwriting(event: JSONObject) {
                main.post { handwriting?.onHandwriting(event) }
            }
        }
        link = BandLink(app, listener, PhoneSettings.config(app)).also { it.start() }
        watchLock(app)
        Log.d(TAG, "started")
        changed()
    }

    // ---- Profiles ----

    /** Makes [id] the active profile: the band gets its gestures, and the phone says its name. */
    fun switchProfile(context: Context, id: String) {
        val app = context.applicationContext
        val before = PhoneProfiles.state(app)
        if (before.profiles.none { it.id == id } || before.active == id) return
        val after = PhoneProfiles.update(app) { it.copy(active = id) }
        applyMapping(app)
        applyLock(app)
        ProfileNotice.show(app, before.current.name, after.current.name)
        changed()
    }

    /** Pinch and turn: the volume while audio plays, otherwise what the active profile says. */
    private fun dial(context: Context, runner: PhoneActions, up: Boolean) {
        val playing = context.getSystemService(AudioManager::class.java)?.isMusicActive == true
        val target = if (playing) "volume" else PhoneProfiles.state(context).current.dial
        val action = when (target) {
            "volume" -> if (up) "volume.up" else "volume.down"
            "brightness" -> if (up) "brightness.up" else "brightness.down"
            "arrows" -> if (up) "key.dpad_up" else "key.dpad_down"
            else -> return
        }
        runner.run(action)?.let { Log.d(TAG, "$action: $it") }
    }

    // ---- The locked phone ----

    /** Whether band gestures count now: always while unlocked, locked only if the profile says so. */
    @Volatile var listening = true
        private set

    private var lockReceiver: BroadcastReceiver? = null
    private var lockContext: Context? = null

    private fun watchLock(app: Context) {
        if (lockReceiver != null) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = applyLock(app)
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) app.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else app.registerReceiver(receiver, filter)
        lockReceiver = receiver
        lockContext = app
        applyLock(app)
    }

    private fun unwatchLock(app: Context) {
        lockReceiver?.let { runCatching { app.unregisterReceiver(it) } }
        lockReceiver = null
        lockContext = null
        lockApplied = false
        listening = true
    }

    /**
     * Locked (or the screen off) with a profile that doesn't count then: the band stops sending
     * gestures and motion (its power saving, as on the glasses with the screen off), and comes
     * back when the phone is unlocked.
     */
    fun applyLock(context: Context) {
        val app = context.applicationContext
        val locked = app.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true ||
            app.getSystemService(PowerManager::class.java)?.isInteractive == false
        val now = !locked || PhoneProfiles.state(app).current.whenLocked
        if (now == listening && lockApplied) return
        listening = now
        lockApplied = true
        Log.d(TAG, if (now) "listening" else "the phone is locked: the band stops until it's unlocked")
        link?.setGestures(now)
        link?.setMotion(now)
        changed()
    }

    private var lockApplied = false

    fun stop() {
        handwriting = null
        lockContext?.let { unwatchLock(it) }
        link?.stop()
        link = null
        actions?.close()
        actions = null
        phase = Phase.STOPPED
        status = JSONObject()
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }
}
