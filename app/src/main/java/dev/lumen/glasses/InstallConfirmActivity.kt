package dev.lumen.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/**
 * What an install will add, shown before anything is installed: the app's name and where it
 * comes from, offline package or online app, whether it asks for the internet
 * (`lumen_internet`), its settings (`lumen_config`), and the installed app it replaces (same
 * manifest id, or same URL), which keeps that app's port and data.
 */
data class InstallPreview(
    val name: String,
    /** The host the app or package comes from. */
    val source: String,
    val offline: Boolean,
    val version: String,
    val internet: Boolean,
    /** The labels of the settings it asks for. */
    val settings: List<String>,
    /** The name of the installed app it updates, or null for a new app. */
    val updates: String?,
    /** The update comes from elsewhere than the installed app: its saved secrets will be forgotten. */
    val clearsSecrets: Boolean = false,
) {
    companion object {
        /** An online app at [url] (HTTPS), named [name] or after its host. */
        @JvmStatic
        fun forWebApp(context: Context, url: String, name: String?): InstallPreview {
            val host = Uri.parse(url).host.orEmpty()
            return InstallPreview(
                name = name?.trim()?.takeIf { it.isNotEmpty() } ?: host.ifEmpty { url },
                source = host,
                offline = false,
                version = "",
                internet = true,
                settings = emptyList(),
                updates = WebAppLibrary.findByUrl(context, url)?.name,
            )
        }

        /** A downloaded package, [WebAppPackages.stage]d from [url]. */
        @JvmStatic
        fun forPackage(context: Context, staged: WebAppPackages.StagedPackage, url: String): InstallPreview = InstallPreview(
            name = staged.name,
            source = Uri.parse(url).host.orEmpty(),
            offline = true,
            version = staged.version,
            internet = staged.internet,
            settings = staged.configFields.map { it.label.ifEmpty { it.key } },
            updates = WebAppLibrary.find(context, staged.id)?.name,
            clearsSecrets = WebAppPackages.clearsSecrets(context, staged),
        )
    }
}

/**
 * The glasses' confirmation for installs asked for from outside: an offline package by URL
 * ([ACTION_INSTALL_PACKAGE], from adb or another app on the glasses), an online app by URL
 * (`VIEW`), or a page's `navigator.install()` ([WebAppActivity]). Nothing is installed until the
 * user activates Install with the band (or the touchpad); Back or Cancel drops it. A package is
 * downloaded first, into a staging folder, to read its manifest; cancelled, it's deleted.
 *
 * Installs from the phone companion ([GridApi]) don't come here: the user acted on the phone.
 *
 * The Meta look of the other native screens ([MetaStyle]): outlines on black, since the HUD is
 * additive and anything filled lights up.
 *
 *     adb shell am start -n dev.lumen.glasses/.InstallConfirmActivity \
 *       -a dev.lumen.glasses.INSTALL_PACKAGE -d https://…/app.mrbd.zip
 *     adb shell am start -n dev.lumen.glasses/.InstallConfirmActivity \
 *       -a android.intent.action.VIEW -d https://… [--es name "An app"]
 */
class InstallConfirmActivity : Activity(), BandAccessibilityService.InputTarget {
    private enum class Kind { WEB, PACKAGE }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var kind: Kind
    private lateinit var url: String
    private var requestedName: String? = null
    /** Open an online app once added (a page's own install leaves the user where they are). */
    private var openAfter = true

    private var preview: InstallPreview? = null
    private var staged: WebAppPackages.StagedPackage? = null
    private var internet: PhoneInternet.Listener? = null
    private var downloading = false
    private var installing = false
    private var failed = false
    /** Cancelled or installed: a download that ends later is thrown away. */
    private var finished = false
    private var focus = CANCEL

