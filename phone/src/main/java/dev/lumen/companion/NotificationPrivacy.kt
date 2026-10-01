package dev.lumen.companion

import android.app.Notification

/** What of a notification's content the glasses get ([NotificationPrivacy.decide]). */
sealed class NotificationContent {
    /** Its own title and text. */
    object Full : NotificationContent()

    /** Its public version's title and text, in place of its own (a private notification). */
    data class Public(val title: String, val text: String) : NotificationContent()

    /** Its title only: the text stays on the phone (the glasses show it as hidden). */
    object Redacted : NotificationContent()
}

/**
 * How private a notification is on the glasses, which show it where anyone near the wearer
 * could read it, as a lock screen does.
 */
object NotificationPrivacy {
    /**
     * `VISIBILITY_PRIVATE` is honoured on Android 14 (API 34) and older: the public version
     * stands in for the notification, as on the lock screen.
     */
    const val LAST_SDK_HONOURING_PRIVATE = 34

    /**
     * [sensitive]: Android redacted it already (a one-time code, say); [hideAll]: the user hides
     * every notification's content; [visibility]: the notification's lock-screen visibility;
     * [publicTitle] and [publicText]: its public version's, when it has one.
     */
    @JvmStatic
    fun decide(
        sdk: Int,
        visibility: Int,
        sensitive: Boolean,
        hideAll: Boolean,
        publicTitle: String?,
        publicText: String?,
    ): NotificationContent {
        if (sensitive || hideAll || visibility == Notification.VISIBILITY_SECRET) return NotificationContent.Redacted
        if (visibility == Notification.VISIBILITY_PRIVATE && sdk <= LAST_SDK_HONOURING_PRIVATE) {
            val title = publicTitle?.trim().orEmpty()
            val text = publicText?.trim().orEmpty()
            return if (title.isEmpty() && text.isEmpty()) NotificationContent.Redacted else NotificationContent.Public(title, text)
        }
        return NotificationContent.Full
    }
}
