package dev.lumen.companion

import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.SettingsEvent

/**
 * The band's settings and status as the glasses last described them ([SettingsEvent]). The
 * glasses own them: this is a copy for the screen, refreshed on every schema and status push.
 */
object BandStore {
    const val BAND_KEY = "band_key"

    @Volatile var schema: SettingsEvent.Schema? = null
        private set

    @Volatile var status: BandStatus = BandStatus()
        private set

    /** The glasses' wireless debugging ([GlassesDebug]). */
    @Volatile var debug: DebugStatus = DebugStatus()
        private set

    /** The last refusal from the glasses (the setting or action, and why), until the next change. */
    @Volatile var lastError: SettingsEvent.Result? = null
        private set

    /** The glasses' self-arm as they last said ([SettingsEvent.SelfArm]). */
    @Volatile var selfArm: SettingsEvent.SelfArm? = null
        private set

    /** The glasses' answer to the band key sent from the phone (subject "band_key"). */
    @Volatile var bandKeyResult: SettingsEvent.Result? = null
        private set

    val listeners = mutableSetOf<() -> Unit>()

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Schema -> {
                schema = event
                status = event.status
                debug = event.debug
                lastError = null
            }
            is SettingsEvent.Status -> status = event.status
            is SettingsEvent.Debug -> debug = event.debug
            is SettingsEvent.Result -> {
                if (event.subject == BAND_KEY) bandKeyResult = event else lastError = event.takeIf { !it.ok }
            }
            is SettingsEvent.SelfArm -> selfArm = event
        }
        listeners.toList().forEach { it() }
    }
}
