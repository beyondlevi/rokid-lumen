package dev.lumen.glasses

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import dev.lumen.band.Phase
import org.json.JSONObject

/**
 * MRBD's on-glasses composer, for the Rokid host: activating a text field opens it (the shim
 * catches Enter on the field), and the text goes back to the field through `input` events,
 * then `change` when it closes. The page never sees the microphone or the band.
 *
 * It opens on a choice: dictate, or write with the band (its handwriting model, see
 * [BandRuntime.setHandwriting]); the last one used comes preselected. A swipe switches, the
 * index tap starts, the middle tap cancels.
 *
 * Dictating, the band drives it: index tap pauses or resumes listening, a left swipe deletes
 * the last word, the middle tap finishes. Writing, the band reads every stroke as writing and
 * only its middle tap gets through (the bridge drops the rest): it finishes, and the band goes
 * back to normal. A pause in the writing finishes too. The band gives no haptic feedback while
 * it writes, so the panel shows each letter as it comes.
 */
class WebComposer(private val activity: Activity, parent: FrameLayout, side: Int) {
    /** Where the text goes: the engine forwards it to the page's shim. */
    interface Target {
        fun composerInput(text: String)
        fun composerClose()
    }

    private enum class Mode { CHOOSING, DICTATING, WRITING }

    private val density = activity.resources.displayMetrics.density
    private val main = Handler(Looper.getMainLooper())
    private val panel = LinearLayout(activity)
    private val status = TextView(activity)
    private val choices = LinearLayout(activity)
    private val dictateChoice = TextView(activity)
    private val writeChoice = TextView(activity)
    private val text = TextView(activity)
    private val hint = TextView(activity)
    private var target: Target? = null
    private var mode = Mode.CHOOSING
    /** The choice the swipes move: true is Write. */
    private var writeSelected = false
    private var buffer = ""
    private var partial = ""
    /** Closing, waiting for the last phrase; a second Back closes at once. */
    private var closing = false
    private val handwriting = BandRuntime.HandwritingListener { onHandwriting(it) }
    private val idleCheck = Runnable { checkIdle() }
    private var lastWriting = 0L
    /** Something was written since the band got ready (the first letter gets longer). */
    private var wrote = false

