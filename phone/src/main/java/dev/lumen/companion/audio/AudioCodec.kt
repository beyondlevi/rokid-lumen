package dev.lumen.companion.audio

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.nio.ByteOrder

/**
 * Audio files for web apps: the glasses' microphone (16 kHz mono PCM16) encoded as an Ogg Opus
 * voice note, and any audio a page hands over decoded back to 16 kHz mono PCM16 for the speech
 * engines. Both with the platform's codecs (MediaCodec, MediaMuxer, MediaExtractor).
 */
object AudioCodec {
    const val RATE = 16_000
    private const val OPUS_BITRATE = 24_000
    private const val TIMEOUT_US = 10_000L
    /** Libopus's usual encoder delay at 48 kHz, if the encoder doesn't say. */
    private const val DEFAULT_PRE_SKIP = 312

    class UnsupportedFormat(message: String) : IOException(message)
    class TooLong : IOException("audio too long")

    /**
     * [pcm] (16 kHz mono PCM16) as an Ogg Opus file: the platform's Opus encoder, and our own
     * Ogg pages ([OggOpusWriter]). MediaMuxer's Ogg gives the first audio page a granule
     * position of 0 though it holds 20 ms, a negative start that RFC 7845 calls invalid; Firefox
     * (GeckoView) then refuses to play it (measured), while Android's decoder doesn't mind.
     */
    @JvmStatic
    fun encodeOggOpus(pcm: ByteArray): ByteArray {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, RATE, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, OPUS_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16 * 1024)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
        var writer: OggOpusWriter? = null
        var preSkip = DEFAULT_PRE_SKIP
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var fed = 0
            var inputDone = false
            var outputDone = false
            while (!outputDone) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (index >= 0) {
                        val buffer = codec.getInputBuffer(index)!!
                        val size = minOf(buffer.remaining(), pcm.size - fed) and 1.inv()
                        val timeUs = fed / 2 * 1_000_000L / RATE
                        if (size <= 0) {
                            codec.queueInputBuffer(index, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.put(pcm, fed, size)
                            codec.queueInputBuffer(index, 0, size, timeUs, 0)
                            fed += size
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // csd-0 is the encoder's OpusHead; its pre-skip is what to tell players.
                        codec.outputFormat.getByteBuffer("csd-0")?.let { head ->
                            val bytes = ByteArray(head.remaining()).also { head.duplicate().get(it) }
                            if (bytes.size >= 12 && String(bytes, 0, 8, Charsets.US_ASCII) == "OpusHead") {
                                preSkip = (bytes[10].toInt() and 0xff) or ((bytes[11].toInt() and 0xff) shl 8)
                            }
                        }
                    }
                    out >= 0 -> {
                        val buffer = codec.getOutputBuffer(out)!!
                        if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val packet = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(packet)
                            (writer ?: OggOpusWriter(preSkip, RATE).also { writer = it }).packet(packet)
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return (writer ?: throw IOException("the encoder gave no audio")).finish()
    }

    /**
     * The first audio track of [file] (Ogg Opus/Vorbis, MP3, AAC/M4A, WAV, FLAC…) as 16 kHz mono
     * PCM16; [UnsupportedFormat] when the platform can't read it, [TooLong] past [maxMs].
     */
    @JvmStatic
    fun decodeToPcm16k(file: File, maxMs: Long): ByteArray {
        val extractor = MediaExtractor()
        try {
            runCatching { extractor.setDataSource(file.path) }.onFailure { throw UnsupportedFormat(it.message ?: "unreadable") }
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw UnsupportedFormat("no audio track")
            val format = extractor.getTrackFormat(trackIndex)
            if (format.containsKey(MediaFormat.KEY_DURATION) && format.getLong(MediaFormat.KEY_DURATION) / 1000 > maxMs) throw TooLong()
            extractor.selectTrack(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)!!
            val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrElse { throw UnsupportedFormat(mime) }
            val out = ByteArrayOutputStream()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val resampler = Resampler()
            try {
                codec.configure(format, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    when {
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                            channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        }
                        index >= 0 -> {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index)!!.order(ByteOrder.LITTLE_ENDIAN)
                                buffer.position(info.offset)
                                val samples = ShortArray(info.size / 2)
                                buffer.asShortBuffer().get(samples)
                                out.write(resampler.feed(mono(samples, channels), rate))
                                if (out.size() / 2L * 1000 / RATE > maxMs) throw TooLong()
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return out.toByteArray()
        } finally {
            extractor.release()
        }
    }

    /** The channels of interleaved [samples] averaged into one. */
    @JvmStatic
    fun mono(samples: ShortArray, channels: Int): ShortArray {
        if (channels <= 1) return samples
        return ShortArray(samples.size / channels) { frame ->
            var sum = 0
            for (c in 0 until channels) sum += samples[frame * channels + c]
            (sum / channels).toShort()
        }
    }

    /**
     * Any rate to 16 kHz, across calls: each output sample is the average of the source samples
     * it spans (a box filter: enough for speech engines, and it keeps 48 kHz Opus from aliasing).
     */
    class Resampler {
        private var position = 0.0
        private var last: Short = 0

        fun feed(input: ShortArray, rate: Int): ByteArray {
            if (input.isEmpty()) return ByteArray(0)
            if (rate == RATE) return toBytes(input)
            val step = rate.toDouble() / RATE
            val out = ArrayList<Short>((input.size / step).toInt() + 2)
            while (position < input.size) {
                val start = position.toInt()
                val end = minOf(input.size, (position + step).toInt().coerceAtLeast(start + 1))
                var sum = 0L
                for (i in start until end) sum += if (i < 0) last.toLong() else input[i].toLong()
                out += (sum / (end - start)).toShort()
                position += step
            }
            position -= input.size
            last = input.last()
            return toBytes(out.toShortArray())
        }

        private fun toBytes(samples: ShortArray): ByteArray {
            val bytes = ByteArray(samples.size * 2)
            samples.forEachIndexed { i, s ->
                bytes[2 * i] = (s.toInt() and 0xff).toByte()
                bytes[2 * i + 1] = (s.toInt() shr 8 and 0xff).toByte()
            }
            return bytes
        }
    }
}
