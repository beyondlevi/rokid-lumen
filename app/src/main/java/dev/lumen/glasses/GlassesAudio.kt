package dev.lumen.glasses

import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import dev.lumen.protocol.AudioError
import dev.lumen.protocol.AudioOps
import dev.lumen.protocol.ChunkReceiver
import dev.lumen.protocol.ChunkSender
import dev.lumen.protocol.Link
import org.json.JSONObject
import java.security.SecureRandom

/**
 * The glasses' side of `window.lumen.audio` ([AudioOps]): a page asks for a recording or a
 * transcription, this asks the phone over Rokid's link and hands the page what comes back
 * (the recording as base64, the text as it grows). The link loses messages, so requests are
 * repeated until the phone answers, and files cross in acknowledged chunks. Main thread.
 *
 * Page → host `{op: record, id, maxMs, live} | {op: stop, id} | {op: cancel, id}
 *              | {op: transcribe, id, data (base64), mime, language}`;
 * host → page `{type: started|level|result|pcm|ended|partial|transcript|error, id, …}` ([Page.event]).
 * A live recording (the page's getUserMedia) gets `pcm` events (base64 16 kHz mono PCM16) as
 * the phone sends them, then `ended`.
 */
object GlassesAudio {
    private const val TAG = "BandAudio"
    /** A request the phone hasn't answered goes again this often… */
    private const val REPEAT_MS = 3_000L
    /** …this many times, then the page hears `no-phone`. */
    private const val REPEATS = 5
    private const val PUMP_MS = 1_000L

    /** Where a job's events go: the page that asked (its engine, its origin). */
    fun interface Page {
        fun event(json: JSONObject)
    }

    private enum class Kind { RECORD, TRANSCRIBE }

    private class Job(val kind: Kind, val pageId: String, val linkId: String, val page: Page, val owner: Any, val live: Boolean = false) {
        /** The request to repeat until the phone answers, and how often it went. */
        var ask: JSONObject? = null
        var asked = 0
        var receiver: ChunkReceiver? = null
        var sender: ChunkSender? = null
        var header: JSONObject? = null
        var acks = 0
        var stopped = false
        var durationMs = 0L
        var reason = "stop"
        var mime = AudioOps.RECORDING_MIME
    }

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val random = SecureRandom()
    private val jobs = LinkedHashMap<String, Job>()

    /** A page's request; [owner] is its screen, so closing it ends what it started ([closeAll]). */
    @JvmStatic
    fun request(owner: Any, message: JSONObject, page: Page) {
        val pageId = message.optString("id")
        if (pageId.isEmpty()) return
        val job = jobs.values.firstOrNull { it.owner === owner && it.pageId == pageId }
        when (message.optString("op")) {
            AudioOps.RECORD -> record(owner, pageId, message.optLong("maxMs", AudioOps.MAX_RECORD_MS), message.optBoolean("live"), page)
            AudioOps.STOP -> job?.takeIf { it.kind == Kind.RECORD && !it.stopped }?.let { stop(it) }
            AudioOps.CANCEL -> job?.let { cancel(it) }
            AudioOps.TRANSCRIBE -> transcribe(owner, pageId, message, page)
        }
    }

    /** A recording or transcription is under way (the microphone and the engine are taken). */
    @JvmStatic
    fun busy() = jobs.isNotEmpty()

    /** The screen closed: its recordings and transcriptions end, on the phone too. */
    @JvmStatic
    fun closeAll(owner: Any) {
        jobs.values.filter { it.owner === owner }.forEach { cancel(it, quiet = true) }
    }

