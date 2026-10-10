package dev.lumen.glasses

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.lumen.protocol.BandDevices
import dev.lumen.protocol.DeviceProfile

/**
 * Where the band goes, from the glasses (the Controls tab's Band tile, or a gesture mapped to
 * [GestureChoices.DEVICES]): first the device (the glasses, the phone, a computer the phone has
 * been a keyboard for), then, for the phone or a computer, the profile it goes there with. The
 * lists come from the phone ([BandSwitch.devices]); the move itself is [BandSwitch]'s.
 *
 * Swipes move the focus, the index tap chooses, the middle tap goes back a step (and out).
 */
class BandDeviceActivity : Activity(), BandAccessibilityService.InputTarget {
    private class Item(val icon: Int, val title: String, val detail: String, val current: Boolean, val next: Boolean, val choose: () -> Unit)

    private class Row(val frame: FrameLayout, val item: Item)

    private var side = 0
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var list: LinearLayout
    private var rows: List<Row> = emptyList()
    private var focus = 0
    /** The profile step: the target and the computer's address. */
    private var step: Pair<String, String>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val metrics = resources.displayMetrics
        side = minOf(metrics.widthPixels, metrics.heightPixels)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        val square = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(px(36f), px(48f), px(36f), 0)
        }
        root.addView(square, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        title = text(32f, MetaStyle.TEXT, MetaStyle.BOLD)
        subtitle = text(20f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR).apply { setPadding(0, px(4f), 0, px(16f)) }
        square.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })
        square.addView(subtitle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = px(8f) })
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        square.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        val hint = text(18f, MetaStyle.TEXT_PLACEHOLDER, MetaStyle.REGULAR).apply {
            gravity = Gravity.CENTER
            setText(R.string.band_switch_hint)
            setPadding(0, 0, 0, px(24f))
        }
        square.addView(hint, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        setContentView(root)
        showDevices()
    }

    override fun onResume() {
        super.onResume()
        BandAccessibilityService.setInputTarget(this)
    }

    override fun onPause() {
        BandAccessibilityService.clearInputTarget(this)
        super.onPause()
    }

    // ---- The steps ----

    private fun showDevices() {
        step = null
        val devices = BandSwitch.devices
        val where = BandSwitch.where(this)
        val items = mutableListOf<Item>()
        items += Item(R.drawable.ic_glasses, getString(R.string.band_switch_glasses), getString(R.string.band_switch_glasses_detail), where == BandDevices.GLASSES, false) {
            BandSwitch.toGlasses(this)
            finish()
        }
        val phoneHere = where == BandDevices.PHONE
        items += Item(R.drawable.ic_phone, getString(R.string.band_switch_phone), profileLine(devices?.phoneProfiles, devices?.phoneProfile, phoneHere), phoneHere, true) {
            if (devices == null || devices.phoneProfiles.isEmpty()) send(BandDevices.PHONE, "", "") else showProfiles(BandDevices.PHONE, "")
        }
        devices?.computers.orEmpty().forEach { computer ->
            val here = where == BandDevices.COMPUTER && devices?.computer == computer.address
            items += Item(R.drawable.ic_laptop, computer.name, profileLine(devices?.computerProfiles, devices?.computerProfile, here), here, true) {
                if (devices == null || devices.computerProfiles.isEmpty()) send(BandDevices.COMPUTER, computer.address, "")
                else showProfiles(BandDevices.COMPUTER, computer.address)
            }
        }
        show(getString(R.string.band_switch_title), getString(R.string.band_switch_subtitle), items)
        focus = items.indexOfFirst { it.current }.let { if (it < 0) 0 else if (items.size > it + 1) it + 1 else it }
        applyFocus()
    }

    private fun showProfiles(target: String, computer: String) {
        val devices = BandSwitch.devices ?: return send(target, computer, "")
        step = target to computer
        val profiles = if (target == BandDevices.COMPUTER) devices.computerProfiles else devices.phoneProfiles
        val active = if (target == BandDevices.COMPUTER) devices.computerProfile else devices.phoneProfile
        val here = BandSwitch.where(this) == target && (target != BandDevices.COMPUTER || devices.computer == computer)
        val items = profiles.map { profile ->
            Item(kindIcon(target), profile.name, kindText(profile), here && profile.id == active, false) { send(target, computer, profile.id) }
        }
        val name = devices.computers.firstOrNull { it.address == computer }?.name ?: getString(R.string.band_switch_computer)
        val heading = if (target == BandDevices.COMPUTER) getString(R.string.band_switch_profile_computer, name) else getString(R.string.band_switch_profile_phone)
        show(heading, getString(R.string.band_switch_profile_subtitle), items)
        focus = profiles.indexOfFirst { it.id == active }.coerceAtLeast(0)
        applyFocus()
    }

    private fun send(target: String, computer: String, profile: String) {
        BandSwitch.send(this, target, computer, profile)
        finish()
    }

    private fun profileLine(profiles: List<DeviceProfile>?, active: String?, here: Boolean): String {
        val name = profiles?.firstOrNull { it.id == active }?.name ?: return getString(R.string.band_switch_profile_unknown)
        return getString(if (here) R.string.band_switch_profile_in_use else R.string.band_switch_profile_last, name)
    }

    private fun kindIcon(target: String) = if (target == BandDevices.COMPUTER) R.drawable.ic_laptop else R.drawable.ic_phone

    private fun kindText(profile: DeviceProfile) = getString(
        when (profile.kind) {
            "media" -> R.string.profile_kind_media
            "navigation" -> R.string.profile_kind_navigation
            "notebook" -> R.string.profile_kind_notebook
            "presentation" -> R.string.profile_kind_presentation
            else -> R.string.profile_kind_custom
        },
    )

    // ---- The list ----

    private fun show(heading: String, detail: String, items: List<Item>) {
        title.text = heading
        subtitle.text = detail
        list.removeAllViews()
        rows = items.map { item -> Row(row(item), item) }
        rows.forEachIndexed { i, row ->
            list.addView(row.frame, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(ROW)).apply { if (i > 0) topMargin = px(GAP) })
        }
    }

    private fun row(item: Item): FrameLayout {
        val frame = FrameLayout(this).apply { clipChildren = false }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(22f), 0, px(22f), 0)
        }
        val circle = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.BLACK)
                setStroke(maxOf(1, px(2f)), Color.argb(90, 255, 255, 255))
            }
        }
        circle.addView(icon(item.icon, MetaStyle.TEXT), FrameLayout.LayoutParams(px(28f), px(28f), Gravity.CENTER))
        content.addView(circle, LinearLayout.LayoutParams(px(64f), px(64f)))
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(24f, MetaStyle.TEXT, MetaStyle.BOLD).apply { text = item.title })
        if (item.detail.isNotEmpty()) texts.addView(text(19f, MetaStyle.TEXT_SECONDARY, MetaStyle.REGULAR).apply {
            text = item.detail
            setPadding(0, px(4f), 0, 0)
        })
        content.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = px(18f) })
        if (item.current) {
            content.addView(text(19f, NOW, MetaStyle.MEDIUM).apply { setText(R.string.band_switch_now) })
        } else if (item.next) {
            content.addView(icon(R.drawable.ic_chevron, MetaStyle.TEXT_SECONDARY), LinearLayout.LayoutParams(px(26f), px(26f)))
        }
        frame.addView(content, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        return frame
    }

    private fun applyFocus() {
        rows.forEachIndexed { index, row ->
            val width = row.frame.width.takeIf { it > 0 } ?: (side - 2 * px(36f))
            row.frame.background = if (index == focus) MetaStyle.focused(this, width) else MetaStyle.outline(this, alpha = 70)
        }
    }

    private fun choose(index: Int) {
        val row = rows.getOrNull(index) ?: return
        if (row.item.current && !row.item.next) {
            finish()
            return
        }
        row.item.choose()
    }

    private fun back() {
        if (step != null) showDevices() else finish()
    }

    override fun onBandCommand(command: String): Boolean {
        when (command) {
            BandCommand.DOWN, BandCommand.RIGHT, BandCommand.FORWARD -> move(1)
            BandCommand.UP, BandCommand.LEFT, BandCommand.BACKWARD -> move(-1)
            BandCommand.ACTIVATE -> choose(focus)
            BandCommand.BACK -> back()
            else -> return false
        }
        return true
    }

    private fun move(delta: Int) {
        if (rows.isEmpty()) return
        focus = (focus + delta).coerceIn(0, rows.lastIndex)
        applyFocus()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val command = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> BandCommand.UP
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> BandCommand.DOWN
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> BandCommand.ACTIVATE
            KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_ESCAPE -> BandCommand.BACK
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_UP) onBandCommand(command)
        return true
    }

    /** The air mouse: the row under it. */
    override fun onPointerTap(x: Float, y: Float) {
        val index = rows.indexOfFirst { it.frame.isUnder(x, y) }
        if (index < 0) return
        focus = index
        applyFocus()
        choose(index)
    }

    private fun text(size: Float, color: Int, face: android.graphics.Typeface) = TextView(this).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(this@BandDeviceActivity, size))
        typeface = face
        isSingleLine = true
        ellipsize = TextUtils.TruncateAt.END
        includeFontPadding = false
    }

    private fun icon(res: Int, color: Int) = ImageView(this).apply {
        setImageResource(res)
        setColorFilter(color, PorterDuff.Mode.SRC_IN)
    }

    private fun px(value: Float) = MetaStyle.px(this, value)

    companion object {
        private const val ROW = 108f
        private const val GAP = 14f
        private val NOW = Color.parseColor("#66F2A5")

        /** Opens the chooser over whatever is in front (from the accessibility service too). */
        @JvmStatic
        fun open(context: Context) {
            context.startActivity(Intent(context, BandDeviceActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}
