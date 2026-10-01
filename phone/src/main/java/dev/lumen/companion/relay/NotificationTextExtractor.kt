// Ported from Rokid Nexus's Relay plugin (https://github.com/Anezium/Rokid-Nexus,
// plugins/relay), Copyright Anezium, Apache License 2.0 (see NOTICE). Modified for the
// Rokid Lumen Companion: package, settings and limits.
package dev.lumen.companion.relay

import android.app.Notification
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import java.util.Locale

internal data class ExtractedMessage(
    val text: String?,
    val sender: String?,
    val timestamp: Long,
)

internal data class NotificationTextInput(
    val messages: List<ExtractedMessage> = emptyList(),
    val expandedLines: List<String?> = emptyList(),
    val bigText: String? = null,
    val text: String? = null,
)

internal object NotificationTextExtractor {
    fun extract(input: NotificationTextInput, messageLimit: Int): String {
        val limit = messageLimit.coerceIn(MIN_MESSAGES, MAX_MESSAGES)
        val messages = messagingStyleText(input.messages, limit)
        val lines = expandedTextLines(input.expandedLines, limit)
        val selected = when {
            messages.isNotBlank() && shouldPreferExpandedLines(messages, lines) -> lines
            messages.isNotBlank() -> messages
            lines.isNotBlank() -> lines
            !input.bigText.isNullOrBlank() -> input.bigText
            else -> input.text.orEmpty()
        }
        return trimFromTop(selected, MAX_BODY_CHARS)
    }

    fun shouldPreferExpandedLines(primary: String, lines: String): Boolean {
        if (lines.isBlank()) return false
        val cleanPrimary = primary.normalizedForComparison()
        val cleanLines = lines.normalizedForComparison()
        if (cleanLines.isBlank() || cleanLines == cleanPrimary) return false
        if (primary.looksLikeCollapsedMessageSummary()) return true
        return lines.count { it == '\n' } >= primary.count { it == '\n' } &&
            cleanLines.length > cleanPrimary.length + EXPANDED_LINES_DETAIL_MARGIN
    }

    /** Drops complete oldest lines first, then the oldest characters of one oversized message. */
    fun trimFromTop(value: String, maxChars: Int): String {
        var remaining = value.trim()
        if (maxChars <= 0) return ""
        while (remaining.length > maxChars) {
            val firstBreak = remaining.indexOf('\n')
            if (firstBreak < 0) return remaining.takeLast(maxChars)
            remaining = remaining.substring(firstBreak + 1).trimStart()
        }
        return remaining
    }

    fun fromExtras(extras: Bundle): NotificationTextInput = NotificationTextInput(
        messages = messagingStyleMessages(extras).map { message ->
            ExtractedMessage(
                text = message.text?.toString(),
                sender = message.senderPerson?.name?.toString(),
                timestamp = message.timestamp,
            )
        },
        expandedLines = extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)
            .orEmpty()
            .map { it?.toString() },
        bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString(),
        text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString(),
    )

    fun messagingStyleMessages(extras: Bundle): List<Notification.MessagingStyle.Message> {
        val bundles = messageBundles(extras) ?: return emptyList()
        val messages = Notification.MessagingStyle.Message.getMessagesFromBundleArray(bundles)
        if (messages.none { it.timestamp > 0L }) return messages
        return messages
            .mapIndexed { index, message -> index to message }
            .sortedWith(
                compareBy<Pair<Int, Notification.MessagingStyle.Message>> {
                    it.second.timestamp.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE
                }.thenBy { it.first },
            )
            .map { it.second }
    }

    private fun messagingStyleText(messages: List<ExtractedMessage>, messageLimit: Int): String {
        val ordered = if (messages.none { it.timestamp > 0L }) {
            messages
        } else {
            messages.withIndex()
                .sortedWith(
                    compareBy<IndexedValue<ExtractedMessage>> {
                        it.value.timestamp.takeIf { timestamp -> timestamp > 0L } ?: Long.MAX_VALUE
                    }.thenBy { it.index },
                )
                .map { it.value }
        }
        return ordered
            .takeLast(messageLimit)
            .mapNotNull { message ->
                val text = message.text?.trim().orEmpty()
                if (text.isBlank()) {
                    null
                } else {
                    val sender = message.sender?.trim().orEmpty()
                    if (sender.isBlank()) text else "$sender: $text"
                }
            }
            .joinToString("\n")
    }

    private fun expandedTextLines(lines: List<String?>, messageLimit: Int): String =
        lines
            .map { it?.trim().orEmpty() }
            .filter(String::isNotBlank)
            .takeLast(messageLimit)
            .joinToString("\n")

    private fun String.normalizedForComparison(): String =
        trim().replace(Regex("\\s+"), " ").lowercase(Locale.ROOT)

    private fun String.looksLikeCollapsedMessageSummary(): Boolean {
        val normalized = normalizedForComparison()
        val tail = normalized.substringAfterLast(": ").trim()
        return COLLAPSED_MESSAGE_SUMMARY_PATTERNS.any { pattern ->
            pattern.matches(normalized) || pattern.matches(tail)
        }
    }

    private fun messageBundles(extras: Bundle): Array<Parcelable>? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            extras.getParcelableArray(Notification.EXTRA_MESSAGES, Parcelable::class.java)
        } else {
            @Suppress("DEPRECATION")
            extras.getParcelableArray(Notification.EXTRA_MESSAGES)
        }

    const val MIN_MESSAGES = 1
    const val MAX_MESSAGES = 10
    /** The glasses show a few lines; the rest stays on the phone. */
    const val MAX_BODY_CHARS = 1_500
    private const val EXPANDED_LINES_DETAIL_MARGIN = 24
    private val COLLAPSED_MESSAGE_SUMMARY_PATTERNS = listOf(
        Regex("""\d+\s+new\s+messages?"""),
        Regex("""\d+\s+messages?\s+from\s+\d+\s+(chats?|contacts?|conversations?)"""),
        Regex("""\d+\s+(chats?|contacts?|conversations?)\s+from\s+\d+\s+messages?"""),
    )
}
