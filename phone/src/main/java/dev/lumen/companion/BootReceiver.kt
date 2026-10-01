package dev.lumen.companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** After a reboot or an update, the link comes back by itself once the companion is authorized. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            CompanionService.ensureRunning(context)
        }
    }
}
