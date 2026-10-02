package dev.lumen.glasses

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.AudioOps
import dev.lumen.protocol.ChunkSender
import dev.lumen.protocol.Chunks
import dev.lumen.protocol.Link
import dev.lumen.protocol.LogsOps
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.zip.GZIPOutputStream

/**
 * The glasses' side of the companion's "Share logs": on request it gathers this app's log
 * (`logcat` of its own process: an app reads only its own lines), the band's recent log, the
 * state of things, and the self-arm helpers' logs when the self-arm's shell can read them;
 * gzips the text and sends it in acknowledged chunks ([LogsOps]). Main thread, but collecting
 * runs in the background.
 */
object GlassesLogs {
    private const val TAG = "BandLogs"
    private const val LOGCAT_LINES = 20_000
    private const val HELPER_BYTES = 200_000

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var id: String? = null
    private var sender: ChunkSender? = null
    private val tick = object : Runnable {
        override fun run() {
            pump()
            if (sender?.let { !it.done && !it.failed } == true) main.postDelayed(this, 1_000)
        }
    }

    /** [Link.LOGS] from the phone. */
    @JvmStatic
    fun onPhoneMessage(context: Context, json: JSONObject) {
        val request = json.optString("id")
        when (json.optString("op")) {
            LogsOps.REQUEST -> {
                Log.d(TAG, "logs asked by the phone")
                main.removeCallbacks(tick)
                id = request
                sender = null
                val app = context.applicationContext
                worker.execute {
                    val bytes = runCatching { gzip(collect(app)) }.getOrElse {
                        Log.w(TAG, "collecting logs failed", it)
                        main.post { PhoneLink.send(Link.LOGS_EVENT, LogsOps.event(LogsOps.ERROR, request).put("message", it.message.orEmpty())) }
                        return@execute
                    }
                    main.post { start(request, bytes) }
                }
            }
            LogsOps.ACK -> if (request == id) {
                sender?.ack(json.optInt("seq", -1))
                pump()
            }
        }
    }

    private fun start(request: String, bytes: ByteArray) {
        if (request != id) return
        sender = ChunkSender(bytes)
        PhoneLink.send(Link.LOGS_EVENT, AudioOps.header(LogsOps.event(LogsOps.FILE, request), bytes))
        Log.d(TAG, "logs: ${bytes.size} bytes in ${Chunks.count(bytes.size)} chunks")
        main.post(tick)
    }

    private fun pump() {
        val request = id ?: return
        val out = sender ?: return
        out.pump(System.currentTimeMillis()) { seq, piece -> PhoneLink.send(Link.LOGS_EVENT, LogsOps.event(LogsOps.CHUNK, request).put("seq", seq), piece) }
        if (out.done) Log.d(TAG, "logs delivered")
        if (out.failed) Log.w(TAG, "logs: the phone stopped answering")
    }

    /** Everything that goes in: newest last, cut from the top to fit [LogsOps.MAX_BYTES]. */
    private fun collect(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        val text = StringBuilder()
        text.append("=== Rokid Lumen (glasses) ===\n")
        text.append("time: ").append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z", Locale.ROOT).format(Date())).append('\n')
        text.append("app: ").append(version?.versionName).append(" (").append(version?.longVersionCode).append(")\n")
        text.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.RELEASE)
            .append(" (").append(Build.DISPLAY).append(")\n")
        text.append("accessibility service: ").append(if (BandAccessibilityService.isServiceActive()) "active" else "not running").append('\n')
        text.append("band: ").append(BandRuntime.phase).append(", battery ").append(BandRuntime.battery()).append('\n')
        text.append("self-arm: ").append(LocalSelfArmStatus.summary(context)).append('\n')
        text.append("web apps: ").append(WebAppLibrary.all(context).joinToString { "${it.name} ${it.version}".trim() }).append('\n')
        text.append("\n=== Band log (recent) ===\n")
        BandRuntime.recentLog().forEach { text.append(it).append('\n') }
        text.append("\n=== Self-arm helpers ===\n")
        text.append(
            runCatching {
                SelfArmController.runShell(context, "for f in /data/local/tmp/lumen-*.log; do echo \"--- \$f\"; tail -c $HELPER_BYTES \"\$f\"; done")
            }.getOrElse { "(unavailable: ${it.message})" },
        ).append('\n')
        text.append("\n=== logcat (this app) ===\n")
        val process = ProcessBuilder("logcat", "-d", "-v", "threadtime", "-t", LOGCAT_LINES.toString()).redirectErrorStream(true).start()
        text.append(process.inputStream.bufferedReader().use { it.readText() })
        process.waitFor()
        return text.toString()
    }

    /** The gzip of [text], cutting its oldest lines until it fits. */
    private fun gzip(text: String): ByteArray {
        var body = text
        while (true) {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(body.toByteArray(Charsets.UTF_8)) }
            if (out.size() <= LogsOps.MAX_BYTES || body.length < 1024) return out.toByteArray()
            body = "(cut)\n" + body.substring(body.length / 4)
        }
    }
}
