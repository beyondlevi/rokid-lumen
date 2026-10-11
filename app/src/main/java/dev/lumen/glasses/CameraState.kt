package dev.lumen.glasses

import android.content.Context
import android.hardware.camera2.CameraManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Whether the glasses' camera is open, by anyone: a web app (Gecko's getUserMedia), Lumen's
 * capture screen ([CameraCapture]) or Rokid's own photos. The status bar shows it while it is
 * ([StatusBarOverlay]), and a web app asking for it while someone else has it is told so.
 * Camera2's availability callback, which every process may listen to without the permission.
 */
object CameraState {
    private const val TAG = "BandCamera"
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val unavailable = HashSet<String>()
    private var started = false
    /** Lumen opened it (a web app it let in, the capture screen), until it's closed. */
    private var ours = false

    /** Some camera is open now. Main thread. */
    val inUse: Boolean get() = unavailable.isNotEmpty()

    /** Someone other than Lumen has it (Rokid's own photo). */
    val busyElsewhere: Boolean get() = inUse && !ours

    /** Lumen is about to open it: until it's closed, it's Lumen's ([busyElsewhere]). */
    @JvmStatic
    fun claim() {
        ours = true
    }

    private val callback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (unavailable.remove(cameraId)) {
                if (unavailable.isEmpty()) ours = false
                changed()
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (unavailable.add(cameraId)) changed()
        }
    }

    @JvmStatic
    fun start(context: Context) {
        if (started) return
        started = true
        runCatching { context.getSystemService(CameraManager::class.java).registerAvailabilityCallback(callback, main) }
            .onFailure { Log.w(TAG, "Couldn't follow the camera", it) }
    }

    private fun changed() {
        Log.d(TAG, "Camera ${if (inUse) "open" else "closed"}")
        listeners.forEach { it() }
    }

    fun addListener(listener: () -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: () -> Unit) {
        listeners -= listener
    }
}
