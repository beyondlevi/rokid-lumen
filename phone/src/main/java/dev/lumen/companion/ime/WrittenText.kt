package dev.lumen.companion.ime

/**
 * What the keyboard has typed from the band's writing, and the edit that turns it into the
 * band's latest text: the band reports its whole text each time (with its deletions applied),
 * the field only takes edits at the cursor.
 */
class WrittenText {
    var sent = ""
        private set

    /** One edit at the cursor: delete [delete] characters before it, then insert [insert]. */
    data class Edit(val delete: Int, val insert: String)

    /** The edit from what was typed to [next]; after it, [next] is what was typed. */
    fun update(next: String): Edit {
        var common = 0
        val limit = minOf(sent.length, next.length)
        while (common < limit && sent[common] == next[common]) common++
        // Never split a surrogate pair (an emoji) in two.
        if (common > 0 && Character.isHighSurrogate(next[common - 1])) common--
        val edit = Edit(sent.length - common, next.substring(common))
        sent = next
        return edit
    }

    /** The field changed by other means (a key on the keyboard): start counting again. */
    fun reset() {
        sent = ""
    }
}
