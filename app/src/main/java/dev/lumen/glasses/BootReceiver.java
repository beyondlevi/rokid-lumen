/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

/**
 * Fires at boot if the bridge was previously armed, as in R08 Access Bridge: restarts the
 * shortcut bridge and the accessibility watchdog over local ADB loopback (127.0.0.1:5555) with
 * the key the self-arm paired. A package the firmware force-stopped doesn't receive this
 * broadcast; the accessibility service connecting and the app's launch run the same path.
 * The band itself needs nothing here: it connects when Android binds the accessibility service.
 */
public final class BootReceiver extends BroadcastReceiver {
    private static final String TAG = "BandBootReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())) {
            return;
        }
        boolean wasArmed = context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false);
        if (!wasArmed) {
            Log.d(TAG, "Boot: bridge not previously armed, nothing to do");
            return;
        }
        Log.d(TAG, "Boot: bridge was armed, attempting local self-arm");
        SelfArmController.armOnBoot(context);
    }
}
