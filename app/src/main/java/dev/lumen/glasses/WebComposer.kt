package dev.lumen.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView

/**
 * MRBD's on-glasses composer, for the Rokid host: activating a text field opens it (the shim
 * catches Enter on the field), dictation fills it, and the text goes back to the field through
 * `input` events, then `change` when it closes. The page never sees the microphone.
 *
 * While it's open the band drives it: index tap pauses or resumes listening, a left swipe
 * deletes the last word, the middle tap finishes.
 */
class WebComposer(private val activity: Activity, parent: FrameLayout, side: Int) {
    /** Where the text goes: the engine forwards it to the page's shim. */
    interface Target {
        fun composerInput(text: String)
        fun composerClose()
    }

    private val density = activity.resources.displayMetrics.density
    private val panel = LinearLayout(activity)
    private val status = TextView(activity)
    private val text = TextView(activity)
    private val hint = TextView(activity)
    private var target: Target? = null
    private var buffer = ""
    private var partial = ""
    /** Closing, waiting for the last phrase; a second Back closes at once. */
    private var closing = false

    val isOpen get() = target != null

    init {
        panel.orientation = LinearLayout.VERTICAL
        panel.setPadding(dp(16), dp(12), dp(16), dp(12))
        panel.background = GradientDrawable().apply {
            setColor(Color.rgb(16, 22, 19))
            cornerRadius = dp(14).toFloat()
            setStroke(dp(2), Color.rgb(102, 242, 165))
        }
        status.setTextColor(Color.rgb(102, 242, 165))
        status.textSize = 12f
        status.typeface = Typeface.DEFAULT_BOLD
        text.setTextColor(Color.rgb(248, 250, 249))
        text.textSize = 17f
        text.maxLines = 5
        text.minHeight = dp(48)
        hint.setTextColor(Color.rgb(161, 183, 172))
        hint.textSize = 10f
        hint.text = activity.getString(R.string.composer_hint)
        panel.addView(status)
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
        panel.visibility = View.VISIBLE
        panel.bringToFront()
        Log.d(TAG, "Open")
        render()
        listen()
    }

    /** The band while the composer is open; returns false when it's closed. */
    fun onBandCommand(command: String): Boolean {
        if (!isOpen) return false
        when (command) {
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
        return true
    }

    /**
     * Finishes: the phone may still be transcribing what was just said (an engine that
     * transcribes after the speech), so the composer waits for it before handing the text over.
     */
    fun close() {
        if (target == null) return
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

    /** Closes at once, whatever is still on its way (the app leaving the screen, a second Back). */
    fun closeNow() {
        Dictation.stop()
        val done = target ?: return
        target = null
        closing = false
        panel.visibility = View.GONE
        Log.d(TAG, "Closed")
        done.composerClose()
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
                else if (isOpen && !Dictation.isListening()) status.text = activity.getString(R.string.composer_paused)
            }
        })
    }

    /** Called by the activity when the microphone permission is answered. */
    fun onPermissionResult(granted: Boolean) {
        if (!isOpen) return
        if (granted) listen() else status.text = activity.getString(R.string.composer_no_mic)
    }

    private fun render() {
        val shown = SpannableStringBuilder(buffer)
        if (partial.isNotBlank()) {
            if (shown.isNotEmpty()) shown.append(' ')
            val start = shown.length
            shown.append(partial)
            shown.setSpan(ForegroundColorSpan(Color.rgb(117, 142, 130)), start, shown.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        text.text = if (shown.isEmpty()) activity.getString(R.string.composer_speak) else shown
    }

    private fun dp(value: Int) = Math.round(value * density)

    companion object {
        const val REQUEST_MICROPHONE = 77
        private const val TAG = "BandComposer"
    }
}
