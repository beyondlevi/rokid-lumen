/*
 * Derived from Rokid Nexus (https://github.com/Anezium/Rokid-Nexus), Copyright Anezium,
 * licensed under the Apache License, Version 2.0 (LICENSES/Apache-2.0.txt):
 * SelfArmAccessibilityHandoff.kt. Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.text.TextUtils

/**
 * The one switch Lumen can't flip for the wearer: its accessibility service, which only a person
 * can turn on in Settings. This lands them as close to it as the firmware allows: the service's own
 * page when Settings knows that screen, the Accessibility list otherwise.
 */
object AccessibilityHandoff {
    enum class Landing { DETAILS, LIST, UNAVAILABLE }

    @JvmStatic
    fun isEnabled(context: Context): Boolean {
        val flat = component(context).flattenToString()
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        if (TextUtils.isEmpty(enabled)) return false
        return enabled.split(':').any { it.equals(flat, ignoreCase = true) }
    }

    @JvmStatic
    fun open(context: Context): Landing {
        // The action and the extra are AOSP-internal (Android 12 has them, the SDK doesn't): ask
        // whether this firmware's Settings has the screen before counting on it. The Rokid's has
        // it but guards it with OPEN_ACCESSIBILITY_DETAILS_SETTINGS, a system-only permission
        // (measured): the start fails and the list opens, Rokid Lumen at its top.
        val details = detailsIntent(context)
        if (resolves(context, details) && start(context, details)) return Landing.DETAILS
        if (start(context, listIntent().setPackage(SETTINGS_PACKAGE))) return Landing.LIST
        if (start(context, listIntent())) return Landing.LIST
        return Landing.UNAVAILABLE
    }

    private fun component(context: Context) = ComponentName(context, BandAccessibilityService::class.java)

    private fun detailsIntent(context: Context): Intent {
        val flat = component(context).flattenToString()
        return Intent(ACTION_DETAILS)
            .setPackage(SETTINGS_PACKAGE)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(EXTRA_COMPONENT, flat)
            // Some Settings builds route the page through SubSettings and read this key instead.
            .putExtra(EXTRA_FRAGMENT_ARG_KEY, flat)
    }

    private fun listIntent() = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)

    private fun resolves(context: Context, intent: Intent) =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private fun start(context: Context, intent: Intent) = runCatching { context.startActivity(intent) }.isSuccess

    private const val SETTINGS_PACKAGE = "com.android.settings"
    private const val ACTION_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS"
    private const val EXTRA_COMPONENT = "android.provider.extra.ACCESSIBILITY_SERVICE_COMPONENT_NAME"
    private const val EXTRA_FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
}
