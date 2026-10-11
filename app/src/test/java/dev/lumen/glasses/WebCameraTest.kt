package dev.lumen.glasses

import android.util.Size
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebCameraTest {
    private val context = org.robolectric.RuntimeEnvironment.getApplication()

    @Test
    fun eachAppKeepsItsAnswerUntilItGoes() {
        assertEquals(CameraAccess.State.ASK, CameraAccess.of(context, "a"))
        CameraAccess.set(context, "a", CameraAccess.State.ALLOWED)
        CameraAccess.set(context, "b", CameraAccess.State.DENIED)
        assertEquals(CameraAccess.State.ALLOWED, CameraAccess.of(context, "a"))
        assertEquals(CameraAccess.State.DENIED, CameraAccess.of(context, "b"))
        CameraAccess.forget(context, "a")
        assertEquals(CameraAccess.State.ASK, CameraAccess.of(context, "a"))
        // The phone's values: unknown is "ask again".
        assertEquals(CameraAccess.State.ALLOWED, CameraAccess.State.of("allowed"))
        assertEquals(CameraAccess.State.ASK, CameraAccess.State.of("whatever"))
        assertEquals(CameraAccess.State.ASK, CameraAccess.State.of(null))
    }

    @Test
    fun theFrameIsTheLargestFourByThreeThatFits() {
        // The RG's YUV sizes (some of them, as Camera2 listed them).
        val sizes = arrayOf(Size(4032, 3024), Size(1920, 1080), Size(1600, 1200), Size(1280, 960), Size(1024, 768), Size(640, 480))
        assertEquals(Size(1600, 1200), CameraCapture.fourByThree(sizes, 1600))
        assertEquals(Size(1280, 960), CameraCapture.fourByThree(sizes, 1280))
        // No 4:3 small enough: the largest that fits, then the smallest of all.
        assertEquals(Size(1920, 1080), CameraCapture.fourByThree(arrayOf(Size(4032, 3024), Size(1920, 1080)), 2000))
        assertEquals(Size(1920, 1080), CameraCapture.fourByThree(arrayOf(Size(4032, 3024), Size(1920, 1080)), 100))
        assertNull(CameraCapture.fourByThree(null, 1600))
    }

    @Test
    fun nv21TurnsAsABitmapWould() {
        // 4 x 2 luma (0..7) and one row of two VU pairs.
        val frame = byteArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 10, 11, 20, 21)
        // Clockwise 90: the bottom row becomes the first column, read upwards.
        assertEquals(listOf<Byte>(4, 0, 5, 1, 6, 2, 7, 3, 10, 11, 20, 21), CameraCapture.rotateNv21(frame, 4, 2, 90).toList())
        assertEquals(listOf<Byte>(7, 6, 5, 4, 3, 2, 1, 0, 20, 21, 10, 11), CameraCapture.rotateNv21(frame, 4, 2, 180).toList())
        // 270 (the RG's): the right column becomes the first row.
        assertEquals(listOf<Byte>(3, 7, 2, 6, 1, 5, 0, 4, 20, 21, 10, 11), CameraCapture.rotateNv21(frame, 4, 2, 270).toList())
        assertEquals(frame.toList(), CameraCapture.rotateNv21(frame, 4, 2, 0).toList())
    }
}
