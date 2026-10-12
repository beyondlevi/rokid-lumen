package dev.lumen.companion

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Log
import android.view.Surface
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.stream.AdbStream

/**
 * The glasses' screen on this phone ("Glasses screen"): over adb ([GlassesAdb]), the glasses'
 * `screenrecord` writes H.264 to the stream (no consent prompt: it runs as the shell user, as
 * for the demo videos), and a hardware decoder draws it on [Surface]. Android 12's screenrecord
 * stops after 3 minutes, so the stream starts again; it only sends frames when the screen
 * changes, so a still screen costs next to nothing. A second connection reads whether the
 * glasses' screen is asleep, wakes it and takes stills.
 */
class GlassesMirror(private val context: Context, private val onState: (State) -> Unit) {
    enum class Phase { CONNECTING, LIVE, PAUSED, ASLEEP, ERROR }

    /** [error]: why there's no picture, when [phase] is [Phase.ERROR]. */
    data class State(val phase: Phase = Phase.CONNECTING, val error: GlassesAdb.Reason? = null)

    /** Light: a low bitrate and at most [LIGHT_FPS] frames drawn a second. Smooth: every frame. */
    enum class Quality(val bitRate: Int, val minFrameMs: Long) { LIGHT(300_000, 1000L / LIGHT_FPS), SMOOTH(2_000_000, 0L) }

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false
    @Volatile private var paused = false
    @Volatile private var asleep = false
    @Volatile private var quality = Quality.LIGHT
    @Volatile private var stream: AdbStream? = null
    @Volatile private var control: Kadb? = null
    private var worker: Thread? = null
    private var watcher: Thread? = null
    @Volatile private var state = State()

    fun start(surface: Surface, quality: Quality) {
        if (running) return
        running = true
        this.quality = quality
        worker = Thread({ run(surface) }, "nb-mirror").apply { isDaemon = true; start() }
    }

    fun stop() {
        running = false
        closeStream()
        runCatching { control?.close() }
        control = null
        worker?.interrupt()
        watcher?.interrupt()
    }

    fun setPaused(on: Boolean) {
        paused = on
        if (on) {
            closeStream()
            report(State(Phase.PAUSED))
        }
    }

    fun setQuality(next: Quality) {
        if (next == quality) return
        quality = next
        // The new bitrate takes a new recording.
        closeStream()
    }

    /** Wakes the glasses' screen (the wake key, as the power button). */
    fun wake() = background { control?.shell("input keyevent 224") }

    /** Saves a still of the glasses' screen in Pictures/Lumen; [done] with whether it worked. */
    fun saveImage(done: (Boolean) -> Unit) = background {
        val ok = runCatching {
            val png = control?.execCmd("screencap", "-p")?.use { it.source.readByteArray() } ?: return@runCatching false
            if (png.size < 8) return@runCatching false
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "Lumen glasses ${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lumen")
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: return@runCatching false
            context.contentResolver.openOutputStream(uri)?.use { it.write(png) } != null
        }.getOrDefault(false)
        main.post { done(ok) }
    }

    private fun run(surface: Surface) {
        report(State(Phase.CONNECTING))
        val adb = try {
            // The control connection first: it pairs when needed, with a timeout (an untrusted
            // key waits forever for a prompt). The stream's has none, since a still screen sends nothing.
            control = GlassesAdb.connect(context)
            GlassesAdb.connect(context, socketTimeoutMs = 0)
        } catch (e: GlassesAdb.Unreachable) {
            Log.w(TAG, "no adb to the glasses: ${e.message}")
            report(State(Phase.ERROR, e.reason))
            running = false
            return
        } catch (e: Exception) {
            Log.w(TAG, "no adb to the glasses", e)
            report(State(Phase.ERROR, GlassesAdb.Reason.NOT_REACHABLE))
            running = false
            return
        }
        watcher = Thread({ watchScreen() }, "nb-mirror-awake").apply { isDaemon = true; start() }
        val decoder = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        try {
            decoder.configure(MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT), surface, null, 0)
            decoder.start()
            while (running) {
                if (paused) {
                    Thread.sleep(200)
                    continue
                }
                record(adb, decoder)
            }
        } catch (_: InterruptedException) {
            // Stopped.
        } catch (e: Exception) {
            Log.w(TAG, "mirror stopped", e)
            if (running) report(State(Phase.ERROR, GlassesAdb.Reason.NOT_REACHABLE))
        } finally {
            runCatching { decoder.stop() }
            runCatching { decoder.release() }
            runCatching { adb.close() }
            running = false
        }
    }

    /** One screenrecord run (up to its 3 minutes), decoded as it arrives. */
    private fun record(adb: Kadb, decoder: MediaCodec) {
        val current = quality
        val recording = adb.execCmd("screenrecord", "--output-format=h264", "--bit-rate", current.bitRate.toString(), "--time-limit", "180", "-")
        stream = recording
        val splitter = AnnexB()
        val buffer = ByteArray(64 * 1024)
        val info = MediaCodec.BufferInfo()
        var lastDrawn = 0L
        var pts = 0L
        try {
            val source = recording.source
            while (running && !paused && quality == current) {
                val n = source.read(buffer)
                if (n < 0) break
                for (unit in splitter.feed(buffer, n)) {
                    val type = AnnexB.type(unit)
                    val flags = if (type == AnnexB.SPS || type == AnnexB.PPS) MediaCodec.BUFFER_FLAG_CODEC_CONFIG else 0
                    val index = decoder.dequeueInputBuffer(INPUT_WAIT_US)
                    if (index < 0) continue
                    decoder.getInputBuffer(index)?.apply { clear(); put(unit) }
                    pts += 1_000
                    decoder.queueInputBuffer(index, 0, unit.size, pts, flags)
                    while (true) {
                        val out = decoder.dequeueOutputBuffer(info, 0)
                        if (out < 0) break
                        val now = System.currentTimeMillis()
                        val draw = now - lastDrawn >= current.minFrameMs
                        if (draw) lastDrawn = now
                        decoder.releaseOutputBuffer(out, draw)
                        if (draw && !asleep && state.phase != Phase.LIVE) report(State(Phase.LIVE))
                    }
                }
            }
        } catch (e: Exception) {
            if (running && !paused && quality == current) throw e
        } finally {
            closeStream()
        }
    }

    /** Every few seconds: is the glasses' screen asleep? (No frames come then.) */
    private fun watchScreen() {
        while (running) {
            val out = runCatching { control?.shell("true; dumpsys power | grep -m1 mWakefulness=")?.output }.getOrNull().orEmpty()
            val sleeping = "Asleep" in out || "Dozing" in out
            if (sleeping != asleep) {
                asleep = sleeping
                if (!paused) report(State(if (sleeping) Phase.ASLEEP else Phase.LIVE))
            }
            try {
                Thread.sleep(WATCH_MS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun closeStream() {
        runCatching { stream?.close() }
        stream = null
    }

    private fun report(next: State) {
        if (next == state) return
        state = next
        main.post { onState(next) }
    }

    private fun background(block: () -> Unit) {
        Thread({ runCatching(block).onFailure { Log.w(TAG, "glasses command failed", it) } }, "nb-mirror-cmd").apply { isDaemon = true }.start()
    }

    companion object {
        private const val TAG = "NbMirror"
        private const val WIDTH = 480
        private const val HEIGHT = 640
        private const val LIGHT_FPS = 5
        private const val INPUT_WAIT_US = 50_000L
        private const val WATCH_MS = 3_000L
    }
}
