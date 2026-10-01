package dev.lumen.glasses

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors

/**
 * The MRBD apps grid: the web apps in a 3x3 grid, as Meta Ray-Ban Display's launcher shows them,
 * opened on top of the Rokid launcher (from its icon or a mapped gesture). The first item is
 * the phone's notifications. The band moves the focus in two dimensions, the index tap opens,
 * the middle tap goes back to the Rokid launcher.
 *
 * Packages pushed to [WebAppPackages.dropFolder] are installed when the grid opens; one from
 * an HTTPS URL goes through [InstallConfirmActivity].
 */
class LauncherActivity : Activity(), BandAccessibilityService.InputTarget, NotificationInbox.Listener, GridStore.Listener {
    private sealed class Entry {
        object Notifications : Entry()
        data class App(val app: WebApp) : Entry()
        /** One of the glasses' Android apps, added to the grid from the phone. */
        data class Native(val pkg: String, val label: String) : Entry()
        /** The glasses' own settings (MainActivity): pairing, self-arm, the band's key and log. */
        object Settings : Entry()
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val grid = GridNavigator()
    private var entries: List<Entry> = emptyList()
    private var focused = 0
    private var shownPage = -1

    private lateinit var clock: TextView
    private lateinit var counter: TextView
    private lateinit var cells: GridLayout
    private lateinit var dots: LinearLayout
    private lateinit var status: TextView
    private var side = 0

    private val tick = object : Runnable {
        override fun run() {
            clock.text = DateFormat.getTimeFormat(this@LauncherActivity).format(Date())
            main.postDelayed(this, 20_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // The grid is the app's entry now: what opening the app used to do, it does here.
        PrivilegedShortcutBridge.ensureReady(this)
        // Shell helpers die with a reboot; opening the app restarts them (R08's launch trigger).
        SelfArmController.armOnLaunch(this)
        requestBluetoothIfMissing()
        val metrics = resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val square = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = dp(10f)
            setPadding(pad, pad, pad, pad)
        }
        root.addView(square, FrameLayout.LayoutParams(side, side, Gravity.CENTER))

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        bar.addView(TextView(this).apply {
            text = getString(R.string.launcher_apps)
            setTextColor(HudStyle.TEXT)
            textSize = 15f
            typeface = Typeface.DEFAULT_BOLD
        }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        counter = TextView(this).apply {
            setTextColor(HudStyle.ACCENT)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(6f), dp(1f), dp(6f), dp(1f))
            background = HudStyle.badge(this@LauncherActivity, HudStyle.ACCENT)
            visibility = View.GONE
        }
        bar.addView(counter)
        clock = TextView(this).apply {
            setTextColor(HudStyle.DETAIL)
            textSize = 13f
            setPadding(dp(8f), 0, 0, 0)
        }
        bar.addView(clock)
        square.addView(bar)

        cells = GridLayout(this).apply {
            columnCount = 3
            rowCount = 3
            // The focused cell grows a little past its bounds.
            clipChildren = false
        }
        square.clipChildren = false
        square.clipToPadding = false
        square.addView(cells, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            topMargin = dp(6f)
        })
        status = TextView(this).apply {
            setTextColor(HudStyle.DETAIL)
            textSize = 10f
            gravity = Gravity.CENTER
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        dots = LinearLayout(this).apply { gravity = Gravity.CENTER }
        square.addView(dots, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(10f)))
        square.addView(status)
        setContentView(root)
    }

    override fun onResume() {
        super.onResume()
        BandAccessibilityService.setInputTarget(this)
        NotificationInbox.addListener(this)
        GridStore.addListener(this)
        main.post(tick)
        refresh()
        io.execute {
            val lines = WebAppPackages.importFromDropFolder(this)
            if (lines.isNotEmpty()) main.post {
                status.text = lines.joinToString(" · ")
                refresh()
            }
        }
    }

    override fun onPause() {
        BandAccessibilityService.clearInputTarget(this)
        NotificationInbox.removeListener(this)
        GridStore.removeListener(this)
        main.removeCallbacks(tick)
        super.onPause()
    }

