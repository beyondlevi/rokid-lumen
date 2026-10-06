package dev.lumen.glasses

import android.content.Context
import android.graphics.Color
import android.util.Log
import dev.lumen.band.ScreenPointer

/**
 * The air mouse on the glasses' display ([ScreenPointer]): the gesture mapped to it
 * ([GestureChoices.POINTER]) turns it on, the band turns it off with the same gesture
 * ([ScreenPointer.OFF]). The cursor and its touches go through [BandAccessibilityService]; the
 * middle pinch is the navigation's Back, and a tap on the home's own screens (which move a focus
 * rather than take touches) reaches them too ([BandAccessibilityService.InputTarget.onPointerTap]).
 * It stops with the band, the screen or the service. Main thread.
 */
object GlassesPointer {
    private const val TAG = "GlassesPointer"

    /** White, a green line on the lens; no outline (black is see-through there). */
    private val STYLE = ScreenPointer.Style(Color.WHITE, 0, 24f)

    private var pointer: ScreenPointer? = null
    private var service: BandAccessibilityService? = null

    @JvmStatic
    val isOn get() = pointer?.on == true

    /** Turns it on; false when the band can't run it. */
    @JvmStatic
    fun start(service: BandAccessibilityService): Boolean {
        if (isOn) {
            retune(service)
            return true
        }
        val pointer = ScreenPointer(service, host(service), STYLE)
        if (!pointer.start(GestureMappings.pointerTuning(service))) return false
        this.pointer = pointer
        this.service = service
        service.showFeedback(service.getString(R.string.feedback_pointer_on))
        Log.d(TAG, "on")
        return true
    }

    @JvmStatic
    fun stop() {
        val pointer = pointer ?: return
        this.pointer = null
        pointer.stop()
        service?.let { it.showFeedback(it.getString(R.string.feedback_pointer_off)) }
        service = null
        Log.d(TAG, "off")
    }

    /** The settings changed from the phone: the cursor follows while it runs. */
    @JvmStatic
    fun retune(context: Context) {
        pointer?.retune(GestureMappings.pointerTuning(context))
    }

    /** The band's records (see dev.lumen.band.Bridge.pointer). */
    @JvmStatic
    fun onBand(records: DoubleArray) {
        pointer?.onBand(records)
    }

    private fun host(service: BandAccessibilityService) = object : ScreenPointer.Host {
        override fun setPointer(on: Boolean, tuning: String) = BandRuntime.setPointer(on, tuning)

        override fun back() = service.onPointerBack()

        override fun tapped(x: Float, y: Float) = service.onPointerTap(x, y)

        // The band is input the system doesn't see: moving the cursor keeps the display on.
        override fun active() = ScreenTimeout.onUserActivity(service)
    }
}
