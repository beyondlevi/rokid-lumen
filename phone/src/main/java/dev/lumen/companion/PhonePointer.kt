package dev.lumen.companion

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.graphics.Color
import android.util.Log
import dev.lumen.band.ScreenPointer

/**
 * The air mouse on this phone's screen ([ScreenPointer]), from a gesture of the phone's own
 * profiles: the cursor and its touches go through the Screen gestures service
 * ([PhoneTouchService]), and the middle pinch is Back. The band turns it off with the same
 * gesture ([ScreenPointer.OFF]); it also stops with the band, a locked phone, writing, or the
 * band going to a computer. Main thread.
 */
object PhonePointer {
    private const val TAG = "NbBand"
    private const val CHANNEL = "phone_pointer"
    private const val NOTICE = 4210

    /** White with a dark outline: seen on light and dark screens alike. */
    private val STYLE = ScreenPointer.Style(Color.WHITE, 0x99000000.toInt(), 30f)

    private var pointer: ScreenPointer? = null
    private var app: Context? = null

    val on get() = pointer?.on == true

    val listeners = mutableSetOf<() -> Unit>()

    /** Turns it on; false when it can't (Screen gestures off, or the band isn't connected here). */
    fun start(context: Context): Boolean {
        val app = context.applicationContext.also { this.app = it }
        if (on) {
            retune(app)
            return true
        }
        val service = PhoneTouchService.instance ?: run {
            Log.d(TAG, "air mouse: Screen gestures is off")
            return false
        }
        if (!PhoneBand.canWrite) {
            Log.d(TAG, "air mouse: the band isn't connected here")
            return false
        }
        val pointer = ScreenPointer(service, host(service), STYLE)
        if (!pointer.start(PhoneSettings.pointerTuning(app))) return false
        this.pointer = pointer
        notice(app)
        changed()
        return true
    }

    /** Turns it off (the band, then the cursor). */
    fun stop() {
        val pointer = pointer ?: return
        this.pointer = null
        pointer.stop()
        app?.getSystemService(NotificationManager::class.java)?.cancel(NOTICE)
        changed()
    }

    /** The settings changed: the cursor follows while it runs. */
    fun retune(context: Context) {
        pointer?.retune(PhoneSettings.pointerTuning(context))
    }

    /** The band's records (see dev.lumen.band.Bridge.pointer). */
    fun onBand(records: DoubleArray) {
        pointer?.onBand(records)
    }

    private fun host(service: PhoneTouchService) = object : ScreenPointer.Host {
        override fun setPointer(on: Boolean, tuning: String) = PhoneBand.setPointer(on, tuning)

        override fun back() {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }

    private fun notice(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.phone_pointer_channel), NotificationManager.IMPORTANCE_LOW).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.phone_pointer_notice_title))
            .setContentText(context.getString(R.string.computer_pointer_notice_text))
            .setCategory(Notification.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        manager.notify(NOTICE, notification)
    }

    private fun changed() = listeners.toList().forEach { it() }
}
