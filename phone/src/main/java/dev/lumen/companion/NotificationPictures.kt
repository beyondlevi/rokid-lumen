package dev.lumen.companion

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.Icon
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import dev.lumen.companion.relay.NotificationTextExtractor
import dev.lumen.protocol.NotificationPicture
import dev.lumen.protocol.PictureOps
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException

/** Why a picture can't go to the glasses: one of [PictureOps]'s reasons. */
class PictureFailure(val reason: String, message: String) : Exception(message)

/** A picture ready for the link: a grayscale JPEG of [width] x [height]. */
class EncodedPicture(val bytes: ByteArray, val width: Int, val height: Int)

/**
 * The pictures in the phone's notifications, read for the glasses: a MessagingStyle message's
 * image (WhatsApp's photos, by content URI) and a BigPictureStyle's picture. Read only when the
 * glasses ask, decoded small (the HUD's square, [PictureOps.MAX_SIDE]) and sent as a grayscale
 * JPEG (the HUD is monochrome), never stored.
 */
internal object NotificationPictures {
    /** The most a source image may weigh in memory before it's decoded (a camera photo is a few MB). */
    private const val MAX_INPUT_BYTES = 32 * 1024 * 1024

    /** The pictures [sbn] carries, newest last (what a post names). */
    fun sourcesOf(sbn: StatusBarNotification): List<PictureSource> {
        val extras = sbn.notification.extras ?: return emptyList()
        val messages = runCatching {
            NotificationTextExtractor.messagingStyleMessages(extras).map { MessageData(it.dataMimeType, it.dataUri?.toString(), it.text?.toString(), it.timestamp) }
        }.getOrDefault(emptyList())
        val big = if (hasBigPicture(extras)) {
            NotificationPicture(sbn.postTime, PictureSizing.caption(extras.getCharSequence(Notification.EXTRA_SUMMARY_TEXT)?.toString()))
        } else {
            null
        }
        return PictureSizing.select(messages, big)
    }

    private fun hasBigPicture(extras: Bundle): Boolean = bigBitmap(extras) != null || bigIcon(extras) != null

    @Suppress("DEPRECATION")
    private fun bigBitmap(extras: Bundle): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) extras.getParcelable(Notification.EXTRA_PICTURE, Bitmap::class.java)
        else extras.getParcelable<Bitmap>(Notification.EXTRA_PICTURE)
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun bigIcon(extras: Bundle): Icon? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) extras.getParcelable(Notification.EXTRA_PICTURE_ICON, Icon::class.java)
        else extras.getParcelable<Icon>(Notification.EXTRA_PICTURE_ICON)
    }.getOrNull()

    /**
     * [source] read and encoded for the glasses; throws [PictureFailure]. Off the main thread: a
     * content URI is read through the app's provider, and decoding takes a moment.
     */
    fun encode(context: Context, sbn: StatusBarNotification, source: PictureSource): EncodedPicture {
        // The extras' own bitmap isn't ours to recycle; what was decoded or drawn here is.
        val extrasBitmap = (source as? PictureSource.Big)?.let { bigBitmap(sbn.notification.extras) }
        val bitmap = extrasBitmap ?: when (source) {
            is PictureSource.Message -> decodeUri(context, Uri.parse(source.uri))
            is PictureSource.Big -> bigIconPicture(context, sbn.notification.extras)
        }
        try {
            return compress(bitmap)
        } finally {
            if (bitmap !== extrasBitmap) bitmap.recycle()
        }
    }

    /**
     * A message's image through its app's provider. Android may or may not let a notification
     * listener read it (it grants listeners some notification URIs on newer versions): a refusal
     * is [PictureOps.REASON_DENIED], and the glasses say so.
     */
    private fun decodeUri(context: Context, uri: Uri): Bitmap {
        val bytes = try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() > MAX_INPUT_BYTES) throw PictureFailure(PictureOps.REASON_TOO_LARGE, "source over $MAX_INPUT_BYTES B")
                }
                out.toByteArray()
            } ?: throw PictureFailure(PictureOps.REASON_GONE, "no stream")
        } catch (e: SecurityException) {
            throw PictureFailure(PictureOps.REASON_DENIED, "the app's provider refused: ${e.message}")
        } catch (e: FileNotFoundException) {
            throw PictureFailure(PictureOps.REASON_GONE, "not found: ${e.message}")
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw PictureFailure(PictureOps.REASON_UNREADABLE, "not an image Android decodes")
        val options = BitmapFactory.Options().apply {
            inSampleSize = PictureSizing.sampleSize(bounds.outWidth, bounds.outHeight, PictureOps.MAX_SIDE)
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            ?: throw PictureFailure(PictureOps.REASON_UNREADABLE, "decoding failed")
        return upright(decoded, bytes)
    }

    /** A camera photo stands as it was taken (its EXIF orientation). */
    private fun upright(bitmap: Bitmap, bytes: ByteArray): Bitmap {
        val orientation = runCatching {
            ExifInterface(bytes.inputStream()).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val degrees = when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(degrees) }, true)
        if (rotated !== bitmap) bitmap.recycle()
        return rotated
    }

    /** The big picture given as an Icon (Android 12's way, when not a Bitmap), drawn at the HUD's size. */
    private fun bigIconPicture(context: Context, extras: Bundle): Bitmap {
        val icon = bigIcon(extras) ?: throw PictureFailure(PictureOps.REASON_GONE, "no big picture")
        val drawable = try {
            icon.loadDrawable(context)
        } catch (e: SecurityException) {
            throw PictureFailure(PictureOps.REASON_DENIED, "the icon's provider refused: ${e.message}")
        } ?: throw PictureFailure(PictureOps.REASON_UNREADABLE, "the icon didn't load")
        val (width, height) = PictureSizing.fit(drawable.intrinsicWidth.takeIf { it > 0 } ?: PictureOps.MAX_SIDE, drawable.intrinsicHeight.takeIf { it > 0 } ?: PictureOps.MAX_SIDE, PictureOps.MAX_SIDE)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(Canvas(bitmap))
        return bitmap
    }

    /** Scaled to fit, gray, JPEG: lower quality, then smaller, until it fits [PictureOps.MAX_BYTES]. */
    private fun compress(source: Bitmap): EncodedPicture {
        val gray = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG).apply {
            colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) })
        }
        var scaled: Bitmap? = null
        var scaledSide = 0
        try {
            for ((side, quality) in PictureSizing.ATTEMPTS) {
                if (side != scaledSide) {
                    scaled?.recycle()
                    val (width, height) = PictureSizing.fit(source.width, source.height, side)
                    scaled = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).also { target ->
                        // Transparency (a PNG's) goes black: what the HUD shows as nothing.
                        val canvas = Canvas(target)
                        canvas.drawColor(Color.BLACK)
                        canvas.drawBitmap(source, Rect(0, 0, source.width, source.height), Rect(0, 0, width, height), gray)
                    }
                    scaledSide = side
                }
                val target = scaled!!
                val out = ByteArrayOutputStream()
                target.compress(Bitmap.CompressFormat.JPEG, quality, out)
                if (out.size() <= PictureOps.MAX_BYTES) return EncodedPicture(out.toByteArray(), target.width, target.height)
            }
        } finally {
            scaled?.recycle()
        }
        throw PictureFailure(PictureOps.REASON_TOO_LARGE, "over ${PictureOps.MAX_BYTES} B at every size")
    }
}
