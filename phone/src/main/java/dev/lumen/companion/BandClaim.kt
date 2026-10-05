package dev.lumen.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.band.BandLink
import dev.lumen.band.GestureDevice
import dev.lumen.band.Phase
import dev.lumen.companion.meta.MetaPairClient
import dev.lumen.companion.meta.MetaSession
import dev.lumen.protocol.SettingsOps
import org.json.JSONObject

/**
 * Generating the band's key with a Meta account, as kinesis and air-gestures do: with the
 * account session ([dev.lumen.companion.meta.MetaLoginActivity]), the phone claims a band in
 * pairing mode (a factory-reset band) through [BandLink]'s claim mode and Meta's hardware graph
 * ([MetaPairClient]). The band leaves Meta's own app and glasses. Then the key goes to the
 * glasses ([GlassesSetup.sendKey]) and the band back to them.
 *
 * [dryRun] (debug builds) stops right after the band's identity read, before anything is sent
 * to Meta: it checks the sign-in and the Bluetooth side without touching the band's owner.
 */
object BandClaim {
    sealed class State {
        object Idle : State()
        data class Running(val step: String) : State()
        object Claimed : State()
        data class DryRunDone(val serial: String) : State()
        data class Failed(val message: String) : State()
    }

    private const val TAG = "NbClaim"
    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile var state: State = State.Idle
        private set
    private var link: BandLink? = null

    val listeners = mutableSetOf<() -> Unit>()

    fun start(context: Context, session: MetaSession, dryRun: Boolean) {
        val app = context.applicationContext
        if (state is State.Running) return
        set(State.Running(app.getString(R.string.claim_step_waiting)))
        // The band talks to one device at a time: the glasses and the phone's own link let go.
        PhoneBand.stop()
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_PHONE))
        val client = MetaPairClient(session)
        val hooks = object : BandLink.Claim {
            override val dryRun = dryRun
            override fun onStage(text: String) = set(State.Running(text))
            override fun pairRequest(request: JSONObject): Pair<ByteArray, String> =
                client.pairRequest(request).let { it.signature to it.receipt }
            override fun pair(pair: JSONObject): Triple<ByteArray, String, ByteArray?> =
                client.pair(pair).let { Triple(it.signature, it.receipt, it.devicePublicKey) }
            override fun onClaimed() {
                main.post {
                    stopLink()
                    set(State.Claimed)
                    // The new key to the glasses, and the band back to them.
                    GlassesSetup.sendKey(app)
                    CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_GLASSES))
                }
            }
            override fun onFailed(message: String) = main.post { stopLink(); set(State.Failed(message)) }.let { }
            override fun onDryRun(serial: String) = main.post { set(State.DryRunDone(serial)) }.let { }
        }
        val listener = object : GestureDevice.Listener {
            override fun onPhase(phase: Phase, band: String?) {
                if (phase == Phase.CONNECTING && state is State.Running) set(State.Running(app.getString(R.string.claim_step_found, band.orEmpty())))
            }
            override fun onStatus(status: JSONObject) = Unit
            override fun onActions(actions: List<String>) = Unit
            override fun onLog(line: String) = Log.d(TAG, line).let { }
        }
        link = BandLink(app, listener, PhoneSettings.config(app), hooks).also { it.start() }
    }

    fun cancel(context: Context) {
        stopLink()
        set(State.Idle)
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_TO_GLASSES))
    }

    fun dismiss() {
        if (state !is State.Running) set(State.Idle)
    }

    private fun stopLink() {
        link?.stop()
        link = null
    }

    private fun set(next: State) {
        state = next
        main.post { listeners.toList().forEach { it() } }
    }
}
