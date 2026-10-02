package dev.lumen.companion.audio

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.companion.Protocol
import dev.lumen.companion.speech.DictationSession
import dev.lumen.companion.speech.SttError
import dev.lumen.protocol.AudioError
import dev.lumen.protocol.AudioOps
import dev.lumen.protocol.ChunkReceiver
import dev.lumen.protocol.ChunkSender
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.Executors

/**
 * The phone's side of `window.lumen.audio` ([AudioOps]): records the glasses' microphone (the
 * CXR-L audio stream the dictation uses) into an Ogg Opus voice note and sends it back in
 * chunks; receives an audio file in chunks, decodes it and transcribes it at its own pace with
 * the dictation engine chosen in the companion, sending the text as it comes. One recording
 * or transcription at a time, never during a dictation (the microphone and the engine are one).
 * Main thread, except the PCM from the link and the codec work.
 */
class PhoneAudio(private val context: Context, private val host: Host) {
    interface Host {
        /** A message to the glasses ([dev.lumen.protocol.Link.AUDIO_EVENT]), with bytes after the JSON. */
        fun sendAudio(json: JSONObject, bytes: ByteArray? = null): Boolean

        /** Whether the glasses' dictation holds the microphone or the engine. */
        fun dictating(): Boolean

        /** Starts the glasses' microphone for [onPcm]; [done] true once it streams, false if refused. */
        fun startMicrophone(onPcm: (ByteArray) -> Unit, done: (Boolean) -> Unit)

        fun stopMicrophone()

        /** A dictation session on the engine the user chose, or null with why not (main thread). */
        fun openSession(sink: DictationSession.Sink, ready: (DictationSession?, String?) -> Unit)

        fun errorText(error: SttError): String
    }

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-audio").apply { isDaemon = true } }

    private inner class Recording(val id: String, val maxMs: Long) {
        val pcm = ByteArrayOutputStream()
        var started = false
        var stopped = false
        var lastLevelAt = 0L
        var peak = 0
    }

    private inner class Outgoing(val id: String, val sender: ChunkSender)

    private inner class Transcription(val id: String, val receiver: ChunkReceiver, val mime: String) {
        var session: DictationSession? = null
        /** The whole file is in and on its way to the engine (repeats of a piece change nothing). */
        var started = false
        var cancelled = false
        val text = StringBuilder()
    }

    private var recording: Recording? = null
    private var outgoing: Outgoing? = null
    private var transcription: Transcription? = null

    fun busy() = recording != null || transcription != null

    /** A message on [dev.lumen.protocol.Link.AUDIO]; [bytes] is a chunk's payload. */
    fun onMessage(json: JSONObject, bytes: ByteArray?) {
        val id = json.optString("id")
        if (id.isEmpty()) return
        when (json.optString("op")) {
            AudioOps.RECORD -> record(id, json.optLong("maxMs", AudioOps.MAX_RECORD_MS))
            AudioOps.STOP -> stop(id, reason = "stop")
            AudioOps.CANCEL -> cancel(id)
            AudioOps.TRANSCRIBE -> beginTranscription(id, json)
            AudioOps.CHUNK -> chunk(id, json.optInt("seq", -1), bytes)
            AudioOps.ACK -> outgoing?.takeIf { it.id == id }?.let { it.sender.ack(json.optInt("seq", -1)); pump() }
        }
    }

    // ---- Recording ----

    private fun record(id: String, maxMs: Long) {
        if (recording?.id == id) return host.sendAudio(AudioOps.event(AudioOps.STARTED, id)).let { }
        if (busy() || host.dictating()) return error(id, AudioError.BUSY)
        val rec = Recording(id, maxMs.coerceIn(1_000, AudioOps.MAX_RECORD_MS))
        recording = rec
        Log.d(TAG, "record $id up to ${rec.maxMs} ms")
        host.startMicrophone(onPcm = { pcm -> onPcm(rec, pcm) }) { ok ->
            main.post {
                if (recording !== rec) return@post
                if (!ok) {
                    recording = null
                    return@post error(id, AudioError.UNAVAILABLE)
                }
                rec.started = true
                host.sendAudio(AudioOps.event(AudioOps.STARTED, id))
            }
        }
    }

