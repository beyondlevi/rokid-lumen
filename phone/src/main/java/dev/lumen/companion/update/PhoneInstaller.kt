package dev.lumen.companion.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import java.io.File

/**
 * Installs the companion's own update through a PackageInstaller session. Android asks the
 * person to confirm (an app can't update itself silently), and the install replaces this
 * process: the outcome is kept in preferences and shown on the next start.
 */
object PhoneInstaller {
    private const val TAG = "NbUpdate"
    private const val ACTION = "dev.lumen.companion.UPDATE_INSTALL_STATUS"

    /** Whether Android lets this app install packages ("install unknown apps" for it). */
    @JvmStatic
    fun allowed(context: Context): Boolean = context.packageManager.canRequestPackageInstalls()

    @JvmStatic
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, StatusReceiver::class.java).setAction(ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, intent, flags).intentSender)
        }
        Log.d(TAG, "companion update committed (session $id)")
    }

    /** The session's answers: Android's confirmation to show, then success or failure. */
    class StatusReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                    context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                PackageInstaller.STATUS_SUCCESS -> Log.d(TAG, "companion updated")
                else -> {
                    val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                    Log.w(TAG, "companion update failed: $status $message")
                    UpdateManager.onPhoneInstallFailed(context, status, message)
                }
            }
        }
    }
}
