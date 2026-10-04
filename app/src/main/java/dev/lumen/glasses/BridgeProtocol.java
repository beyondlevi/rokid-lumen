/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

/**
 * Names shared by the app and the two shell helpers the self-arm installs (the Hi Rokid
 * shortcut bridge and the accessibility watchdog). Adapted from R08 Access Bridge; every
 * file and path is renamed so both apps can be armed on the same glasses without their
 * helpers overwriting each other.
 */
public final class BridgeProtocol {
    public static final String APP_PACKAGE = "dev.lumen.glasses";
    public static final String MAIN_ACTIVITY = "dev.lumen.glasses.MainActivity";
    public static final String COMMAND_ACTIVITY = "dev.lumen.glasses.BridgeCommandActivity";
    public static final String INTERNAL_COMMAND_PERMISSION = APP_PACKAGE + ".permission.INTERNAL_COMMAND";

    public static final int DEFAULT_ADB_PORT = 5555;
    public static final String ADB_LOOPBACK_HOST = "127.0.0.1";

    public static final String EXTRA_INIT_SHORTCUT_BRIDGE = "init_shortcut_bridge";
    public static final String EXTRA_BRIDGE_WIFI_OFF = "bridge_wifi_off";
    public static final String EXTRA_EXIT_AFTER_COMMAND = "exit_after_command";
    public static final String EXTRA_SET_BRIDGE_ARMED = "set_bridge_armed";

    public static final String ACTION_HI_ROKID_SHORTCUT = "hi_rokid_shortcut";
    public static final String BRIDGE_DIR_NAME = "shortcut_bridge";
    public static final String SELF_ARM_DIR_NAME = "self_arm";
    public static final String SELF_ARM_ADB_KEY_FILE = "adbkey.pem";
    public static final String SELF_ARM_WATCHDOG_SCRIPT_FILE = "lumen-a11y-watchdog.sh";
    public static final String REQUEST_FILE = "request";
    public static final String RESPONSE_FILE = "response";
    public static final String HEARTBEAT_FILE = "heartbeat";
    public static final String DOORBELL_FILE = "doorbell";

    public static final String COMMAND_SHORTCUT = "shortcut";
    public static final String COMMAND_WIFI_ENABLE = "wifi_enable";
    public static final String COMMAND_WIFI_DISABLE = "wifi_disable";

    public static final String REMOTE_SCRIPT = "/data/local/tmp/lumen-shortcut-bridge.sh";
    public static final String REMOTE_WATCHDOG_SCRIPT = "/data/local/tmp/lumen-a11y-watchdog.sh";

    /** The bridge was armed at least once: boot, service connection and launch restart the helpers. */
    public static final String PREF_BRIDGE_ARMED = "bridge_armed";
    public static final String PREFS_BRIDGE = "neuralband_bridge";

    private BridgeProtocol() {
    }

    public static String bridgeDir() {
        return "/sdcard/Android/data/" + APP_PACKAGE + "/files/" + BRIDGE_DIR_NAME;
    }

    public static String selfArmDir() {
        return "/sdcard/Android/data/" + APP_PACKAGE + "/files/" + SELF_ARM_DIR_NAME;
    }

    public static String selfArmWatchdogScriptPath() {
        return selfArmDir() + "/" + SELF_ARM_WATCHDOG_SCRIPT_FILE;
    }
}
