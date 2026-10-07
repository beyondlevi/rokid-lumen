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
import dev.lumen.band.ScreenPointer
import dev.lumen.companion.computer.ComputerKeys
import dev.lumen.companion.computer.ComputerLink
import dev.lumen.companion.computer.ComputerPointer
import dev.lumen.companion.computer.ComputerProfiles
import dev.lumen.companion.computer.ComputerWriter
import dev.lumen.companion.ime.HandwritingSwitch
import dev.lumen.protocol.BandDevices
import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.SettingsEvent
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
 *
 * The band on the phone can also work for a computer ([useOnComputer]): the phone keeps the band
 * and is the computer's Bluetooth keyboard and mouse ([ComputerLink]), with the computer's own
 * profiles ([ComputerProfiles]). For the glasses the band is on the phone then, as before.
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
        if (CompanionPrefs.bandOnComputer(context)) ComputerLink.start(context)
    }

    /** The companion's "Use on the phone": the glasses let go, the phone connects. */
    fun useHere(context: Context) {
        leaveComputer(context)
        if (CompanionPrefs.bandOnPhone(context) && link != null) return
        CompanionPrefs.setBandOnPhone(context, true)
        // Until the glasses see the request they still say "not the phone's": no change then.
        glassesSaidPhone = false
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_PHONE))
        start(context)
    }

    /** The companion's "Use on the glasses": the phone lets go, then the glasses take it. */
    fun useOnGlasses(context: Context) {
        leaveComputer(context)
        CompanionPrefs.setBandOnPhone(context, false)
        stop()
        glassesSaidPhone = true
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_GLASSES))
    }

    /**
     * The band for a computer: here, from the glasses if it's there, and the phone becomes the
     * computer's keyboard and mouse (it connects to the last computer).
     */
    fun useOnComputer(context: Context) {
        PhonePointer.stop()
        CompanionPrefs.setBandOnComputer(context, true)
        if (!CompanionPrefs.bandOnPhone(context) || link == null) {
            CompanionPrefs.setBandOnPhone(context, true)
            glassesSaidPhone = false
            CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_PHONE))
            start(context)
        } else {
            applyMapping(context)
            applyLock(context)
        }
        ComputerLink.start(context)
        changed()
    }

    /** Back to the phone's own gestures: the computer loses its keyboard. */
    private fun leaveComputer(context: Context) {
        if (!CompanionPrefs.bandOnComputer(context)) return
        ComputerWriter.stop()
        ComputerPointer.stop()
        CompanionPrefs.setBandOnComputer(context, false)
        ComputerLink.stop()
        applyMapping(context)
        applyLock(context)
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
            leaveComputer(context)
            CompanionPrefs.setBandOnPhone(context, false)
            stop()
        }
    }

    /**
     * The glasses' Controls sent the band here ([SettingsEvent.BandTarget]): to this phone with
     * one of its profiles, or to a computer through this phone with a computer profile. The
     * glasses let it go themselves, so nothing is asked of them (a late request over Rokid's link
     * could take the band from them again after they took it back).
     */
    fun onGlassesTarget(context: Context, target: SettingsEvent.BandTarget) {
        val app = context.applicationContext
        Log.d(TAG, "the glasses send the band to the ${target.target}")
        // They let it go already; a status of theirs from before must not read as taking it back.
        glassesSaidPhone = false
        when (target.target) {
            BandDevices.PHONE -> {
                if (PhoneProfiles.state(app).profiles.any { it.id == target.profile }) {
                    PhoneProfiles.update(app) { it.copy(active = target.profile) }
                }
                if (CompanionPrefs.bandOnComputer(app)) leaveComputer(app)
            }
            BandDevices.COMPUTER -> {
                if (ComputerProfiles.state(app).profiles.any { it.id == target.profile }) {
                    ComputerProfiles.update(app) { it.copy(active = target.profile) }
                }
                if (target.computer.isNotEmpty()) ComputerLink.prefer(app, target.computer)
                PhonePointer.stop()
                CompanionPrefs.setBandOnComputer(app, true)
            }
            else -> return
        }
        CompanionPrefs.setBandOnPhone(app, true)
        if (link == null) start(app) else {
            applyMapping(app)
            applyLock(app)
        }
        if (target.target == BandDevices.COMPUTER) {
            ComputerLink.start(app)
            if (target.computer.isNotEmpty() && ComputerLink.computer?.address != target.computer) ComputerLink.connect(app, target.computer)
        }
        changed()
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
        PhonePointer.stop()
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

    /**
     * The air mouse on the band here (see [ComputerPointer] and [PhonePointer]); false when the
     * band isn't connected here or can't run it.
     */
    fun setPointer(on: Boolean, tuning: String): Boolean {
        val link = link ?: return false
        if (on && phase != Phase.CONNECTED) return false
        return link.setPointer(on, tuning)
    }

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
                    // The air mouse ends with the connection.
                    if (phase != Phase.CONNECTED) {
                        ComputerPointer.stop()
                        PhonePointer.stop()
                    }
                    changed()
                }
            }

            override fun onPointer(records: DoubleArray) {
                main.post { if (ComputerPointer.on) ComputerPointer.onBand(records) else PhonePointer.onBand(records) }
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
                            action == ScreenPointer.OFF -> {
                                ComputerPointer.stop()
                                PhonePointer.stop()
                            }
                            // Locked, and this profile doesn't count then (the band is told to
                            // stop sending too; this covers what was already on its way).
                            !listening -> Log.d(TAG, "$action ignored: the phone is locked")
                            CompanionPrefs.bandOnComputer(app) -> onComputer(app, action)
                            action == PhoneSettings.SWITCH_TO_GLASSES -> useOnGlasses(app)
                            action == PhoneProfiles.DIAL_UP || action == PhoneProfiles.DIAL_DOWN ->
                                dial(app, runner, action == PhoneProfiles.DIAL_UP)
                            action == PhoneProfiles.NEXT -> switchProfile(app, PhoneProfiles.step(PhoneProfiles.state(app), +1))
                            action == PhoneProfiles.PREVIOUS -> switchProfile(app, PhoneProfiles.step(PhoneProfiles.state(app), -1))
                            action.startsWith(PhoneProfiles.GO_PREFIX) -> switchProfile(app, action.removePrefix(PhoneProfiles.GO_PREFIX))
                            action == ScreenPointer.TOGGLE -> if (!PhonePointer.start(app)) Log.d(TAG, "$action: the air mouse can't start")
                            action == PhoneSettings.WRITE -> if (!HandwritingSwitch.start(app)) Log.d(TAG, "$action: can't switch to the handwriting keyboard")
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

    /** A gesture's action while the band works for a computer: a key there, writing, or a profile switch. */
    private fun onComputer(context: Context, action: String) {
        val computer = ComputerProfiles.state(context)
        val sent = when {
            action == PhoneProfiles.NEXT -> switchComputerProfile(context, ComputerProfiles.step(computer, +1))
            action == PhoneProfiles.PREVIOUS -> switchComputerProfile(context, ComputerProfiles.step(computer, -1))
            action.startsWith(PhoneProfiles.GO_PREFIX) -> switchComputerProfile(context, action.removePrefix(PhoneProfiles.GO_PREFIX))
            action == ComputerKeys.WRITE -> ComputerWriter.start(context)
            action == ComputerKeys.POINTER -> ComputerPointer.start(context)
            action == PhoneSettings.SWITCH_TO_GLASSES -> {
                useOnGlasses(context)
                true
            }
            action == PhoneProfiles.DIAL_UP || action == PhoneProfiles.DIAL_DOWN ->
                ComputerProfiles.dialAction(computer.current.dial, action == PhoneProfiles.DIAL_UP)?.let { send(context, it) } ?: true
            else -> send(context, action)
        }
        if (!sent) Log.d(TAG, "$action: not sent (no computer connected)")
    }

    private fun send(context: Context, action: String): Boolean {
        val output = ComputerKeys.action(action, ComputerProfiles.invertScroll(context), ComputerProfiles.scrollSteps(context)) ?: return true
        return ComputerLink.send(output)
    }

    /** Makes the computer profile [id] the active one; the phone says its name. */
    fun switchComputerProfile(context: Context, id: String): Boolean {
        val app = context.applicationContext
        val before = ComputerProfiles.state(app)
        if (before.profiles.none { it.id == id } || before.active == id) return true
        val after = ComputerProfiles.update(app) { it.copy(active = id) }
        applyMapping(app)
        ProfileNotice.show(app, before.current.name, after.current.name)
        changed()
        return true
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
        // For a computer the band always counts: the phone is in a pocket then.
        val now = !locked || CompanionPrefs.bandOnComputer(app) || PhoneProfiles.state(app).current.whenLocked
        // No cursor over a locked phone or a dark screen.
        if (locked) PhonePointer.stop()
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
        ComputerWriter.stop()
        ComputerPointer.stop()
        PhonePointer.stop()
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