    private var side = 0
    private lateinit var iconFrame: FrameLayout
    private lateinit var nameView: TextView
    private lateinit var sourceView: TextView
    private lateinit var details: LinearLayout
    private lateinit var status: TextView
    private lateinit var cancelButton: TextView
    private lateinit var installButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!readRequest(intent)) {
            finish()
            return
        }
        buildViews()
        when (kind) {
            Kind.WEB -> showPreview(InstallPreview.forWebApp(this, url, requestedName), null)
            Kind.PACKAGE -> download()
        }
    }

    /** What to install, from the intent; false (with a notice) for anything but an HTTPS URL. */
    private fun readRequest(intent: Intent?): Boolean {
        val action = intent?.action
        kind = when (action) {
            ACTION_INSTALL_PACKAGE -> Kind.PACKAGE
            Intent.ACTION_VIEW -> Kind.WEB
            else -> return false
        }
        url = intent.dataString ?: intent.getStringExtra(EXTRA_URL) ?: return false
        requestedName = intent.getStringExtra(EXTRA_NAME)
        openAfter = intent.getBooleanExtra(EXTRA_OPEN, true)
        if (!WebAppLibrary.isAcceptable(url)) {
            Toast.makeText(this, if (kind == Kind.PACKAGE) R.string.package_https_only else R.string.webapp_https_apps_only, Toast.LENGTH_LONG).show()
            return false
        }
        Log.d(TAG, "Install asked: $kind from ${Uri.parse(url).host}")
        return true
    }

    override fun onResume() {
        super.onResume()
        BandAccessibilityService.setInputTarget(this)
    }

    override fun onPause() {
        BandAccessibilityService.clearInputTarget(this)
        super.onPause()
    }

    override fun onDestroy() {
        if (!installing) drop()
        super.onDestroy()
    }

    // The package: downloaded (through the phone's internet when the glasses have none) and
    // unpacked into a staging folder, to show what it is.

    private fun download() {
        showStatus(getString(R.string.launcher_downloading))
        val listener = object : PhoneInternet.Listener {
            override fun onStatus(text: String) {
                if (!downloading) showStatus(text)
            }

            override fun onReady(proxy: String?) {
                if (downloading || finished) return
                downloading = true
                showStatus(getString(R.string.launcher_downloading))
                io.execute {
                    val result = runCatching { WebAppPackages.download(applicationContext, url, proxy) }
                    main.post { downloaded(result) }
                }
            }

            override fun onFailed(text: String) {
                if (downloading) return
                releaseInternet()
                fail(getString(R.string.launcher_not_installed, text))
            }
        }
        internet = listener
        PhoneInternet.acquire(this, listener)
    }

    private fun downloaded(result: Result<WebAppPackages.StagedPackage>) {
        releaseInternet()
        val pkg = result.getOrElse { error ->
            Log.w(TAG, "Package not staged: ${error.message}")
            if (!finished) fail(getString(R.string.launcher_not_installed, WebAppPackages.describe(this, error)))
            return
        }
        if (finished || isDestroyed) {
            io.execute { WebAppPackages.discard(pkg) }
            return
        }
        staged = pkg
        showPreview(InstallPreview.forPackage(this, pkg, url), pkg.icon)
    }

    private fun releaseInternet() {
        internet?.let { PhoneInternet.release(it) }
        internet = null
    }

    // Install and Cancel.

    private fun install() {
        val shown = preview ?: return
        if (installing || finished) return
        installing = true
        showStatus(getString(R.string.install_installing))
        renderButtons()
        when (kind) {
            Kind.WEB -> {
                val app = WebAppLibrary.add(this, url, requestedName)
                installed(app, if (app == null) getString(R.string.webapp_https_apps_only) else getString(R.string.webapp_added, app.name))
            }
            Kind.PACKAGE -> {
                val pkg = staged ?: return
                staged = null
                io.execute {
                    val result = runCatching { WebAppPackages.commit(applicationContext, pkg) }
                    main.post {
                        result.fold(
                            { installed(it, getString(R.string.launcher_installed, it.name)) },
                            { installed(null, getString(R.string.launcher_not_installed, WebAppPackages.describe(this, it))) },
                        )
                    }
                }
            }
        }
        Log.d(TAG, "Install confirmed: ${shown.name}")
    }

    private fun installed(app: WebApp?, line: String) {
        finished = true
        Toast.makeText(this, line, Toast.LENGTH_SHORT).show()
        if (app != null) {
            GridApi.pushState()
            GridStore.notifyChanged()
            if (kind == Kind.WEB && openAfter) WebAppActivity.open(this, app)
        }
        finish()
    }

    private fun cancel() {
        if (installing) return
        Log.d(TAG, "Install cancelled")
        drop()
        finish()
    }

    /** Nothing gets installed: the staged package goes, and so does the borrowed internet. */
    private fun drop() {
        finished = true
        releaseInternet()
        staged?.let { pkg -> io.execute { WebAppPackages.discard(pkg) } }
        staged = null
    }

    // The band: any swipe moves between Cancel and Install, the index tap presses, the middle
    // tap cancels. Cancel has the focus first, so a stray tap never installs.

    override fun onBandCommand(command: String): Boolean {
        when (command) {
            BandCommand.ACTIVATE -> if (focus == INSTALL && canInstall()) install() else cancel()
            BandCommand.BACK -> cancel()
            BandCommand.LEFT, BandCommand.RIGHT, BandCommand.UP, BandCommand.DOWN,
            BandCommand.FORWARD, BandCommand.BACKWARD -> move()
            else -> return false
        }
        return true
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

    private fun canInstall() = preview != null && !installing && !failed && !finished

    private fun move() {
        if (!canInstall()) return
        focus = if (focus == CANCEL) INSTALL else CANCEL
        renderButtons()
    }

    // The screen.

    private fun buildViews() {
        val metrics = resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        val root = FrameLayout(this).apply {
            setBackgroundColor(MetaStyle.WINDOW)
            // No confirming through something drawn over this screen.
            filterTouchesWhenObscured = true
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(32f), px(24f), px(32f), px(24f))
        }
        root.addView(column, FrameLayout.LayoutParams(side, side, Gravity.CENTER))

        val chip = text(getString(R.string.install_title), 22f, MetaStyle.TEXT, MetaStyle.MEDIUM, 1).apply {
            gravity = Gravity.CENTER
            setPadding(px(16f), 0, px(16f), 0)
            background = MetaStyle.pill(context)
        }
        column.addView(chip, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(44f)).apply { gravity = Gravity.CENTER_HORIZONTAL })

        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        iconFrame = FrameLayout(this)
        head.addView(iconFrame, LinearLayout.LayoutParams(px(64f), px(64f)).apply { marginEnd = px(16f) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        nameView = text("", 28f, MetaStyle.TEXT, MetaStyle.BOLD, 2)
        sourceView = text("", 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1)
        names.addView(nameView)
        names.addView(sourceView)
        head.addView(names, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        column.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(20f) })

        details = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(20f), px(10f), px(20f), px(10f))
            background = MetaStyle.outline(context, 24f)
            visibility = View.GONE
        }
        column.addView(details, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(16f) })

        status = text("", 22f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 3)
        column.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(12f) })

        column.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        cancelButton = button(getString(R.string.install_cancel))
        installButton = button(getString(R.string.install_button))
        buttons.addView(cancelButton, LinearLayout.LayoutParams(0, px(72f), 1f).apply { marginEnd = px(8f) })
        buttons.addView(installButton, LinearLayout.LayoutParams(0, px(72f), 1f).apply { marginStart = px(8f) })
        column.addView(buttons, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        column.addView(text(getString(R.string.install_hint), 18f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR, 1).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = px(10f) })

        setContentView(root)
        val host = Uri.parse(url).host.orEmpty()
        nameView.text = requestedName?.trim()?.takeIf { it.isNotEmpty() } ?: host
        sourceView.text = host
        showIcon(null, nameView.text.toString())
        renderButtons()
    }

    private fun showPreview(shown: InstallPreview, iconFile: File?) {
        preview = shown
        nameView.text = shown.name
        sourceView.text = shown.source
        showIcon(iconFile?.let { decodeIcon(it) }, shown.name)
        details.removeAllViews()
        shown.updates?.let { details.addView(detail(getString(R.string.install_updates, it), MetaStyle.TEXT, MetaStyle.BOLD)) }
        if (shown.clearsSecrets) details.addView(detail(getString(R.string.install_clears_secrets), MetaStyle.TEXT, MetaStyle.BOLD))
        details.addView(detail(when {
            !shown.offline -> getString(R.string.install_online)
            shown.version.isNotEmpty() -> getString(R.string.install_offline_version, shown.version)
            else -> getString(R.string.install_offline)
        }))
        details.addView(detail(getString(if (shown.internet) R.string.install_internet else R.string.install_no_internet)))
        if (shown.settings.isNotEmpty()) details.addView(detail(getString(R.string.install_settings, shown.settings.joinToString(", "))))
        details.visibility = View.VISIBLE
        showStatus("")
        renderButtons()
    }

    private fun fail(message: String) {
        failed = true
        showStatus(message)
        status.setTextColor(MetaStyle.TEXT)
        focus = CANCEL
        renderButtons()
    }

    private fun showStatus(text: String) {
        status.text = text
        status.visibility = if (text.isEmpty()) View.GONE else View.VISIBLE
    }

    private fun renderButtons() {
        if (!canInstall()) focus = CANCEL
        cancelButton.text = getString(if (failed) R.string.install_close else R.string.install_cancel)
        listOf(cancelButton to CANCEL, installButton to INSTALL).forEach { (view, index) ->
            val focused = index == focus
            view.background = if (focused) MetaStyle.focused(this, side, 36f) else MetaStyle.outline(this, 36f)
            view.typeface = if (focused) MetaStyle.BOLD else MetaStyle.MEDIUM
        }
        installButton.alpha = if (canInstall()) 1f else 0.35f
    }

    private fun showIcon(bitmap: Bitmap?, name: String) {
        iconFrame.removeAllViews()
        val view = if (bitmap != null) {
            ImageView(this).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
        } else {
            text(name.trim().take(1).uppercase(), 28f, MetaStyle.TEXT, MetaStyle.BOLD, 1).apply {
                gravity = Gravity.CENTER
                background = MetaStyle.circle(context)
            }
        }
        iconFrame.addView(view, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    /** The package's icon, scaled down while decoding (a package could ship a huge one). */
    private fun decodeIcon(file: File): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= ICON_PX && bounds.outHeight / (sample * 2) >= ICON_PX) sample *= 2
        BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    private fun detail(value: String, color: Int = MetaStyle.TEXT_SECONDARY, face: android.graphics.Typeface = MetaStyle.REGULAR) =
        text(value, 22f, color, face, 2).apply { setPadding(0, px(4f), 0, px(4f)) }

    private fun button(label: String) = text(label, 24f, MetaStyle.TEXT, MetaStyle.MEDIUM, 1).apply {
        gravity = Gravity.CENTER
    }

    private fun text(value: String, size: Float, color: Int, face: android.graphics.Typeface, lines: Int) = TextView(this).apply {
        text = value
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(context, size))
        setTextColor(color)
        typeface = face
        maxLines = lines
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun px(value: Float) = MetaStyle.px(this, value)

    companion object {
        private const val TAG = "BandInstall"
        private const val CANCEL = 0
        private const val INSTALL = 1
        private const val ICON_PX = 128

        /** An offline package to download and install: `-a dev.lumen.glasses.INSTALL_PACKAGE -d https://…`. */
        const val ACTION_INSTALL_PACKAGE = "dev.lumen.glasses.INSTALL_PACKAGE"
        const val EXTRA_URL = "url"
        const val EXTRA_NAME = "name"
        /** False: stay where the request came from once the online app is added. */
        const val EXTRA_OPEN = "open"

        /** Downloads, unpacks and installs, one at a time; never shut down (a staged package may still need deleting). */
        private val io = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "nb-install").apply { isDaemon = true } }

        /**
         * A page's `navigator.install(url)`: asks the user on the glasses, then adds [url] (HTTPS
         * only) without opening it.
         */
        @JvmStatic
        fun confirmWebApp(context: Context, url: String, name: String?) {
            if (!WebAppLibrary.isAcceptable(url)) {
                Toast.makeText(context, R.string.webapp_https_apps_only, Toast.LENGTH_SHORT).show()
                return
            }
            val intent = Intent(context, InstallConfirmActivity::class.java)
                .setAction(Intent.ACTION_VIEW)
                .setData(Uri.parse(url))
                .putExtra(EXTRA_NAME, name)
                .putExtra(EXTRA_OPEN, false)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
