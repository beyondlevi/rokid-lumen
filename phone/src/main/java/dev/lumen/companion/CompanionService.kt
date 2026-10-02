package dev.lumen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import com.rokid.cxr.Caps
import com.rokid.cxr.link.CXRLink
import com.rokid.cxr.link.callbacks.IAudioStreamCbk
import dev.lumen.companion.audio.PhoneAudio
import com.rokid.cxr.link.callbacks.ICXRLinkCbk
import com.rokid.cxr.link.callbacks.ICustomViewCbk
import com.rokid.cxr.link.callbacks.IGlassAppCbk
import com.rokid.cxr.link.callbacks.IImageStreamCbk
import com.rokid.cxr.link.utils.GlassInfo
import com.rokid.cxr.link.callbacks.ICXRSessionCbk
import com.rokid.cxr.link.callbacks.ICustomCmdCbk
import com.rokid.cxr.link.utils.CxrDefs
import dev.lumen.protocol.DictationCommand
import dev.lumen.protocol.DictationEvent
import dev.lumen.protocol.Link
import dev.lumen.protocol.NetCommand
import dev.lumen.protocol.NetEvent
import dev.lumen.protocol.NotifyCommand
import dev.lumen.protocol.NotifyEvent
import dev.lumen.protocol.AudioOps
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridOps
import dev.lumen.protocol.SettingsEvent
import dev.lumen.protocol.SettingsOps
import org.json.JSONObject
import dev.lumen.companion.speech.AndroidStt
import java.io.File
import dev.lumen.companion.speech.CloudStt
import dev.lumen.companion.speech.DictationSession
import dev.lumen.companion.speech.SpeechProvider
import dev.lumen.companion.speech.SpeechSecrets
import dev.lumen.companion.speech.SpeechSettings
import dev.lumen.companion.speech.SttError
import dev.lumen.companion.speech.SttErrorKind
import dev.lumen.companion.speech.SttListener
import dev.lumen.companion.speech.SttSession
import dev.lumen.companion.speech.VoskStt

/**
 * Keeps the CXR-L link to the glasses (through the Hi Rokid app), carries the phone's
 * notifications to them ([NotificationForwarder]) and serves dictation to the glasses app: on `start` it takes the glasses' microphone, transcribes it with Vosk and sends
 * partial and final text back; on `stop` it releases the mic. The glasses app is the CUSTOMAPP
 * of the session, so the link carries our custom commands only while it's in front there.
 *
 * The messages are in the shared :protocol module ([Link]); [Protocol] is the Caps transport.
 */
