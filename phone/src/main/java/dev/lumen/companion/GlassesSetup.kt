package dev.lumen.companion

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import dev.lumen.protocol.SettingsOps

/**
 * First setup with no computer, as Rokid Nexus does it: Hi Rokid's authorization, the phone's
 * Wi-Fi, the glasses app installed from here through Rokid's link
 * ([dev.lumen.companion.update.UpdateManager.installGlassesFirstTime]), and its accessibility
 * service, the one switch the wearer turns on on the glasses. Done when the glasses app answers
 * the phone, which it only does with that switch on.
 */
object GlassesSetup {
    data class State(
        val authorized: Boolean = false,
        val linkReady: Boolean = false,
        val wifiOn: Boolean = false,
        /** What Rokid's link says; null until asked or without a link. */
        val installed: Boolean? = null,
        /** The glasses app answered (its settings schema came): its service is on. */
        val responding: Boolean = false,
        val checking: Boolean = false,
        /** The glasses' self-arm, as they last said; null before they answer. */
        val selfArm: dev.lumen.protocol.SettingsEvent.SelfArm? = null,
        /** A self-arm asked for from here, until the glasses say how it went. */
        val arming: Boolean = false,
        /** The glasses hold the band's key (null: they don't say). */
        val glassesHaveKey: Boolean? = null,
        /** This phone holds a key to send (imported in the Band tab, or claimed). */
        val phoneHasKey: Boolean = false,
        /** The glasses' answer to the key sent from here. */
        val keyResult: dev.lumen.protocol.SettingsEvent.Result? = null,
    ) {
        val armed: Boolean get() = selfArm?.armed == true
        val keyDone: Boolean get() = glassesHaveKey == true
        /** The glasses answer, are armed and have the band's key. */
        val done: Boolean get() = responding && armed && keyDone
        /** Steps done of [STEPS]: authorization, Wi-Fi, install, accessibility, self-arm, band key. */
        val stepsDone: Int get() = listOf(authorized && linkReady, wifiOn, installed == true || responding, responding, armed, keyDone).count { it }
    }

    const val STEPS = 6

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var installed: Boolean? = null
    @Volatile private var checking = false

    val listeners = mutableSetOf<() -> Unit>()

    fun state(context: Context): State = State(
        authorized = CompanionPrefs.token(context) != null,
        linkReady = CompanionService.linkReady,
        wifiOn = context.getSystemService(WifiManager::class.java)?.isWifiEnabled == true,
        installed = installed,
        // A schema the glasses sent before their app was removed doesn't count.
        responding = BandStore.schema != null && installed != false,
        checking = checking,
        // Nor its armed state and key: an app installed again starts over on both.
        selfArm = BandStore.selfArm.takeIf { installed != false },
        arming = arming && BandStore.selfArm?.let { !it.armed && it.state !in ARM_STOPPED } != false,
        glassesHaveKey = BandStore.status.hasKey.takeIf { installed != false },
        phoneHasKey = dev.lumen.band.Identity.present(context),
        keyResult = BandStore.bandKeyResult,
    )

    /** Self-arm states where it stopped (an error, or a step only a person can do). */
    private val ARM_STOPPED = setOf(
        "api_30_required", "accessibility_service_needed", "nexus_present", "usb_debugging_off", "adb_key_missing",
        "adb_loopback_unreachable", "bootstrap_rearm_failed", "pairing_code_expired", "wifi_enable_timeout",
        "wireless_setup_timeout", "self_pairing_failed", "developer_options_manual_step_needed",
        "wireless_debugging_manual_step_needed",
    )

    @Volatile private var arming = false

    /** Runs the self-arm on the glasses; its steps come back as the glasses' messages. */
    fun prepareGlasses() {
        arming = true
        CompanionService.requestSettings(SettingsOps.action(SettingsOps.ACTION_SELF_ARM))
        changed()
    }

    /** Sends this phone's band key to the glasses; false when there's none or no link. */
    fun sendKey(context: Context): Boolean {
        val bundle = dev.lumen.band.Identity.exportBundle(context) ?: return false
        return CompanionService.sendBandKey(bundle).also { changed() }
    }

    /** Asks Rokid's link whether the glasses app is there, and the app itself to answer. */
    fun check() {
        // The answer also brings the self-arm's state: always ask (one small message).
        CompanionService.requestSettings(SettingsOps.describe())
        if (checking || !CompanionService.linkReady) return
        checking = true
        changed()
        Thread({
            val answer = CompanionService.isInstalledOnGlasses()
            main.post {
                checking = false
                if (answer != null) installed = answer
                changed()
            }
        }, "nb-setup-check").start()
    }

    /** Opens the glasses app's setup entry there: the accessibility switch, or its home once on. */
    fun openOnGlasses() {
        Thread({ CompanionService.openOnGlasses(dev.lumen.companion.update.UpdateManager.SETUP_ENTRY) }, "nb-setup-open").start()
    }

    private fun changed() = main.post { listeners.toList().forEach { it() } }
}
