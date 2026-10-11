package dev.lumen.glasses

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.SystemClock
import android.util.Log
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Debug builds only: records what the band sends while it writes, for measuring a decoder
 * offline. Each writing session in Lumen's keyboard becomes `files/writing-probe/<time>.jsonl`:
 * a `session` line with the field's label (a test page puts the phrase's id there), then every
 * handwriting event the band link gives, each sample's likeliest classes (`frame`) and the
 * gestures that came meanwhile (`gesture`) included, each with the host's clock. On and off with
 * `am broadcast -a dev.lumen.glasses.SIMULATE --es writing_probe on|off`.
 */
object WritingProbe {
    private const val TAG = "WritingProbe"
    private const val PREFS = "lumen_writing_probe"
    private const val KEY_ON = "on"
    private const val DIR = "writing-probe"

    /** One thread writes the files, in the order the events came. */
    private val io = Executors.newSingleThreadExecutor()
    /** Only touched on [io]. */
    private var out: BufferedWriter? = null
    private var recording = false

    private fun debuggable(context: Context) = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    @JvmStatic
    fun enabled(context: Context): Boolean =
        debuggable(context) && context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    @JvmStatic
    fun set(context: Context, on: Boolean) {
        if (!debuggable(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
        BandRuntime.setHandwritingProbe(on)
        Log.d(TAG, "probe ${if (on) "on" else "off"}")
    }

    private val listener = BandRuntime.HandwritingListener { event ->
        val line = "{\"host\":${SystemClock.elapsedRealtimeNanos() / 1e9},\"e\":$event}"
        io.execute {
            out?.apply {
                write(line)
                newLine()
            }
        }
    }

    /** Lumen's keyboard starts writing into a field labelled [label]. */
    @JvmStatic
    fun begin(context: Context, label: String) {
        if (!enabled(context)) return
        end()
        recording = true
        BandRuntime.addHandwritingListener(listener)
        val dir = File(context.filesDir, DIR)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date())
        val header = org.json.JSONObject()
            .put("type", "session")
            .put("label", label)
            .put("wall", System.currentTimeMillis())
            .put("host", SystemClock.elapsedRealtimeNanos() / 1e9)
            .toString()
        io.execute {
            runCatching {
                dir.mkdirs()
                out = File(dir, "$stamp.jsonl").bufferedWriter().apply {
                    write(header)
                    newLine()
                }
            }.onFailure { Log.w(TAG, "can't record", it) }
        }
        Log.d(TAG, "recording $stamp")
    }

    /** The writing ended: the file is closed. */
    @JvmStatic
    fun end() {
        if (!recording) return
        recording = false
        BandRuntime.removeHandwritingListener(listener)
        io.execute {
            runCatching { out?.close() }
            out = null
        }
        Log.d(TAG, "recording ends")
    }
}