    override fun onInboxChanged() = refresh()

    override fun onGridChanged() = refresh()

    private fun refresh() {
        val apps = WebAppLibrary.all(this)
        // The phone arranges the grid (GridStore); ids that no longer resolve are skipped.
        val web = apps.associateBy { dev.lumen.protocol.GridItem.WEB_PREFIX + it.id }
        val native = GridStore.nativeApps(this)
        entries = GridStore.layout(this).mapNotNull { id ->
            when {
                id == dev.lumen.protocol.GridItem.NOTIFICATIONS_ID -> Entry.Notifications
                id == dev.lumen.protocol.GridItem.SETTINGS_ID -> Entry.Settings
                id in web -> Entry.App(web.getValue(id))
                id.startsWith(dev.lumen.protocol.GridItem.APP_PREFIX) -> id.removePrefix(dev.lumen.protocol.GridItem.APP_PREFIX)
                    .let { pkg -> native[pkg]?.let { Entry.Native(pkg, it) } }
                else -> null
            }
        }
        focused = focused.coerceIn(0, entries.size - 1)
        val unread = NotificationInbox.unread()
        Log.d(TAG, "refresh apps=${apps.size} notifications=${NotificationInbox.all().size} unread=$unread")
        counter.visibility = if (unread > 0) View.VISIBLE else View.GONE
        counter.text = unread.toString()
        if (status.text.isNullOrEmpty() && apps.isEmpty()) {
            status.text = getString(R.string.launcher_empty, WebAppPackages.dropFolder(this)?.path.orEmpty())
        }
        shownPage = -1
        render()
        WebAppIcons.fetchMissing(this, apps) { main.post { refresh() } }
    }

    private fun render() {
        val page = grid.pageOf(focused)
        if (page != shownPage) {
            shownPage = page
            cells.removeAllViews()
            val start = page * grid.pageSize
            val cellSide = (side - dp(20f)) / 3
            val cellHeight = (side - dp(20f) - dp(44f)) / 3
            for (i in 0 until grid.pageSize) {
                val params = GridLayout.LayoutParams(
                    GridLayout.spec(i / 3), GridLayout.spec(i % 3),
                ).apply {
                    width = cellSide
                    height = cellHeight
                }
                val entry = entries.getOrNull(start + i)
                cells.addView(if (entry == null) View(this) else cell(entry), params)
            }
            renderDots(page)
        }
        val start = page * grid.pageSize
        for (i in 0 until cells.childCount) {
            val view = cells.getChildAt(i)
            val isFocused = start + i == focused
            if (view is LinearLayout) {
                view.background = HudStyle.outline(this, isFocused)
                view.scaleX = if (isFocused) 1.06f else 1f
                view.scaleY = view.scaleX
            }
        }
    }

    private fun renderDots(page: Int) {
        dots.removeAllViews()
        val pages = grid.pageCount(entries.size)
        if (pages < 2) return
        for (p in 0 until pages) {
            dots.addView(View(this).apply {
                background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(if (p == page) HudStyle.TEXT else HudStyle.MUTED)
                }
            }, LinearLayout.LayoutParams(dp(6f), dp(6f)).apply { marginStart = dp(3f); marginEnd = dp(3f) })
        }
    }

