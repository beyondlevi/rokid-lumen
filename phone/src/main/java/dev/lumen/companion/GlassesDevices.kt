package dev.lumen.companion

import android.content.Context
import dev.lumen.companion.computer.ComputerLink
import dev.lumen.companion.computer.ComputerProfiles
import dev.lumen.protocol.BandDevices
import dev.lumen.protocol.DeviceComputer
import dev.lumen.protocol.DeviceProfile

/**
 * What the glasses' Controls show and offer for the band ([BandDevices]): where it is, the phone's
 * profiles, the computers this phone has been a keyboard for and the computer profiles. The
 * companion's service sends it when it changes ([CompanionService]).
 */
object GlassesDevices {
    private var lastWhere: String? = null
    private var since = 0L

    fun current(context: Context): BandDevices {
        val app = context.applicationContext
        val phone = PhoneProfiles.state(app)
        val computer = ComputerProfiles.state(app)
        val onPhone = CompanionPrefs.bandOnPhone(app)
        val onComputer = onPhone && CompanionPrefs.bandOnComputer(app)
        val where = when {
            !onPhone -> BandDevices.GLASSES
            onComputer -> BandDevices.COMPUTER
            else -> BandDevices.PHONE
        }
        if (where != lastWhere) {
            // The first reading after a start says nothing about when it moved.
            since = if (lastWhere == null) 0L else System.currentTimeMillis()
            lastWhere = where
        }
        val computers = ComputerLink.computers(app)
        // The gesture that resumes, for the glasses' toast (the switch gesture is never the pause).
        val actions = when (where) {
            BandDevices.PHONE -> phone.current::action
            BandDevices.COMPUTER -> computer.current::action
            else -> null
        }
        val pauseGesture = actions?.let { action ->
            PhoneSettings.GESTURES.firstOrNull { it != phone.switchGesture && action(it) == PhoneSettings.PAUSE }
        }.orEmpty()
        return BandDevices(
            where = where,
            computer = if (onComputer) ComputerLink.computer?.address ?: computers.firstOrNull()?.address.orEmpty() else "",
            paused = onPhone && PhoneBand.status.optBoolean("paused"),
            pauseGesture = pauseGesture,
            phoneProfiles = phone.profiles.map { DeviceProfile(it.id, it.name, it.kind) },
            phoneProfile = phone.active,
            computers = computers.map { DeviceComputer(it.address, it.name) },
            computerProfiles = computer.profiles.map { DeviceProfile(it.id, it.name, it.kind) },
            computerProfile = computer.active,
            since = since,
            phase = if (onPhone) PhoneBand.phase.name.lowercase() else "",
            computerConnected = onComputer && ComputerLink.connected,
        )
    }
}
