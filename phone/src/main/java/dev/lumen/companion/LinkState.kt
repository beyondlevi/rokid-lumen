package dev.lumen.companion

import androidx.annotation.StringRes

/**
 * Where the link to the glasses stands, as [CompanionService] last saw it. The screen shows
 * [label] in the phone's language; the log gets the name and the SDK's reason.
 */
enum class LinkState(@StringRes val label: Int, val healthy: Boolean = false) {
    STOPPED(R.string.link_stopped),
    NOT_AUTHORIZED(R.string.link_not_authorized),
    CONNECTING(R.string.link_connecting),
    REFUSED(R.string.link_refused),
    HI_ROKID_CONNECTED(R.string.link_hi_rokid_connected, healthy = true),
    HI_ROKID_DISCONNECTED(R.string.link_hi_rokid_disconnected),
    GLASSES_CONNECTED(R.string.link_glasses_connected, healthy = true),
    GLASSES_DISCONNECTED(R.string.link_glasses_disconnected),
    SESSION_READY(R.string.link_session_ready, healthy = true),
    SESSION_ACTIVE(R.string.link_session_active, healthy = true),
    SESSION_PAUSED(R.string.link_session_paused),
    SESSION_UNAVAILABLE(R.string.link_session_unavailable),
    DICTATING(R.string.link_dictating, healthy = true),
}
