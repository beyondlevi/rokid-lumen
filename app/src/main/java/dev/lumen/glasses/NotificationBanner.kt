package dev.lumen.glasses

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A phone notification's banner at the top of the HUD, over whatever app is in front: an
 * accessibility overlay window, so it needs no overlay permission. It wakes the display, goes
 * away by itself after [VISIBLE_MS], and while it's up it takes the band's navigation: index
 * opens it in the inbox, middle dismisses, and swipes stop there (they used to reach the app
 * behind it). Volume and the mapped actions still work. With [PhoneNotification.focus] a black
 * window covers the HUD behind it: on the additive display black is see-through, so only the
 * notification stays (as Rokid Nexus's Relay does).
 *
 * Two swipes down snooze the banners for 15 minutes ([NotificationSnooze]): the first asks for
 * the second in the hint line (and restarts the banner's time), the second confirms. The inbox
 * still gets everything meanwhile.
 *
 * A banner that woke the display turns it off again when it goes away by itself, unless the
 * user did something meanwhile ([onUserActivity]): the Rokid's own screen-off timeout is days
 * long, so without this the HUD stayed on after every notification.
 */
class NotificationBanner(private val service: AccessibilityService) {
    private val main = Handler(Looper.getMainLooper())
    private val windows = service.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private var shade: View? = null
    private var shown: PhoneNotification? = null
    private var hint: TextView? = null
    /** The first swipe down happened: the next one snoozes. */
    private var snoozeArmed = false
    /** The display was off and a banner woke it; no user activity since. */
    private var wokeDisplay = false
    private var shownAt = 0L
    private val hide = Runnable {
        val lock = wokeDisplay
        wokeDisplay = false
        dismiss()
        if (lock) lockDisplay()
    }

    val isShowing get() = view != null

    fun show(notification: PhoneNotification) {
        if (NotificationSnooze.isActive(service)) {
            Log.d(TAG, "Snoozed: no banner for ${notification.key.takeLast(16)}")
            return
        }
        dismiss()
        wakeDisplay()
        val metrics = service.resources.displayMetrics
        val side = minOf(metrics.widthPixels, metrics.heightPixels)
        if (notification.focus) addShade()
        val content = build(notification)
        val params = WindowManager.LayoutParams(
            cardWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            // The top of the HUD's square (the display can be taller than it is wide).
            y = (metrics.heightPixels - side) / 2 + px(24f)
        }
        runCatching { windows.addView(content, params) }
            .onSuccess {
                view = content
                shown = notification
                shownAt = System.currentTimeMillis()
                main.postDelayed(hide, VISIBLE_MS)
            }
            .onFailure { Log.w(TAG, "Couldn't show the banner", it) }
    }

    /** The band while the banner is up: true when it took the gesture. */
    fun onBandCommand(command: String): Boolean {
        val current = shown ?: return false
        Log.d(TAG, "Band $command taken by the banner")
        return when (command) {
            BandCommand.ACTIVATE -> {
                // Opening it is using the glasses: the display stays on.
                onUserActivity()
                dismiss()
                service.startActivity(
                    Intent(service, NotificationInboxActivity::class.java)
                        .putExtra(NotificationInboxActivity.EXTRA_KEY, current.key)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                )
                true
            }
            // Dismissed: a display the banner woke goes back to sleep at once.
            BandCommand.BACK -> {
                val lock = wokeDisplay
                wokeDisplay = false
                dismiss()
                if (lock) lockDisplay()
                true
            }
            BandCommand.DOWN -> {
                if (snoozeArmed) snooze() else armSnooze()
                true
            }
            // Swipes and pinch-and-turn steps: kept from the app behind while the banner is up.
            else -> {
                if (snoozeArmed && command.startsWith(NAV_PREFIX)) disarmSnooze()
                command.startsWith(NAV_PREFIX)
            }
        }
    }

    /** The user did something (a band gesture, a click or scroll on screen): the display stays on. */
    fun onUserActivity() {
        if (!wokeDisplay) return
        wokeDisplay = false
        Log.d(TAG, "User activity: the display stays on after the banner")
    }

    /**
     * A click or scroll on screen (the touchpad), unless it came as the display was waking (the
     * launcher settling, measured 1-2 s): only then is it the user.
     */
    fun onScreenInteraction() {
        if (System.currentTimeMillis() - shownAt < WAKE_SETTLE_MS) return
        onUserActivity()
    }

    /** The display went off by other means: nothing to turn off later. */
    fun onScreenOff() {
        wokeDisplay = false
    }

    private fun lockDisplay() {
        val power = service.getSystemService(PowerManager::class.java)
        if (power != null && !power.isInteractive) return
        val locked = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
        Log.d(TAG, "Banner gone without user activity, display off: $locked")
    }

    private fun armSnooze() {
        snoozeArmed = true
        hint?.apply {
            text = service.getString(R.string.notification_snooze_confirm, NotificationSnooze.DURATION_MS / 60_000)
            setTextColor(MetaStyle.ACCENT)
        }
        // Time to read it and swipe again.
        main.removeCallbacks(hide)
        main.postDelayed(hide, VISIBLE_MS)
    }

    private fun disarmSnooze() {
        snoozeArmed = false
        hint?.apply {
            text = service.getString(R.string.notification_banner_hint)
            setTextColor(MetaStyle.TEXT_PLACEHOLDER)
        }
    }

    private fun snooze() {
        val until = NotificationSnooze.start(service)
        Log.d(TAG, "Snoozed until $until")
        dismiss()
        message(service.getString(R.string.notification_snoozed, NotificationSnooze.DURATION_MS / 60_000))
    }

    /** A short note in the banner's place that takes no gestures (the snooze's confirmation). */
    private fun message(text: String) {
        val metrics = service.resources.displayMetrics
        val side = minOf(metrics.widthPixels, metrics.heightPixels)
        val panel = LinearLayout(service).apply {
            gravity = Gravity.CENTER
            setPadding(px(16f), px(8f), px(16f), px(8f))
            background = MetaStyle.pill(service)
            addView(meta(text, 22f, MetaStyle.TEXT, MetaStyle.REGULAR, 2))
        }
        val params = WindowManager.LayoutParams(
            cardWidth(),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (metrics.heightPixels - side) / 2 + px(24f)
        }
        runCatching { windows.addView(panel, params) }
            .onSuccess {
                view = panel
                main.postDelayed(hide, MESSAGE_MS)
            }
            .onFailure { Log.w(TAG, "Couldn't show the snooze note", it) }
    }

    private fun addShade() {
        val black = View(service).apply { setBackgroundColor(Color.BLACK) }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE,
        )
        runCatching { windows.addView(black, params) }
            .onSuccess { shade = black }
            .onFailure { Log.w(TAG, "Couldn't darken behind the banner", it) }
    }

    fun dismiss() {
        main.removeCallbacks(hide)
        view?.let { runCatching { windows.removeView(it) } }
        shade?.let { runCatching { windows.removeView(it) } }
        view = null
        shade = null
        shown = null
        hint = null
        snoozeArmed = false
    }

    /** The Meta toolkit's look: a focused card, the app's round icon, eyebrow, title, message. */
    private fun build(notification: PhoneNotification): View {
        val width = cardWidth()
        val panel = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(16f), px(16f), px(28f), px(16f))
            background = MetaStyle.focused(service, width)
        }
        val avatar: View = if (notification.icon != null) {
            ImageView(service).apply {
                setImageBitmap(notification.icon)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(px(6f), px(6f), px(6f), px(6f))
            }
        } else {
            meta(notification.appName.take(1).uppercase(), 28f, MetaStyle.TEXT, MetaStyle.BOLD, 1).apply {
                gravity = Gravity.CENTER
                background = MetaStyle.circle(service)
            }
        }
        panel.addView(avatar, LinearLayout.LayoutParams(px(64f), px(64f)).apply { marginEnd = px(16f) })
        val texts = LinearLayout(service).apply { orientation = LinearLayout.VERTICAL }
        val app = notification.appName.ifEmpty { service.getString(R.string.notification_phone) }
        texts.addView(meta(service.getString(R.string.notification_eyebrow, app, service.getString(R.string.inbox_now)).uppercase(), 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1))
        if (notification.title.isNotEmpty()) texts.addView(meta(notification.title, 24f, MetaStyle.TEXT, MetaStyle.BOLD, 1))
        val body = if (notification.redacted) service.getString(R.string.notification_hidden) else PhoneNotification.lastLines(notification.text, 2)
        if (body.isNotEmpty()) texts.addView(meta(body, 24f, MetaStyle.TEXT, MetaStyle.REGULAR, 2))
        texts.addView(meta(service.getString(R.string.notification_banner_hint), 18f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR, 1).also { hint = it })
        panel.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        return panel
    }

    /** The banner's width: the HUD's square less the toolkit's 24 px margins. */
    private fun cardWidth(): Int {
        val metrics = service.resources.displayMetrics
        return minOf(metrics.widthPixels, metrics.heightPixels) - px(48f)
    }

    private fun px(value: Float) = MetaStyle.px(service, value)

    private fun meta(text: String, size: Float, color: Int, face: Typeface, lines: Int) = TextView(service).apply {
        this.text = text
        setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(service, size))
        setTextColor(color)
        typeface = face
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
        setLineSpacing(MetaStyle.textPx(service, size * 0.3f), 1f)
        setBackgroundColor(Color.TRANSPARENT)
    }


    @Suppress("DEPRECATION")
    private fun wakeDisplay() {
        val power = service.getSystemService(PowerManager::class.java) ?: return
        if (power.isInteractive) return
        runCatching {
            power.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "Lumen:notification",
            ).acquire(VISIBLE_MS)
            wokeDisplay = true
        }.onFailure { Log.w(TAG, "Couldn't wake the display", it) }
    }

    companion object {
        private const val TAG = "BandBanner"
        const val VISIBLE_MS = 6_000L
        private const val WAKE_SETTLE_MS = 2_000L
        private const val MESSAGE_MS = 2_500L
        private const val NAV_PREFIX = "nav."
    }
}
