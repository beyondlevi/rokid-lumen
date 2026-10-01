package dev.lumen.companion.speech

import dev.lumen.companion.Protocol
import org.vosk.Model
import org.vosk.Recognizer

/** The offline engine (Vosk, the small model of the dictation language), one utterance at a time. */
class VoskStt(model: Model, private val listener: SttListener) : SttSession {
    private val lock = Any()
    private var recognizer: Recognizer? = Recognizer(model, Pcm.SAMPLE_RATE.toFloat())
    /** Vosk ends phrases on its own pauses too: they add up to the utterance. */
    private val phrases = mutableListOf<String>()
    private var lastPartial = ""

    override fun start() = Unit

    override fun acceptPcm(pcm: ByteArray) {
        val partial = synchronized(lock) {
            val r = recognizer ?: return
            if (r.acceptWaveForm(pcm, pcm.size)) {
                Protocol.field(r.result, "text").takeIf { it.isNotBlank() }?.let { phrases += it }
                ""
            } else {
                Protocol.field(r.partialResult, "partial")
            }.let { current -> (phrases + current).filter { it.isNotBlank() }.joinToString(" ") }
        }
        if (partial != lastPartial) {
            lastPartial = partial
            listener.onPartial(partial)
        }
    }

    override fun finishAudio() {
        val text = synchronized(lock) {
            val r = recognizer ?: return
            recognizer = null
            Protocol.field(r.finalResult, "text").takeIf { it.isNotBlank() }?.let { phrases += it }
            r.close()
            phrases.joinToString(" ")
        }
        if (text.isBlank()) listener.onError(SttError(SttErrorKind.NO_SPEECH, SpeechProvider.VOSK)) else listener.onFinal(text)
    }

    override fun cancel() {
        synchronized(lock) {
            recognizer?.close()
            recognizer = null
        }
    }
}
