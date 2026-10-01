package dev.lumen.glasses

import android.content.Context
import android.content.Intent

object LocalSelfArmStatus {
    const val ACTION_CHANGED = "dev.lumen.glasses.LOCAL_SELF_ARM_STATUS"

    private const val KEY_STATE = "local_self_arm_state"
    private const val KEY_MESSAGE = "local_self_arm_message"
    private const val KEY_ERROR = "local_self_arm_error"
    private const val KEY_UPDATED_AT = "local_self_arm_updated_at"

    @JvmStatic
    fun reportSimple(context: Context, setupState: String) {
        report(context, setupState = setupState)
    }

    @JvmStatic
    fun report(
        context: Context,
        setupState: String,
        wifiIp: String = "",
        adbPairCode: String = "",
        adbPairHost: String = "",
        adbPairPort: Int = 0,
        adbConnectPort: Int = 0,
        errorMessage: String = "",
    ) {
        val message = label(context.applicationContext, setupState, adbPairPort, adbConnectPort, errorMessage)
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, setupState)
            .putString(KEY_MESSAGE, message)
            .putString(KEY_ERROR, errorMessage)
            .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
            .apply()
        runCatching {
            context.sendBroadcast(
                Intent(ACTION_CHANGED)
                    .setPackage(context.packageName)
                    .putExtra("state", setupState)
                    .putExtra("message", message)
                    .putExtra("wifiIp", wifiIp)
                    .putExtra("adbPairCodePresent", adbPairCode.isNotBlank())
                    .putExtra("adbPairHost", adbPairHost)
                    .putExtra("adbPairPort", adbPairPort)
                    .putExtra("adbConnectPort", adbConnectPort),
            )
        }
    }

    @JvmStatic
    fun summary(context: Context): String =
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getString(KEY_MESSAGE, "")
            .orEmpty()

    @JvmStatic
    fun state(context: Context): String =
        context.applicationContext
            .getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
            .getString(KEY_STATE, "")
            .orEmpty()

    private fun label(
        context: Context,
        setupState: String,
        adbPairPort: Int,
        adbConnectPort: Int,
        errorMessage: String,
    ): String =
        when (setupState) {
            "wireless_debugging_open" -> portLabel(context, context.getString(R.string.sa_wd_open), adbConnectPort)
            "wireless_debugging_on" -> portLabel(context, context.getString(R.string.sa_wd_on), adbConnectPort)
            "pairing_ready" -> portLabel(context, context.getString(R.string.sa_code_ready), adbPairPort)
            "self_pairing_failed" -> context.getString(
                R.string.sa_pairing_failed,
                errorMessage.ifBlank { context.getString(R.string.sa_pairing_failed_default) },
            )
            else -> STATE_LABELS[setupState]?.let { context.getString(it) }
                ?: context.getString(R.string.sa_other, setupState.replace('_', ' '))
        }

    /** The setup states' labels; the maintenance self-arm's outcomes are among them. */
    private val STATE_LABELS = mapOf(
        "requested" to R.string.sa_requested,
        "api_30_required" to R.string.self_arm_needs_11,
        "accessibility_service_needed" to R.string.enable_accessibility_first,
        "starting_wireless_debugging_setup" to R.string.sa_opening_setup,
        "enabling_wifi" to R.string.sa_enabling_wifi,
        "wifi_on" to R.string.sa_wifi_on,
        "waiting_for_settings" to R.string.sa_waiting_settings,
        "developer_options_disabled" to R.string.sa_enabling_dev,
        "opening_developer_options" to R.string.sa_opening_dev,
        "enabling_developer_options" to R.string.sa_tapping_build,
        "searching_build_number" to R.string.sa_finding_build,
        "developer_options_manual_step_needed" to R.string.sa_dev_manual,
        "opening_wireless_debugging" to R.string.sa_opening_wd,
        "searching_wireless_debugging" to R.string.sa_finding_wd,
        "turning_wireless_debugging_on" to R.string.sa_turning_wd_on,
        "confirming_wireless_debugging" to R.string.sa_confirming_wd,
        "opening_pairing_code" to R.string.sa_opening_code,
        "waiting_for_pairing_code" to R.string.sa_waiting_code,
        "searching_pairing_code" to R.string.sa_finding_code,
        "self_pairing_started" to R.string.sa_pairing,
        "wireless_bootstrap_complete" to R.string.sa_complete,
        "auto_rearm_complete" to R.string.sa_auto_complete,
        "nexus_present" to R.string.self_arm_nexus,
        "usb_debugging_off" to R.string.sa_usb_off,
        "adb_key_missing" to R.string.sa_key_missing,
        "adb_loopback_unreachable" to R.string.sa_loopback,
        "bootstrap_rearm_failed" to R.string.sa_rearm_failed,
        "pairing_code_expired" to R.string.sa_code_expired,
        "wifi_enable_timeout" to R.string.sa_wifi_timeout,
        "wireless_setup_timeout" to R.string.sa_setup_timeout,
        "wireless_debugging_manual_step_needed" to R.string.sa_settings_tap,
    )

    private fun portLabel(context: Context, prefix: String, port: Int): String =
        if (port > 0) context.getString(R.string.sa_with_port, prefix, port) else prefix
}
