package dev.lumen.glasses

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.protocol.ChunkReceiver
import dev.lumen.protocol.Chunks
import dev.lumen.protocol.Link
import dev.lumen.protocol.NotifyCommand
import dev.lumen.protocol.PictureAnswer
import dev.lumen.protocol.PictureOps
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.Executors

/**
 * The pictures of the phone's notifications ([PictureOps]): asked for when a notification opens
 * in full (never with the post, which stays small on Rokid's slow link), one transfer at a time,
 * the one in view first. Decoded ones stay in a small LRU in memory ([CACHE]), dropped when
 * Android runs low; nothing is written to disk. Main thread.
 *
 * A debug build's simulated notification ("debug|" key) gets drawn test pictures instead, after
 * a moment, so the photo screens can be tried on the glasses alone.
 */
object NotificationPictures {
    private const val TAG = "BandPictures"
    /** Decoded pictures kept: an open chat's, and the one before. */
    private const val CACHE = 4
    /** A request the phone hasn't answered goes again this often… */
    private const val REPEAT_MS = 3_000L
    /** …this many times, then the picture is unavailable (the phone isn't there). */
    private const val REPEATS = 5
    /** After the header, a transfer whose chunks stop coming for this long fails. */
    private const val STALL_MS = 20_000L
    private const val SIMULATED = "debug|"
    private const val SIMULATED_DELAY_MS = 900L

    /** Reasons of the glasses' own (the phone's are [PictureOps]'). */
    const val REASON_NO_PHONE = "no-phone"
    const val REASON_TIMEOUT = "timeout"

    /** Where one picture stands. */
    sealed class State {
        object Loading : State()
        data class Ready(val bitmap: Bitmap) : State()
        data class Failed(val reason: String) : State()
    }

    fun interface Listener {
        fun onPicture(key: String, index: Int, state: State)
    }

    private class Wanted(val key: String, val index: Int, val at: Long) {
        val id get() = cacheKey(key, index, at)
    }

    private class Job(val wanted: Wanted, val linkId: String) {
        var receiver: ChunkReceiver? = null
        var asked = 0
        var lastProgress = SystemClock.uptimeMillis()
    }

