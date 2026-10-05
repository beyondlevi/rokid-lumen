package dev.lumen.companion.update

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.companion.BandStore
import dev.lumen.companion.CompanionActivity
import dev.lumen.companion.CompanionService
import dev.lumen.companion.R
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Updates from the repository's GitHub releases, for this companion and the glasses app, as
 * Rokid Nexus does: the phone checks the releases (on open, every few hours from the service,
 * or when asked), downloads each APK and checks it ([ApkDownload]), hands the glasses' to Rokid's
 * link (CXR-L `appUploadAndInstall`, over Wi-Fi) and installs its own through Android's
 * installer. Glasses first: installing the companion restarts it, which would cut the glasses'
 * delivery short. State for the screens is [state]; listeners hear every change on the main
 * thread.
 */
object UpdateManager {
    private const val TAG = "NbUpdate"
    private const val PREFS = "lumen_updates"
    private const val KEY_AUTO = "auto"
    private const val KEY_BETA = "beta"
    private const val KEY_LAST_CHECK = "last_check"
    private const val KEY_NOTIFIED = "notified"
    private const val KEY_PHONE_FAILURE = "phone_failure"
    const val GLASSES_PACKAGE = "dev.lumen.glasses"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 7
    /** At most one automatic check every four hours (GitHub's anonymous quota is 60 an hour). */
    const val CHECK_INTERVAL_MS = 4 * 60 * 60_000L
    /** How long the glasses get to come back on the new version after installing. */
    private const val REARM_TIMEOUT_MS = 4 * 60_000L

    enum class Problem { OFFLINE, NO_WIFI, GLASSES_OFFLINE, NO_DIGEST, DIGEST, SIGNER, PACKAGE, INSTALL_FAILED, NO_PERMISSION }

    /** Where one app's update stands. */
    sealed class Step {
        object Idle : Step()
        object Waiting : Step()
        data class Downloading(val done: Long, val total: Long) : Step()
        /** Glasses: sent to Rokid's link, which uploads and installs it. */
        object Sending : Step()
        /** Glasses: installed; waiting for them to report the new version (the band service comes back). */
        object Rearming : Step()
        /** Phone: Android's confirmation is up. */
        object Confirming : Step()
        object Done : Step()
        data class Failed(val problem: Problem) : Step()
    }

    data class State(
        val checking: Boolean = false,
        val lastCheck: Long = 0L,
        val checkProblem: Problem? = null,
        val releases: List<Release> = emptyList(),
        val phoneVersion: String = "",
        val glassesVersion: String = "",
        val phoneUpdate: Release? = null,
        val glassesUpdate: Release? = null,
        val phoneStep: Step = Step.Idle,
        val glassesStep: Step = Step.Idle,
        val auto: Boolean = true,
        val beta: Boolean = false,
    ) {
        val busy: Boolean get() = phoneStep.running || glassesStep.running
        /** The newest version on offer, for the home's card and the notes. */
        val offered: Release? get() = listOfNotNull(phoneUpdate, glassesUpdate).maxByOrNull { it.version }
    }

    private val Step.running: Boolean get() = this is Step.Waiting || this is Step.Downloading || this is Step.Sending || this is Step.Rearming || this is Step.Confirming

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private var steps = Pair<Step, Step>(Step.Idle, Step.Idle) // phone, glasses
    private var checking = false
    private var checkProblem: Problem? = null
    private var releases: List<Release>? = null

    val listeners = LinkedHashSet<() -> Unit>()

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun autoCheck(context: Context) = prefs(context).getBoolean(KEY_AUTO, true)

    @JvmStatic
    fun setAutoCheck(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        changed()
    }

    @JvmStatic
    fun includeBeta(context: Context) = prefs(context).getBoolean(KEY_BETA, false)

