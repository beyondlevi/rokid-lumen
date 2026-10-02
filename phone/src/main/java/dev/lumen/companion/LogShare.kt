package dev.lumen.companion

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import dev.lumen.protocol.AudioOps
import dev.lumen.protocol.ChunkReceiver
import dev.lumen.protocol.Link
import dev.lumen.protocol.LogsOps
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPInputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * "Share logs": both apps' logs without adb. Asks the glasses for theirs ([LogsOps], over
 * Rokid's link, in acknowledged chunks), adds this app's (`logcat` of its own process) and a
 * summary, and zips them as `rokid-lumen-logs-<date>-<time>.zip` for Android's share sheet.
 * Without the glasses (no link, or no answer in [GLASSES_TIMEOUT_MS]) the zip says why. Main
 * thread; [listeners] hear [state] change.
 */
object LogShare {
    private const val TAG = "NbLogs"
    private const val GLASSES_TIMEOUT_MS = 90_000L
    private const val LOGCAT_LINES = 20_000

    sealed class State {
        object Idle : State()
        /** Waiting for the glasses: [done] of [total] chunks so far (0 of 0 before their header). */
        data class Collecting(val done: Int, val total: Int) : State()
        data class Ready(val file: File, val withGlasses: Boolean) : State()
        object Failed : State()
    }

    var state: State = State.Idle
        private set
    val listeners = LinkedHashSet<() -> Unit>()

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var id: String? = null
    private var receiver: ChunkReceiver? = null
    private var appContext: Context? = null
    private val timeout = Runnable { finish(null, "the glasses didn't answer in ${GLASSES_TIMEOUT_MS / 1000} s") }

    @JvmStatic
    fun start(context: Context) {
        if (state is State.Collecting) return
        appContext = context.applicationContext
        val request = System.currentTimeMillis().toString()
        id = request
        receiver = null
        set(State.Collecting(0, 0))
        if (!CompanionService.requestLogs(LogsOps.message(LogsOps.REQUEST, request))) {
            finish(null, "no link to the glasses")
            return
        }
        main.postDelayed(timeout, GLASSES_TIMEOUT_MS)
    }

    /** [Link.LOGS_EVENT] from the glasses. */
    @JvmStatic
    fun onGlassesEvent(json: JSONObject, bytes: ByteArray?) {
        if (json.optString("id") != id || state !is State.Collecting) return
        when (json.optString("type")) {
            LogsOps.FILE -> {
                receiver = ChunkReceiver(json.optInt("size"), json.optString("sha256"), json.optInt("chunks"))
                set(State.Collecting(0, json.optInt("chunks")))
            }
            LogsOps.CHUNK -> {
                val seq = json.optInt("seq", -1)
                val into = receiver ?: return
                if (bytes != null && into.put(seq, bytes)) {
                    CompanionService.requestLogs(LogsOps.message(LogsOps.ACK, id!!).put("seq", seq))
                }
                set(State.Collecting((0 until into.chunks).count { into.has(it) }, into.chunks))
                if (into.complete) {
                    val file = into.bytes()
                    if (file == null) finish(null, "the glasses' file didn't match its checksum")
                    else finish(runCatching { GZIPInputStream(file.inputStream()).bufferedReader().use { it.readText() } }.getOrNull(), "unreadable")
                }
            }
            LogsOps.ERROR -> finish(null, "the glasses couldn't collect them: ${json.optString("message")}")
        }
    }

    /** Builds the zip with what came ([glasses], or [why] not). */
    private fun finish(glasses: String?, why: String) {
        main.removeCallbacks(timeout)
        val context = appContext ?: return
        id = null
        receiver = null
        worker.execute {
            val result = runCatching { zip(context, glasses, why) }
            main.post {
                result.onSuccess { set(State.Ready(it, glasses != null)) }.onFailure {
                    Log.w(TAG, "logs zip failed", it)
                    set(State.Failed)
                }
            }
        }
    }

    private fun zip(context: Context, glasses: String?, why: String): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        val dir = File(context.cacheDir, "logs").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "rokid-lumen-logs-$stamp.zip")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("info.txt", info(context))
            put("companion.log", companionLog())
            put(if (glasses != null) "glasses.log" else "glasses-unavailable.txt", glasses ?: "The glasses' logs aren't included: $why.\n")
        }
        Log.d(TAG, "logs ready: ${file.name} (${file.length()} bytes, glasses ${glasses != null})")
        return file
    }

    private fun info(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            append("Rokid Lumen logs\n")
            append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
            append("companion: ").append(version?.versionName).append(" (").append(version?.longVersionCode).append(")\n")
            append("glasses app: ").append(BandStore.schema?.appVersion?.ifEmpty { null } ?: "unknown").append('\n')
            append("phone: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE).append('\n')
            append("link: ").append(CompanionService.state).append('\n')
            append("band (glasses): ").append(BandStore.status.phase).append(", battery ").append(BandStore.status.battery).append('\n')
        }
    }

    private fun companionLog(): String = runCatching {
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString()).redirectErrorStream(true).start()
        process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
    }.getOrElse { "(logcat unavailable: ${it.message})\n" }

    /** Android's share sheet for the ready zip. */
    @JvmStatic
    fun share(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val send = Intent(Intent.ACTION_SEND)
            .setType("application/zip")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_SUBJECT, file.name)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        context.startActivity(Intent.createChooser(send, context.getString(R.string.logs_share_title)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    @JvmStatic
    fun dismiss() {
        if (state !is State.Collecting) set(State.Idle)
    }

    private fun set(next: State) {
        state = next
        listeners.toList().forEach { it() }
    }
}
