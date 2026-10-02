package dev.lumen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.SettingsOps
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The glasses' wireless debugging as this phone shows it ([DebugStatus], switched on from the
 * Settings tab): the `adb connect` command to copy, and a notification with that command
 * whenever the glasses come up at a new address (they rejoined the network, a new lease), so
 * the computer reconnects without anyone looking the address up. The notification stays on
 * this phone: [NotificationForwarder] skips the companion's own.
 */
object GlassesDebug {
    private const val CHANNEL = "glasses_debug"
    private const val NOTIFICATION_ID = 4210
    const val ACTION_COPY = "dev.lumen.companion.COPY_ADB"
    private const val EXTRA_COMMAND = "command"

    /** The value asked for and not yet confirmed by the glasses (the switch shows a spinner). */
    @Volatile var pending: Boolean? = null
        private set

    /** The last address a notification announced, so a repeat of the same state stays quiet. */
    private var announced: String? = null

    private val main by lazy { android.os.Handler(android.os.Looper.getMainLooper()) }
    /** Rokid's link loses messages: the ask goes again a few times before the switch gives up. */
    private const val RETRY_MS = 8_000L
    private const val TRIES = 4

    /** Asks the glasses to switch it; false without a link. */
    @JvmStatic
    fun request(on: Boolean): Boolean {
        if (!send(on)) return false
        pending = on
        main.removeCallbacksAndMessages(this)
        retry(on, TRIES - 1)
        return true
    }

    private fun send(on: Boolean) = CompanionService.requestSettings(SettingsOps.set(SettingsOps.KEY_WIRELESS_DEBUG, on.toString()))

    private fun retry(on: Boolean, left: Int) {
        main.postAtTime({
            if (pending != on) return@postAtTime
            if (left > 0 && send(on)) return@postAtTime retry(on, left - 1)
            pending = null
            BandStore.listeners.toList().forEach { it() }
        }, this, android.os.SystemClock.uptimeMillis() + RETRY_MS)
    }

    /** A new state from the glasses. Main thread. */
    @JvmStatic
    fun onStatus(context: Context, status: DebugStatus) {
        if (pending == status.enabled) {
            pending = null
            // The screen redrew on this event before the switch knew it was confirmed.
            BandStore.listeners.toList().forEach { it() }
        }
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (!status.ready) {
            if (!status.enabled) {
                manager.cancel(NOTIFICATION_ID)
                announced = null
            }
            return
        }
        if (announced == status.command) return
        announced = status.command
        notify(context, manager, status)
    }

    private fun notify(context: Context, manager: NotificationManager, status: DebugStatus) {
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.debug_channel), NotificationManager.IMPORTANCE_LOW))
        val copy = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, CopyReceiver::class.java).setAction(ACTION_COPY).putExtra(EXTRA_COMMAND, status.command),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, CompanionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(context.getString(R.string.debug_notification_title, status.address))
            .setContentText(status.command)
            .setSubText(status.ssid.ifEmpty { null })
            .setContentIntent(open)
            .addAction(Notification.Action.Builder(null, context.getString(R.string.debug_copy), copy).build())
            .setOnlyAlertOnce(true)
            .build()
        runCatching { manager.notify(NOTIFICATION_ID, notification) }
    }

    @JvmStatic
    fun copy(context: Context, command: String) {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("adb", command))
        // Android 13+ shows its own confirmation for a copy.
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(context, R.string.debug_copied, Toast.LENGTH_SHORT).show()
    }

    /**
     * Whether this phone's Wi-Fi is on the glasses' network (same /24): a hint, the computer is
     * what has to be there. Null when the phone has no Wi-Fi address.
     */
    @JvmStatic
    fun phoneOnSameNetwork(glasses: String): Boolean? {
        val phone = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { it.isUp && it.name.startsWith("wlan") }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress.orEmpty() }
        }.getOrDefault(emptyList())
        if (phone.isEmpty() || glasses.isEmpty()) return null
        return phone.any { it.substringBeforeLast('.') == glasses.substringBeforeLast('.') }
    }

    /** The notification's Copy button. */
    class CopyReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != ACTION_COPY) return
            copy(context, intent.getStringExtra(EXTRA_COMMAND) ?: return)
        }
    }
}
