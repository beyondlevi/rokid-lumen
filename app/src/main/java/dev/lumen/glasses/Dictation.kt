package dev.lumen.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/**
 * MEASURED ON THE ROKID GLASSES (firmware of 2026-09): a third-party AudioRecord is silenced by
 * the audio policy there (AudioService logs every session as "silenced", even from the top
 * activity, with no call and no accessibility service), and Rokid's CXRService refuses binds
 * (onBind returns null), so its on-glasses audio API is absent. This engine therefore gets
 * zeros on the glasses; the glasses' microphone reaches apps only on the phone, over CXR-L
 * (as Rokid Nexus does). Kept for devices where a local AudioRecord works.
 *
 * Offline speech to text for Lumen's keyboard ([LumenKeyboard]), on Vosk (Kaldi): the glasses have no
 * RecognitionService, and dictation must work without Wi-Fi. The Portuguese small model
 * (~31 MB) is downloaded once into the app's files and loaded once per process.
 */
object Dictation {
    private const val TAG = "BandDictation"
    private const val MODEL_NAME = "vosk-model-small-pt-0.3"
    private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
    private const val SAMPLE_RATE = 16_000f

    interface Listener {
        /** Model download or loading progress, for the keyboard's status line. */
        fun onStatus(status: String)
        fun onPartial(text: String)

        /** One finished phrase (a pause ended it). */
        fun onPhrase(text: String)
        fun onError(message: String)

        /** Listening is over and every phrase is in (after [stop], or the phone stopped by itself). */
        fun onStopped() {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var loading = false
    private var service: SpeechService? = null

    @JvmStatic
    fun isListening() = service != null || PhoneDictation.isActive()

    /** Starts listening once the model is ready; phrases arrive until [stop]. */
    @JvmStatic
    private var appContext: Context? = null

    private fun text(id: Int, vararg args: Any): String = appContext?.getString(id, *args).orEmpty()

    fun start(context: Context, listener: Listener) {
        stop()
        appContext = context.applicationContext
        // On the Rokid glasses a local AudioRecord only gets silence: the phone listens.
        if (PhoneDictation.applies(context)) {
            PhoneDictation.start(listener)
            return
        }
        val ready = model
        if (ready != null) {
            listen(ready, listener)
            return
        }
        if (loading) {
            listener.onStatus(text(R.string.dictation_preparing))
            return
        }
        loading = true
        val app = context.applicationContext
        thread(name = "vosk-model") {
            try {
                val dir = modelDir(app)
                // Newer models keep the acoustic model in am/, older ones (this one) at the top.
                if (!File(dir, "am/final.mdl").isFile && !File(dir, "final.mdl").isFile) {
                    download(app, listener)
                }
                main.post { listener.onStatus(text(R.string.dictation_loading)) }
                val loaded = Model(dir.absolutePath)
                main.post {
                    loading = false
                    model = loaded
                    listen(loaded, listener)
                }
            } catch (e: Exception) {
                Log.w(TAG, "model unavailable", e)
                main.post {
                    loading = false
                    listener.onError(text(R.string.dictation_model_unavailable, e.message.orEmpty()))
                }
            }
        }
    }

    @JvmStatic
    fun stop() {
        PhoneDictation.stop()
        service?.let {
            it.stop()
            it.shutdown()
        }
        service = null
    }

    private fun listen(model: Model, listener: Listener) {
        try {
            val speech = SpeechService(Recognizer(model, SAMPLE_RATE), SAMPLE_RATE)
            service = speech
            speech.startListening(object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    listener.onPartial(field(hypothesis, "partial"))
                }

                override fun onResult(hypothesis: String?) {
                    field(hypothesis, "text").takeIf { it.isNotBlank() }?.let(listener::onPhrase)
                }

                override fun onFinalResult(hypothesis: String?) {
                    field(hypothesis, "text").takeIf { it.isNotBlank() }?.let(listener::onPhrase)
                }

                override fun onError(exception: Exception?) {
                    listener.onError(exception?.message ?: text(R.string.dictation_mic_error))
                }

                override fun onTimeout() {
                    stop()
                }
            })
            listener.onStatus(text(R.string.dictation_listening))
        } catch (e: Exception) {
            Log.w(TAG, "microphone unavailable", e)
            service = null
            listener.onError(text(R.string.dictation_mic_unavailable, e.message.orEmpty()))
        }
    }

    private fun field(json: String?, key: String): String =
        runCatching { JSONObject(json ?: "{}").optString(key) }.getOrDefault("")

    private fun modelDir(context: Context) = File(File(context.filesDir, "vosk"), MODEL_NAME)

    /** Downloads and unpacks the model; entries outside the model directory are refused. */
    private fun download(context: Context, listener: Listener) {
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val tmp = File(root, "$MODEL_NAME.partial").apply { deleteRecursively() }
        val connection = URL(MODEL_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        val total = connection.contentLengthLong
        var read = 0L
        var lastReport = -1L
        connection.inputStream.use { raw ->
            val counting = object : java.io.FilterInputStream(raw) {
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val n = super.read(b, off, len)
                    if (n > 0) {
                        read += n
                        val percent = if (total > 0) read * 100 / total else -1
                        if (percent != lastReport && percent % 5 == 0L) {
                            lastReport = percent
                            main.post { listener.onStatus(text(R.string.dictation_downloading, percent)) }
                        }
                    }
                    return n
                }
            }
            ZipInputStream(counting).use { zip ->
                val base = tmp.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // The zip wraps everything in "<MODEL_NAME>/"; drop that level.
                    val name = entry.name.substringAfter('/', "")
                    if (name.isEmpty()) continue
                    val out = File(tmp, name)
                    if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad entry ${entry.name}")
                    if (entry.isDirectory) {
                        out.mkdirs()
                    } else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
            }
        }
        val dir = modelDir(context)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IllegalStateException("could not move the model into place")
        Log.d(TAG, "model ready in ${dir.absolutePath}")
    }
}