    val isOpen get() = target != null

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), dp(12), dp(16), dp(12))
        panel.background = GradientDrawable().apply {
            setColor(Color.rgb(16, 22, 19))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), ACCENT)
        }
        status.setTextColor(ACCENT)
        status.textSize = 12f
        status.typeface = Typeface.DEFAULT_BOLD
        choices.orientation = LinearLayout.HORIZONTAL
        choices.gravity = Gravity.CENTER
        for ((view, label) in listOf(dictateChoice to R.string.composer_mode_dictate, writeChoice to R.string.composer_mode_write)) {
            view.text = activity.getString(label)
            view.textSize = 15f
            view.gravity = Gravity.CENTER
            view.setPadding(dp(14), dp(8), dp(14), dp(8))
            choices.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(dp(4), dp(8), dp(4), dp(4))
            })
        }
        text.setTextColor(Color.rgb(248, 250, 249))
        text.textSize = 17f
        text.maxLines = 5
        text.minHeight = dp(48)
        hint.setTextColor(Color.rgb(161, 183, 172))
        hint.textSize = 10f
        panel.addView(status)
        panel.addView(choices)
        panel.addView(text)
        panel.addView(hint)
        panel.visibility = View.GONE
        val params = FrameLayout.LayoutParams(side - dp(24), FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        parent.addView(panel, params)
    }

    fun open(initial: String, target: Target) {
        closing = false
        this.target = target
        buffer = initial.trim()
        partial = ""
        writeSelected = lastMode(activity) == MODE_WRITE
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        Log.d(TAG, "Open")
        choose()
    }

    /** The band while the composer is open; returns false when it's closed. */
    fun onBandCommand(command: String): Boolean {
        if (!isOpen) return false
        when (mode) {
            Mode.CHOOSING -> when (command) {
                BandCommand.LEFT, BandCommand.RIGHT, BandCommand.UP, BandCommand.DOWN,
                BandCommand.FORWARD, BandCommand.BACKWARD -> {
                    writeSelected = !writeSelected
                    renderChoices()
                }
                BandCommand.ACTIVATE -> if (writeSelected) write() else dictate()
                BandCommand.BACK -> closeNow()
            }
            Mode.DICTATING -> when (command) {
                BandCommand.ACTIVATE -> if (Dictation.isListening()) {
                    Dictation.stop()
                    status.text = activity.getString(R.string.composer_paused)
                } else {
                    listen()
                }
                BandCommand.LEFT -> {
                    buffer = buffer.substringBeforeLast(' ', "").trim()
                    target?.composerInput(buffer)
                    render()
                }
                BandCommand.BACK -> if (closing) closeNow() else close()
            }
            // Only the middle tap reaches here while the band writes (the touchpad's Back too).
            Mode.WRITING -> if (command == BandCommand.BACK) closeNow()
        }
        return true
    }

    /**
     * Finishes: the phone may still be transcribing what was just said (an engine that
     * transcribes after the speech), so the composer waits for it before handing the text over.
     */
    fun close() {
        if (target == null) return
        if (mode != Mode.DICTATING) {
            closeNow()
            return
        }
        val busy = PhoneDictation.isBusy()
        Dictation.stop()
        if (busy && !closing) {
            closing = true
            status.text = activity.getString(R.string.composer_finishing)
            Log.d(TAG, "Closing after the last phrase")
            return
        }
        closeNow()
    }

    /**
     * Closes at once, whatever is still on its way (the app leaving the screen, a second Back).
     * Writing, the band goes back to normal on its own after this (the band link restores it).
     */
    fun closeNow() {
        if (mode == Mode.DICTATING) Dictation.stop()
        if (mode == Mode.WRITING) stopWriting()
        val done = target ?: return
        target = null
        closing = false
        mode = Mode.CHOOSING
        panel.visibility = View.GONE
        Log.d(TAG, "Closed")
        done.composerClose()
    }

    private fun choose() {
        mode = Mode.CHOOSING
        status.text = activity.getString(R.string.composer_choose)
        hint.text = activity.getString(R.string.composer_choose_hint)
        choices.visibility = View.VISIBLE
        renderChoices()
        render()
    }

    private fun dictate() {
        remember(MODE_DICTATE)
        mode = Mode.DICTATING
        choices.visibility = View.GONE
        hint.text = activity.getString(R.string.composer_hint)
        render()
        listen()
    }

    private fun write() {
        if (BandRuntime.phase != Phase.CONNECTED) {
            status.text = activity.getString(R.string.composer_write_no_band)
            return
        }
        BandRuntime.addHandwritingListener(handwriting)
        if (!BandRuntime.setHandwriting(true)) {
            BandRuntime.removeHandwritingListener(handwriting)
            status.text = activity.getString(R.string.composer_write_no_band)
            return
        }
        // The band writes after what the field already holds.
        BandRuntime.resetHandwritingText(buffer)
        remember(MODE_WRITE)
        mode = Mode.WRITING
        choices.visibility = View.GONE
        status.text = activity.getString(R.string.composer_write_preparing)
        hint.text = activity.getString(R.string.composer_write_hint)
        lastWriting = System.currentTimeMillis()
        wrote = false
        render()
        Log.d(TAG, "Writing")
    }

    private fun stopWriting() {
        main.removeCallbacks(idleCheck)
        BandRuntime.removeHandwritingListener(handwriting)
        BandRuntime.setHandwriting(false)
        Log.d(TAG, "Writing ends")
    }

    private fun onHandwriting(event: JSONObject) {
        if (mode != Mode.WRITING || !isOpen) return
        when (event.optString("type")) {
            "state" -> when (event.optString("phase")) {
                "ready" -> {
                    status.text = activity.getString(R.string.composer_write_ready)
                    lastWriting = System.currentTimeMillis()
                    main.removeCallbacks(idleCheck)
                    main.postDelayed(idleCheck, IDLE_CHECK_MS)
                }
                // The band stopped writing on its own (a failure, then its restore).
                "restoring", "finished" -> {
                    val problem = event.optString("problem").takeIf { it.isNotEmpty() && it != "null" }
                    main.removeCallbacks(idleCheck)
                    BandRuntime.removeHandwritingListener(handwriting)
                    mode = Mode.CHOOSING
                    choose()
                    if (problem != null) status.text = activity.getString(R.string.composer_write_failed, problem)
                }
            }
            "text" -> {
                lastWriting = System.currentTimeMillis()
                wrote = true
                val written = event.optString("text")
                if (written != buffer) {
                    buffer = written
                    target?.composerInput(buffer)
                }
                render()
            }
        }
    }

    /** A pause in the writing finishes, as kinesis does: the text stays, the band goes back. */
    private fun checkIdle() {
        if (mode != Mode.WRITING || !isOpen) return
        val limit = if (wrote) IDLE_MS else FIRST_LETTER_MS
        if (System.currentTimeMillis() - lastWriting >= limit) {
            Log.d(TAG, "Writing paused for ${limit / 1000} s: done")
            closeNow()
        } else {
            main.postDelayed(idleCheck, IDLE_CHECK_MS)
        }
    }

    private fun listen() {
        if (activity.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "Asking for the microphone")
            status.text = activity.getString(R.string.composer_allow_mic)
            activity.requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_MICROPHONE)
            return
        }
        Dictation.start(activity, object : Dictation.Listener {
            override fun onStatus(status: String) {
                Log.d(TAG, "Status: $status")
                this@WebComposer.status.text = status
            }

            override fun onPartial(text: String) {
                partial = text
                render()
            }

            override fun onPhrase(text: String) {
                buffer = listOf(buffer, text).filter { it.isNotBlank() }.joinToString(" ")
                partial = ""
                target?.composerInput(buffer)
                render()
            }

            override fun onError(message: String) {
                Log.d(TAG, "Error: $message")
                status.text = message
            }

            override fun onStopped() {
                if (closing) closeNow()
                else if (isOpen && mode == Mode.DICTATING && !Dictation.isListening()) status.text = activity.getString(R.string.composer_paused)
            }
        })
    }

    /** Called by the activity when the microphone permission is answered. */
    fun onPermissionResult(granted: Boolean) {
        if (!isOpen || mode != Mode.DICTATING) return
        if (granted) listen() else status.text = activity.getString(R.string.composer_no_mic)
    }

    private fun renderChoices() {
        for ((view, selected) in listOf(dictateChoice to !writeSelected, writeChoice to writeSelected)) {
            view.background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                if (selected) setColor(ACCENT) else setStroke(dp(1), Color.rgb(74, 99, 86))
            }
            view.setTextColor(if (selected) Color.rgb(8, 20, 14) else Color.rgb(205, 220, 212))
            view.typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
    }

    private fun render() {
        val shown = SpannableStringBuilder(buffer)
        if (partial.isNotBlank()) {
            if (shown.isNotEmpty()) shown.append(' ')
            val start = shown.length
            shown.append(partial)
            shown.setSpan(ForegroundColorSpan(Color.rgb(117, 142, 130)), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        // Choosing, an empty field shows nothing: neither "speak" nor "write" applies yet.
        text.visibility = if (mode == Mode.CHOOSING && shown.isEmpty()) View.GONE else View.VISIBLE
        val empty = when (mode) {
            Mode.WRITING -> R.string.composer_write_now
            else -> R.string.composer_speak
        }
        text.text = if (shown.isEmpty()) activity.getString(empty) else shown
    }

    private fun remember(chosen: String) {
        prefs(activity).edit().putString(KEY_MODE, chosen).apply()
    }

    private fun dp(value: Int) = Math.round(value * density)

    companion object {
        const val REQUEST_MICROPHONE = 77
        private const val TAG = "BandComposer"
        private val ACCENT = Color.rgb(102, 242, 165)
        private const val PREFS = "lumen_composer"
        private const val KEY_MODE = "mode"
        private const val MODE_DICTATE = "dictate"
        private const val MODE_WRITE = "write"
        /** Writing that pauses this long finishes (kinesis waits 12 s; letters here take longer). */
        private const val IDLE_MS = 15_000L
        /** Before the first letter: time to read the panel and start. */
        private const val FIRST_LETTER_MS = 30_000L
        private const val IDLE_CHECK_MS = 1_000L

        private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        /** The mode the composer opened in last time: dictate (the default) or write. */
        fun lastMode(context: Context): String = prefs(context).getString(KEY_MODE, MODE_DICTATE) ?: MODE_DICTATE
    }
}