    private fun newId() = ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) }

    private fun record(owner: Any, pageId: String, maxMs: Long, live: Boolean, page: Page) {
        if (jobs.values.any { it.owner === owner && it.pageId == pageId }) return
        if (PhoneLink.ensure() == null) return page.event(error(pageId, AudioError.NO_PHONE))
        if (Dictation.isListening() || jobs.isNotEmpty()) return page.event(error(pageId, AudioError.BUSY))
        val job = Job(Kind.RECORD, pageId, newId(), page, owner, live)
        jobs[job.linkId] = job
        Log.d(TAG, "record ${job.linkId} for page id $pageId${if (live) " (live)" else ""}")
        val limit = if (live) AudioOps.MAX_LIVE_MS else AudioOps.MAX_RECORD_MS
        ask(job, AudioOps.message(AudioOps.RECORD, job.linkId).put("maxMs", maxMs.coerceIn(1_000, limit)).put("live", live))
    }

    private fun stop(job: Job) {
        job.stopped = true
        ask(job, AudioOps.message(AudioOps.STOP, job.linkId))
    }

    private fun cancel(job: Job, quiet: Boolean = false) {
        jobs.remove(job.linkId)
        main.removeCallbacksAndMessages(job)
        send(AudioOps.message(AudioOps.CANCEL, job.linkId))
        if (!quiet) job.page.event(error(job.pageId, AudioError.CANCELLED))
    }

    private fun transcribe(owner: Any, pageId: String, message: JSONObject, page: Page) {
        if (jobs.values.any { it.owner === owner && it.pageId == pageId }) return
        val bytes = runCatching { Base64.decode(message.optString("data"), Base64.DEFAULT) }.getOrNull()
            ?: return page.event(error(pageId, AudioError.UNSUPPORTED_FORMAT))
        if (bytes.isEmpty() || bytes.size > AudioOps.MAX_TRANSCRIBE_BYTES) return page.event(error(pageId, AudioError.TOO_LARGE))
        if (PhoneLink.ensure() == null) return page.event(error(pageId, AudioError.NO_PHONE))
        if (Dictation.isListening() || jobs.isNotEmpty()) return page.event(error(pageId, AudioError.BUSY))
        val job = Job(Kind.TRANSCRIBE, pageId, newId(), page, owner)
        jobs[job.linkId] = job
        job.sender = ChunkSender(bytes)
        job.header = AudioOps.header(AudioOps.message(AudioOps.TRANSCRIBE, job.linkId), bytes)
            .put("mime", message.optString("mime")).put("language", message.optString("language"))
        Log.d(TAG, "transcribe ${job.linkId}: ${bytes.size} B ${message.optString("mime")}")
        pump(job)
    }

    /** Sends [request] now and again every [REPEAT_MS] until the phone answers ([answered]). */
    private fun ask(job: Job, request: JSONObject) {
        job.ask = request
        job.asked = 0
        main.removeCallbacksAndMessages(job)
        repeatAsk(job)
    }

    private fun repeatAsk(job: Job) {
        val request = job.ask ?: return
        if (jobs[job.linkId] !== job) return
        if (job.asked >= REPEATS) return fail(job, AudioError.NO_PHONE)
        job.asked++
        send(request)
        main.postAtTime({ repeatAsk(job) }, job, android.os.SystemClock.uptimeMillis() + REPEAT_MS)
    }

    private fun answered(job: Job) {
        job.ask = null
        main.removeCallbacksAndMessages(job)
    }

    /** The outgoing file: due pieces now, the header again until the phone acknowledges a piece. */
    private fun pump(job: Job) {
        if (jobs[job.linkId] !== job) return
        val sender = job.sender ?: return
        if (job.acks == 0) {
            if (job.asked >= REPEATS) return fail(job, AudioError.NO_PHONE)
            job.asked++
            job.header?.let { send(it) }
        }
        sender.pump(System.currentTimeMillis()) { seq, piece -> send(AudioOps.message(AudioOps.CHUNK, job.linkId).put("seq", seq), piece) }
        if (sender.failed) return fail(job, AudioError.TIMEOUT)
        if (!sender.done) main.postAtTime({ pump(job) }, job, android.os.SystemClock.uptimeMillis() + if (job.acks == 0) REPEAT_MS else PUMP_MS)
    }

    /** [Link.AUDIO_EVENT] from the phone; [bytes] is a chunk's payload. */
    @JvmStatic
    fun onPhoneEvent(json: JSONObject, bytes: ByteArray?) {
        val job = jobs[json.optString("id")] ?: return
        when (json.optString("type")) {
            AudioOps.STARTED -> if (job.kind == Kind.RECORD && !job.stopped) {
                answered(job)
                job.page.event(event("started", job.pageId))
            }
            AudioOps.LEVEL -> if (job.kind == Kind.RECORD && !job.stopped) {
                answered(job)
                job.page.event(event("level", job.pageId).put("level", json.optDouble("level")).put("ms", json.optLong("ms")))
            }
            AudioOps.FILE -> if (job.kind == Kind.RECORD && job.receiver == null) {
                answered(job)
                job.receiver = ChunkReceiver(json.optInt("size"), json.optString("sha256"), json.optInt("chunks"))
                job.durationMs = json.optLong("durationMs")
                job.reason = json.optString("reason", "stop")
                job.mime = json.optString("mime", AudioOps.RECORDING_MIME)
                // A stop on its own (the length limit): the page learns it with the file.
                job.stopped = true
            }
            AudioOps.CHUNK -> {
                val receiver = job.receiver ?: return
                val seq = json.optInt("seq", -1)
                if (bytes == null || !receiver.put(seq, bytes)) return
                send(AudioOps.message(AudioOps.ACK, job.linkId).put("seq", seq))
                if (!receiver.complete) return
                jobs.remove(job.linkId)
                main.removeCallbacksAndMessages(job)
                val file = receiver.bytes() ?: return job.page.event(error(job.pageId, AudioError.UNAVAILABLE, "the recording arrived damaged"))
                Log.d(TAG, "record ${job.linkId}: ${file.size} B, ${job.durationMs} ms (${job.reason})")
                job.page.event(
                    event("result", job.pageId).put("data", Base64.encodeToString(file, Base64.NO_WRAP))
                        .put("mime", job.mime).put("durationMs", job.durationMs).put("reason", job.reason),
                )
            }
            AudioOps.ACK -> job.sender?.let { sender ->
                job.acks++
                sender.ack(json.optInt("seq", -1))
                if (sender.done) main.removeCallbacksAndMessages(job) else pump(job)
            }
            AudioOps.PCM -> if (job.live && bytes != null) {
                answered(job)
                job.page.event(event("pcm", job.pageId).put("seq", json.optInt("seq")).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
            }
            AudioOps.ENDED -> {
                jobs.remove(job.linkId)
                main.removeCallbacksAndMessages(job)
                job.page.event(event("ended", job.pageId).put("reason", json.optString("reason")))
            }
            AudioOps.PARTIAL -> job.page.event(event("partial", job.pageId).put("text", json.optString("text")))
            AudioOps.TRANSCRIPT -> {
                jobs.remove(job.linkId)
                main.removeCallbacksAndMessages(job)
                job.page.event(event("transcript", job.pageId).put("text", json.optString("text")))
            }
            AudioOps.ERROR -> {
                jobs.remove(job.linkId)
                main.removeCallbacksAndMessages(job)
                job.page.event(error(job.pageId, AudioError.of(json.optString("code")), json.optString("message")))
            }
        }
    }

    private fun fail(job: Job, code: AudioError) {
        if (jobs.remove(job.linkId) == null) return
        main.removeCallbacksAndMessages(job)
        Log.w(TAG, "audio ${job.linkId}: ${code.code}")
        send(AudioOps.message(AudioOps.CANCEL, job.linkId))
        job.page.event(error(job.pageId, code))
    }

    private fun send(json: JSONObject, bytes: ByteArray? = null) = PhoneLink.send(Link.AUDIO, json, bytes)

    private fun event(type: String, pageId: String) = JSONObject().put("type", type).put("id", pageId)

    private fun error(pageId: String, code: AudioError, message: String = "") =
        event("error", pageId).put("code", code.code).put("message", message)
}