    @JvmStatic
    fun setIncludeBeta(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_BETA, on).apply()
        changed()
    }

    @JvmStatic
    fun phoneVersion(context: Context): String =
        runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull().orEmpty()

    /** The glasses app's version as the glasses last said, "" before they say (or from an old app). */
    @JvmStatic
    fun glassesVersion(): String = BandStore.schema?.appVersion.orEmpty()

    @JvmStatic
    fun state(context: Context): State {
        val all = releases ?: ReleaseFeed.cached(context).also { releases = it }
        val beta = includeBeta(context)
        val phone = phoneVersion(context)
        val glasses = glassesVersion()
        return State(
            checking = checking,
            lastCheck = prefs(context).getLong(KEY_LAST_CHECK, 0L),
            checkProblem = checkProblem,
            releases = all,
            phoneVersion = phone,
            glassesVersion = glasses,
            phoneUpdate = Release.newest(all, phone, beta) { it.companionApk },
            // An app older than the updater doesn't say its version: anything newer than the phone's is on offer.
            glassesUpdate = Release.newest(all, glasses.ifEmpty { "0.0.0" }, beta) { it.glassesApk },
            phoneStep = steps.first,
            glassesStep = steps.second,
            auto = autoCheck(context),
            beta = beta,
        )
    }

    // ---- Checking ----

    /** The automatic check: on open and from the service, at most every [CHECK_INTERVAL_MS]. */
    @JvmStatic
    fun checkIfDue(context: Context) {
        if (!autoCheck(context)) return
        val last = prefs(context).getLong(KEY_LAST_CHECK, 0L)
        if (System.currentTimeMillis() - last < CHECK_INTERVAL_MS) return
        check(context, notify = true)
    }

    /** Asks GitHub now; [notify] posts a notification once per new version. */
    @JvmStatic
    fun check(context: Context, notify: Boolean = false) {
        if (checking) return
        val app = context.applicationContext
        // Saved before the call, so restarts can't hammer GitHub.
        prefs(app).edit().putLong(KEY_LAST_CHECK, System.currentTimeMillis()).apply()
        checking = true
        changed()
        worker.execute {
            val result = ReleaseFeed.fetch(app)
            main.post {
                checking = false
                result.onSuccess { releases = it; checkProblem = null }.onFailure { checkProblem = Problem.OFFLINE }
                changed()
                if (notify) notifyIfNew(app)
            }
        }
    }

    private fun notifyIfNew(context: Context) {
        val offered = state(context).offered ?: return
        if (prefs(context).getString(KEY_NOTIFIED, null) == offered.tag) return
        prefs(context).edit().putString(KEY_NOTIFIED, offered.tag).apply()
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, context.getString(R.string.update_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, CompanionActivity::class.java).putExtra(CompanionActivity.EXTRA_PAGE, CompanionActivity.PAGE_UPDATES)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        manager.notify(
            NOTIFICATION_ID,
            Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setContentTitle(context.getString(R.string.update_notification_title, offered.version.toString()))
                .setContentText(context.getString(R.string.update_notification_text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build(),
        )
    }

    // ---- Updating ----

    /** Updates what has an update: the glasses first, then this companion. */
    @JvmStatic
    fun updateAll(context: Context) {
        val app = context.applicationContext
        val now = state(app)
        if (now.busy) return
        val glasses = now.glassesUpdate
        val phone = now.phoneUpdate
        if (glasses == null && phone == null) return
        cancelled.set(false)
        setSteps(if (phone != null) Step.Waiting else Step.Idle, if (glasses != null) Step.Waiting else Step.Idle)
        worker.execute {
            val glassesOk = glasses == null || updateGlasses(app, glasses)
            if (phone != null && !cancelled.get()) {
                if (glassesOk) updatePhone(app, phone) else setSteps(Step.Idle, steps.second)
            }
        }
    }

    /** Stops a download in progress (an upload already handed to Rokid's link can't be). */
    @JvmStatic
    fun cancel() {
        cancelled.set(true)
        val (phone, glasses) = steps
        setSteps(if (phone.running && phone !is Step.Confirming) Step.Idle else phone, if (glasses is Step.Downloading || glasses is Step.Waiting) Step.Idle else glasses)
    }

    /** The glasses app's setup entry, opened over Rokid's link after a first install. */
    const val SETUP_ENTRY = "$GLASSES_PACKAGE.SetupEntryActivity"

    /**
     * First setup: installs the glasses app from the newest release (a beta when there's no
     * stable one yet) through Rokid's link, then opens its setup entry there, which hands the
     * wearer to the accessibility switch. There's no rearm wait: nothing on the glasses answers
     * the phone until that switch is on.
     */
    @JvmStatic
    fun installGlassesFirstTime(context: Context) {
        val app = context.applicationContext
        if (steps.second.running) return
        cancelled.set(false)
        setSteps(steps.first, Step.Waiting)
        worker.execute {
            val all = releases?.takeIf { it.isNotEmpty() }
                ?: ReleaseFeed.fetch(app).getOrNull()?.also { releases = it }
                ?: ReleaseFeed.cached(app)
            val release = Release.newest(all, "0.0.0", false) { it.glassesApk }
                ?: Release.newest(all, "0.0.0", true) { it.glassesApk }
            if (release == null) {
                failGlasses(Problem.OFFLINE)
                return@execute
            }
            if (installGlassesApk(app, release)) {
                setSteps(steps.first, Step.Done)
                CompanionService.openOnGlasses(SETUP_ENTRY)
            }
        }
    }

    private fun updateGlasses(context: Context, release: Release): Boolean {
        if (!installGlassesApk(context, release)) return false
        // The app restarts on the glasses (the self-arm's watchdog brings its service back) and
        // says its version again when asked: done when it's the new one.
        setSteps(steps.first, Step.Rearming)
        val deadline = System.currentTimeMillis() + REARM_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            if (SemVer.parse(glassesVersion()) == release.version) break
            CompanionService.requestSettings(dev.lumen.protocol.SettingsOps.describe())
            Thread.sleep(10_000)
        }
        // Installed either way (Rokid's link said so); the version only confirms the restart.
        setSteps(steps.first, Step.Done)
        return true
    }

    /** Downloads, checks and hands [release]'s glasses APK to Rokid's link; false (and Failed) if any step fails. */
    private fun installGlassesApk(context: Context, release: Release): Boolean {
        val asset = release.glassesApk ?: return false
        val wifi = context.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled != true) return failGlasses(Problem.NO_WIFI)
        if (!CompanionService.linkReady) return failGlasses(Problem.GLASSES_OFFLINE)
        val apk = download(context, asset, glasses = true) ?: return false
        try {
            ApkDownload.verify(context, apk, GLASSES_PACKAGE, release.version)
        } catch (e: ApkDownload.Refused) {
            Log.w(TAG, "glasses APK refused: ${e.message}")
            return failGlasses(problemOf(e.reason))
        }
        setSteps(steps.first, Step.Sending)
        val ok = CompanionService.installOnGlasses(apk)
        apk.delete()
        if (!ok) return failGlasses(Problem.INSTALL_FAILED)
        return true
    }

    private fun updatePhone(context: Context, release: Release) {
        val asset = release.companionApk ?: return
        if (!PhoneInstaller.allowed(context)) {
            setSteps(Step.Failed(Problem.NO_PERMISSION), steps.second)
            return
        }
        val apk = download(context, asset, glasses = false) ?: return
        try {
            ApkDownload.verify(context, apk, context.packageName, release.version)
        } catch (e: ApkDownload.Refused) {
            Log.w(TAG, "companion APK refused: ${e.message}")
            setSteps(Step.Failed(problemOf(e.reason)), steps.second)
            return
        }
        setSteps(Step.Confirming, steps.second)
        runCatching { PhoneInstaller.install(context, apk) }.onFailure {
            Log.w(TAG, "companion install: ${it.message}")
            setSteps(Step.Failed(Problem.INSTALL_FAILED), steps.second)
        }
    }

    private fun download(context: Context, asset: ReleaseAsset, glasses: Boolean): File? {
        fun progress(done: Long, total: Long) {
            val step = Step.Downloading(done, total)
            if (glasses) setSteps(steps.first, step) else setSteps(step, steps.second)
        }
        progress(0, asset.size)
        return try {
            ApkDownload.download(context, asset, { cancelled.get() }, ::progress)
        } catch (e: InterruptedException) {
            null
        } catch (e: ApkDownload.Refused) {
            Log.w(TAG, "download refused: ${e.message}")
            val failed = Step.Failed(problemOf(e.reason))
            if (glasses) setSteps(steps.first, failed) else setSteps(failed, steps.second)
            null
        } catch (e: Exception) {
            Log.w(TAG, "download: ${e.message}")
            val failed = Step.Failed(Problem.OFFLINE)
            if (glasses) setSteps(steps.first, failed) else setSteps(failed, steps.second)
            null
        }
    }

    private fun failGlasses(problem: Problem): Boolean {
        setSteps(steps.first, Step.Failed(problem))
        return false
    }

    private fun problemOf(reason: ApkDownload.Reason) = when (reason) {
        ApkDownload.Reason.NO_DIGEST -> Problem.NO_DIGEST
        ApkDownload.Reason.DIGEST -> Problem.DIGEST
        ApkDownload.Reason.SIGNER -> Problem.SIGNER
        ApkDownload.Reason.PACKAGE, ApkDownload.Reason.VERSION -> Problem.PACKAGE
        ApkDownload.Reason.NETWORK -> Problem.OFFLINE
    }

    /** Android's installer refused or the person declined (PhoneInstaller's receiver). */
    @JvmStatic
    fun onPhoneInstallFailed(context: Context, status: Int, message: String) {
        prefs(context).edit().putString(KEY_PHONE_FAILURE, "$status $message").apply()
        setSteps(Step.Failed(Problem.INSTALL_FAILED), steps.second)
    }

    /** Back to idle once the person saw how it ended. */
    @JvmStatic
    fun dismiss() {
        val (phone, glasses) = steps
        setSteps(if (phone.running) phone else Step.Idle, if (glasses.running) glasses else Step.Idle)
    }

    private fun setSteps(phone: Step, glasses: Step) {
        steps = phone to glasses
        changed()
    }

    private fun changed() {
        if (Looper.myLooper() == Looper.getMainLooper()) listeners.toList().forEach { it() }
        else main.post { listeners.toList().forEach { it() } }
    }
}