    private fun cell(entry: Entry): View {
        val cell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(4f), dp(4f), dp(4f), dp(4f))
        }
        val iconSide = dp(52f)
        val iconFrame = FrameLayout(this)
        val label: String
        when (entry) {
            is Entry.Notifications -> {
                label = getString(R.string.launcher_notifications)
                iconFrame.addView(TextView(this).apply {
                    text = "N"
                    gravity = Gravity.CENTER
                    setTextColor(Color.BLACK)
                    textSize = 22f
                    typeface = Typeface.DEFAULT_BOLD
                    background = android.graphics.drawable.GradientDrawable().apply {
                        setColor(HudStyle.ACCENT)
                        cornerRadius = dp(14f).toFloat()
                    }
                }, FrameLayout.LayoutParams(iconSide, iconSide))
                val unread = NotificationInbox.unread()
                if (unread > 0) iconFrame.addView(corner(unread.toString(), HudStyle.WARN), cornerParams())
            }
            is Entry.Settings -> {
                label = getString(R.string.launcher_settings)
                iconFrame.addView(ImageView(this).apply {
                    setImageResource(R.drawable.ic_settings)
                    imageTintList = android.content.res.ColorStateList.valueOf(HudStyle.TEXT)
                    scaleType = ImageView.ScaleType.CENTER_INSIDE
                    setPadding(dp(12f), dp(12f), dp(12f), dp(12f))
                    background = HudStyle.badge(this@LauncherActivity, HudStyle.DETAIL)
                }, FrameLayout.LayoutParams(iconSide, iconSide))
            }
            is Entry.Native -> {
                label = entry.label
                iconFrame.addView(ImageView(this).apply {
                    runCatching { setImageDrawable(packageManager.getApplicationIcon(entry.pkg)) }
                    scaleType = ImageView.ScaleType.FIT_CENTER
                }, FrameLayout.LayoutParams(iconSide, iconSide))
            }
            is Entry.App -> {
                val app = entry.app
                label = app.name
                val bitmap = WebAppIcons.load(app)
                iconFrame.addView(if (bitmap != null) {
                    ImageView(this).apply {
                        setImageBitmap(bitmap)
                        scaleType = ImageView.ScaleType.FIT_CENTER
                    }
                } else {
                    TextView(this).apply {
                        text = app.name.trim().take(1).uppercase()
                        gravity = Gravity.CENTER
                        setTextColor(HudStyle.TEXT)
                        textSize = 22f
                        typeface = Typeface.DEFAULT_BOLD
                        background = HudStyle.letterTile(this@LauncherActivity, app.name)
                    }
                }, FrameLayout.LayoutParams(iconSide, iconSide))
                if (app.offline) iconFrame.addView(corner(getString(R.string.launcher_offline), HudStyle.ACCENT), cornerParams())
            }
        }
        cell.addView(iconFrame, LinearLayout.LayoutParams(iconSide + dp(10f), iconSide + dp(6f)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        (iconFrame.getChildAt(0).layoutParams as FrameLayout.LayoutParams).gravity = Gravity.CENTER
        cell.addView(TextView(this).apply {
            text = label
            setTextColor(HudStyle.TEXT)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setPadding(0, dp(3f), 0, 0)
        })
        return cell
    }

    private fun corner(text: String, color: Int) = TextView(this).apply {
        this.text = text
        setTextColor(color)
        textSize = 8f
        typeface = Typeface.DEFAULT_BOLD
        setPadding(dp(3f), 0, dp(3f), 0)
        background = HudStyle.badge(this@LauncherActivity, color)
    }

    private fun cornerParams() = FrameLayout.LayoutParams(
        FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END,
    )

    override fun onBandCommand(command: String): Boolean = when (command) {
        BandCommand.ACTIVATE -> { open(); true }
        BandCommand.BACK -> { finish(); true }
        BandCommand.FORWARD, BandCommand.BACKWARD, BandCommand.UP, BandCommand.DOWN,
        BandCommand.LEFT, BandCommand.RIGHT -> { move(command); true }
        else -> false
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

    private fun move(command: String) {
        val next = grid.move(focused, entries.size, command)
        if (next != focused) {
            focused = next
            render()
        }
    }

    private fun open() {
        when (val entry = entries.getOrNull(focused) ?: return) {
            is Entry.Notifications -> startActivity(Intent(this, NotificationInboxActivity::class.java))
            is Entry.App -> WebAppActivity.open(this, entry.app)
            is Entry.Settings -> startActivity(Intent(this, MainActivity::class.java))
            is Entry.Native -> packageManager.getLaunchIntentForPackage(entry.pkg)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?.let { runCatching { startActivity(it) } }
        }
    }

    /** The band needs Bluetooth; the grid is the first screen, so it asks (as MainActivity did). */
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

    private fun dp(value: Float) = HudStyle.dp(this, value)

    companion object {
        private const val TAG = "BandLauncher"
        private const val PERMISSION_REQUEST = 43
    }
}
