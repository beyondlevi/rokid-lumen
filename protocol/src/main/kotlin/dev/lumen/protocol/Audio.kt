package dev.lumen.protocol

import org.json.JSONObject
import java.security.MessageDigest

/**
 * Audio for web apps (`window.lumen.audio`): the Rokid glasses silence a third-party
 * microphone, so the phone records the glasses' mic over CXR-L and hands the recording back,
 * and transcribes an audio the page passes with the dictation engine chosen in the companion.
 *
 *   glasses → phone  [Link.AUDIO]        {op: record, id, maxMs} | {op: stop, id} | {op: cancel, id}
 *                                        {op: transcribe, id, size, sha256, chunks, mime, language}
 *                                        {op: chunk, id, seq} + bytes (a file going to the phone)
 *                                        {op: ack, id, seq} (a chunk of the phone's file arrived)
 *   phone → glasses  [Link.AUDIO_EVENT]  {type: started, id} | {type: level, id, level, ms}
 *                                        {type: file, id, size, sha256, chunks, mime, durationMs, reason}
 *                                        {type: chunk, id, seq} + bytes | {type: ack, id, seq}
 *                                        {type: partial, id, text} | {type: transcript, id, text}
 *                                        {type: error, id, code, message}
 *
 * A file crosses Rokid's link (which loses and delays messages) in [Chunks.SIZE] pieces, a few
 * in flight at a time ([ChunkSender]), each acknowledged, missing ones sent again; the receiver
 * ([ChunkReceiver]) checks the size and SHA-256 at the end. Bytes ride in the Caps after the
 * JSON. Error codes are the page's ([AudioError]).
 */
object AudioOps {
    const val RECORD = "record"
    const val STOP = "stop"
    const val CANCEL = "cancel"
    const val TRANSCRIBE = "transcribe"
    const val CHUNK = "chunk"
    const val ACK = "ack"

    const val STARTED = "started"
    const val LEVEL = "level"
    const val FILE = "file"
    const val PARTIAL = "partial"
    const val TRANSCRIPT = "transcript"
    const val ERROR = "error"

    /** The longest recording, and the default. */
    const val MAX_RECORD_MS = 120_000L
    /** The biggest audio a page may hand over for transcription, and the longest. */
    const val MAX_TRANSCRIBE_BYTES = 5 * 1024 * 1024
    const val MAX_TRANSCRIBE_MS = 5 * 60_000L

    /** What a recording is: Ogg Opus, mono, 16 kHz (a voice note). */
    const val RECORDING_MIME = "audio/ogg; codecs=opus"

    @JvmStatic
    fun message(op: String, id: String): JSONObject = Link.message().put("op", op).put("id", id)

    @JvmStatic
    fun event(type: String, id: String): JSONObject = Link.message().put("type", type).put("id", id)

    @JvmStatic
    fun error(id: String, code: AudioError, message: String = ""): JSONObject =
        event(ERROR, id).put("code", code.code).put("message", message)

    /** The header of a file about to cross: what the receiver checks at the end. */
    @JvmStatic
    fun header(json: JSONObject, bytes: ByteArray): JSONObject =
        json.put("size", bytes.size).put("sha256", sha256(bytes)).put("chunks", Chunks.count(bytes.size))

    @JvmStatic
    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}

/** The page's error codes (`error.code`). */
enum class AudioError(val code: String) {
    BUSY("busy"),
    NO_PHONE("no-phone"),
    UNAVAILABLE("unavailable"),
    TOO_LARGE("too-large"),
    UNSUPPORTED_FORMAT("unsupported-format"),
    NO_SPEECH("no-speech"),
    ENGINE("engine"),
    CANCELLED("cancelled"),
    TIMEOUT("timeout");

    companion object {
        @JvmStatic
        fun of(code: String?) = entries.firstOrNull { it.code == code } ?: ENGINE
    }
}

object Chunks {
    /** Bytes per piece: a few binder transactions' worth, well under what the link carries. */
    const val SIZE = 12 * 1024

    @JvmStatic
    fun count(size: Int) = maxOf(1, (size + SIZE - 1) / SIZE)

    @JvmStatic
    fun piece(bytes: ByteArray, seq: Int): ByteArray =
        bytes.copyOfRange(seq * SIZE, minOf(bytes.size, (seq + 1) * SIZE))
}

/**
 * Sends [bytes] in [Chunks]: at most [window] unacknowledged at a time, a piece not
 * acknowledged within [retryMs] goes again, and after [maxTries] sends of one piece it gives
 * up. Not thread-safe: drive it from one thread ([pump] after a start, an ack or a tick).
 */
class ChunkSender(
    private val bytes: ByteArray,
    private val window: Int = 4,
    private val retryMs: Long = 4_000,
    private val maxTries: Int = 6,
) {
    val total = Chunks.count(bytes.size)
    private val acked = BooleanArray(total)
    private val sentAt = LongArray(total) { -1 }
    private val tries = IntArray(total)

    val done: Boolean get() = acked.all { it }

    /** A piece was sent [maxTries] times without an answer. */
    var failed = false
        private set

    fun ack(seq: Int) {
        if (seq in 0 until total) acked[seq] = true
    }

    /** The pieces to send now ([send] each, with its bytes); updates the bookkeeping. */
    fun pump(now: Long, send: (seq: Int, piece: ByteArray) -> Unit) {
        if (failed) return
        var inFlight = (0 until total).count { !acked[it] && sentAt[it] >= 0 && now - sentAt[it] < retryMs }
        for (seq in 0 until total) {
            if (acked[seq]) continue
            val due = sentAt[seq] < 0 || now - sentAt[seq] >= retryMs
            if (!due) continue
            if (inFlight >= window) break
            if (tries[seq] >= maxTries) {
                failed = true
                return
            }
            tries[seq]++
            sentAt[seq] = now
            inFlight++
            send(seq, Chunks.piece(bytes, seq))
        }
    }
}

/** Collects a file's [chunks] (in any order, repeats included) and checks it against the header. */
class ChunkReceiver(val size: Int, val sha256: String, val chunks: Int) {
    private val pieces = arrayOfNulls<ByteArray>(chunks)

    val complete: Boolean get() = pieces.all { it != null }

    /** Stores a piece; false for one out of range or of the wrong length. */
    fun put(seq: Int, piece: ByteArray): Boolean {
        if (seq !in 0 until chunks) return false
        val expected = minOf(Chunks.SIZE, size - seq * Chunks.SIZE)
        if (piece.size != expected) return false
        pieces[seq] = piece
        return true
    }

    /** The whole file once [complete] and matching the header, else null. */
    fun bytes(): ByteArray? {
        if (!complete) return null
        val out = ByteArray(size)
        var at = 0
        pieces.forEach { piece -> piece!!.copyInto(out, at); at += piece.size }
        return out.takeIf { AudioOps.sha256(it) == sha256.lowercase() }
    }
}