class CompanionService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var link: CXRLink? = null
    /** The dictation the glasses' audio goes to; an earlier one may still be finishing. */
    private var dictation: DictationSession? = null
    private val finishing = mutableSetOf<DictationSession>()
    private var streaming = false

    /** Where the glasses' microphone goes while a web app records ([PhoneAudio]); null otherwise. */
    @Volatile private var recorderPcm: ((ByteArray) -> Unit)? = null

    private val audio by lazy { PhoneAudio(this, audioHost) }

    private val audioHost = object : PhoneAudio.Host {
        override fun sendAudio(json: JSONObject, bytes: ByteArray?): Boolean {
            val cxr = link ?: return false
            val caps = Protocol.encode(json).apply { if (bytes != null) write(bytes) }
            val result = runCatching { cxr.sendCustomCmd(Link.AUDIO_EVENT, caps) }
            if (json.optString("type") != AudioOps.LEVEL && json.optString("type") != AudioOps.CHUNK) {
                Log.d(TAG, "→ glasses audio ${json.optString("type")} = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
            }
            return result.getOrNull() == 0
        }

        override fun dictating() = dictation != null || finishing.isNotEmpty()

        override fun startMicrophone(onPcm: (ByteArray) -> Unit, done: (Boolean) -> Unit) {
            val cxr = link ?: return done(false)
            recorderPcm = onPcm
            cxr.setInterruptAiWake(true)
            startRecorderAudio(cxr, attempt = 1, done)
        }

        override fun stopMicrophone() {
            recorderPcm = null
            if (dictation == null) stopAudio()
        }

        override fun openSession(sink: DictationSession.Sink, ready: (DictationSession?, String?) -> Unit) {
            val own = object : DictationSession.Sink by sink {
                override fun stopped() {
                    sink.stopped()
                    main.post { if (dictation == null && finishing.isEmpty()) useMicrophoneForeground(false) }
                }
            }
            this@CompanionService.openSession(own, ready = ready)
        }

        override fun errorText(error: SttError) = this@CompanionService.errorText(error)
    }

    /** [tryStartAudio] for a web app's recording. */
    private fun startRecorderAudio(cxr: CXRLink, attempt: Int, done: (Boolean) -> Unit) {
        if (recorderPcm == null || link !== cxr) return
        if (attempt == 2) runCatching { cxr.stopAudioStream() }
        streaming = cxr.startAudioStream(Protocol.CODEC_PCM)
        Log.d(TAG, "startAudioStream (recording) attempt $attempt = $streaming")
        when {
            streaming -> done(true)
            attempt < AUDIO_START_ATTEMPTS -> main.postDelayed({ startRecorderAudio(cxr, attempt + 1, done) }, 400)
            else -> {
                recorderPcm = null
                cxr.setInterruptAiWake(false)
                done(false)
            }
        }
    }
    private var microphoneForeground = false
    private val network by lazy { PhoneNetwork(this, ::sendNet) }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        startInForeground()
        connect()
        PhoneBand.resume(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RECONNECT) {
            link?.disconnect()
            link = null
            connect()
        }
        // Debug builds: `adb shell run-as dev.lumen.companion am startservice -a <action> …`
        // exercises the glasses' internet without the glasses asking.
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) when (intent?.action) {
            ACTION_NET_UP -> network.up(intent.getLongExtra("lease_ms", PhoneNetwork.LEASE_MS))
            ACTION_NET_DOWN -> network.down()
            ACTION_BENCH -> bench(intent.getIntExtra("size", 4096), intent.getIntExtra("count", 64))
            ACTION_DICTATE_FILE -> dictateFile(File(filesDir, intent.getStringExtra("file") ?: "speech.pcm"))
        }
        // Not sticky: a crash here must not become a restart loop.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        main.removeCallbacksAndMessages(null)
        PhoneBand.stop()
        cancelListening("service stopped")
        network.down()
        link?.disconnect()
        link = null
        report(LinkState.STOPPED, "service destroyed")
        super.onDestroy()
    }

    private fun connect() {
        val token = CompanionPrefs.token(this)
        if (token == null) {
            report(LinkState.NOT_AUTHORIZED)
            return
        }
        val cxr = CXRLink(this)
        link = cxr
        cxr.configCXRSession(
            CxrDefs.CXRSession(CxrDefs.CXRSessionType.CUSTOMAPP, Protocol.GLASSES_PACKAGE),
            object : ICXRSessionCbk {
                override fun onSessionAvailable(reason: CxrDefs.CXRSessionReason) {
                    report(LinkState.SESSION_READY, reason.toString())
                    scheduleSync()
                    main.postDelayed({
                        sendSettings(SettingsOps.describe())
                        sendGrid(GridOps.describe())
                    }, 2_000)
                }
                override fun onSessionStart(reason: CxrDefs.CXRSessionReason) = report(LinkState.SESSION_ACTIVE, reason.toString())
                override fun onSessionPause(reason: CxrDefs.CXRSessionReason) = report(LinkState.SESSION_PAUSED, reason.toString())
                override fun onSessionUnavailable(reason: CxrDefs.CXRSessionReason) {
                    report(LinkState.SESSION_UNAVAILABLE, reason.toString())
                    main.post {
                        cancelListening("session unavailable")
                        audio.reset()
                    }
                }
            },
        )
        cxr.setCXRCustomCmdCbk(ICustomCmdCbk { name, data -> main.post { onGlassesMessage(name, data) } })
        cxr.setCXRAudioCbk(object : IAudioStreamCbk {
            // Measured with Hi Rokid: the two ints are the chunk's offset and length (640 bytes
            // = 20 ms of 16 kHz mono PCM16), not a sample rate and a channel count.
            override fun onAudioReceived(data: ByteArray, offset: Int, length: Int) = onAudio(data, offset, length)
            override fun onAudioError(code: Int, msg: String) {
                Log.w(TAG, "audio error $code $msg")
                main.post {
                    send(DictationEvent.ERROR, getString(R.string.dictation_error_mic, msg))
                    cancelListening("audio error")
                }
            }
            override fun onAudioStreamStateChanged(streaming: Boolean) {
                Log.d(TAG, "audio streaming=$streaming")
            }
        })
        // CXR-L 1.1.2 keeps every callback in a lateinit field and reads all of them when the
        // Hi Rokid service connects: an unset one crashes the app there (measured). Set them all.
        cxr.setCXRLinkCbk(object : ICXRLinkCbk {
            override fun onCXRLConnected(connected: Boolean) = report(if (connected) LinkState.HI_ROKID_CONNECTED else LinkState.HI_ROKID_DISCONNECTED)
            override fun onGlassBtConnected(connected: Boolean) {
                report(if (connected) LinkState.GLASSES_CONNECTED else LinkState.GLASSES_DISCONNECTED)
                if (connected) scheduleSync()
            }
            override fun onGlassDeviceInfo(info: GlassInfo) { Log.d(TAG, "glasses: $info") }
            override fun onGlassWearingStatus(wearing: Boolean) { Log.d(TAG, "wearing=$wearing") }
            override fun onGlassAiAssistStart() { Log.d(TAG, "AI assist start") }
            override fun onGlassAiAssistStop() { Log.d(TAG, "AI assist stop") }
            override fun onGlassAiInterrupt(interrupted: Boolean) { Log.d(TAG, "AI interrupt=$interrupted") }
            override fun onGlassLauncherResume() { Log.d(TAG, "glasses launcher resumed") }
        })
        cxr.setCXRImageCbk(object : IImageStreamCbk {
            override fun onImageReceived(data: ByteArray) = Unit
            override fun onImageError(code: Int, msg: String) = Unit
        })
        cxr.setCXRCustomViewCbk(object : ICustomViewCbk {
            override fun onCustomViewOpened() = Unit
            override fun onCustomViewUpdated() = Unit
            override fun onCustomViewClosed() = Unit
            override fun onCustomViewIconsSent() = Unit
            override fun onCustomViewError(code: Int, msg: String) = Unit
        })
        cxr.setCXRGlassAppCbk(object : IGlassAppCbk {
            override fun onInstallAppResult(ok: Boolean) = Unit
            override fun onUnInstallAppResult(ok: Boolean) = Unit
            override fun onOpenAppResult(ok: Boolean) { Log.d(TAG, "open app on glasses=$ok") }
            override fun onStopAppResult(ok: Boolean) = Unit
            override fun onGlassAppResume(resumed: Boolean) { Log.d(TAG, "glasses app resumed=$resumed") }
            override fun onQueryAppResult(installed: Boolean) { Log.d(TAG, "glasses app installed=$installed") }
        })
        preferGlobalHiRokid()
        restoreGrantedPermissions()
        val ok = cxr.connect(token)
        report(if (ok) LinkState.CONNECTING else LinkState.REFUSED)
    }

    /**
     * CXR-L picks the Chinese Rokid AI app unless a static flag says Hi Rokid (the global app,
     * com.rokid.sprite.global.aiapp) is the one; only requestAuthorization sets it, so in a
     * fresh process connect() binds to an app that isn't there and fails (measured). The flag
     * is AuthorizationHelper's obfuscated `a`, set here when only the global app is installed.
     */
    private fun preferGlobalHiRokid() {
        val installed = { pkg: String -> runCatching { packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false) }
        if (!installed("com.rokid.sprite.global.aiapp") || installed("com.rokid.sprite.aiapp")) return
        runCatching {
            com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper::class.java.getDeclaredField("a").apply {
                isAccessible = true
                setBoolean(null, true)
            }
        }.onFailure { Log.w(TAG, "couldn't select Hi Rokid", it) }
    }

    /**
     * The same static-state trap for permissions: hasGlassPermission (checked by
     * startAudioStream) wants the granted permissions in AuthorizationHelper's `b` AND its `c`
     * flag, which only parseAuthorizationResult sets on a successful result. After a restart
     * both are empty and the SDK refuses the mic ("No GlassPermission MICROPHONE", measured
     * after an update). Restore both for what the user granted with the token.
     */
    private fun restoreGrantedPermissions() {
        val granted = CompanionPrefs.permissions(this)
        if (granted.isEmpty()) return
        runCatching {
            val helper = com.rokid.sprite.aiapp.externalapp.auth.AuthorizationHelper::class.java
            helper.getDeclaredField("b").apply {
                isAccessible = true
                set(null, granted.toTypedArray())
            }
            helper.getDeclaredField("c").apply {
                isAccessible = true
                setBoolean(null, true)
            }
        }.onFailure { Log.w(TAG, "couldn't restore glasses permissions", it) }
    }

    private fun onGlassesMessage(name: String, data: ByteArray?) {
        val json = Protocol.decode(data)
        // The command and the kind of message only: payloads carry notification keys, settings
        // and grid changes (config values among them).
        Log.d(TAG, "glasses: $name ${listOf("type", "op", "action").firstNotNullOfOrNull { json.optString(it).ifEmpty { null } } ?: "-"}")
        when (name) {
            Link.NOTIFY -> {
                if (NotifyCommand.isSync(json)) scheduleSync()
                NotifyCommand.snoozeUntil(json)?.let { until -> main.post { PhoneSnooze.onState(until) } }
                NotifyCommand.dismissedKeys(json)?.let { keys -> NotificationForwarder.dismiss(keys) }
            }
            Link.AUDIO -> audio.onMessage(json, Protocol.binary(data))
            Link.NET -> when (NetCommand.from(json)) {
                NetCommand.UP -> main.post { network.up() }
                NetCommand.DOWN -> main.post { network.down() }
                null -> Unit
            }
            Link.SETTINGS_EVENT -> SettingsEvent.from(json)?.let { event ->
                main.post {
                    BandStore.onEvent(event)
                    PhoneBand.onGlassesStatus(this, BandStore.status)
                    if (event is SettingsEvent.Debug || event is SettingsEvent.Schema) GlassesDebug.onStatus(this, BandStore.debug)
                }
            }
            Link.GRID_EVENT -> GridEvent.from(json)?.let { event ->
                main.post {
                    GridCache.onEvent(event)
                    if (event is GridEvent.State) GridCache.missingIcons().takeIf { it.isNotEmpty() }?.let { sendGrid(GridOps.icons(it)) }
                }
            }
            Link.DICTATION -> when (DictationCommand.from(json)) {
                DictationCommand.HELLO -> {
                    // A screen that just opened has no composer: a dictation still running was
                    // left by one that died without saying stop (the glasses app reinstalled, say).
                    if (streaming || dictation != null || finishing.isNotEmpty()) cancelListening("the glasses opened a new screen")
                    send(DictationEvent.READY, "")
                }
                DictationCommand.START -> startListening()
                DictationCommand.STOP -> stopListening("stop requested")
                null -> Unit
            }
        }
    }

    private fun startListening() {
        val cxr = link ?: return send(DictationEvent.ERROR, getString(R.string.dictation_error_no_link))
        // A web app is recording or transcribing (window.lumen.audio): one microphone, one engine.
        if (audio.busy()) return send(DictationEvent.ERROR, getString(R.string.dictation_error_mic_busy))
        stopListening("restart")
        var session: DictationSession? = null
        val sink = object : DictationSession.Sink {
            override fun partial(text: String) { main.post { if (session != null && (session === dictation || session in finishing)) send(DictationEvent.PARTIAL, text) } }
            override fun phrase(text: String) { main.post { send(DictationEvent.PHRASE, text) } }
            override fun error(error: SttError) { main.post { send(DictationEvent.ERROR, errorText(error)) } }
            override fun stopped() { main.post { session?.let { onDictationStopped(it) } } }
        }
        openSession(sink, onStatus = { send(DictationEvent.STATUS, it) }) { opened, problem ->
            if (opened == null) return@openSession send(DictationEvent.ERROR, problem.orEmpty())
            if (link !== cxr) return@openSession opened.cancel()
            session = opened
            dictation = opened
            opened.start()
            // Hi Rokid's own wake word would otherwise hold the glasses' microphone.
            cxr.setInterruptAiWake(true)
            tryStartAudio(cxr, attempt = 1)
        }
    }

    /**
     * A dictation session on the engine the user chose (its model loaded, its key read), not
     * started; or null and why not. The dictation and web apps' transcriptions both use it.
     */
    private fun openSession(sink: DictationSession.Sink, onStatus: (String) -> Unit = {}, ready: (DictationSession?, String?) -> Unit) {
        val engine = SpeechSettings.engine(this)
        SpeechSettings.missing(this, engine)?.let { return ready(null, missingText(engine.provider, it)) }
        val language = SpeechSettings.language(this)
        val patience = SpeechSettings.patience(this)
        fun build(factory: (SttListener) -> SttSession) = DictationSession(this, engine, language, patience, factory, sink)
        when (engine.provider) {
            SpeechProvider.VOSK -> PhoneModel.load(this, PhoneModel.chosen(this), onStatus = onStatus) { model, problem ->
                if (model == null) ready(null, problem ?: getString(R.string.dictation_error_no_model))
                else ready(build { listener -> VoskStt(model, listener) }, null)
            }
            SpeechProvider.ANDROID -> {
                // The recognizer checks the caller's microphone use: the service says it records.
                useMicrophoneForeground(true)
                ready(build { listener -> AndroidStt(this, patience, listener) }, null)
            }
            else -> {
                val key = SpeechSecrets.key(this, engine.provider)
                    ?: return ready(null, missingText(engine.provider, SpeechSettings.Missing.KEY))
                val region = SpeechSecrets.azureRegion(this)
                ready(build { listener -> CloudStt.create(engine, key, region, language, listener) }, null)
            }
        }
    }

    /** A dictation is over: all its text is out (or it gave up). */
    private fun onDictationStopped(session: DictationSession) {
        finishing -= session
        if (session === dictation) {
            // It stopped by itself (nobody spoke for long, or an engine error): the glasses too.
            dictation = null
            stopAudio()
            send(DictationEvent.STOPPED, "")
        } else if (dictation == null && finishing.isEmpty()) {
            send(DictationEvent.STOPPED, "")
        }
        if (dictation == null && finishing.isEmpty()) {
            useMicrophoneForeground(false)
            report(LinkState.SESSION_READY, "dictation stopped")
        }
    }

    private fun missingText(provider: SpeechProvider, missing: SpeechSettings.Missing): String = when (missing) {
        SpeechSettings.Missing.KEY -> getString(R.string.speech_error_missing_key, provider.displayName)
        SpeechSettings.Missing.REGION -> getString(R.string.speech_error_missing_region)
        SpeechSettings.Missing.MICROPHONE -> getString(R.string.speech_error_missing_microphone)
    }

    private fun errorText(error: SttError): String {
        val name = error.provider.displayName
        return when (error.kind) {
            SttErrorKind.AUTH -> getString(R.string.speech_error_auth, name)
            SttErrorKind.QUOTA_RATE -> getString(R.string.speech_error_quota, name)
            SttErrorKind.NETWORK -> getString(R.string.speech_error_network, name)
            SttErrorKind.TIMEOUT -> getString(R.string.speech_error_timeout, name)
            SttErrorKind.UNSUPPORTED_LANGUAGE -> getString(R.string.speech_error_language, name)
            else -> getString(R.string.speech_error_other, name, error.detail.ifBlank { error.kind.name.lowercase() })
        }
    }

    /**
     * The first start right after the glasses app comes forward can be refused while the scene
     * settles (measured); a few retries 400 ms apart cover it, without blocking the main thread.
     */
    private fun tryStartAudio(cxr: CXRLink, attempt: Int) {
        if (dictation == null || link !== cxr) return
        // A stream left open by an earlier process (killed by an update, say) makes Hi Rokid
        // refuse a new one: close whatever is there first.
        if (attempt == 2) runCatching { cxr.stopAudioStream() }
        streaming = cxr.startAudioStream(Protocol.CODEC_PCM)
        Log.d(TAG, "startAudioStream attempt $attempt = $streaming")
        when {
            streaming -> {
                report(LinkState.DICTATING)
                send(DictationEvent.LISTENING, "")
            }
            attempt < AUDIO_START_ATTEMPTS -> main.postDelayed({ tryStartAudio(cxr, attempt + 1) }, 400)
            else -> {
                cxr.setInterruptAiWake(false)
                val refused = dictation
                dictation = null
                refused?.cancel()
                send(DictationEvent.ERROR, getString(R.string.dictation_error_mic_refused))
            }
        }
    }

    /** Resend the notifications, once, shortly after the link (or the glasses app) asks. */
    private fun scheduleSync() {
        main.removeCallbacks(sync)
        main.postDelayed(sync, 1_500)
    }

    private val sync = Runnable {
        Log.d(TAG, "notification sync (listener ${if (NotificationForwarder.instance != null) "bound" else "not bound"})")
        NotificationForwarder.requestSync()
    }

    /** Debug: how much the Rokid link carries, phone → glasses (the glasses log the arrival side). */
    private fun bench(size: Int, count: Int) {
        Log.d(TAG, "bench $count × $size B (link ${if (link != null) "up" else "down"})")
        val cxr = link ?: return
        Thread {
            val payload = ByteArray(size) { it.toByte() }
            val start = System.nanoTime()
            var failed = 0
            for (seq in 0 until count) {
                val code = runCatching { cxr.sendCustomCmd(Link.BENCH, Protocol.encode(Link.message().put("seq", seq).put("of", count)), payload) }.getOrNull()
                if (code != 0) failed++
                if (seq == 0) Log.d(TAG, "bench first send = $code")
            }
            Log.d(TAG, "bench sent $count × $size B in ${(System.nanoTime() - start) / 1_000_000} ms, failed $failed")
        }.start()
    }

    /**
     * Debug: a file of 16 kHz mono PCM16 in place of the glasses' microphone, at its pace (20 ms
     * chunks). Into the dictation the glasses opened, if one is running (their text shows in the
     * composer); otherwise into the Android recognizer alone, with what it hears in the log.
     */
    private fun dictateFile(file: File) {
        val pcm = runCatching { file.readBytes() }.getOrElse { return Log.w(TAG, "dictate file: ${it.message}").let { } }
        Log.d(TAG, "dictate file ${file.name}: ${pcm.size} bytes, into ${if (dictation != null) "the glasses' dictation" else "the log"}")
        val session = dictation ?: run {
            useMicrophoneForeground(true)
            val patience = SpeechSettings.patience(this)
            DictationSession(this, SpeechSettings.engine(this), SpeechSettings.language(this), patience,
                { listener -> AndroidStt(this, patience, listener) },
                object : DictationSession.Sink {
                    override fun partial(text: String) = Log.d(TAG, "dictate file partial '$text'").let { }
                    override fun phrase(text: String) = Log.d(TAG, "dictate file phrase '$text'").let { }
                    override fun error(error: SttError) = Log.d(TAG, "dictate file error '${errorText(error)}'").let { }
                    override fun stopped() {
                        Log.d(TAG, "dictate file stopped")
                        main.post { if (dictation == null && finishing.isEmpty()) useMicrophoneForeground(false) }
                    }
                }).also { it.start() }
        }
        val own = session !== dictation
        injecting = true
        Thread {
            for (at in pcm.indices step 640) {
                session.onPcm(pcm.copyOfRange(at, minOf(at + 640, pcm.size)))
                Thread.sleep(20)
            }
            injecting = false
            if (own) session.stop()
        }.start()
    }

    /** Debug: a file plays in place of the glasses' microphone ([dictateFile]). */
    @Volatile private var injecting = false

    private fun sendNet(event: NetEvent) {
        val json = event.toJson()
        val cxr = link ?: return
        val result = runCatching { cxr.sendCustomCmd(Link.NET_EVENT, Protocol.encode(json)) }
        Log.d(TAG, "→ glasses net ${json.optString("type")} = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
    }

    private fun sendSettings(json: JSONObject) {
        val cxr = link ?: return
        val result = runCatching { cxr.sendCustomCmd(Link.SETTINGS, Protocol.encode(json)) }
        Log.d(TAG, "→ glasses settings ${json.optString("op")} = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
    }

    private fun sendGrid(json: JSONObject) {
        val cxr = link ?: return
        val result = runCatching { cxr.sendCustomCmd(Link.GRID, Protocol.encode(json)) }
        Log.d(TAG, "→ glasses grid ${json.optString("op")} = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
    }

    private fun sendNotify(json: JSONObject) {
        val cxr = link ?: return
        val result = runCatching { cxr.sendCustomCmd(Link.NOTIFY_EVENT, Protocol.encode(json)) }
        Log.d(TAG, "→ glasses notify ${json.optString("type")} = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
    }

    /** The glasses' microphone off; what was said still reaches them, then STOPPED. */
    private fun stopListening(reason: String) {
        stopAudio()
        val session = dictation ?: return
        Log.d(TAG, "listening stopped: $reason")
        dictation = null
        finishing += session
        session.stop()
    }

    /** Everything dropped at once, the text in flight too. */
    private fun cancelListening(reason: String) {
        stopAudio()
        val sessions = finishing + listOfNotNull(dictation)
        dictation = null
        finishing.clear()
        Log.d(TAG, "listening cancelled: $reason")
        sessions.forEach { it.cancel() }
        if (sessions.isNotEmpty()) {
            send(DictationEvent.STOPPED, "")
            useMicrophoneForeground(false)
            report(LinkState.SESSION_READY, "dictation cancelled")
        }
    }

    private fun stopAudio() {
        val cxr = link
        if (streaming && cxr != null) {
            runCatching { cxr.stopAudioStream() }
            cxr.setInterruptAiWake(false)
        }
        streaming = false
    }

    /** 16 kHz mono PCM16 from the glasses, on the SDK's thread. */
    private fun onAudio(data: ByteArray, offset: Int, length: Int) {
        val recorder = recorderPcm
        val session = dictation
        if (recorder == null && session == null) return
        if (injecting) return
        val start = offset.coerceIn(0, data.size)
        val end = (start + length).coerceIn(start, data.size)
        val pcm = if (start == 0 && end == data.size) data else data.copyOfRange(start, end)
        if (recorder != null) return recorder(pcm)
        session ?: return
        if (++chunks % 250 == 1) Log.d(TAG, "audio chunk $chunks: ${pcm.size} bytes, level ${Protocol.level(pcm)}")
        session.onPcm(pcm)
    }

    private var chunks = 0

    private fun send(type: String, text: String) {
        val cxr = link ?: return
        val result = runCatching { cxr.sendCustomCmd(Link.DICTATION_EVENT, Protocol.encode(DictationEvent(type, text).toJson())) }
        Log.d(TAG, "→ glasses $type '${text.take(40)}' = ${result.getOrNull() ?: result.exceptionOrNull()?.message}")
    }

    private fun report(next: LinkState, detail: String = "") {
        Log.d(TAG, "link $next $detail")
        state = next
        main.post { listeners.forEach { it(next) } }
    }

    /**
     * The Android recognizer checks that the caller records: while it listens the service is
     * also a microphone service. A refusal (the app in the background, say) is only logged: the
     * recognizer then answers with its own error.
     */
    private fun useMicrophoneForeground(on: Boolean) {
        if (on == microphoneForeground) return
        microphoneForeground = on
        runCatching { startInForeground() }.onFailure { Log.w(TAG, "microphone foreground refused", it) }
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.service_channel), NotificationManager.IMPORTANCE_MIN))
        val notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.service_text))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
            (if (microphoneForeground) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0)
        startForeground(1, notification, types)
    }

    companion object {
        private const val TAG = "NbCompanion"
        private const val CHANNEL = "link"
        const val ACTION_RECONNECT = "dev.lumen.companion.RECONNECT"
        const val ACTION_NET_UP = "dev.lumen.companion.NET_UP"
        const val ACTION_NET_DOWN = "dev.lumen.companion.NET_DOWN"
        const val ACTION_BENCH = "dev.lumen.companion.BENCH"
        const val ACTION_DICTATE_FILE = "dev.lumen.companion.DICTATE_FILE"

        private const val AUDIO_START_ATTEMPTS = 4

        @Volatile var state: LinkState = LinkState.STOPPED
            private set

        @Volatile private var instance: CompanionService? = null

        val isRunning get() = instance != null

        /** A notification message for the glasses, from any thread; dropped (false) while the link is down. */
        fun sendNotify(json: JSONObject): Boolean {
            val service = instance ?: return false
            if (service.link == null) return false
            service.main.post { service.sendNotify(json) }
            return true
        }

        /** A settings request for the glasses ([SettingsOps]); false while the link is down. */
        fun requestSettings(json: JSONObject): Boolean {
            val service = instance ?: return false
            if (service.link == null) return false
            service.main.post { service.sendSettings(json) }
            return true
        }

        /** A grid request for the glasses ([GridOps]); false while the link is down. */
        fun requestGrid(json: JSONObject): Boolean {
            val service = instance ?: return false
            if (service.link == null) return false
            service.main.post { service.sendGrid(json) }
            return true
        }

        /** One notification sync shortly, however many ask for it (listener, link, glasses). */
        /** Starts or ends the glasses' banner snooze; false without a link. */
        fun requestSnooze(on: Boolean): Boolean {
            val service = instance ?: return false
            if (service.link == null) return false
            service.main.post { service.sendNotify(NotifyEvent.snooze(on)) }
            return true
        }

        fun scheduleNotificationSync() {
            val service = instance ?: return
            service.main.post { service.scheduleSync() }
        }

        /** Starts the service if it's authorized and not running (the listener and boot call this). */
        fun ensureRunning(context: Context) {
            if (instance != null || CompanionPrefs.token(context) == null) return
            runCatching { start(context) }.onFailure { Log.w(TAG, "couldn't start the link service", it) }
        }
        val listeners = mutableSetOf<(LinkState) -> Unit>()

        fun start(context: Context, reconnect: Boolean = false) {
            val intent = Intent(context, CompanionService::class.java)
            if (reconnect) intent.action = ACTION_RECONNECT
            context.startForegroundService(intent)
        }
    }
}
