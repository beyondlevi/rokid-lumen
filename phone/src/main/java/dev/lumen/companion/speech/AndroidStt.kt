package dev.lumen.companion.speech

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.Executors

/**
 * The phone's own speech recognizer fed with the glasses' audio (Nexus's AndroidSttSession): the
 * PCM goes through a pipe as `EXTRA_AUDIO_SOURCE` (Android 13+). Recognizers are tried in order:
 * the system's default, then every Google recognition service (segmented), then the on-device
 * one; a recognizer that refuses injected audio (ERROR_CLIENT) passes to the next. It speaks
 * the phone's language.
 */
class AndroidStt(
    private val context: Context,
    private val patience: SpeechPatience,
    private val listener: SttListener,
) : SttSession {
    private val main = Handler(Looper.getMainLooper())
    /** Writes to the pipe: they block until the recognizer reads, so never on the main thread. */
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "android-stt-pipe") }
    private var recognizer: SpeechRecognizer? = null
    /** Guarded by [audio]: the pipe of the current recognizer. */
    private var pipe: OutputStream? = null
    /**
     * Our end of the recognizer's read side. [SpeechRecognizer.startListening] only parcels the
     * intent later (on its handler, or once its service connects): closing this before then
     * fails every recognizer with "Bad file descriptor" (ERROR_CLIENT). Closed once it listens.
     */
    private var readSide: ParcelFileDescriptor? = null
    private var targets: List<Target> = emptyList()
    private var targetIndex = 0
    private var done = false
    private var inputClosed = false
    private var best = ""
    private val segments = mutableListOf<String>()
    /** Audio that came before the recognizer was ready, and everything since (for a retry). */
    private val audio = java.io.ByteArrayOutputStream()

    private data class Target(val component: ComponentName?, val onDevice: Boolean, val segmented: Boolean)

    override fun start() = main.post { begin() }.let { }

    private fun begin() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            return fail(SttErrorKind.SOURCE_UNAVAILABLE, "Injected audio recognition requires Android 13 or later")
        }
        if (!SpeechRecognizer.isRecognitionAvailable(context)) return fail(SttErrorKind.SOURCE_UNAVAILABLE, "No speech recognizer on this phone")
        targets = buildTargets()
        startTarget()
    }

    private fun buildTargets(): List<Target> {
        val list = mutableListOf(Target(null, onDevice = false, segmented = false))
        context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName.startsWith("com.google.android") }
            .forEach { list += Target(it, onDevice = false, segmented = true) }
        if (SpeechRecognizer.isOnDeviceRecognitionAvailable(context)) list += Target(null, onDevice = true, segmented = true)
        return list
    }

    private fun startTarget() {
        val target = targets.getOrNull(targetIndex) ?: return fail(
            SttErrorKind.SOURCE_UNAVAILABLE,
            "This phone's speech service doesn't accept the glasses' audio: choose another engine in the companion",
        )
        releaseRecognizer()
        val created = runCatching {
            when {
                target.onDevice -> SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                target.component != null -> SpeechRecognizer.createSpeechRecognizer(context, target.component)
                else -> SpeechRecognizer.createSpeechRecognizer(context)
            }
        }.getOrElse { return next("create: ${it.message}") }
        recognizer = created
        val (read, write) = ParcelFileDescriptor.createPipe()
        readSide = read
        val out = ParcelFileDescriptor.AutoCloseOutputStream(write)
        created.setRecognitionListener(Callbacks())
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // Ints, not Longs: RecognizerIntent reads them with getInt (a Long reads as 0; Nexus).
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 2_500)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, patience.androidSilenceMs)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, patience.androidSilenceMs)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, read)
            if (target.segmented) putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, Pcm.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
        }
        Log.d(TAG, "recognizer ${target.component?.flattenToShortString() ?: if (target.onDevice) "on-device" else "default"}")
        runCatching { created.startListening(intent) }.onFailure { return next("start: ${it.message}") }
        // What came before (or a retry): the whole utterance so far, then the rest as it comes.
        val sofar = synchronized(audio) {
            pipe = out
            audio.toByteArray()
        }
        if (sofar.isNotEmpty()) write(out, sofar)
        if (inputClosed) closeInput()
    }

    /** The recognizer has the intent (and our read side): that copy is no longer needed. */
    private fun listening() {
        runCatching { readSide?.close() }
        readSide = null
    }

    private fun next(why: String) {
        Log.d(TAG, "recognizer ${targetIndex + 1}/${targets.size} gave up: $why")
        targetIndex++
        startTarget()
    }

    override fun acceptPcm(pcm: ByteArray) {
        val out = synchronized(audio) {
            audio.write(pcm)
            pipe
        }
        if (out != null) write(out, pcm)
    }

    /** In order, on [writer]; a pipe closed meanwhile (a retry, the end) drops what's left. */
    private fun write(out: OutputStream, pcm: ByteArray) {
        runCatching {
            writer.execute {
                if (out === broken) return@execute
                try {
                    out.write(pcm)
                } catch (e: IOException) {
                    // The recognizer ended by itself (it heard the end of speech): once is enough.
                    broken = out
                    Log.d(TAG, "pipe closed: ${e.message}")
                }
            }
        }
    }

    /** On [writer]: the pipe that failed, not written to again. */
    private var broken: OutputStream? = null

    override fun finishAudio() = main.post {
        inputClosed = true
        closeInput()
        // The recognizer may keep the last words to itself: take the best we have after a while.
        main.postDelayed({ if (!done) finish(best) }, FINAL_RESULT_TIMEOUT_MS)
    }.let { }

    private fun closeInput() {
        val out = synchronized(audio) { pipe.also { pipe = null } } ?: return
        val stopping = recognizer
        val segmented = targets.getOrNull(targetIndex)?.segmented == true
        // After the audio queued before it.
        runCatching {
            writer.execute {
                runCatching { out.close() }
                // A recognizer outside a segmented session doesn't end on a closed input by itself.
                if (!segmented) main.post { if (recognizer === stopping) runCatching { stopping?.stopListening() } }
            }
        }
    }

    override fun cancel() = main.post {
        done = true
        releaseRecognizer()
        writer.shutdown()
    }.let { }

    private fun releaseRecognizer() {
        val out = synchronized(audio) { pipe.also { pipe = null } }
        // Closed at once (not after the queue): a writer stuck on a full pipe gets EPIPE.
        runCatching { out?.close() }
        listening()
        recognizer?.let { runCatching { it.cancel() }; runCatching { it.destroy() } }
        recognizer = null
    }

    private fun transcript(): String = (segments + best).filter { it.isNotBlank() }.joinToString(" ")

    private fun finish(text: String) {
        if (done) return
        done = true
        releaseRecognizer()
        writer.shutdown()
        if (text.isBlank()) listener.onError(SttError(SttErrorKind.NO_SPEECH, SpeechProvider.ANDROID)) else listener.onFinal(text.trim())
    }

    private fun fail(kind: SttErrorKind, detail: String) {
        if (done) return
        done = true
        releaseRecognizer()
        writer.shutdown()
        listener.onError(SttError(kind, SpeechProvider.ANDROID, detail))
    }

    private inner class Callbacks : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listening()
        override fun onBeginningOfSpeech() = listening()
        override fun onRmsChanged(rmsdB: Float) = Unit
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            val text = first(partialResults) ?: return
            if (text.isBlank()) return
            best = text
            if (!done) listener.onPartial(transcript())
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            first(segmentResults)?.takeIf { it.isNotBlank() }?.let { segments += it }
            best = ""
            if (!done) listener.onPartial(transcript())
        }

        override fun onEndOfSegmentedSession() = finish(transcript())

        override fun onResults(results: Bundle?) {
            first(results)?.takeIf { it.isNotBlank() }?.let { best = it }
            finish(transcript())
        }

        override fun onError(error: Int) {
            if (done) return
            val text = transcript()
            when {
                // What was heard so far is the answer to a late error (Nexus).
                text.isNotBlank() && (inputClosed || error in LATE_OK) -> finish(text)
                error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
                    error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE || error == SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ||
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> next("error $error")
                error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish("")
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                    fail(SttErrorKind.SOURCE_UNAVAILABLE, "Allow the microphone for the companion (Settings, Dictation)")
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> fail(SttErrorKind.NETWORK, "error $error")
                else -> fail(SttErrorKind.PROVIDER, "error $error")
            }
        }

        private fun first(bundle: Bundle?): String? =
            bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
    }

    companion object {
        private const val TAG = "NbAndroidStt"
        private const val FINAL_RESULT_TIMEOUT_MS = 2_500L
        private val LATE_OK = setOf(
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT, SpeechRecognizer.ERROR_SERVER,
            SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
            SpeechRecognizer.ERROR_CLIENT,
        )
    }
}
