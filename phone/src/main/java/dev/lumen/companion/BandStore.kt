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

    /**
     * Values just set from the phone that the glasses haven't confirmed yet (Rokid's link can hold
     * a request for minutes): the screen shows them, as on their way, until a schema has them, a
     * refusal comes, or [PENDING_MS] pass.
     */
    @Volatile private var pendingSets: Map<String, Pair<String, Long>> = emptyMap()

    val pending: Map<String, String>
        get() {
            val now = System.currentTimeMillis()
            return pendingSets.filterValues { now - it.second < PENDING_MS }.mapValues { it.value.first }
        }

    fun setPending(key: String, value: String) {
        pendingSets = pendingSets + (key to (value to System.currentTimeMillis()))
        listeners.toList().forEach { it() }
    }

    private const val PENDING_MS = 10 * 60_000L

    fun onEvent(event: SettingsEvent) {
        when (event) {
            is SettingsEvent.Schema -> {
                schema = event
                status = event.status
                debug = event.debug
                lastError = null
                // What the glasses now have is no longer on its way.
                pendingSets = pendingSets.filter { (key, value) -> event.settings.firstOrNull { it.key == key }?.value != value.first }
            }
            is SettingsEvent.Status -> status = event.status
            is SettingsEvent.Debug -> debug = event.debug
            is SettingsEvent.Result -> {
                if (event.subject == BAND_KEY) bandKeyResult = event else lastError = event.takeIf { !it.ok }
                if (!event.ok) pendingSets = pendingSets - event.subject
            }
            is SettingsEvent.SelfArm -> selfArm = event
            // The glasses moved the band ([PhoneBand.onGlassesTarget]): nothing to keep here.
            is SettingsEvent.BandTarget -> Unit
        }
        listeners.toList().forEach { it() }
    }
}
