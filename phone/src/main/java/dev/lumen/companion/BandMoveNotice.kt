package dev.lumen.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import dev.lumen.band.Phase
import dev.lumen.companion.computer.ComputerLink
import dev.lumen.companion.computer.ComputerProfiles
import dev.lumen.protocol.BandStatus

/**
 * A move of the band, step by step in one notification on the phone (a silent heads-up, updated
 * in place): coming here or to a computer (the glasses sent it), or going to the glasses (the
 * phone's gesture, or the glasses took it). The steps here come from the band's link and the
 * computer's; the glasses' come from their status, which reaches the phone in about a second.
 * After [SLOW_MS] it says the move takes long and keeps following it; the arrival ends it.
 * Main thread.
 */
object BandMoveNotice {
    private const val TAG = "NbBandMove"
    private const val CHANNEL = "band_move"
    private const val ID = 4212
    private const val DONE_SHOWN_MS = 4_000L
    private const val SLOW_MS = 30_000L
    private const val GIVE_UP_MS = 180_000L

    private enum class Leg { HERE, GLASSES }

    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private var leg: Leg? = null
    private var startedAt = 0L
    private var slow = false

    /** What the notification says now, so an unchanged step isn't posted again. */
    private var posted: Pair<String, String>? = null

    private val changed: () -> Unit = { main.post { update() } }
    private val slowCheck = Runnable { onSlow() }

    /** The band is coming to the phone (or a computer, as the companion's prefs say). */
    fun toPhone(context: Context) = begin(context, Leg.HERE)

    /** The band is going to the glasses. */
    fun toGlasses(context: Context) = begin(context, Leg.GLASSES)

    /** The glasses' status changed: the next step of a move to them. */
    fun onGlasses() {
        if (leg == Leg.GLASSES) update()
    }

    private fun begin(context: Context, next: Leg) {
        val app = context.applicationContext
        this.app = app
        if (leg == null) {
            PhoneBand.listeners += changed
            ComputerLink.listeners += changed
        }
        leg = next
        startedAt = SystemClock.elapsedRealtime()
        slow = false
        posted = null
        Log.d(TAG, "following the band to the ${next.name.lowercase()}")
        main.removeCallbacks(slowCheck)
        main.postDelayed(slowCheck, SLOW_MS)
        update()
    }

    private fun update() {
        val app = app ?: return
        val leg = leg ?: return
        when (leg) {
            Leg.HERE -> {
                // Sent elsewhere meanwhile (the glasses took it back): that move has its own notice.
                if (!CompanionPrefs.bandOnPhone(app)) return end(cancel = true)
                val computer = CompanionPrefs.bandOnComputer(app)
                val name = ComputerLink.computer?.name ?: ComputerLink.computers(app).firstOrNull()?.name ?: app.getString(R.string.band_move_a_computer)
                val title = if (computer) app.getString(R.string.band_move_to_computer, name) else app.getString(R.string.band_move_to_phone)
                when {
                    PhoneBand.phase == Phase.CONNECTING -> progress(title, app.getString(R.string.band_move_connecting))
                    PhoneBand.phase != Phase.CONNECTED -> progress(title, app.getString(R.string.band_move_searching))
                    computer && !ComputerLink.connected -> progress(title, app.getString(R.string.band_move_computer, name))
                    else -> {
                        val profile = if (computer) ComputerProfiles.state(app).current.name else PhoneProfiles.state(app).current.name
                        done(
                            if (computer) app.getString(R.string.band_move_on_computer, name) else app.getString(R.string.band_move_on_phone),
                            app.getString(R.string.band_move_profile, profile),
                        )
                    }
                }
            }
            Leg.GLASSES -> {
                if (CompanionPrefs.bandOnPhone(app)) return end(cancel = true)
                val glasses = BandStore.status
                val title = app.getString(R.string.band_move_to_glasses)
                when {
                    // Their status still says the phone has it: the request hasn't reached them.
                    glasses.onPhone -> progress(title, app.getString(R.string.band_move_waiting_glasses))
                    glasses.phase == BandStatus.PHASE_CONNECTED -> done(app.getString(R.string.band_move_on_glasses), "")
                    glasses.phase == BandStatus.PHASE_CONNECTING -> progress(title, app.getString(R.string.band_move_glasses_connecting))
                    glasses.phase == BandStatus.PHASE_SEARCHING -> progress(title, app.getString(R.string.band_move_glasses_searching))
                    else -> progress(title, app.getString(R.string.band_move_waiting_glasses))
                }
            }
        }
    }

    /** The glasses never answered the move to them: the band stays with the phone ([PhoneBand.useOnGlasses]). */
    fun notReceived(context: Context) {
        val app = context.applicationContext
        this.app = app
        end(cancel = false)
        post(app.getString(R.string.band_move_not_received), app.getString(R.string.band_move_not_received_text), inProgress = false, keep = true)
    }

    private fun progress(title: String, text: String) {
        if (slow) return
        post(title, text, inProgress = true)
    }

    private fun done(title: String, text: String) {
        Log.d(TAG, "the band arrived")
        post(title, text, inProgress = false)
        end(cancel = false)
    }

    private fun onSlow() {
        val app = app ?: return
        val leg = leg ?: return
        val elapsed = SystemClock.elapsedRealtime() - startedAt
        if (elapsed >= GIVE_UP_MS) return end(cancel = true)
        if (!slow) {
            slow = true
            Log.d(TAG, "the band is slow to arrive")
            if (leg == Leg.HERE) post(app.getString(R.string.band_move_slow_here), app.getString(R.string.band_move_slow_here_text), inProgress = false, keep = true)
            else post(app.getString(R.string.band_move_slow_glasses), app.getString(R.string.band_move_slow_glasses_text), inProgress = false, keep = true)
        }
        main.postDelayed(slowCheck, GIVE_UP_MS - elapsed)
    }

    private fun end(cancel: Boolean) {
        main.removeCallbacks(slowCheck)
        PhoneBand.listeners -= changed
        ComputerLink.listeners -= changed
        leg = null
        slow = false
        posted = null
        if (cancel) app?.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }

    private fun post(title: String, text: String, inProgress: Boolean, keep: Boolean = false) {
        val app = app ?: return
        if (posted == title to text) return
        posted = title to text
        val manager = app.getSystemService(NotificationManager::class.java) ?: return
        if (!manager.areNotificationsEnabled()) return
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, app.getString(R.string.band_move_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                setSound(null, null)
                enableVibration(false)
                setShowBadge(false)
            },
        )
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(title)
            .setCategory(if (inProgress) Notification.CATEGORY_PROGRESS else Notification.CATEGORY_STATUS)
            .setAutoCancel(true)
        if (text.isNotEmpty()) builder.setContentText(text)
        if (inProgress) builder.setProgress(0, 0, true)
        if (!inProgress && !keep) builder.setTimeoutAfter(DONE_SHOWN_MS)
        runCatching { manager.notify(ID, builder.build()) }
    }
}
