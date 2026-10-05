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
    ) {
        val done: Boolean get() = responding
        /** Steps done of [STEPS]: authorization, Wi-Fi, install, accessibility. */
        val stepsDone: Int get() = listOf(authorized && linkReady, wifiOn, installed == true || responding, responding).count { it }
    }

    const val STEPS = 4

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
    )

    /** Asks Rokid's link whether the glasses app is there, and the app itself to answer. */
    fun check() {
        if (BandStore.schema == null) CompanionService.requestSettings(SettingsOps.describe())
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