    val listeners = LinkedHashSet<Listener>()
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val decoder = Executors.newSingleThreadExecutor()
    private val random = SecureRandom()
    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>?) = size > CACHE
    }
    private val failed = HashMap<String, String>()
    private val queue = ArrayDeque<Wanted>()
    private var job: Job? = null
    private var trimHooked = false

    private fun cacheKey(key: String, index: Int, at: Long) = "$key|$index|$at"

    /** Picture [index] of [notification] as it stands now. */
    @JvmStatic
    fun state(notification: PhoneNotification, index: Int): State {
        val at = notification.pictures.getOrNull(index)?.at ?: return State.Failed(PictureOps.REASON_GONE)
        val id = cacheKey(notification.key, index, at)
        cache[id]?.let { return State.Ready(it) }
        failed[id]?.let { return State.Failed(it) }
        return State.Loading
    }

    /**
     * [notification] is open: its pictures not in memory are asked for, [first] first, then the
     * others in order. What was wanted for another notification is dropped (its transfer too).
     */
    @JvmStatic
    fun want(context: Context, notification: PhoneNotification, first: Int = 0) {
        hookTrim(context)
        if (notification.redacted || notification.pictures.isEmpty()) return stop()
        val order = listOf(first) + notification.pictures.indices.filter { it != first }
        val wanted = order.filter { it in notification.pictures.indices }
            .map { Wanted(notification.key, it, notification.pictures[it].at) }
            .filter { it.id !in cache && it.id !in failed }
        queue.clear()
        job?.let { current -> if (wanted.none { it.id == current.wanted.id }) cancel(current) }
        wanted.filter { it.id != job?.wanted?.id }.forEach { queue.addLast(it) }
        next()
    }

    /** Asks again for a picture that didn't come. */
    @JvmStatic
    fun retry(context: Context, notification: PhoneNotification, index: Int) {
        val at = notification.pictures.getOrNull(index)?.at ?: return
        failed.remove(cacheKey(notification.key, index, at))
        notify(notification.key, index, State.Loading)
        want(context, notification, first = index)
    }

    /** The notification closed: nothing more is wanted, the transfer in flight ends on the phone too. */
    @JvmStatic
    fun stop() {
        queue.clear()
        job?.let { cancel(it) }
    }

    private fun cancel(current: Job) {
        if (job !== current) return
        job = null
        main.removeCallbacksAndMessages(current)
        if (!current.wanted.key.startsWith(SIMULATED)) PhoneLink.send(Link.PICTURE, PictureOps.message(PictureOps.CANCEL, current.linkId))
        Log.d(TAG, "picture ${current.linkId} cancelled")
    }

    private fun next() {
        if (job != null) return
        val wanted = queue.removeFirstOrNull() ?: return
        val current = Job(wanted, ByteArray(8).also(random::nextBytes).joinToString("") { "%02x".format(it) })
        job = current
        if (wanted.key.startsWith(SIMULATED)) {
            main.postAtTime({ if (job === current) finish(current, simulated(wanted.index)) }, current, SystemClock.uptimeMillis() + SIMULATED_DELAY_MS)
            return
        }
        Log.d(TAG, "picture ${current.linkId}: index ${wanted.index} of ${wanted.key.takeLast(24)}")
        ask(current)
    }

    /** The request, again every [REPEAT_MS] until the header comes (the link loses messages). */
    private fun ask(current: Job) {
        if (job !== current || current.receiver != null) return
        if (current.asked >= REPEATS) return fail(current, REASON_NO_PHONE)
        current.asked++
        val sent = PhoneLink.send(Link.NOTIFY, NotifyCommand.picture(current.linkId, current.wanted.key, current.wanted.index, current.wanted.at))
        // No link at all: no use repeating.
        if (!sent && PhoneLink.ensure() == null) return fail(current, REASON_NO_PHONE)
        main.postAtTime({ ask(current) }, current, SystemClock.uptimeMillis() + REPEAT_MS)
    }

    /** [Link.PICTURE_EVENT] from the phone; [bytes] is a chunk's payload. */
    @JvmStatic
    fun onPhoneEvent(json: JSONObject, bytes: ByteArray?) {
        val current = job ?: return
        if (json.optString("id") != current.linkId) return
        when (json.optString("type")) {
            PictureOps.PICTURE -> {
                val answer = PictureAnswer.from(json) ?: return
                if (current.receiver != null) return
                main.removeCallbacksAndMessages(current)
                if (!answer.ok) return fail(current, answer.reason.ifEmpty { PictureOps.REASON_UNREADABLE })
                if (answer.size !in 1..PictureOps.MAX_BYTES || answer.chunks != Chunks.count(answer.size)) return fail(current, PictureOps.REASON_TOO_LARGE)
                current.receiver = ChunkReceiver(answer.size, answer.sha256, answer.chunks)
                current.lastProgress = SystemClock.uptimeMillis()
                watchStall(current)
            }
            PictureOps.CHUNK -> {
                val receiver = current.receiver ?: return
                val seq = json.optInt("seq", -1)
                if (bytes == null || !receiver.put(seq, bytes)) return
                current.lastProgress = SystemClock.uptimeMillis()
                PhoneLink.send(Link.PICTURE, PictureOps.message(PictureOps.ACK, current.linkId).put("seq", seq))
                if (!receiver.complete) return
                main.removeCallbacksAndMessages(current)
                val file = receiver.bytes() ?: return fail(current, PictureOps.REASON_UNREADABLE)
                decode(current, file)
            }
        }
    }

    private fun watchStall(current: Job) {
        main.postAtTime({
            if (job !== current) return@postAtTime
            if (SystemClock.uptimeMillis() - current.lastProgress >= STALL_MS) fail(current, REASON_TIMEOUT) else watchStall(current)
        }, current, SystemClock.uptimeMillis() + 2_000)
    }

    private fun decode(current: Job, file: ByteArray) {
        decoder.execute {
            // Gray already (the phone sends it so): half the memory of ARGB.
            val bitmap = runCatching { BitmapFactory.decodeByteArray(file, 0, file.size, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.RGB_565 }) }.getOrNull()
            main.post {
                if (job !== current) return@post
                if (bitmap == null) fail(current, PictureOps.REASON_UNREADABLE) else finish(current, bitmap)
            }
        }
    }

    private fun finish(current: Job, bitmap: Bitmap) {
        job = null
        main.removeCallbacksAndMessages(current)
        cache[current.wanted.id] = bitmap
        Log.d(TAG, "picture ${current.linkId}: ${bitmap.width}x${bitmap.height}")
        notify(current.wanted.key, current.wanted.index, State.Ready(bitmap))
        next()
    }

    private fun fail(current: Job, reason: String) {
        if (job !== current) return
        job = null
        main.removeCallbacksAndMessages(current)
        failed[current.wanted.id] = reason
        Log.w(TAG, "picture ${current.linkId}: $reason")
        if (!current.wanted.key.startsWith(SIMULATED)) PhoneLink.send(Link.PICTURE, PictureOps.message(PictureOps.CANCEL, current.linkId))
        notify(current.wanted.key, current.wanted.index, State.Failed(reason))
        next()
    }

    private fun notify(key: String, index: Int, state: State) = listeners.toList().forEach { it.onPicture(key, index, state) }

    /** Android is short of memory (Gecko's pages come first): the decoded pictures go. */
    @JvmStatic
    fun trim() {
        if (cache.isEmpty()) return
        Log.d(TAG, "memory low: ${cache.size} picture(s) dropped")
        cache.clear()
    }

    private fun hookTrim(context: Context) {
        if (trimHooked) return
        trimHooked = true
        context.applicationContext.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                // Running low, or the app in the background with the system reclaiming: not the
                // mere UI_HIDDEN of a web app opening over the home.
                if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL ||
                    level >= ComponentCallbacks2.TRIM_MEMORY_BACKGROUND) main.post { trim() }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() {
                main.post { trim() }
            }
        })
    }

    /** A debug picture: a landscape (sky, sun, ridges), a different sky for each [index]. */
    private fun simulated(index: Int): Bitmap {
        val width = 480
        val height = 320
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val top = if (index % 2 == 0) Color.rgb(30, 30, 30) else Color.rgb(90, 90, 90)
        paint.shader = LinearGradient(0f, 0f, 0f, height.toFloat(), top, Color.rgb(220, 220, 220), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawCircle(width * (0.25f + 0.2f * (index % 3)), height * 0.35f, 34f, paint)
        paint.color = Color.rgb(60, 60, 60)
        canvas.drawPath(Path().apply {
            moveTo(0f, height.toFloat()); lineTo(0f, 220f); lineTo(120f, 150f); lineTo(230f, 230f)
            lineTo(330f, 130f); lineTo(width.toFloat(), 240f); lineTo(width.toFloat(), height.toFloat()); close()
        }, paint)
        paint.color = Color.BLACK
        canvas.drawPath(Path().apply {
            moveTo(0f, height.toFloat()); lineTo(0f, 280f); lineTo(160f, 240f); lineTo(300f, 290f)
            lineTo(420f, 250f); lineTo(width.toFloat(), 270f); lineTo(width.toFloat(), height.toFloat()); close()
        }, paint)
        return bitmap
    }
}
