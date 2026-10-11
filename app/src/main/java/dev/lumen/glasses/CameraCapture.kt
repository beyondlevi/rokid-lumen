package dev.lumen.glasses

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Size
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.io.File

/**
 * The capture screen of an HTML photo field (the camera canvas, boards 3 and 4): what the
 * glasses' camera sees in the square, "Photo for [App]" on top; the index tap takes the photo,
 * then Use photo or Take another (left and right, the index tap), the middle tap cancels. The
 * photo goes back to the page as a JPEG of at most [LONG_EDGE] px, upright.
 *
 * Camera2 straight from Lumen's process: the camera is free unless Rokid's assistant is taking
 * its own photo. Pictures are turned by the sensor's orientation (270 on the RG), as Gecko's
 * camera does. The photo is a frame of the preview's own pipeline (a YUV stream), not a still
 * capture: the RG's still pipeline processes the whole 12 MP sensor, and its memory took Lumen
 * down with it (measured, lmkd).
 */
class CameraCapture(private val activity: Activity, root: FrameLayout, private val side: Int) {
    private enum class Stage { PREVIEW, TAKING, REVIEW }

    private val main = Handler(Looper.getMainLooper())
    private var answer: ((Uri?) -> Unit)? = null
    private var stage = Stage.PREVIEW
    /** 0 Use photo, 1 Take another. */
    private var choice = 0
    /** The key pressed down while it's up, whose up acts. */
    private var pressed = NONE
    private var photo: File? = null

    private var thread: HandlerThread? = null
    private var handler: Handler? = null
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var reader: ImageReader? = null
    private var previewSize: Size? = null
    private var previewSurface: Surface? = null
    /** The sensor's orientation: the photo's turn, which the preview's SurfaceTexture already applies. */
    private var sensor = 0

