package dev.lumen.glasses

import android.content.Context
import android.util.Log
import org.json.JSONObject

/**
 * The page's `SpeechRecognition` (the Web Speech API, which the engines lack here): served by
 * the glasses' dictation, the same the composer uses (the phone transcribes the glasses'
 * microphone with the engine chosen in the companion). One page at a time.
 *
 * Page → host `{op: recognize, id, continuous, interimResults} | {op: recognizeStop, id}
 * | {op: recognizeAbort, id}`; host → page `{type: start|result|error|end, id, …}`, a result
 * carrying `text` and `final`.
 */
object WebRecognition {
    private const val TAG = "BandWebSpeech"

    private class Session(val owner: Any, val pageId: String, val page: GlassesAudio.Page, val continuous: Boolean, val interim: Boolean) {
        var started = false
        var ended = false
    }

    private var current: Session? = null

    @JvmStatic
    fun handles(message: JSONObject) = message.optString("op").startsWith("recognize")

    @JvmStatic
    fun request(context: Context, owner: Any, message: JSONObject, page: GlassesAudio.Page) {
        val pageId = message.optString("id")
        val session = current?.takeIf { it.owner === owner && it.pageId == pageId }
        when (message.optString("op")) {
            "recognize" -> start(context, owner, pageId, message.optBoolean("continuous"), message.optBoolean("interimResults"), page)
            "recognizeStop" -> if (session != null) Dictation.stop()
            "recognizeAbort" -> if (session != null) {
                current = null
                Dictation.stop()
                end(session, error = "aborted")
            }
        }
    }

    /** The screen closed: a recognition it started stops. */
    @JvmStatic
    fun closeAll(owner: Any) {
        val session = current?.takeIf { it.owner === owner } ?: return
        current = null
        session.ended = true
        Dictation.stop()
    }

    private fun start(context: Context, owner: Any, pageId: String, continuous: Boolean, interim: Boolean, page: GlassesAudio.Page) {
        if (current != null || Dictation.isListening() || GlassesAudio.busy()) {
            page.event(event("error", pageId).put("error", "audio-capture").put("message", "the microphone is in use"))
            page.event(event("end", pageId))
            return
        }
        val session = Session(owner, pageId, page, continuous, interim)
        current = session
        Log.d(TAG, "recognize for page id $pageId (continuous=$continuous interim=$interim)")
        Dictation.start(context, object : Dictation.Listener {
            override fun onStatus(status: String) = began(session)

            override fun onPartial(text: String) {
                began(session)
                if (session.interim && !session.ended) page.event(event("result", pageId).put("text", text).put("final", false))
            }

            override fun onPhrase(text: String) {
                began(session)
                if (session.ended) return
                page.event(event("result", pageId).put("text", text).put("final", true))
                if (!session.continuous) Dictation.stop()
            }

            override fun onError(message: String) {
                if (current === session) current = null
                end(session, error = "network", message = message)
            }

            override fun onStopped() {
                if (current === session) current = null
                end(session)
            }
        })
    }

    private fun began(session: Session) {
        if (session.started || session.ended) return
        session.started = true
        session.page.event(event("start", session.pageId))
    }

    private fun end(session: Session, error: String? = null, message: String = "") {
        if (session.ended) return
        session.ended = true
        if (error != null) session.page.event(event("error", session.pageId).put("error", error).put("message", message))
        session.page.event(event("end", session.pageId))
    }

    private fun event(type: String, pageId: String) = JSONObject().put("type", type).put("id", pageId)
}
