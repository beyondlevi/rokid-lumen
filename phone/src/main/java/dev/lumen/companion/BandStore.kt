package dev.lumen.companion

import dev.lumen.protocol.BandStatus
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.SettingsEvent

/**
 * The band's settings and status as the glasses last described them ([SettingsEvent]). The
 * glasses own them: this is a copy for the screen, refreshed on every schema and status push.
 */
object BandStore {
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
            is SettingsEvent.Result -> lastError = event.takeIf { !it.ok }
        }
        listeners.toList().forEach { it() }
    }
}