    private val view = FrameLayout(activity).apply {
        setBackgroundColor(Color.BLACK)
        visibility = View.GONE
        isClickable = true
    }
    private val texture = TextureView(activity)
    private val still = ImageView(activity).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        visibility = View.GONE
    }
    private val chip = text(20f, MetaStyle.MEDIUM, MetaStyle.TEXT).apply {
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(20f), 0, px(20f), 0)
        background = MetaStyle.pill(activity)
        compoundDrawablePadding = px(10f)
        setCompoundDrawablesRelative(icon(R.drawable.ic_camera_outline, 22.5f), null, null, null)
    }
    private val choices = listOf(R.string.camera_review_use to R.drawable.ic_check, R.string.camera_review_again to R.drawable.ic_camera_outline).map { (label, drawable) ->
        text(22.5f, MetaStyle.MEDIUM, MetaStyle.TEXT).apply {
            setText(label)
            gravity = Gravity.CENTER
            compoundDrawablePadding = px(10f)
            setCompoundDrawablesRelative(icon(drawable, 25f), null, null, null)
        }
    }
    private val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        visibility = View.GONE
    }
    private val hint = text(20f, MetaStyle.REGULAR, MetaStyle.TEXT_SECONDARY).apply {
        gravity = Gravity.CENTER
        setPadding(px(22f), 0, px(22f), 0)
        background = MetaStyle.pill(activity)
        visibility = View.GONE
    }

    /** The screen is up: the band's keys are its own. */
    val shown: Boolean get() = answer != null

    init {
        view.addView(texture, FrameLayout.LayoutParams(side, side))
        view.addView(still, FrameLayout.LayoutParams(side, side * 400 / 480))
        view.addView(chip, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(45f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = px(20f) })
        choices.forEachIndexed { i, button ->
            row.addView(button, LinearLayout.LayoutParams(0, px(75f), 1f).apply { if (i > 0) marginStart = px(12.5f) })
        }
        view.addView(row, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, px(75f), Gravity.BOTTOM).apply {
            marginStart = px(30f)
            marginEnd = px(30f)
            bottomMargin = px(15f)
        })
        root.addView(view, FrameLayout.LayoutParams(side, side, Gravity.CENTER))
        val strip = (activity.resources.displayMetrics.heightPixels - side) / 2
        if (strip >= px(HINT + 10f)) {
            root.addView(hint, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, px(HINT), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = (strip - px(HINT)) / 2
            })
        }
        texture.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                if (shown && stage != Stage.REVIEW) startPreview()
            }

            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
    }

    /** Opens the camera for [appName]'s photo field; [answer] gets the photo, null when cancelled. */
    fun open(appName: String, answer: (Uri?) -> Unit) {
        finish(null)
        this.answer = answer
        pressed = NONE
        chip.text = activity.getString(R.string.camera_capture_for, appName)
        view.visibility = View.VISIBLE
        hint.visibility = View.VISIBLE
        preview()
    }

    /** The band's keys while it's up: all of them are its own. */
    fun onKey(event: KeyEvent): Boolean {
        if (!shown) return false
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (event.repeatCount == 0) pressed = event.keyCode
            return true
        }
        // The up of a key pressed before it showed (the tap that opened it) does nothing.
        if (event.action != KeyEvent.ACTION_UP || event.keyCode != pressed) return true
        pressed = NONE
        val code = event.keyCode
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            finish(null)
            return true
        }
        val enter = code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER
        when (stage) {
            Stage.PREVIEW -> if (enter) take()
            Stage.TAKING -> Unit
            Stage.REVIEW -> when {
                code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_UP -> moveChoice(0)
                code == KeyEvent.KEYCODE_DPAD_RIGHT || code == KeyEvent.KEYCODE_DPAD_DOWN -> moveChoice(1)
                enter && choice == 0 -> photo?.let { finish(Uri.fromFile(it)) }
                enter -> preview()
            }
        }
        return true
    }

    /** The screen goes: cancelled. */
    fun dismiss() = finish(null)

    private fun preview() {
        stage = Stage.PREVIEW
        photo?.delete()
        photo = null
        still.setImageDrawable(null)
        still.visibility = View.GONE
        row.visibility = View.GONE
        texture.visibility = View.VISIBLE
        chip.visibility = View.VISIBLE
        hint.setText(R.string.camera_capture_hint)
        if (texture.isAvailable) startPreview()
    }

    private fun moveChoice(to: Int) {
        if (to == choice) return
        choice = to
        renderChoices()
    }

    private fun renderChoices() {
        choices.forEachIndexed { i, button ->
            val focused = i == choice
            button.background = if (focused) MetaStyle.focused(activity, 0, radius = 999f) else MetaStyle.outline(activity, radius = 999f)
            button.typeface = if (focused) MetaStyle.BOLD else MetaStyle.MEDIUM
        }
    }

    // --- Camera2, on its own thread ---

    @SuppressLint("MissingPermission") // WebCamera asks for CAMERA before opening this screen.
    private fun startPreview() {
        if (device != null) return createSession()
        val manager = activity.getSystemService(CameraManager::class.java)
        val id = runCatching {
            manager.cameraIdList.firstOrNull { manager.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK }
                ?: manager.cameraIdList.firstOrNull()
        }.getOrNull() ?: return failed("no camera")
        val characteristics = manager.getCameraCharacteristics(id)
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return failed("no stream sizes")
        val preview = fourByThree(map.getOutputSizes(SurfaceTexture::class.java), PREVIEW_WIDTH) ?: return failed("no preview size")
        val frame = fourByThree(map.getOutputSizes(ImageFormat.YUV_420_888), FRAME_WIDTH) ?: return failed("no photo size")
        sensor = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0
        previewSize = preview
        val worker = HandlerThread("LumenCamera").also { it.start() }
        thread = worker
        val background = Handler(worker.looper)
        handler = background
        reader = ImageReader.newInstance(frame.width, frame.height, ImageFormat.YUV_420_888, 2).apply {
            setOnImageAvailableListener({ r -> saveFrame(r) }, background)
        }
        CameraState.claim()
        Log.d(TAG, "Opening camera $id (sensor $sensor°): preview $preview, photo $frame")
        runCatching {
            manager.openCamera(id, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    main.post {
                        if (!shown) return@post camera.close()
                        device = camera
                        createSession()
                    }
                }

                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    main.post { if (device === camera) failed("disconnected") }
                }

                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    main.post { failed("error $error") }
                }
            }, background)
        }.onFailure { failed("open: ${it.message}") }
    }

    private fun createSession() {
        val camera = device ?: return
        val size = previewSize ?: return
        val st = texture.surfaceTexture ?: return
        val frames = reader ?: return
        st.setDefaultBufferSize(size.width, size.height)
        texture.setTransform(upright(size))
        val surface = previewSurface ?: Surface(st).also { previewSurface = it }
        runCatching {
            @Suppress("DEPRECATION")
            camera.createCaptureSession(listOf(surface, frames.surface), object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(s: CameraCaptureSession) {
                    main.post {
                        if (device !== camera) return@post s.close()
                        session = s
                        repeatPreview()
                    }
                }

                override fun onConfigureFailed(s: CameraCaptureSession) {
                    main.post { failed("session") }
                }
            }, handler)
        }.onFailure { failed("session: ${it.message}") }
    }

    private fun repeatPreview() {
        val s = session ?: return
        val surface = previewSurface ?: return
        runCatching {
            val request = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            s.setRepeatingRequest(request.build(), null, handler)
        }.onFailure { Log.w(TAG, "Preview didn't start", it) }
    }

    /** One preview request that also fills the YUV stream: the frame on screen, at [FRAME_WIDTH]. */
    private fun take() {
        val s = session ?: return
        val frames = reader ?: return
        val surface = previewSurface ?: return
        stage = Stage.TAKING
        hint.setText(R.string.camera_capture_taking)
        runCatching {
            val request = s.device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                addTarget(frames.surface)
                set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE)
            }
            s.capture(request.build(), null, handler)
        }.onFailure {
            Log.w(TAG, "Photo failed", it)
            stage = Stage.PREVIEW
            hint.setText(R.string.camera_capture_hint)
        }
    }

    /**
     * On the camera thread: the frame turned by the sensor's orientation and encoded once, as a
     * JPEG into the cache (a decode, a turn and a second encode took about 5 s on the RG).
     */
    private fun saveFrame(r: ImageReader) {
        val image = r.acquireLatestImage() ?: return
        val started = android.os.SystemClock.elapsedRealtime()
        val width = image.width
        val height = image.height
        val nv21 = try {
            nv21(image)
        } finally {
            image.close()
        }
        val copied = android.os.SystemClock.elapsedRealtime()
        val file = runCatching {
            val turned = rotateNv21(nv21, width, height, sensor)
            val rotated = android.os.SystemClock.elapsedRealtime()
            val swap = sensor % 180 != 0
            val w = if (swap) height else width
            val h = if (swap) width else height
            val dir = File(activity.cacheDir, "camera").apply { mkdirs() }
            dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > STALE_MS) it.delete() }
            val out = File(dir, "photo-${System.currentTimeMillis()}.jpg")
            out.outputStream().use { android.graphics.YuvImage(turned, ImageFormat.NV21, w, h, null).compressToJpeg(android.graphics.Rect(0, 0, w, h), 88, it) }
            // Shown in the review only: half size is plenty for the square.
            val shown = BitmapFactory.decodeFile(out.path, BitmapFactory.Options().apply { inSampleSize = 2 }) ?: error("not a JPEG")
            Log.d(TAG, "Photo ${w}x$h, ${out.length() / 1024} KB in ${android.os.SystemClock.elapsedRealtime() - started} ms (copy ${copied - started}, turn ${rotated - copied})")
            out to shown
        }.onFailure { Log.w(TAG, "Couldn't keep the photo", it) }.getOrNull()
        main.post {
            if (!shown || stage != Stage.TAKING) {
                file?.first?.delete()
                return@post
            }
            if (file == null) {
                stage = Stage.PREVIEW
                hint.setText(R.string.camera_capture_hint)
                return@post
            }
            review(file.first, file.second)
        }
    }

    private fun review(file: File, bitmap: Bitmap) {
        stage = Stage.REVIEW
        photo = file
        session?.let { runCatching { it.stopRepeating() } }
        still.setImageBitmap(bitmap)
        still.visibility = View.VISIBLE
        texture.visibility = View.INVISIBLE
        chip.visibility = View.GONE
        row.visibility = View.VISIBLE
        choice = 0
        renderChoices()
        hint.setText(R.string.camera_review_hint)
    }

    /**
     * The preview's transform: TextureView stretches the camera's frame, already turned by the
     * sensor's orientation, over the square; this undoes the stretch and scales it to cover the
     * square (the middle).
     */
    private fun upright(size: Size): Matrix {
        val turned = sensor % 180 != 0
        val w = (if (turned) size.height else size.width).toFloat()
        val h = (if (turned) size.width else size.height).toFloat()
        val v = side.toFloat()
        val cover = maxOf(v / w, v / h)
        return Matrix().apply {
            setScale(w / v, h / v)
            postTranslate(-w / 2, -h / 2)
            postScale(cover, cover)
            postTranslate(v / 2, v / 2)
        }
    }

    /** A YUV_420_888 frame as NV21 (Y, then V and U interleaved), whatever its strides. */
    private fun nv21(image: android.media.Image): ByteArray {
        val width = image.width
        val height = image.height
        val out = ByteArray(width * height * 3 / 2)
        val y = image.planes[0]
        val yBuffer = y.buffer
        var pos = 0
        for (row in 0 until height) {
            yBuffer.position(row * y.rowStride)
            if (y.pixelStride == 1) {
                yBuffer.get(out, pos, width)
                pos += width
            } else {
                for (col in 0 until width) out[pos++] = yBuffer.get(row * y.rowStride + col * y.pixelStride)
            }
        }
        val u = image.planes[1]
        val v = image.planes[2]
        val uBuffer = u.buffer
        val vBuffer = v.buffer
        // Most often the V plane already is NV21's VU interleave (pixel stride 2, U one byte
        // after): one copy, not a read per byte (which took seconds on the RG).
        val chroma = width * height / 2
        val interleaved = v.pixelStride == 2 && u.pixelStride == 2 && v.rowStride == width && u.rowStride == width &&
            vBuffer.remaining() >= chroma - 1 && (0 until 3).all { vBuffer.get(2 * it + 1) == uBuffer.get(2 * it) }
        if (interleaved) {
            vBuffer.position(0)
            vBuffer.get(out, pos, chroma - 1)
            out[pos + chroma - 1] = uBuffer.get(uBuffer.limit() - 1)
            return out
        }
        for (row in 0 until height / 2) {
            for (col in 0 until width / 2) {
                out[pos++] = vBuffer.get(row * v.rowStride + col * v.pixelStride)
                out[pos++] = uBuffer.get(row * u.rowStride + col * u.pixelStride)
            }
        }
        return out
    }

    private fun failed(why: String) {
        Log.w(TAG, "Camera unavailable: $why")
        if (!shown) return
        MetaToast.show(R.drawable.ic_alert, activity.getString(R.string.camera_in_use), activity.getString(R.string.camera_in_use_meta))
        finish(null)
    }

    private fun stopCamera() {
        runCatching { session?.close() }
        session = null
        runCatching { device?.close() }
        device = null
        runCatching { reader?.close() }
        reader = null
        previewSurface?.release()
        previewSurface = null
        thread?.quitSafely()
        thread = null
        handler = null
    }

    private fun finish(uri: Uri?) {
        val done = answer ?: return
        answer = null
        stopCamera()
        if (uri == null) photo?.delete()
        photo = null
        still.setImageDrawable(null)
        view.visibility = View.GONE
        hint.visibility = View.GONE
        done(uri)
    }

    private fun text(size: Float, face: android.graphics.Typeface, color: Int) = TextView(activity).apply {
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, MetaStyle.textPx(activity, size))
        typeface = face
        includeFontPadding = false
        isSingleLine = true
    }

    private fun icon(drawable: Int, size: Float) = activity.getDrawable(drawable)?.mutate()?.apply {
        setTint(MetaStyle.TEXT)
        setBounds(0, 0, px(size), px(size))
    }

    private fun px(value: Float) = MetaStyle.px(activity, value)

    companion object {
        private const val TAG = "BandCamera"
        private const val PREVIEW_WIDTH = 1280
        /** The photo's frame: 1600 x 1200 on the RG. */
        private const val FRAME_WIDTH = 1600
        /** The photo's longer side, for a page (a chat photo, a form). */
        private const val LONG_EDGE = 1600
        /** A photo left in the cache longer than this was the page's long ago. */
        private const val STALE_MS = 60 * 60 * 1000L
        private const val HINT = 50f
        private const val NONE = -1

        /** An NV21 frame of [width] x [height] turned clockwise by [degrees] (0, 90, 180 or 270). */
        fun rotateNv21(data: ByteArray, width: Int, height: Int, degrees: Int): ByteArray {
            val turn = ((degrees % 360) + 360) % 360
            if (turn == 0) return data
            val out = ByteArray(data.size)
            turnPlane(data, 0, out, 0, width, height, turn, 1)
            turnPlane(data, width * height, out, width * height, width / 2, height / 2, turn, 2)
            return out
        }

        /** One plane of [w] x [h] cells of [size] bytes (the luma's 1, the chroma's VU pairs) turned. */
        private fun turnPlane(src: ByteArray, from: Int, out: ByteArray, to: Int, w: Int, h: Int, turn: Int, size: Int) {
            val outW = if (turn == 180) w else h
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val nx: Int
                    val ny: Int
                    when (turn) {
                        90 -> { nx = h - 1 - y; ny = x }
                        180 -> { nx = w - 1 - x; ny = h - 1 - y }
                        else -> { nx = y; ny = w - 1 - x }
                    }
                    val s = from + (y * w + x) * size
                    val d = to + (ny * outW + nx) * size
                    out[d] = src[s]
                    if (size == 2) out[d + 1] = src[s + 1]
                }
            }
        }

        /** The largest 4:3 size at most [maxWidth] wide (the sensor's own shape), else the smallest. */
        fun fourByThree(sizes: Array<Size>?, maxWidth: Int): Size? {
            val all = sizes.orEmpty()
            return all.filter { it.width * 3 == it.height * 4 && it.width <= maxWidth }.maxByOrNull { it.width }
                ?: all.filter { it.width <= maxWidth }.maxByOrNull { it.width.toLong() * it.height }
                ?: all.minByOrNull { it.width.toLong() * it.height }
        }
    }
}