    /** The link's audio thread. */
    private fun onPcm(rec: Recording, pcm: ByteArray) {
        synchronized(rec) {
            if (rec.stopped) return
            rec.pcm.write(pcm)
            rec.peak = maxOf(rec.peak, Protocol.level(pcm))
            val ms = rec.pcm.size() / 2L * 1000 / AudioCodec.RATE
            val now = System.currentTimeMillis()
            if (now - rec.lastLevelAt >= LEVEL_MS) {
                rec.lastLevelAt = now
                val level = (rec.peak / LEVEL_FULL_SCALE).coerceIn(0.0, 1.0)
                rec.peak = 0
                main.post { if (recording === rec) host.sendAudio(AudioOps.event(AudioOps.LEVEL, rec.id).put("level", level).put("ms", ms)) }
            }
            if (ms >= rec.maxMs) main.post { stop(rec.id, reason = "max") }
        }
    }

    private fun stop(id: String, reason: String) {
        val rec = recording?.takeIf { it.id == id } ?: return
        val pcm = synchronized(rec) {
            if (rec.stopped) return
            rec.stopped = true
            rec.pcm.toByteArray()
        }
        recording = null
        host.stopMicrophone()
        val durationMs = pcm.size / 2L * 1000 / AudioCodec.RATE
        Log.d(TAG, "record $id stopped ($reason): $durationMs ms")
        if (pcm.isEmpty()) return error(id, AudioError.UNAVAILABLE, "no audio from the glasses")
        work.execute {
            val file = runCatching { AudioCodec.encodeOggOpus(pcm) }
            main.post {
                file.onSuccess { bytes ->
                    Log.d(TAG, "record $id: ${bytes.size} B of Ogg Opus")
                    val header = AudioOps.header(AudioOps.event(AudioOps.FILE, id), bytes)
                        .put("mime", AudioOps.RECORDING_MIME).put("durationMs", durationMs).put("reason", reason)
                    host.sendAudio(header)
                    outgoing = Outgoing(id, ChunkSender(bytes))
                    pump()
                }.onFailure {
                    Log.w(TAG, "encoding failed", it)
                    error(id, AudioError.UNAVAILABLE, "encoding failed: ${it.message}")
                }
            }
        }
    }

    private fun cancel(id: String) {
        recording?.takeIf { it.id == id }?.let { rec ->
            synchronized(rec) { rec.stopped = true }
            recording = null
            host.stopMicrophone()
            Log.d(TAG, "record $id cancelled")
        }
        if (outgoing?.id == id) outgoing = null
        transcription?.takeIf { it.id == id }?.let { t ->
            t.cancelled = true
            t.session?.cancel()
            transcription = null
            Log.d(TAG, "transcription $id cancelled")
        }
    }

    /** Sends what's due of the outgoing file, and checks again later (lost pieces, lost acks). */
    private fun pump() {
        val out = outgoing ?: return
        out.sender.pump(System.currentTimeMillis()) { seq, piece ->
            host.sendAudio(AudioOps.event(AudioOps.CHUNK, out.id).put("seq", seq), piece)
        }
        when {
            out.sender.done -> outgoing = null
            out.sender.failed -> {
                outgoing = null
                error(out.id, AudioError.TIMEOUT, "the glasses stopped answering")
            }
            else -> {
                main.removeCallbacks(pumpLater)
                main.postDelayed(pumpLater, PUMP_MS)
            }
        }
    }

    private val pumpLater = Runnable { pump() }

    // ---- Transcription ----

    private fun beginTranscription(id: String, json: JSONObject) {
        if (transcription?.id == id) return
        val size = json.optInt("size", -1)
        if (size !in 1..AudioOps.MAX_TRANSCRIBE_BYTES) return error(id, AudioError.TOO_LARGE)
        if (busy() || host.dictating()) return error(id, AudioError.BUSY)
        transcription = Transcription(id, ChunkReceiver(size, json.optString("sha256"), json.optInt("chunks")), json.optString("mime"))
        Log.d(TAG, "transcribe $id: $size B ${json.optString("mime")}")
    }

