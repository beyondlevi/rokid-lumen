package dev.lumen.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.companion.speech.SpeechLanguage
import dev.lumen.companion.speech.SpeechSettings
import org.vosk.Model
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

/**
 * Vosk's small offline models. The SHA-256 of each zip was measured on the files alphacephei.com
 * served on 2026-09-30 (the site publishes no checksums); a download that doesn't match is
 * thrown away.
 */
enum class VoskModel(val dirName: String, val sha256: String, val language: SpeechLanguage) {
    EN("vosk-model-small-en-us-0.15", "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498", SpeechLanguage.EN),
    PT("vosk-model-small-pt-0.3", "6e1ce909032e1afa7a88e68a3d628ecafff302bdf195befab308826c395e93b7", SpeechLanguage.PT),
    ;

    val url: String get() = "https://alphacephei.com/vosk/models/$dirName.zip"

    companion object {
        /** The languages Vosk offers in the dictation settings: automatic, then one per model. */
        val languages: List<SpeechLanguage> = listOf(SpeechLanguage.AUTO) + entries.map { it.language }

        /**
         * The model for the dictation [language]: automatic follows the phone's language
         * ([phoneLanguage], an ISO 639 code), and English covers the languages without a model.
         */
        fun forLanguage(language: SpeechLanguage, phoneLanguage: String = Locale.getDefault().language): VoskModel {
            val wanted = if (language == SpeechLanguage.AUTO) entries.firstOrNull { it.language.id == phoneLanguage }?.language else language
            return entries.firstOrNull { it.language == wanted } ?: EN
        }
    }
}

/** The chosen Vosk model, downloaded once into the app's files and loaded when dictation needs it. */
object PhoneModel {
    private const val TAG = "NbModel"

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { Thread(it, "vosk-model") }
    // Main thread only.
    private var loaded: Pair<VoskModel, Model>? = null
    private val waiting = mutableMapOf<VoskModel, MutableList<(Model?, String?) -> Unit>>()

    /** The model the dictation language picks ([VoskModel.forLanguage]). */
    fun chosen(context: Context): VoskModel = VoskModel.forLanguage(SpeechSettings.language(context))

    fun isDownloaded(context: Context, model: VoskModel = chosen(context)) =
        dir(context, model).let { File(it, "final.mdl").isFile || File(it, "am/final.mdl").isFile }

    /** Calls [onReady] on the main thread with [model] loaded, or null and why. */
    fun load(context: Context, model: VoskModel, onStatus: (String) -> Unit, onReady: (Model?, String?) -> Unit) {
        loaded?.takeIf { it.first == model }?.let { return onReady(it.second, null) }
        val queue = waiting.getOrPut(model) { mutableListOf() }
        queue += onReady
        if (queue.size > 1) return
        val app = context.applicationContext
        worker.execute {
            val result = runCatching {
                if (!isDownloaded(app, model)) download(app, model) { percent -> main.post { onStatus(app.getString(R.string.model_downloading, percent)) } }
                main.post { onStatus(app.getString(R.string.model_loading)) }
                Model(dir(app, model).absolutePath)
            }
            main.post {
                // The previous model isn't closed: a dictation may still be using it (closing it
                // under a recognizer crashes in native code). Switching languages is rare.
                result.getOrNull()?.let { loaded = model to it }
                val problem = result.exceptionOrNull()?.let { app.getString(R.string.model_error, it.message.orEmpty()) }
                if (problem != null) Log.w(TAG, problem, result.exceptionOrNull())
                waiting.remove(model).orEmpty().forEach { it(result.getOrNull(), problem) }
            }
        }
    }

    private fun dir(context: Context, model: VoskModel) = File(File(context.filesDir, "vosk"), model.dirName)

    private fun download(context: Context, model: VoskModel, progress: (Long) -> Unit) {
        val root = File(context.filesDir, "vosk").apply { mkdirs() }
        val tmp = File(root, "${model.dirName}.partial").apply { deleteRecursively() }
        val connection = URL(model.url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val total = connection.contentLengthLong
            var read = 0L
            var last = -1L
            connection.inputStream.use { raw ->
                val counting = object : FilterInputStream(raw) {
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        val n = super.read(b, off, len)
                        if (n > 0 && total > 0) {
                            read += n
                            val percent = read * 100 / total
                            if (percent != last && percent % 5 == 0L) {
                                last = percent
                                progress(percent)
                            }
                        }
                        return n
                    }
                }
                ModelZip.unpack(counting, tmp, model.sha256)
            }
        } finally {
            connection.disconnect()
        }
        val dir = dir(context, model)
        dir.deleteRecursively()
        if (!tmp.renameTo(dir)) throw IllegalStateException("could not move the model into place")
    }
}

/** A model zip's extraction, checked against its pinned SHA-256 (no Android dependency, for tests). */
internal object ModelZip {
    /**
     * Extracts a model zip into [into] (the entries' top folder dropped) while hashing every byte
     * read; when the SHA-256 isn't [sha256], [into] is deleted and this throws.
     */
    internal fun unpack(input: InputStream, into: File, sha256: String) {
        val digest = MessageDigest.getInstance("SHA-256")
        val hashing = DigestInputStream(input, digest)
        try {
            ZipInputStream(hashing).use { zip ->
                val base = into.canonicalPath + File.separator
                while (true) {
                    val entry = zip.nextEntry ?: break
                    val name = entry.name.substringAfter('/', "")
                    if (name.isEmpty()) continue
                    val out = File(into, name)
                    if (!out.canonicalPath.startsWith(base)) throw SecurityException("bad entry ${entry.name}")
                    if (entry.isDirectory) out.mkdirs() else {
                        out.parentFile?.mkdirs()
                        out.outputStream().use { zip.copyTo(it) }
                    }
                }
                // The zip reader stops at the last entry: the central directory still counts.
                val buffer = ByteArray(16 * 1024)
                while (hashing.read(buffer) >= 0) Unit
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sha256) throw SecurityException("the voice model's checksum doesn't match")
        } catch (e: Throwable) {
            into.deleteRecursively()
            throw e
        }
    }
}
