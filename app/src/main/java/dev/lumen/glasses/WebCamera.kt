package dev.lumen.glasses

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.view.KeyEvent
import android.widget.FrameLayout

/**
 * A web app's way to the glasses' camera (the camera canvas), for [WebAppActivity]: getUserMedia
 * asks the wearer once per app ([WebCameraPrompt], kept in [CameraAccess] and changed from the
 * phone), a photo field opens the capture screen ([CameraCapture]), the wearer's own act. Both
 * need Android's CAMERA permission, asked the first time; a blocked app and a camera someone
 * else has get a notice instead.
 */
class WebCamera(private val activity: Activity, root: FrameLayout, side: Int, private val appId: String, private val appName: String) {
    private val prompt = WebCameraPrompt(activity, root, side)
    private val capture = CameraCapture(activity, root, side)
    private var waiting: ((Boolean) -> Unit)? = null

    init {
        CameraState.start(activity)
    }

    /** The prompt or the capture screen is up: the band's keys are theirs. */
    val shown: Boolean get() = prompt.shown || capture.shown

    fun cameraRequest(answer: WebEngine.Answer<Boolean>) {
        when (CameraAccess.of(activity, appId)) {
            CameraAccess.State.DENIED -> {
                MetaToast.show(R.drawable.ic_camera_outline, activity.getString(R.string.camera_blocked, appName), activity.getString(R.string.camera_blocked_meta))
                answer.done(false)
            }
            CameraAccess.State.ALLOWED -> open(answer)
            CameraAccess.State.ASK -> prompt.ask(appName) { allowed ->
                CameraAccess.set(activity, appId, if (allowed) CameraAccess.State.ALLOWED else CameraAccess.State.DENIED)
                GridApi.pushStateSoon()
                if (allowed) open(answer) else answer.done(false)
            }
        }
    }

    fun photoRequest(answer: WebEngine.Answer<Uri?>) {
        if (busy()) return answer.done(null)
        withPermission { granted -> if (granted) capture.open(appName) { answer.done(it) } else answer.done(null) }
    }

    private fun open(answer: WebEngine.Answer<Boolean>) {
        if (busy()) return answer.done(false)
        withPermission { granted ->
            if (granted) CameraState.claim()
            answer.done(granted)
        }
    }

    /** Someone else has the camera (Rokid's own photo): a notice. */
    private fun busy(): Boolean {
        if (!CameraState.busyElsewhere) return false
        MetaToast.show(R.drawable.ic_alert, activity.getString(R.string.camera_in_use), activity.getString(R.string.camera_in_use_meta))
        return true
    }

    private fun withPermission(then: (Boolean) -> Unit) {
        if (activity.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) return then(true)
        waiting?.invoke(false)
        waiting = then
        activity.requestPermissions(arrayOf(Manifest.permission.CAMERA), REQUEST)
    }

    /** Android's answer to [REQUEST]. */
    fun onPermissionResult(granted: Boolean) {
        val then = waiting ?: return
        waiting = null
        then(granted)
    }

    fun onKey(event: KeyEvent): Boolean = prompt.onKey(event) || capture.onKey(event)

    /** The screen goes (hidden or closed): a question is Not now, a capture is cancelled. */
    fun dismiss() {
        prompt.dismiss()
        capture.dismiss()
    }

    companion object {
        const val REQUEST = 78
    }
}
