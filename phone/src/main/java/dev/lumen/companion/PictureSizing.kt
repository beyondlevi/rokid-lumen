package dev.lumen.companion

import dev.lumen.protocol.NotificationPicture
import dev.lumen.protocol.PictureOps

/** A message's attachment as a MessagingStyle notification describes it, oldest first. */
internal data class MessageData(val mime: String?, val uri: String?, val text: String?, val timestamp: Long)

/** Where one of a notification's pictures is: a message's content URI, or the big picture. */
internal sealed class PictureSource {
    abstract val picture: NotificationPicture

    data class Message(val uri: String, override val picture: NotificationPicture) : PictureSource()

    data class Big(override val picture: NotificationPicture) : PictureSource()
}

/**
 * The pure part of a notification's pictures: which ones a post names, which one a request
 * means, and how big the phone decodes and sends them.
 */
internal object PictureSizing {
    /** What a JPEG goes through until it fits [PictureOps.MAX_BYTES]: (longer side, quality), in order. */
    val ATTEMPTS = listOf(PictureOps.MAX_SIDE to 70, PictureOps.MAX_SIDE to 55, PictureOps.MAX_SIDE to 40, 360 to 50, 280 to 45)

    /**
     * The pictures of a notification, newest last: messages with an image (their text the
     * caption), then the big picture, the newest [PictureOps.MAX_PICTURES] of them.
     */
    fun select(messages: List<MessageData>, big: NotificationPicture?): List<PictureSource> {
        val fromMessages = messages.filter { it.mime?.startsWith("image/") == true && !it.uri.isNullOrBlank() }
            .map { PictureSource.Message(it.uri!!, NotificationPicture(it.timestamp, caption(it.text))) }
        return (fromMessages + listOfNotNull(big?.let { PictureSource.Big(it) })).takeLast(PictureOps.MAX_PICTURES)
    }

    /** The caption the glasses match against the text's lines: the message's last line. */
    fun caption(text: String?): String =
        text.orEmpty().lines().map { it.trim() }.lastOrNull { it.isNotEmpty() }.orEmpty().take(PictureOps.MAX_CAPTION)

    /**
     * The picture a request means: the one at [index] when its time is [at], else the one with
     * that time (the notification changed since the post), else none.
     */
    fun resolve(sources: List<PictureSource>, index: Int, at: Long): PictureSource? {
        sources.getOrNull(index)?.takeIf { it.picture.at == at }?.let { return it }
        return sources.firstOrNull { it.picture.at == at }
    }

    /**
     * BitmapFactory's inSampleSize for a [width] x [height] picture: the largest power of two
     * that still decodes it at least [maxSide] on its longer side (the exact scaling follows).
     */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        val longer = maxOf(width, height)
        if (longer <= 0 || maxSide <= 0) return 1
        var sample = 1
        while (longer / (sample * 2) >= maxSide) sample *= 2
        return sample
    }

    /** [width] x [height] scaled down to at most [maxSide] on its longer side (never up), each side at least 1. */
    fun fit(width: Int, height: Int, maxSide: Int): Pair<Int, Int> {
        val longer = maxOf(width, height)
        if (longer <= maxSide || longer <= 0) return maxOf(1, width) to maxOf(1, height)
        val scale = maxSide.toDouble() / longer
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
