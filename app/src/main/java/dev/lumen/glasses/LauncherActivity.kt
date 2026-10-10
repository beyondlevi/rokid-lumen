package dev.lumen.glasses

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.lumen.protocol.GridItem
import java.util.concurrent.Executors

/**
 * The home: the toolkit's SubNavigationPager, natively. Three tabs, Notifications, Apps and
 * Controls, in a pill at the top ([SubNavigationView]); each a page ([NotificationsPage],
 * [AppsPage], [ControlsPage]) in a pager
 * that slides between them as the toolkit's does (the new page from the side, 50 px of overlap,
 * 400 ms, the old one fading in 300). Opened on top of the Rokid launcher (from its icon or a
 * mapped gesture); a banner's index tap opens the Notifications tab on that notification.
 *
 * Focus: on the pill, left and right change the tab and down (or the index tap) goes into the
 * page; in a page, up from its top and left (or right) from its edge come back out, as the
 * toolkit's focus handoff does. While the pill has the focus, the toolkit's scrim darkens the
 * page from the top. The middle tap goes back a level, then to the tabs: it never leaves Lumen
 * (the Rokid's touchpad Back neither). Only the Controls tab's Rokid launcher tile does.
 *
 * Packages pushed to [WebAppPackages.dropFolder] are installed when the home opens; one from an
 * HTTPS URL goes through [InstallConfirmActivity].
 */
class LauncherActivity : Activity(), BandAccessibilityService.InputTarget, NotificationInbox.Listener, GridStore.Listener {
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var side = 0

