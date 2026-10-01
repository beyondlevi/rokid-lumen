// Ported from Rokid Nexus's Relay plugin (https://github.com/Anezium/Rokid-Nexus,
// plugins/relay), Copyright Anezium, Apache License 2.0 (see NOTICE). Modified for the
// Rokid Lumen Companion: package, settings and limits.
package dev.lumen.companion.relay

internal object SensitiveNotificationDetector {
    const val HIDDEN_BODY = "Hidden by Android — read it on your phone"
    const val ENGLISH_REDACTION_MESSAGE = "Sensitive notification content hidden"

    fun isRedacted(
        title: String?,
        text: String?,
        resolvedStrings: Collection<String>,
    ): Boolean {
        val markers = resolvedStrings
            .filter(String::isNotBlank)
            .toSet()
            .ifEmpty { setOf(ENGLISH_REDACTION_MESSAGE) }
        return listOfNotNull(
            title?.takeIf(String::isNotBlank),
            text?.takeIf(String::isNotBlank),
        ).any(markers::contains)
    }
}
