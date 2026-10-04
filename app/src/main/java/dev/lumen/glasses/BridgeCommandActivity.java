/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

/**
 * The self-arm bootstrap's entry into the app (signature-protected): prepares the shortcut
 * bridge's request file and records that the bridge is armed. Trimmed from R08 Access Bridge.
 */
public final class BridgeCommandActivity extends Activity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        handleCommand(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleCommand(intent);
    }

    private void handleCommand(Intent intent) {
        if (intent == null) {
            finish();
            return;
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE, false)) {
            PrivilegedShortcutBridge.ensureReady(this);
            setBridgeArmed(true);
        }
        if (intent.hasExtra(BridgeProtocol.EXTRA_SET_BRIDGE_ARMED)) {
            setBridgeArmed(intent.getBooleanExtra(BridgeProtocol.EXTRA_SET_BRIDGE_ARMED, false));
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_BRIDGE_WIFI_OFF, false)) {
            PrivilegedShortcutBridge.requestWifiEnabled(this, false);
        }
        if (intent.getBooleanExtra(BridgeProtocol.EXTRA_EXIT_AFTER_COMMAND, true)) {
            mainHandler.postDelayed(this::finish, 300L);
        }
    }

    private void setBridgeArmed(boolean armed) {
        getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, MODE_PRIVATE)
                .edit()
                .putBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, armed)
                .apply();
    }
}
