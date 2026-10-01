package dev.lumen.glasses

import android.content.Context
import android.util.Log

/**
 * The Settings app's own labels, in the device's language, read from its resources by their AOSP
 * names. The self-arm matches screens against these first: its hand-written lists missed the
 * Portuguese (Portugal) firmware ("Opções de programador", not "Opções do desenvolvedor") and
 * timed out on a page it was already on (measured). Names a firmware lacks are skipped.
 */
internal object SettingsLabels {
    private const val TAG = "BandSettingsLabels"
    private const val SETTINGS = "com.android.settings"
    private val cache = HashMap<String, List<String>>()

    const val DEVELOPER_OPTIONS = "development_settings_title"
    const val WIRELESS_DEBUGGING = "enable_adb_wireless"
    const val USE_WIRELESS_DEBUGGING = "wireless_debugging_main_switch_title"
    const val PAIR_WITH_CODE = "adb_pair_method_code_title"
    const val PAIR_DIALOG_TITLE = "adb_pairing_device_dialog_title"
    const val PAIRING_CODE = "adb_pairing_device_dialog_pairing_code_label"
    const val IP_AND_PORT = "adb_device_ip_addr_title"
    const val DEVELOPER_OPTIONS_FIRST = "dev_settings_disabled_warning"

    /** The labels for [names] that this firmware has, non-blank. */
    @JvmStatic
    fun of(context: Context, vararg names: String): Array<String> =
        names.flatMap { name -> cache.getOrPut(name) { load(context, name) } }.toTypedArray()

    private fun load(context: Context, name: String): List<String> = runCatching {
        val resources = context.packageManager.getResourcesForApplication(SETTINGS)
        val id = resources.getIdentifier(name, "string", SETTINGS)
        if (id == 0) emptyList() else listOf(resources.getString(id)).filter { it.isNotBlank() }
    }.onSuccess { Log.d(TAG, "$name = $it") }
        .onFailure { Log.d(TAG, "$name unreadable: ${it.message}") }
        .getOrDefault(emptyList())
}