    private lateinit var pill: SubNavigationView
    private lateinit var scrim: View
    private lateinit var status: TextView
    private lateinit var clock: TextView
    private lateinit var wifi: ImageView
    private val wifiNetworks = HashSet<Network>()
    private val clockReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateClock()
    }
    private val wifiCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            main.post { wifiNetworks += network; updateWifi() }
        }
        override fun onLost(network: Network) {
            main.post { wifiNetworks -= network; updateWifi() }
        }
    }
    private lateinit var notifications: NotificationsPage
    private lateinit var apps: AppsPage
    private lateinit var controls: ControlsPage
    private lateinit var pages: List<HomePage>
    private var tab = TAB_APPS
    private var onPill = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The home is the app's entry: what opening the app used to do, it does here.
        PrivilegedShortcutBridge.ensureReady(this)
        // Shell helpers die with a reboot; opening the app restarts them (R08's launch trigger).
        SelfArmController.armOnLaunch(this)
        requestBluetoothIfMissing()
        val metrics = resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val square = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            clipChildren = true
        }
        root.addView(square, FrameLayout.LayoutParams(side, side, Gravity.CENTER))

        notifications = NotificationsPage(this)
        apps = AppsPage(this) { open(it) }
        controls = ControlsPage(this) { say(it) }
        pages = listOf(notifications, apps, controls)
        pages.forEach { square.addView(it.view, FrameLayout.LayoutParams(side, side)) }

        // The toolkit's navigation scrim: black over the top tenth, fading out down the page.
        scrim = View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Color.BLACK, Color.BLACK, Color.TRANSPARENT)).apply {
                setGradientCenter(0.5f, 0.1f)
            }
            alpha = 0f
        }
        square.addView(scrim, FrameLayout.LayoutParams(side, side))

        pill = SubNavigationView(this, listOf(
            SubNavigationView.Tab(R.drawable.ic_bell, getString(R.string.home_tab_notifications)),
            SubNavigationView.Tab(R.drawable.ic_apps, getString(R.string.home_tab_apps)),
            SubNavigationView.Tab(R.drawable.ic_controls, getString(R.string.home_tab_controls)),
        ))
        square.addView(pill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = px(20f)
        })

        // The time at the top right, level with the tabs, and Wi-Fi's icon left of it while
        // connected (the phone's hotspot for a web app counts): as the glasses' status row.
        clock = TextView(this).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 24f))
            typeface = MetaStyle.MEDIUM
            includeFontPadding = false
            isSingleLine = true
        }
        wifi = ImageView(this).apply {
            setImageResource(R.drawable.ic_wifi)
            setColorFilter(MetaStyle.TEXT, android.graphics.PorterDuff.Mode.SRC_IN)
            contentDescription = getString(R.string.status_wifi)
            visibility = View.GONE
        }
        val statusRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(wifi, LinearLayout.LayoutParams(px(24f), px(24f)).apply { marginEnd = px(8f) })
            addView(clock)
        }
        square.addView(statusRow, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(44f), Gravity.TOP or Gravity.END).apply {
            topMargin = px(20f)
            marginEnd = px(32f)
        })

        status = TextView(this).apply {
            setTextColor(MetaStyle.TEXT)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, 20f))
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(px(16f), px(8f), px(16f), px(8f))
            background = MetaStyle.pill(context)
            visibility = View.GONE
        }
        square.addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = px(20f)
            marginStart = px(24f)
            marginEnd = px(24f)
        })
        setContentView(root)

        tab = requestedTab(intent) ?: lastTab
        lastTab = tab
        showTab(tab, animate = false)
        setPillFocus(false, animate = false)
    }

    /**
     * The tab [intent] asks for: a banner's notification ([EXTRA_KEY], which the Notifications
     * tab opens in full) or a tab by name ([EXTRA_TAB]); null for none. It doesn't change [tab]:
     * the caller shows the tab (a [tab] set here made the switch a no-op, measured on the glasses:
     * a banner's notification opened over the Apps tab while every gesture went to the hidden
     * Notifications page, so nothing answered).
     */
    private fun requestedTab(intent: Intent?): Int? {
        intent ?: return null
        var wanted: Int? = null
        intent.getStringExtra(EXTRA_KEY)?.let { key ->
            notifications.openKey(key)
            wanted = TAB_NOTIFICATIONS
        }
        when (intent.getStringExtra(EXTRA_TAB)) {
            TAB_NAME_NOTIFICATIONS -> wanted = TAB_NOTIFICATIONS
            TAB_NAME_APPS -> wanted = TAB_APPS
            TAB_NAME_CONTROLS -> wanted = TAB_CONTROLS
        }
        return wanted
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val wanted = requestedTab(intent)
        if (wanted != null && wanted != tab) switchTo(wanted, intoPage = true)
        else setPillFocus(false)
    }

    override fun onResume() {
        super.onResume()
        registerReceiver(clockReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateClock()
        wifiNetworks.clear()
        updateWifi()
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerNetworkCallback(
                NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), wifiCallback)
        }
        BandAccessibilityService.setInputTarget(this)
        NotificationInbox.addListener(this)
        GridStore.addListener(this)
        // The self-arm may have finished since, or Rokid's assistant taken the keyboard back.
        KeyboardDefault.apply(this)
        pages[tab].onShow()
        refreshApps()
        refreshDot()
        io.execute {
            val lines = WebAppPackages.importFromDropFolder(this)
            if (lines.isNotEmpty()) main.post {
                say(lines.joinToString(" · "))
                refreshApps()
            }
        }
    }

    override fun onPause() {
        runCatching { unregisterReceiver(clockReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(wifiCallback) }
        BandAccessibilityService.clearInputTarget(this)
        NotificationInbox.removeListener(this)
        GridStore.removeListener(this)
        pages[tab].onHide()
        super.onPause()
    }

    override fun onInboxChanged() = refreshDot()

    private fun updateClock() {
        clock.text = android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date())
    }

    private fun updateWifi() {
        wifi.visibility = if (wifiNetworks.isEmpty()) View.GONE else View.VISIBLE
    }

    override fun onGridChanged() = refreshApps()

    private fun refreshDot() = pill.setDot(TAB_NOTIFICATIONS, tab != TAB_NOTIFICATIONS && NotificationInbox.unread() > 0)

    private fun refreshApps() {
        val webApps = WebAppLibrary.all(this)
        // The phone arranges the grid (GridStore); ids that no longer resolve are skipped.
        val web = webApps.associateBy { GridItem.WEB_PREFIX + it.id }
        val native = GridStore.nativeApps(this)
        val entries = GridStore.layout(this).mapNotNull { id ->
            when {
                id == GridItem.SETTINGS_ID -> AppsPage.Entry.Settings
                id in web -> AppsPage.Entry.App(web.getValue(id))
                id.startsWith(GridItem.APP_PREFIX) -> id.removePrefix(GridItem.APP_PREFIX)
                    .let { pkg -> native[pkg]?.let { AppsPage.Entry.Native(pkg, it) } }
                else -> null
            }
        }
        Log.d(TAG, "apps=${entries.size} notifications=${NotificationInbox.all().size} unread=${NotificationInbox.unread()}")
        apps.setEntries(entries)
        if (webApps.isEmpty() && status.visibility != View.VISIBLE) {
            say(getString(R.string.launcher_empty, WebAppPackages.dropFolder(this)?.path.orEmpty()))
        }
        WebAppIcons.fetchMissing(this, webApps) { main.post { refreshApps() } }
    }

    private val hideStatus = Runnable { status.animate().alpha(0f).setDuration(MetaStyle.FOCUS_MS).withEndAction { status.visibility = View.GONE }.start() }

    private fun say(text: String) {
        status.text = text
        status.alpha = 1f
        status.visibility = View.VISIBLE
        main.removeCallbacks(hideStatus)
        main.postDelayed(hideStatus, STATUS_MS)
    }

    // ---- Tabs and focus ----

    private fun showTab(index: Int, animate: Boolean) {
        pages.forEachIndexed { i, page ->
            page.view.animate().cancel()
            page.view.translationX = 0f
            page.view.alpha = if (i == index) 1f else 0f
            page.view.visibility = if (i == index) View.VISIBLE else View.INVISIBLE
        }
        pill.setActive(index, animate)
        refreshDot()
    }

    /**
     * Slides to tab [to]: the new page in from its side, the old out the other way (offset by the
     * width less 50), as the toolkit's Pager. [intoPage]: the focus lands in the new page (a
     * handoff from the old page's edge); otherwise it stays on the pill.
     */
    private fun switchTo(to: Int, intoPage: Boolean) {
        if (to == tab || to !in pages.indices) return
        val from = tab
        val direction = if (to > from) 1 else -1
        val offset = (side - px(50f)).toFloat()
        val old = pages[from]
        val new = pages[to]
        old.active = false
        old.onHide()
        tab = to
        lastTab = to
        new.view.visibility = View.VISIBLE
        new.view.translationX = direction * offset
        new.view.alpha = 0f
        new.view.animate().translationX(0f).setDuration(SLIDE_MS).setInterpolator(EASE).start()
        new.view.animate().alpha(1f).setDuration(FADE_MS).start()
        old.view.animate().translationX(-direction * offset).alpha(0f).setDuration(SLIDE_MS).setInterpolator(EASE)
            .withEndAction { if (tab != from) old.view.visibility = View.INVISIBLE }.start()
        if (new === controls) controls.resetFocus()
        new.onShow()
        pill.setActive(to)
        refreshDot()
        if (intoPage) setPillFocus(false) else new.active = false
    }

    /** The focus on the pill (the scrim darkens the page) or in the page in front. */
    private fun setPillFocus(focused: Boolean, animate: Boolean = true) {
        onPill = focused
        pill.setPillFocused(focused, animate)
        pages.forEachIndexed { i, page -> page.active = !focused && i == tab }
        val alpha = if (focused) 1f else 0f
        if (animate) scrim.animate().alpha(alpha).setDuration(MetaStyle.FOCUS_MS).setInterpolator(MetaStyle.FOCUS_EASING).start()
        else scrim.alpha = alpha
    }

    override fun onBandCommand(command: String): Boolean {
        Log.d(TAG, "band $command tab=$tab onPill=$onPill")
        if (onPill) {
            when (command) {
                BandCommand.LEFT, BandCommand.BACKWARD -> switchTo(tab - 1, intoPage = false)
                BandCommand.RIGHT, BandCommand.FORWARD -> switchTo(tab + 1, intoPage = false)
                BandCommand.DOWN, BandCommand.ACTIVATE -> setPillFocus(false)
                BandCommand.BACK -> close()
                BandCommand.UP -> Unit
                else -> return false
            }
            return true
        }
        val result = pages[tab].onCommand(command)
        Log.d(TAG, "band $command → ${pages[tab].javaClass.simpleName} $result")
        return when (result) {
            HomeResult.HANDLED -> true
            HomeResult.UP_OUT -> { setPillFocus(true); true }
            HomeResult.LEFT_OUT -> { if (tab > 0) switchTo(tab - 1, intoPage = true); true }
            HomeResult.RIGHT_OUT -> { if (tab < pages.lastIndex) switchTo(tab + 1, intoPage = true); true }
            HomeResult.CLOSE -> { close(); true }
            HomeResult.UNHANDLED -> false
        }
    }

    /** The air mouse: a tab of the pill, or what's under it in the page in front. */
    override fun onPointerTap(x: Float, y: Float) {
        val index = pill.tabAt(x, y)
        if (index >= 0) {
            if (index != tab) switchTo(index, intoPage = true)
            if (onPill) setPillFocus(false)
            return
        }
        if (onPill) setPillFocus(false)
        val handled = pages[tab].onTap(x, y)
        Log.d(TAG, "air mouse tap → ${pages[tab].javaClass.simpleName} $handled")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> BandCommand.UP
            KeyEvent.KEYCODE_DPAD_DOWN -> BandCommand.DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> BandCommand.LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> BandCommand.RIGHT
            KeyEvent.KEYCODE_TAB -> if (event.isShiftPressed) BandCommand.BACKWARD else BandCommand.FORWARD
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> BandCommand.ACTIVATE
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> BandCommand.BACK
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP) onBandCommand(command)
        return true
    }

    /**
     * Back from the top level: the focus goes to the tabs and the home stays. Leaving Lumen for
     * the Rokid launcher is the Controls tab's tile ([SystemControls.openRokidLauncher]).
     */
    private fun close() {
        if (!onPill) setPillFocus(true)
    }

    private fun open(entry: AppsPage.Entry) {
        when (entry) {
            is AppsPage.Entry.App -> WebAppActivity.open(this, entry.app)
            is AppsPage.Entry.Settings -> startActivity(Intent(this, MainActivity::class.java))
            is AppsPage.Entry.Native -> packageManager.getLaunchIntentForPackage(entry.pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { startActivity(it) } }
        }
    }

    /** The band needs Bluetooth; the home is the first screen, so it asks (as MainActivity did). */
    private fun requestBluetoothIfMissing() {
        if (BandRuntime.bluetoothGranted(this)) return
        val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            arrayOf(android.Manifest.permission.BLUETOOTH_SCAN, android.Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        requestPermissions(needed, PERMISSION_REQUEST)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST && BandAccessibilityService.isServiceActive()) BandRuntime.restart(this)
    }

    private fun px(value: Float) = MetaStyle.px(this, value)

    companion object {
        private const val TAG = "BandLauncher"
        private const val PERMISSION_REQUEST = 43
        const val TAB_NOTIFICATIONS = 0
        const val TAB_APPS = 1
        const val TAB_CONTROLS = 2
        /** A notification to open in full on the Notifications tab (a banner's index tap). */
        const val EXTRA_KEY = "key"
        /** "notifications", "apps" or "controls". */
        const val EXTRA_TAB = "tab"
        const val TAB_NAME_NOTIFICATIONS = "notifications"
        const val TAB_NAME_APPS = "apps"
        const val TAB_NAME_CONTROLS = "controls"
        /** The toolkit Pager's page transition: transform 400 ms, opacity 300 ms, CSS `ease`. */
        private const val SLIDE_MS = 400L
        private const val FADE_MS = 300L
        private val EASE = android.view.animation.PathInterpolator(0.25f, 0.1f, 0.25f, 1f)
        private const val STATUS_MS = 6_000L
        /** The tab the home was last on, while the process lives. */
        private var lastTab = TAB_APPS
    }
}
