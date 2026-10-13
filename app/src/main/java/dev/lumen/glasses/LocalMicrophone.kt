package dev.lumen.glasses

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The glasses' own microphone for a page's live audio (`getUserMedia` with deviceId
 * `glasses-direct`): Android's voice-call capture (VOICE_COMMUNICATION, the platform's echo
 * cancellation and noise suppression), 16 kHz mono PCM16 in 20 ms pieces, handed to the page on
 * the glasses themselves. The default microphone goes through the phone (Rokid's link and
 * back): fine for dictation, too slow for a call. Measured 2026-10-13: Gecko's own capture
 * (its WebRTC processing) erased the voice on the glasses, so this one is Lumen's.
 */
class LocalMicrophone(
    private val context: Context,
    private val onStarted: () -> Unit,
    private val onPcm: (seq: Int, bytes: ByteArray) -> Unit,
    private val onFailed: (String) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ run() }, "LumenLocalMic").apply { start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    @SuppressLint("MissingPermission")
    private fun run() {
        if (!granted()) {
            // The self-arm's shell grants it: there's no dialog to answer on the HUD.
            runCatching { SelfArmController.runShell(context, "pm grant ${context.packageName} ${Manifest.permission.RECORD_AUDIO}") }
            if (!granted()) return fail("no microphone permission")
        }
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = runCatching {
            AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, PIECE_BYTES * 4))
        }.getOrNull()
        if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
            record?.release()
            return fail("the microphone didn't open")
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) runCatching { AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        val ns = if (NoiseSuppressor.isAvailable()) runCatching { NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        Log.d(TAG, "local microphone: voice communication, aec=${aec != null} ns=${ns != null}")
        try {
            record.startRecording()
            if (record.recordingState != AudioRecord.RECORDSTATE_RECORDING) return fail("the microphone didn't start")
            main.post { if (running) onStarted() }
            var seq = 0
            while (running) {
                val piece = ByteArray(PIECE_BYTES)
                var read = 0
                while (running && read < piece.size) {
                    val n = record.read(piece, read, piece.size - read)
                    if (n < 0) return fail("the microphone failed ($n)")
                    read += n
                }
                if (!running) break
                val s = seq++
                main.post { if (running) onPcm(s, piece) }
            }
        } finally {
            runCatching { record.stop() }
            record.release()
            aec?.release()
            ns?.release()
            Log.d(TAG, "local microphone stopped")
        }
    }

    private fun granted() = context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String) {
        Log.w(TAG, "local microphone: $message")
        running = false
        main.post { onFailed(message) }
    }

    companion object {
        private const val TAG = "BandAudio"
        const val RATE = 16_000
        /** 20 ms of 16 kHz mono PCM16. */
        private const val PIECE_BYTES = RATE / 50 * 2
    }
}