    private fun chunk(id: String, seq: Int, bytes: ByteArray?) {
        val t = transcription?.takeIf { it.id == id } ?: return
        if (bytes == null || !t.receiver.put(seq, bytes)) return
        host.sendAudio(AudioOps.event(AudioOps.ACK, id).put("seq", seq))
        if (!t.receiver.complete || t.started) return
        val file = t.receiver.bytes() ?: run {
            transcription = null
            return error(id, AudioError.UNSUPPORTED_FORMAT, "the file arrived damaged")
        }
        t.started = true
        work.execute {
            val scratch = File(context.cacheDir, "transcribe-$id")
            val pcm = runCatching {
                scratch.writeBytes(file)
                AudioCodec.decodeToPcm16k(scratch, AudioOps.MAX_TRANSCRIBE_MS)
            }.also { scratch.delete() }
            main.post {
                if (transcription !== t || t.cancelled) return@post
                pcm.onSuccess { transcribe(t, it) }.onFailure {
                    transcription = null
                    Log.w(TAG, "decoding $id failed: ${it.message}")
                    when (it) {
                        is AudioCodec.TooLong -> error(id, AudioError.TOO_LARGE)
                        else -> error(id, AudioError.UNSUPPORTED_FORMAT, it.message.orEmpty())
                    }
                }
            }
        }
    }

    private fun transcribe(t: Transcription, pcm: ByteArray) {
        Log.d(TAG, "transcribe ${t.id}: ${pcm.size / 32} ms of speech to the engine")
        var failed: String? = null
        host.openSession(object : DictationSession.Sink {
            override fun partial(text: String) = main.post {
                if (transcription === t) host.sendAudio(AudioOps.event(AudioOps.PARTIAL, t.id).put("text", join(t.text, text)))
            }.let { }

            override fun phrase(text: String) = main.post {
                if (t.text.isNotEmpty()) t.text.append(' ')
                t.text.append(text.trim())
                if (transcription === t) host.sendAudio(AudioOps.event(AudioOps.PARTIAL, t.id).put("text", t.text.toString()))
            }.let { }

            override fun error(error: SttError) = main.post { failed = host.errorText(error) }.let { }

            override fun stopped() = main.post {
                if (transcription !== t) return@post
                transcription = null
                when {
                    t.cancelled -> Unit
                    failed != null -> error(t.id, AudioError.ENGINE, failed!!)
                    t.text.isBlank() -> error(t.id, AudioError.NO_SPEECH)
                    else -> host.sendAudio(AudioOps.event(AudioOps.TRANSCRIPT, t.id).put("text", t.text.toString()))
                }
            }.let { }
        }) { session, problem ->
            if (transcription !== t) {
                session?.cancel()
                return@openSession
            }
            if (session == null) {
                transcription = null
                return@openSession error(t.id, AudioError.ENGINE, problem.orEmpty())
            }
            t.session = session
            session.start()
            // At the audio's own pace: the session's pauses and limits are in real time.
            Thread({
                for (at in pcm.indices step FRAME_BYTES) {
                    if (t.cancelled) return@Thread
                    session.onPcm(pcm.copyOfRange(at, minOf(at + FRAME_BYTES, pcm.size)))
                    Thread.sleep(FRAME_MS)
                }
                // A little silence closes the last utterance before the stop.
                repeat(TAIL_FRAMES) { session.onPcm(ByteArray(FRAME_BYTES)); Thread.sleep(FRAME_MS) }
                session.stop()
            }, "nb-transcribe").apply { isDaemon = true }.start()
        }
    }

    private fun join(done: StringBuilder, partial: String) =
        if (done.isEmpty()) partial else "$done ${partial.trim()}"

    private fun error(id: String, code: AudioError, message: String = "") {
        Log.d(TAG, "audio $id error ${code.code} $message")
        host.sendAudio(AudioOps.error(id, code, message))
    }

    /** The link went away: whatever was going on is over (the glasses ask again). */
    fun reset() {
        recording?.let { cancel(it.id) }
        transcription?.let { cancel(it.id) }
        outgoing = null
    }

    companion object {
        private const val TAG = "NbAudio"
        private const val LEVEL_MS = 200L
        /** A loud voice at the glasses' microphone (PCM16 RMS). */
        private const val LEVEL_FULL_SCALE = 6_000.0
        private const val PUMP_MS = 1_000L
        private const val FRAME_BYTES = 640
        private const val FRAME_MS = 20L
        private const val TAIL_FRAMES = 50
    }
}
