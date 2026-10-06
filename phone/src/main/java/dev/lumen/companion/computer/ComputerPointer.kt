package dev.lumen.companion.computer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.companion.PhoneBand
import dev.lumen.companion.R

/**
 * The air mouse on the computer: the band's forearm aim moves the pointer, its index and middle
 * pinches are the buttons (rust/bridge's controller does the math, ported from kinesis'
 * experimental air cursor). The gesture mapped to [ComputerKeys.POINTER] turns it on here; the
 * band turns it off with the same gesture ([OFF]). Its records arrive in batches, so the
 * movement plays back through a [PointerPacer], a frame every [FRAME_MS] while there's some, in
 * whole mouse counts ([PointerCounts]) at [ComputerProfiles.pointerSpeed] counts a degree.
 * It stops with the computer, the band or writing. Main thread.
 */
object ComputerPointer {
    /** The band's action when it turns the air mouse off (rust/bridge `POINTER_OFF`). */
    const val OFF = "pointer.off"

    private const val TAG = "NbComputer"
    private const val CHANNEL = "computer_pointer"
    private const val NOTICE = 4209
    private const val FRAME_MS = 8L
    private const val MOVE = 0.0
    private const val CLEAR = 1.0
    private const val BUTTONS = 2.0

    private val main = Handler(Looper.getMainLooper())
    private val pacer = PointerPacer()
    private val counts = PointerCounts()
    private var app: Context? = null
    private var speed = ComputerProfiles.POINTER_SPEED_DEFAULT.toDouble()
    private var framing = false

    @Volatile var on = false
        private set

    val listeners = mutableSetOf<() -> Unit>()

    private val linkWatch: () -> Unit = {
        if (on && !ComputerLink.connected) {
            Log.d(TAG, "air mouse: the computer went away")
            stop()
        }
    }

    private val frame = Runnable { frame() }

    /** Turns it on for the connected computer; false when it can't (no computer, no band, writing). */
    fun start(context: Context): Boolean {
        val app = context.applicationContext.also { this.app = it }
        if (on) {
            retune(app)
            return true
        }
        if (!ComputerLink.connected || !PhoneBand.canWrite || ComputerWriter.writing) {
            Log.d(TAG, "air mouse: no computer, no band here, or writing")
            return false
        }
        speed = ComputerProfiles.pointerSpeed(app).toDouble()
        if (!PhoneBand.setPointer(true, tuning(app))) {
            Log.d(TAG, "air mouse: the band can't run it")
            return false
        }
        on = true
        pacer.clear()
        counts.reset()
        ComputerLink.listeners += linkWatch
        Log.d(TAG, "air mouse on")
        notice(app)
        changed()
        return true
    }

    /** Turns it off: the band goes back to the profile's gestures, no button stays down. */
    fun stop() {
        if (!on) return
        on = false
        PhoneBand.setPointer(false, "")
        ComputerLink.release()
        ComputerLink.listeners -= linkWatch
        pacer.clear()
        counts.reset()
        main.removeCallbacks(frame)
        framing = false
        app?.getSystemService(NotificationManager::class.java)?.cancel(NOTICE)
        Log.d(TAG, "air mouse off")
        changed()
    }

    /** The settings changed: the band and the speed follow while it runs. */
    fun retune(context: Context) {
        if (!on) return
        speed = ComputerProfiles.pointerSpeed(context).toDouble()
        PhoneBand.setPointer(true, tuning(context))
    }

    private fun tuning(context: Context) =
        "steadiness=${ComputerProfiles.pointerSteadiness(context)};boost=${ComputerProfiles.pointerBoost(context)}"

    /** The band's records (see dev.lumen.band.Bridge.pointer): movement to play, buttons at once. */
    fun onBand(records: DoubleArray) {
        if (!on) return
        var i = 0
        while (i + 3 < records.size) {
            val a = records[i + 2]
            when (records[i]) {
                MOVE -> pacer.add(a * speed, records[i + 3] * speed, records[i + 1])
                CLEAR -> {
                    pacer.clear()
                    counts.reset()
                }
                BUTTONS -> ComputerLink.buttons(a.toInt())
            }
            i += 4
        }
        if (!framing && !pacer.isEmpty) {
            framing = true
            main.post(frame)
        }
    }

    private fun frame() {
        framing = false
        if (!on) return
        pacer.take(SystemClock.elapsedRealtimeNanos() / 1e9)?.let { (x, y) ->
            val (dx, dy) = counts.add(x, y)
            if (dx != 0 || dy != 0) ComputerLink.move(dx, dy)
        }
        if (!pacer.isEmpty) {
            framing = true
            main.postDelayed(frame, FRAME_MS)
        }
    }

    private fun notice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.computer_pointer_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val name = ComputerLink.computer?.name ?: context.getString(R.string.computer_generic)
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.computer_pointer_notice_title, name))
            .setContentText(context.getString(R.string.computer_pointer_notice_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTICE, notification)
    }

    private fun changed() = listeners.toList().forEach { it() }
}
