package dev.lumen.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import dev.lumen.protocol.DictationCommand
import dev.lumen.protocol.DictationEvent
import dev.lumen.protocol.Link
import org.json.JSONObject

/**
 * Dictation served by the phone companion: the Rokid glasses silence a third-party microphone,
 * so the companion takes the glasses' mic over Rokid's CXR-L link, transcribes on the phone and
 * sends the text back through [PhoneLink].
 */
object PhoneDictation {

    private var context: Context? = null

    private fun text(id: Int): String = context?.getString(id).orEmpty()
    private const val ANSWER_TIMEOUT_MS = 6_000L
    /** After a stop, the phone's last phrases may take this long (a buffered engine's final). */
    private const val DRAIN_TIMEOUT_MS = 20_000L

    private val main = Handler(Looper.getMainLooper())
    private var listener: Dictation.Listener? = null
    /** Stopped, still waiting for what the phone was transcribing (until STOPPED). */
    private var draining: Dictation.Listener? = null
    private var answered = false

    @JvmStatic
    fun isActive() = listener != null

    /** Listening, or stopped with text still on its way. */
    @JvmStatic
    fun isBusy() = listener != null || draining != null

    /** On Rokid glasses the phone route is the only one with real audio. */
    @JvmStatic
    fun applies(context: Context): Boolean {
        this.context = context.applicationContext
        return PhoneLink.applies(context)
    }

    /** Our app is in front: tell Rokid's service, and say hello so the companion knows. */
    @JvmStatic
    fun announce() {
        PhoneLink.appLaunched()
        PhoneLink.send(Link.DICTATION, DictationCommand.HELLO.toJson())
    }

    @JvmStatic
    fun start(listener: Dictation.Listener) {
        // Resuming before the phone was done: its last phrases go to the new listener.
        draining = null
        main.removeCallbacks(drained)
        this.listener = listener
        answered = false
        if (PhoneLink.ensure() == null) {
            listener.onError(text(R.string.dictation_phone_unavailable))
            this.listener = null
            return
        }
        PhoneLink.dictationListener = { _, json -> onEvent(json) }
        listener.onStatus(text(R.string.dictation_connecting))
        PhoneLink.appLaunched()
        PhoneLink.send(Link.DICTATION, DictationCommand.START.toJson())
        main.postDelayed({
            if (this.listener === listener && !answered) {
                this.listener = null
                // A slow link (it can take tens of seconds right after the companion restarts)
                // may still deliver the start: the phone mustn't keep the glasses' mic for nothing.
                PhoneLink.send(Link.DICTATION, DictationCommand.STOP.toJson())
                listener.onError(text(R.string.dictation_no_answer))
            }
        }, ANSWER_TIMEOUT_MS)
    }

    @JvmStatic
    fun stop() {
        val current = listener ?: return
        PhoneLink.send(Link.DICTATION, DictationCommand.STOP.toJson())
        listener = null
        // What was being said still comes (STOPPED says when it's all in).
        draining = current
        main.removeCallbacks(drained)
        main.postDelayed(drained, DRAIN_TIMEOUT_MS)
    }

    private val drained = Runnable {
        val done = draining ?: return@Runnable
        draining = null
        done.onStopped()
    }

    private fun onEvent(json: JSONObject) {
        val text = json.optString("text")
        val type = json.optString("type")
        val finishing = draining
        if (listener == null && finishing != null) {
            when (type) {
                DictationEvent.PARTIAL -> finishing.onPartial(text)
                DictationEvent.PHRASE -> finishing.onPhrase(text)
                DictationEvent.STOPPED, DictationEvent.ERROR -> {
                    main.removeCallbacks(drained)
                    draining = null
                    if (type == DictationEvent.ERROR) finishing.onError(text)
                    finishing.onStopped()
                }
            }
            return
        }
        val current = listener ?: return
        when (type) {
            DictationEvent.STATUS -> { answered = true; current.onStatus(text) }
            DictationEvent.LISTENING -> { answered = true; current.onStatus(text(R.string.dictation_listening_glasses)) }
            DictationEvent.PARTIAL -> { answered = true; current.onPartial(text) }
            DictationEvent.PHRASE -> { answered = true; current.onPhrase(text) }
            // After an error nothing is listening: the next tap retries instead of pausing.
            DictationEvent.ERROR -> { answered = true; listener = null; current.onError(text) }
            DictationEvent.READY -> answered = true
            // The phone stopped on its own (nobody spoke for a while): nothing is listening.
            DictationEvent.STOPPED -> if (answered) { listener = null; current.onStopped() }
        }
    }
}
