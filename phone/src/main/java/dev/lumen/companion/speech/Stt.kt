package dev.lumen.companion.speech

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * One utterance's recognizer (Nexus's SttSession): 16 kHz mono PCM16 in, the text so far
 * ([SttListener.onPartial], the whole hypothesis, not a delta) and then one [SttListener.onFinal]
 * or [SttListener.onError], which ends it. Calls may come from any thread.
 */
interface SttSession {
    fun start()
    fun acceptPcm(pcm: ByteArray)

    /** No more audio: the final text follows. */
    fun finishAudio()
    fun cancel()
}

interface SttListener {
    fun onPartial(text: String)
    fun onFinal(text: String)
    fun onError(error: SttError)
}

enum class SttErrorKind { SOURCE_UNAVAILABLE, NO_SPEECH, AUTH, QUOTA_RATE, NETWORK, TIMEOUT, UNSUPPORTED_LANGUAGE, PROVIDER, INTERNAL }

data class SttError(val kind: SttErrorKind, val provider: SpeechProvider, val detail: String = "")

/** A provider's failure, sorted by its HTTP status and words (Nexus's providerError). */
fun providerError(provider: SpeechProvider, status: Int?, message: String?, cause: Throwable? = null): SttError {
    val text = (message ?: cause?.message).orEmpty()
    val lower = text.lowercase()
    val kind = when {
        status == 401 || status == 403 || "auth" in lower || "api key" in lower || "unauthorized" in lower -> SttErrorKind.AUTH
        status == 408 || status == 504 || "timeout" in lower || "timed out" in lower -> SttErrorKind.TIMEOUT
        status == 429 || "quota" in lower || "rate" in lower || "throttl" in lower || "resource_exhausted" in lower -> SttErrorKind.QUOTA_RATE
        "language" in lower && "unsupported" in lower -> SttErrorKind.UNSUPPORTED_LANGUAGE
        "no speech" in lower || "no match" in lower || "insufficient_audio" in lower -> SttErrorKind.NO_SPEECH
        cause is IOException -> SttErrorKind.NETWORK
        else -> SttErrorKind.PROVIDER
    }
    return SttError(kind, provider, text.take(200))
}

object Pcm {
    const val SAMPLE_RATE = 16_000

    /** 100 ms of 16 kHz PCM16: what the cloud engines are sent at a time. */
    const val CHUNK_BYTES = 3_200

    /** A 44-byte RIFF header in front of mono PCM16. */
    fun wav(pcm: ByteArray, rate: Int = SAMPLE_RATE): ByteArray {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + pcm.size); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(pcm.size)
        }
        return ByteArrayOutputStream(44 + pcm.size).apply { write(header.array()); write(pcm) }.toByteArray()
    }

    /** 16 kHz to 24 kHz by linear interpolation (OpenAI Realtime takes 24 kHz). */
    fun upsample16kTo24k(pcm: ByteArray): ByteArray {
        val inSamples = pcm.size / 2
        if (inSamples == 0) return ByteArray(0)
        val input = ShortArray(inSamples) { i -> ((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort() }
        val outSamples = inSamples * 3 / 2
        val out = ByteBuffer.allocate(outSamples * 2).order(ByteOrder.LITTLE_ENDIAN)
        for (i in 0 until outSamples) {
            val position = i * 2.0 / 3.0
            val index = position.toInt()
            val next = (index + 1).coerceAtMost(inSamples - 1)
            val fraction = position - index
            out.putShort((input[index] + (input[next] - input[index]) * fraction).toInt().toShort())
        }
        return out.array()
    }

    /** Mean and peak absolute sample of a chunk. */
    fun levels(pcm: ByteArray): Pair<Int, Int> {
        var sum = 0L
        var peak = 0
        val samples = pcm.size / 2
        for (i in 0 until samples) {
            val value = kotlin.math.abs(((pcm[i * 2].toInt() and 0xff) or (pcm[i * 2 + 1].toInt() shl 8)).toShort().toInt())
            sum += value
            if (value > peak) peak = value
        }
        return (if (samples == 0) 0 else (sum / samples).toInt()) to peak
    }
}

/**
 * Speech or not, per chunk (Nexus's VoiceActivityDetector thresholds): a chunk is voice when its
 * mean absolute sample reaches 350 or its peak 2 800.
 */
object Voice {
    const val AVERAGE_THRESHOLD = 350
    const val PEAK_THRESHOLD = 2_800

    fun isVoice(pcm: ByteArray): Boolean {
        val (average, peak) = Pcm.levels(pcm)
        return average >= AVERAGE_THRESHOLD || peak >= PEAK_THRESHOLD
    }
}
