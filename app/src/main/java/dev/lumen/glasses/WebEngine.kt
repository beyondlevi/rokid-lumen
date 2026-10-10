package dev.lumen.glasses

import android.view.View

/**
 * One browser engine under [WebAppActivity]: the system WebView ([SystemWebEngine], Chromium 95
 * on the Rokid) or GeckoView ([GeckoWebEngine]). Both show a 600x600 CSS pixel viewport in the
 * HUD's square, inject assets/mrbd-shim.js before the app's scripts and bridge the shim's
 * `MrbdHost` calls to [Host]; the activity keeps what doesn't depend on the engine (the band,
 * the composer, speech).
 */
interface WebEngine {
    /** What the page asks of the app, through the shim. Called on the main thread. */
    interface Host {
        /** The shim's Back found nothing to do (no Escape handler, no navigation). */
        fun onBackUnhandled()
        fun onOpenComposer(value: String, multiline: Boolean)
        fun onInstall(url: String, name: String)
        fun onSpeak(id: Int, text: String, lang: String, rate: Float, pitch: Float)
        fun onCancelSpeech()
        fun onLoadFailed(description: String)

        /**
         * The page asks for its configuration (`window.lumen.config.get()`); answer with
         * [configResult]. [pageUrl] is the page's address, checked against the app's origin
         * (the values may be secrets).
         */
        fun onGetConfig(id: Int, pageUrl: String?)

        /** A `window.lumen.audio` request from the page at [pageUrl] ([GlassesAudio]). */
        fun onAudio(message: org.json.JSONObject, pageUrl: String?)

        /** A text field got focus, or its value was read again ([PhoneKeyboard]). */
        fun onTextFocus(value: String, type: String, multiline: Boolean, label: String, reason: String)

        /** No text field has focus any more. */
        fun onTextBlur()

        /** The page's process died again soon after a reload: it waits for [retry] ([held]). */
        fun onPageHeld()

        /**
         * The shown page's process died (out of memory, on the glasses): called before it's
         * loaded again or held, so what else holds memory can let go first.
         */
        fun onPageLost()
    }

    /** The view to place in the HUD's square (the engine sizes itself inside it). */
    val view: View

    fun load(url: String)

    /** A key press (down and up) to the page, as MRBD's arrows and Enter. */
    fun key(keyCode: Int)

    /** MRBD's Back: Escape to the page first; [Host.onBackUnhandled] when that did nothing. */
    fun back()

    /** Goes back in the session history; false when there's nothing behind. */
    fun historyBack(): Boolean

    fun composerInput(text: String)
    fun composerClose()

    /** Whether the phone's keyboard is open: Enter on a field then reaches the page. */
    fun keyboardState(open: Boolean)

    /** The phone keyboard's text, the focused field's whole value. */
    fun keyboardInput(text: String)

    /** Has the page report its focused field again ([Host.onTextFocus], reason sync). */
    fun keyboardSync()
    fun speechEvent(id: String, type: String, code: String?)

    /** The answer to [Host.onGetConfig], for the page at [origin] only. */
    fun configResult(id: Int, values: org.json.JSONObject, origin: String)

    /** The configuration changed while the app is open (`window.lumen.config.onChange`). */
    fun configChanged(values: org.json.JSONObject, origin: String)

    /** A `window.lumen.audio` event to the app's page at [origin]. */
    fun audioEvent(event: org.json.JSONObject, origin: String)

    fun onResume()
    fun onPause()

    /** The screen is seen again (onStart): undoes [onHidden]. */
    fun onShown() {}

    /**
     * Nobody sees the screen (onStop: another screen covers it, or the display is off): the page
     * stops drawing and running until [onShown], and is kept as it was.
     */
    fun onHidden() {}

    fun destroy()

    /**
     * The page's process was killed again soon after it was reloaded (the system out of memory,
     * in practice): it isn't reloaded on its own, which would loop, until [retry].
     */
    val held: Boolean get() = false

    /** Loads a [held] page again. */
    fun retry() {}

    companion object {
        /** MRBD's viewport, in CSS pixels. */
        const val MRBD_VIEWPORT = 600
    }
}
