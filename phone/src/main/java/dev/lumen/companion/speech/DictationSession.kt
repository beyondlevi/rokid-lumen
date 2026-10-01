package dev.lumen.companion.speech

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Continuous dictation over one engine: the glasses' audio is held until someone speaks (with a
 * short lead-in, so the first syllable isn't cut), each utterance goes to a fresh engine session,
 * and a pause of the chosen patience ends it; listening goes on for the next one. Engines hear
 * only speech (the cloud ones cost nothing while nobody talks). Finished utterances reach the
 * [Sink] in the order they were spoken, even when a slow buffered one finishes after a later one.
 *
 * Thread-safe: [onPcm] comes from the link's audio thread, the rest from anywhere; everything
 * runs on one worker thread.
 */
class DictationSession(
    private val context: Context,
    private val engine: SpeechEngine,
    private val language: SpeechLanguage,
    private val patience: SpeechPatience,
    private val factory: (SttListener) -> SttSession,
    private val sink: Sink,
) {
    interface Sink {
        fun partial(text: String)
        fun phrase(text: String)
        fun error(error: SttError)

        /** Everything spoken has been delivered (after [stop]), or the session gave up. */
        fun stopped()
    }

    private inner class Utterance(val index: Int) : SttListener {
        val session: SttSession = factory(this)
        var result: String? = null
        var finishing = false
        var startedAt = 0L
        var lastVoiceAt = 0L

        override fun onPartial(text: String) = post { if (this === current) sink.partial(text) }
        override fun onFinal(text: String) = post { settle(this, text) }
        override fun onError(error: SttError) = post {
            if (error.kind == SttErrorKind.NO_SPEECH) settle(this, "") else abort(error)
        }
    }

    private val worker = Executors.newSingleThreadScheduledExecutor { Thread(it, "dictation") }
    private var tick: ScheduledFuture<*>? = null
    private var current: Utterance? = null
    private val pending = ArrayDeque<Utterance>()
    private var count = 0
    private val leadIn = ByteArrayOutputStream()
    private var lastSpeechAt = now()
    private var stopping = false
    private var over = false

    private fun now() = System.currentTimeMillis()

    /** On the worker; dropped once the session is over (late engine callbacks, say). */
    private fun post(block: () -> Unit) {
        runCatching { worker.execute(block) }
    }

    fun start() = post {
        Log.d(TAG, "start ${engine.id} language=${language.id} patience=${patience.id}")
        tick = runCatching { worker.scheduleWithFixedDelay({ check() }, TICK_MS, TICK_MS, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    fun onPcm(pcm: ByteArray) {
        val copy = pcm.copyOf()
        post { feed(copy) }
    }

    private fun feed(pcm: ByteArray) {
        if (over || stopping) return
        val voice = Voice.isVoice(pcm)
        val open = current
        if (open != null) {
            open.session.acceptPcm(pcm)
            if (voice) open.lastVoiceAt = now()
            return
        }
        if (!voice) {
            // Keep the last moments before speech: the lead-in of the next utterance.
            leadIn.write(pcm)
            if (leadIn.size() > LEAD_IN_BYTES) {
                val all = leadIn.toByteArray()
                leadIn.reset()
                leadIn.write(all, all.size - LEAD_IN_BYTES, LEAD_IN_BYTES)
            }
            return
        }
        val utterance = Utterance(++count)
        utterance.startedAt = now()
        utterance.lastVoiceAt = now()
        lastSpeechAt = now()
        current = utterance
        pending.addLast(utterance)
        utterance.session.start()
        leadIn.toByteArray().takeIf { it.isNotEmpty() }?.let { utterance.session.acceptPcm(it) }
        leadIn.reset()
        utterance.session.acceptPcm(pcm)
        Log.d(TAG, "utterance ${utterance.index} started")
    }

    private fun check() {
        if (over) return
        val open = current
        val time = now()
        if (open != null) {
            val quiet = time - open.lastVoiceAt
            if ((time - open.startedAt >= MIN_UTTERANCE_MS && quiet >= patience.silenceMs) || time - open.startedAt >= MAX_UTTERANCE_MS) {
                close(open)
                lastSpeechAt = time
            }
        } else if (!stopping && time - lastSpeechAt >= IDLE_STOP_MS) {
            // Nobody has spoken for a long while: give the microphone back.
            Log.d(TAG, "idle for ${IDLE_STOP_MS / 1000} s")
            stop()
        }
    }

    private fun close(utterance: Utterance) {
        if (current === utterance) current = null
        if (utterance.finishing) return
        utterance.finishing = true
        Log.d(TAG, "utterance ${utterance.index} ends")
        utterance.session.finishAudio()
    }

    private fun settle(utterance: Utterance, text: String) {
        if (over || utterance.result != null) return
        utterance.result = text
        if (current === utterance) current = null
        // In order: a later utterance waits for the earlier ones.
        while (pending.firstOrNull()?.result != null) {
            val done = pending.removeFirst()
            done.result?.takeIf { it.isNotBlank() }?.let { sink.phrase(it) }
        }
        if (stopping && pending.isEmpty()) finish()
    }

    /** Stops listening: what was being said is transcribed and delivered, then [Sink.stopped]. */
    fun stop() = post {
        if (over || stopping) return@post
        stopping = true
        current?.let { close(it) }
        if (pending.isEmpty()) finish()
        else runCatching { worker.schedule({ if (!over) finish() }, CloudStt.FINAL_TIMEOUT_MS + 2_000, TimeUnit.MILLISECONDS) }
    }

    /** Drops everything at once (the glasses opened another screen). */
    fun cancel() = post {
        if (over) return@post
        pending.forEach { it.session.cancel() }
        pending.clear()
        current = null
        finish()
    }

    private fun abort(error: SttError) {
        if (over) return
        Log.w(TAG, "engine error ${error.kind}: ${error.detail}")
        pending.forEach { it.session.cancel() }
        pending.clear()
        current = null
        sink.error(error)
        finish()
    }

    private fun finish() {
        if (over) return
        over = true
        tick?.cancel(false)
        pending.forEach { it.session.cancel() }
        pending.clear()
        sink.stopped()
        worker.shutdown()
    }

    companion object {
        private const val TAG = "NbDictation"
        private const val TICK_MS = 120L
        /** 600 ms before the first voice chunk. */
        private const val LEAD_IN_BYTES = Pcm.SAMPLE_RATE * 2 * 6 / 10
        private const val MIN_UTTERANCE_MS = 1_000L
        private const val MAX_UTTERANCE_MS = 30_000L
        private const val IDLE_STOP_MS = 90_000L
    }
}
