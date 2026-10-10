package dev.lumen.glasses

import dev.lumen.protocol.NotificationPicture

/** One bubble of an open notification: a line of its text, or one of its pictures with its caption. */
sealed class DetailBubble {
    data class Text(val line: String) : DetailBubble()

    /** Picture [index] of the notification; [caption] is the line it took ("" for none). */
    data class Photo(val index: Int, val caption: String) : DetailBubble()
}

/** The parts of an open notification the band's focus moves through, top to bottom. */
enum class DetailZone { CONTENT, REPLY, BAR }

/** The reply row's two stops: the field and the send button. */
enum class ReplyPart { FIELD, SEND }

/** What the reply row's hint (under the HUD's square) says. */
enum class ReplyHint { FIELD, SEND, RETRY }

/**
 * The reply field's state: what's typed, and whether it's on its way to the phone or didn't
 * go. Sending needs text and nothing in flight; a reply that went clears the field, one that
 * didn't keeps the text for another try.
 */
data class ReplyState(val text: String = "", val sending: Boolean = false, val failed: Boolean = false) {
    val canSend: Boolean get() = text.isNotBlank() && !sending

    fun edited(next: String): ReplyState = if (next == text) this else copy(text = next, failed = false)

    fun sent(): ReplyState = copy(sending = true, failed = false)

    fun replied(ok: Boolean): ReplyState = if (ok) ReplyState() else copy(sending = false, failed = true)

    /** The hint for the band on [part] (none while the field is typed in: the keyboard has its own). */
    fun hint(part: ReplyPart, typing: Boolean): ReplyHint? = when {
        typing -> null
        part == ReplyPart.FIELD -> ReplyHint.FIELD
        failed && canSend -> ReplyHint.RETRY
        canSend -> ReplyHint.SEND
        else -> null
    }
}

/**
 * The pure part of an open notification ([NotificationsPage]): its bubbles, and where the band
 * goes between its content (the pictures), the reply row and the quick bar.
 */
object NotificationDetail {
    /** A vertical move: scroll the content by one step, or go to another zone, or nothing. */
    sealed class Step {
        object Stay : Step()
        data class Scroll(val delta: Int) : Step()
        data class Zone(val zone: DetailZone) : Step()
    }

    /**
     * The text's [lines] (oldest first) and its [pictures] as bubbles: a picture takes the place
     * of its caption's line ("Ana: caption" or the caption alone), matched from the newest, as
     * the text keeps the newest messages; pictures whose line isn't there (an older message, a
     * big picture) come first, with their own caption.
     */
    @JvmStatic
    fun bubbles(lines: List<String>, pictures: List<NotificationPicture>): List<DetailBubble> {
        val placed = arrayOfNulls<Int>(lines.size)
        val unplaced = mutableListOf<Int>()
        var until = lines.size
        for (index in pictures.indices.reversed()) {
            val caption = pictures[index].caption.trim()
            val at = if (caption.isEmpty()) -1 else (until - 1 downTo 0).firstOrNull { placed[it] == null && matches(lines[it], caption) } ?: -1
            if (at >= 0) {
                placed[at] = index
                until = at
            } else {
                unplaced += index
            }
        }
        return unplaced.sorted().map { DetailBubble.Photo(it, pictures[it].caption.trim()) } +
            lines.mapIndexed { at, line -> placed[at]?.let { DetailBubble.Photo(it, line) } ?: DetailBubble.Text(line) }
    }

    private fun matches(line: String, caption: String): Boolean {
        val trimmed = line.trim()
        return trimmed == caption || trimmed.endsWith(": $caption")
    }

    /** The zones an open notification has, top to bottom. */
    @JvmStatic
    fun zones(photos: Boolean, replyRow: Boolean, bar: Boolean): List<DetailZone> =
        listOfNotNull(DetailZone.CONTENT.takeIf { photos }, DetailZone.REPLY.takeIf { replyRow }, DetailZone.BAR.takeIf { bar })

    /**
     * Where the focus starts: on the pictures when there are some (the photo is what came),
     * else on the quick bar's first button, as before the reply row.
     */
    @JvmStatic
    fun initial(zones: List<DetailZone>): DetailZone? = when {
        DetailZone.CONTENT in zones -> DetailZone.CONTENT
        DetailZone.BAR in zones -> DetailZone.BAR
        else -> zones.firstOrNull()
    }

    /**
     * Up or [down] from [current]: the content scrolls until its end ([canScrollDown]), then the
     * focus goes down to the reply row and the bar; up goes back the same way. The top zone's
     * up scrolls the text back, the bottom zone's down scrolls it on (the bar's old way, when
     * it was the only one); with no zones, both scroll.
     */
    @JvmStatic
    fun vertical(zones: List<DetailZone>, current: DetailZone?, down: Boolean, canScrollDown: Boolean): Step {
        val delta = if (down) 1 else -1
        val at = zones.indexOf(current)
        if (current == null || at < 0) return Step.Scroll(delta)
        if (current == DetailZone.CONTENT && (!down || canScrollDown)) return Step.Scroll(delta)
        val next = zones.getOrNull(at + delta) ?: return if (current == DetailZone.CONTENT) Step.Stay else Step.Scroll(delta)
        return Step.Zone(next)
    }

    /** Left or right in the reply row. */
    @JvmStatic
    fun side(delta: Int): ReplyPart = if (delta > 0) ReplyPart.SEND else ReplyPart.FIELD

    /** [width] x [height] fitted in [maxWidth] x [maxHeight] (a picture in its bubble), keeping its shape, never enlarged, each side at least 1. */
    @JvmStatic
    fun fitInto(width: Int, height: Int, maxWidth: Int, maxHeight: Int): Pair<Int, Int> {
        if (width <= 0 || height <= 0) return 1 to 1
        val scale = minOf(1.0, maxWidth.toDouble() / width, maxHeight.toDouble() / height)
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }
}
