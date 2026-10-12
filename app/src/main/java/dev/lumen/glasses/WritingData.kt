package dev.lumen.glasses

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/**
 * The writing suggestions' files on the glasses: the lexicon (an asset, `writing/writing.lex`,
 * copied once per version into `files/writing/` so the band library can map it) and the wearer's
 * store, what the suggestions learned (written by the band library, never logged).
 */
object WritingData {
    private const val TAG = "BandWriting"
    private const val DIR = "writing"
    private const val ASSET = "writing/writing.lex"
    private const val MANIFEST = "writing/writing.json"
    private const val STORE = "personal.bin"

    /** One thread copies; the band's threads never wait on it. */
    private val io = Executors.newSingleThreadExecutor()
    @Volatile
    private var lexicon: File? = null

    private fun dir(context: Context) = File(context.filesDir, DIR)

    /** What the suggestions learned. */
    @JvmStatic
    fun store(context: Context): File = File(dir(context), STORE)

    /** The lexicon's copy when it's ready (see [prepare]), else null. */
    @JvmStatic
    fun lexicon(): File? = lexicon?.takeIf { it.exists() }

    /** Copies the lexicon if this version isn't there yet, off the main thread; [then] runs on that thread after. */
    @JvmStatic
    fun prepare(context: Context, then: Runnable?) {
        val app = context.applicationContext
        io.execute {
            runCatching { copy(app) }.onFailure { Log.w(TAG, "couldn't prepare the lexicon", it) }
            then?.run()
        }
    }

    private fun copy(context: Context) {
        lexicon?.takeIf { it.exists() }?.let { return }
        val version = context.assets.open(MANIFEST).use { JSONObject(it.reader().readText()).getString("sha256").take(16) }
        val dir = dir(context).apply { mkdirs() }
        val file = File(dir, "writing-$version.lex")
        if (!file.exists()) {
            val started = System.currentTimeMillis()
            val temporary = File(dir, "writing-$version.tmp")
            context.assets.open(ASSET).use { input -> temporary.outputStream().use { input.copyTo(it) } }
            if (!temporary.renameTo(file)) error("rename failed")
            dir.listFiles { other -> other.name.startsWith("writing-") && other != file }?.forEach { it.delete() }
            Log.d(TAG, "lexicon ready (${file.length()} bytes, ${System.currentTimeMillis() - started} ms)")
        }
        lexicon = file
    }

    /** Forgets what the suggestions learned (the companion's "Forget what writing learned"). */
    @JvmStatic
    fun forget(context: Context) {
        val gone = store(context).delete()
        Log.d(TAG, "what writing learned ${if (gone) "forgotten" else "was already empty"}")
    }

    /** Today, in days since 1970 (the store fades what isn't used). */
    @JvmStatic
    fun today(): Int = (System.currentTimeMillis() / 86_400_000L).toInt()
}
