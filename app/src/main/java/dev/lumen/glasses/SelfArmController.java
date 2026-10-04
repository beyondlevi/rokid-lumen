/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.flyfishxu.kadb.Kadb;
import com.flyfishxu.kadb.cert.KadbCert;
import com.flyfishxu.kadb.cert.KadbCertPolicy;
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Maintenance self-arm: restores the two shell helpers over local ADB after the first
 * successful setup. Adapted from R08 Access Bridge (without its Nexus coexistence).
 *
 * Shell processes do not survive a reboot. Recovery runs from the boot receiver,
 * accessibility service connection, and app launch. Some firmware withholds both boot
 * delivery and the accessibility binding after a force-stop; opening the app then recovers it.
 */
final class SelfArmController {
    private static final String TAG = "BandSelfArm";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int SHELL_TIMEOUT_MS = 15000;
    private static final long LOOPBACK_BIND_WAIT_MS = 15000L;

    /** Global setting (hidden in the SDK): 0 keeps Wireless-Debugging-paired ADB keys forever. */
    static final String SETTING_ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time";

    /** adbd binds 127.0.0.1:5555 late at boot, and the glasses often suspend right after booting. */
    static final long[] BOOT_RETRY_DELAYS_MS = {4_000L, 30_000L, 90_000L, 240_000L};
    static final long[] SERVICE_CONNECTED_RETRY_DELAYS_MS = {8_000L, 30_000L, 90_000L, 240_000L};
    private static final long[] LAUNCH_DELAYS_MS = {0L};
    /** Time for init to start adbd after USB debugging is switched on. */
    private static final long ADBD_START_GRACE_MS = 4_000L;
    /** Heartbeat TTL plus margin: after that, a stale heartbeat no longer reads as "armed". */
    private static final long HELPER_LIVENESS_SETTLE_MS = 16_000L;

    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);
    private static volatile boolean certConfigured;

    enum UsbDebugging { ON, ENABLED_NOW, OFF_NO_PERMISSION }

    static final class KeyMissingException extends Exception {
        KeyMissingException(String message) {
            super(message);
        }
    }

    static final class Outcome {
        final boolean success;
        final boolean terminal;
        final String state;
        final String detail;

        private Outcome(boolean success, boolean terminal, String state, String detail) {
            this.success = success;
            this.terminal = terminal;
            this.state = state;
            this.detail = detail;
        }

        static Outcome success(String detail) {
            return new Outcome(true, true, "auto_rearm_complete", detail);
        }

        static Outcome terminal(String state, String detail) {
            return new Outcome(false, true, state, detail);
        }

        static Outcome retry(String state, String detail) {
            return new Outcome(false, false, state, detail);
        }
    }

    private SelfArmController() {
    }

    static void armOnLaunch(Context context) {
        armAsync(context, "launch", LAUNCH_DELAYS_MS, false);
    }

    static void armOnBoot(Context context) {
        armAsync(context, "boot", BOOT_RETRY_DELAYS_MS, false);
    }

    /**
     * The accessibility service (re)connected, providing another boot trigger when the
     * firmware permits the binding. Only acts when the helpers look dead: a fresh heartbeat means the shell
     * watchdog is still alive and nothing needs restarting.
     */
    static void armOnServiceConnected(Context context) {
        armAsync(context, "service_connected", SERVICE_CONNECTED_RETRY_DELAYS_MS, true);
    }

    /**
     * Stores an ADB private key handed over by the phone companion so the maintenance
     * self-arm can authenticate on 127.0.0.1:5555 after a reboot. The key lands in app-private
     * storage; a file dropped in external storage by the shell user is unreadable to the app.
     */
    static boolean importProvisionedKey(Context context, String base64Pem) {
        try {
            byte[] pem = Base64.decode(base64Pem, Base64.DEFAULT);
            File key = internalKeyFile(context.getApplicationContext());
            File dir = key.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("cannot create self-arm key dir");
            }
            Files.write(key.toPath(), pem);
            synchronized (SelfArmController.class) {
                certConfigured = false;
            }
            Log.i(TAG, "imported provisioned adb key bytes=" + pem.length);
            return true;
        } catch (IOException | IllegalArgumentException exception) {
            Log.w(TAG, "provisioned adb key import failed", exception);
            return false;
        }
    }

    /**
     * Runs synchronously at the end of the on-glasses self-arm, while Wi-Fi is still on.
     * Makes sure adbd will come back after a reboot (USB debugging on, paired key kept) and,
     * if switching USB debugging on restarted adbd and took the fresh helpers with it,
     * restarts them over loopback right away.
     */
    static String afterLocalBootstrap(Context context) {
        Context appContext = context.getApplicationContext();
        if (NexusGuard.isPresent(appContext)) {
            return "Nexus manages ADB; USB debugging left alone";
        }
        UsbDebugging usb = ensureUsbDebuggingEnabled(appContext);
        keepAdbKeysForever(appContext);
        switch (usb) {
            case ON:
                return "usb debugging on";
            case OFF_NO_PERMISSION:
                LocalSelfArmStatus.reportSimple(appContext, "usb_debugging_off");
                return "usb debugging OFF (enable it in Hi Rokid settings)";
            case ENABLED_NOW:
            default:
                break;
        }
        try {
            Thread.sleep(HELPER_LIVENESS_SETTLE_MS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return "usb debugging enabled";
        }
        if (PrivilegedShortcutBridge.isArmed(appContext)) {
            return "usb debugging enabled; helpers alive";
        }
        Log.i(TAG, "helpers died after enabling usb debugging; re-arming over loopback");
        try {
            configureCert(appContext);
            return "usb debugging enabled; re-armed: " + armOverLoopback(appContext);
        } catch (Exception exception) {
            Log.w(TAG, "post-bootstrap loopback re-arm failed", exception);
            LocalSelfArmStatus.reportSimple(appContext, "bootstrap_rearm_failed");
            return "usb debugging enabled; loopback re-arm failed: " + firstLine(shortMessage(exception));
        }
    }

    /**
     * Makes 127.0.0.1:5555 answer now instead of after the next reboot: asks adbd, over the
     * Wireless Debugging session the bootstrap still holds, to restart in TCP mode (what
     * `adb tcpip 5555` does), waits for it, then re-installs and restarts the helpers over the
     * new port, since restarting adbd kills them (they descend from it). Measured: without
     * this, everything that needs the shell after the self-arm (joining the phone's hotspot)
     * failed with ECONNREFUSED on 5555 until the glasses rebooted.
     */
    static String bindLoopbackNow(Context context, Kadb session) {
        Context appContext = context.getApplicationContext();
        if (isLoopbackUp()) {
            return "5555 already up";
        }
        try {
            session.open("tcpip:" + BridgeProtocol.DEFAULT_ADB_PORT).close();
        } catch (Exception exception) {
            // adbd restarts at once and the stream may die mid-reply: only the wait tells.
            Log.d(TAG, "tcpip request: " + firstLine(shortMessage(exception)));
        }
        long deadline = System.currentTimeMillis() + LOOPBACK_BIND_WAIT_MS;
        while (!isLoopbackUp()) {
            if (System.currentTimeMillis() > deadline) {
                return "5555 did not come up (it will after a reboot)";
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return "interrupted waiting for 5555";
            }
        }
        try {
            configureCert(appContext);
            return "5555 up; " + armOverLoopback(appContext);
        } catch (Exception exception) {
            Log.w(TAG, "re-arm over 5555 failed", exception);
            return "5555 up; re-arm failed: " + firstLine(shortMessage(exception));
        }
    }

    private static final String KEY_HELPERS_HASH = "helpers_hash";

    /**
     * Whether the running helpers are this app's version. Live helpers used to count as done,
     * so an updated script never reached the glasses until they rebooted (measured).
     */
    private static boolean helpersCurrent(Context context) {
        String deployed = context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getString(KEY_HELPERS_HASH, "");
        return bundledHelpersHash(context).equals(deployed);
    }

    private static String bundledHelpersHash(Context context) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            digest.update(readRawResource(context, R.raw.neuralband_shortcut_bridge));
            digest.update(readRawResource(context, R.raw.neuralband_a11y_watchdog));
            return Base64.encodeToString(digest.digest(), Base64.NO_WRAP);
        } catch (Exception exception) {
            return "";
        }
    }

    private static boolean isLoopbackUp() {
        try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress(BridgeProtocol.ADB_LOOPBACK_HOST,
                    BridgeProtocol.DEFAULT_ADB_PORT), 500);
            return true;
        } catch (IOException exception) {
            return false;
        }
    }

    private static void armAsync(Context context, String reason, long[] delaysMs, boolean onlyWhenHelpersDown) {
        Context appContext = context.getApplicationContext();
        if (!isBridgeArmed(appContext)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " bridge not armed");
            return;
        }
        if (onlyWhenHelpersDown && PrivilegedShortcutBridge.isArmed(appContext) && helpersCurrent(appContext)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " helpers alive");
            return;
        }
        if (!RUNNING.compareAndSet(false, true)) {
            Log.d(TAG, "skip self-arm reason=" + reason + " already running");
            return;
        }
        Thread thread = new Thread(() -> {
            try {
                Outcome last = null;
                for (int attempt = 1; attempt <= delaysMs.length; attempt++) {
                    long delay = delaysMs[attempt - 1];
                    if (delay > 0L) {
                        Thread.sleep(delay);
                    }
                    if (!isBridgeArmed(appContext)) return;
                    last = armOnce(appContext);
                    Log.i(TAG, "self-arm " + reason + " attempt=" + attempt + "/" + delaysMs.length
                            + " state=" + last.state + " " + last.detail);
                    if (last.success) {
                        LocalSelfArmStatus.reportSimple(appContext, last.state);
                        return;
                    }
                    if (last.terminal) {
                        break;
                    }
                }
                if (last != null && !PrivilegedShortcutBridge.isArmed(appContext)) {
                    // Only surface the failure when the helpers are really down; a fresh
                    // heartbeat means ring control works and nothing needs the user's attention.
                    LocalSelfArmStatus.reportSimple(appContext, last.state);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                Log.d(TAG, "self-arm interrupted reason=" + reason);
            } catch (Throwable throwable) {
                Log.w(TAG, "self-arm failed reason=" + reason, throwable);
            } finally {
                RUNNING.set(false);
            }
        }, "neuralband-self-arm");
        thread.setDaemon(true);
        thread.start();
    }

    static Outcome armOnce(Context context) throws InterruptedException {
        String secureStatus = repairAccessibilityFromApp(context);
        if (PrivilegedShortcutBridge.isArmed(context) && helpersCurrent(context)) {
            return Outcome.success(secureStatus + "helpers alive");
        }
        if (NexusGuard.isPresent(context)) {
            return Outcome.terminal("nexus_present", "Nexus manages ADB on these glasses");
        }
        UsbDebugging usb = ensureUsbDebuggingEnabled(context);
        if (usb == UsbDebugging.OFF_NO_PERMISSION) {
            return Outcome.terminal("usb_debugging_off", "failed: USB debugging off and no WRITE_SECURE_SETTINGS");
        }
        if (usb == UsbDebugging.ENABLED_NOW) {
            Thread.sleep(ADBD_START_GRACE_MS);
        }
        keepAdbKeysForever(context);
        try {
            configureCert(context);
        } catch (KeyMissingException exception) {
            return Outcome.terminal("adb_key_missing", "failed: " + exception.getMessage());
        }
        try {
            return Outcome.success(secureStatus + armOverLoopback(context));
        } catch (InterruptedException exception) {
            throw exception;
        } catch (Exception exception) {
            return Outcome.retry("adb_loopback_unreachable", "failed: " + firstLine(shortMessage(exception)));
        }
    }

    /**
     * Pushes both helper scripts over 127.0.0.1:5555 and starts them. Both "start" verbs are
     * idempotent ("already running pid=..."), so this is safe while an old instance is alive.
     */
    private static String armOverLoopback(Context context) throws Exception {
        String bridgeScript = Base64.encodeToString(readRawResource(context, R.raw.neuralband_shortcut_bridge), Base64.NO_WRAP);
        String watchdogScript = Base64.encodeToString(readRawResource(context, R.raw.neuralband_a11y_watchdog), Base64.NO_WRAP);
        // The bridge polls the request file from its first loop; create it before starting.
        PrivilegedShortcutBridge.ensureReady(context);
        Kadb kadb = null;
        try {
            kadb = new Kadb(BridgeProtocol.ADB_LOOPBACK_HOST, BridgeProtocol.DEFAULT_ADB_PORT,
                    CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS);
            String probe = kadb.shell("echo neuralband-self-arm").getOutput().trim();
            if (!probe.endsWith("neuralband-self-arm")) {
                throw new IOException("adb probe mismatch " + firstLine(probe));
            }
            // A changed script (an app update) restarts its helper: "start" keeps the old
            // process, which would run the old code until the next reboot.
            String bridgeVerb = installScript(kadb, bridgeScript, BridgeProtocol.REMOTE_SCRIPT) ? " restart" : " start";
            String watchdogVerb = installScript(kadb, watchdogScript, BridgeProtocol.REMOTE_WATCHDOG_SCRIPT) ? " restart" : " start";
            String bridgeStatus = firstLine(kadb.shell("sh " + BridgeProtocol.REMOTE_SCRIPT + bridgeVerb)
                    .getOutput()
                    .trim());
            String watchdogStatus = firstLine(kadb.shell("sh " + BridgeProtocol.REMOTE_WATCHDOG_SCRIPT + watchdogVerb)
                    .getOutput()
                    .trim());
            if (!bridgeStatus.contains("pid=")) {
                throw new IOException("shortcut bridge did not start: " + bridgeStatus);
            }
            if (!watchdogStatus.contains("pid=")) {
                throw new IOException("watchdog did not start: " + watchdogStatus);
            }
            context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE).edit()
                    .putString(KEY_HELPERS_HASH, bundledHelpersHash(context)).apply();
            return "bridge " + bridgeStatus + "; watchdog " + watchdogStatus;
        } finally {
            if (kadb != null) {
                try {
                    kadb.close();
                } catch (RuntimeException ignored) {
                    // Nothing useful to recover here; the shell commands already completed.
                }
            }
        }
    }

    /**
     * Runs one command as the shell user over ADB loopback, with the self-arm's paired key, and
     * returns its output. Blocking: call it off the main thread. Used for what a normal app can't
     * do (joining a given Wi-Fi network for the phone's internet, see PhoneInternet).
     */
    static String runShell(Context context, String command) throws Exception {
        configureCert(context);
        Kadb kadb = new Kadb(BridgeProtocol.ADB_LOOPBACK_HOST, BridgeProtocol.DEFAULT_ADB_PORT,
                CONNECT_TIMEOUT_MS, SHELL_TIMEOUT_MS);
        try {
            return kadb.shell(command).getOutput();
        } finally {
            try {
                kadb.close();
            } catch (RuntimeException ignored) {
                // The command already ran.
            }
        }
    }

    /** Writes the script if its content changed; true when it did (or it wasn't there). */
    private static boolean installScript(Kadb kadb, String encodedScript, String remotePath) {
        String next = remotePath + ".new";
        String out = kadb.shell("printf '%s' '" + encodedScript + "' | base64 -d > " + next
                + " && if cmp -s " + next + " " + remotePath + "; then rm -f " + next + "; echo same;"
                + " else mv -f " + next + " " + remotePath + " && chmod 755 " + remotePath + " && echo changed; fi")
                .getOutput();
        return !out.contains("same");
    }

    /**
     * adbd only starts at boot when USB debugging is on (or Wireless Debugging, which Android
     * switches off with Wi-Fi). Glasses set up from the Hi Rokid app without a cable usually
     * have it off, so 127.0.0.1:5555 never comes back after a reboot. Switching it on needs
     * WRITE_SECURE_SETTINGS, which the self-arm bootstrap grants.
     */
    static UsbDebugging ensureUsbDebuggingEnabled(Context context) {
        ContentResolver resolver = context.getContentResolver();
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 1) {
            return UsbDebugging.ON;
        }
        try {
            if (Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)) {
                Log.i(TAG, "usb debugging was off; enabled it so adbd survives reboots");
                return UsbDebugging.ENABLED_NOW;
            }
            Log.w(TAG, "usb debugging is off and could not be enabled");
        } catch (SecurityException exception) {
            Log.w(TAG, "usb debugging is off; WRITE_SECURE_SETTINGS not granted", exception);
        }
        return UsbDebugging.OFF_NO_PERMISSION;
    }

    /**
     * Keys paired through Wireless Debugging are revoked after 7 days without a connection by
     * default. The maintenance self-arm relies on that key, so keep it indefinitely.
     */
    static void keepAdbKeysForever(Context context) {
        ContentResolver resolver = context.getContentResolver();
        try {
            long current = Settings.Global.getLong(resolver, SETTING_ADB_ALLOWED_CONNECTION_TIME, -1L);
            if (current == 0L) {
                return;
            }
            if (Settings.Global.putLong(resolver, SETTING_ADB_ALLOWED_CONNECTION_TIME, 0L)) {
                Log.i(TAG, "adb key expiry disabled (was " + current + " ms)");
            }
        } catch (SecurityException exception) {
            Log.d(TAG, "cannot change adb key expiry; WRITE_SECURE_SETTINGS not granted");
        }
    }

    private static String repairAccessibilityFromApp(Context context) {
        try {
            ComponentName component = new ComponentName(context, BandAccessibilityService.class);
            String service = component.flattenToString();
            String current = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (current == null || "null".equals(current)) {
                current = "";
            }
            if (!containsService(current, service)) {
                String updated = TextUtils.isEmpty(current) ? service : current + ":" + service;
                Settings.Secure.putString(
                        context.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                        updated);
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility repaired; ";
            }
            String enabled = Settings.Secure.getString(
                    context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED);
            if (!"1".equals(enabled)) {
                Settings.Secure.putInt(
                        context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED,
                        1);
                return "accessibility enabled; ";
            }
        } catch (SecurityException exception) {
            Log.d(TAG, "WRITE_SECURE_SETTINGS not available; shell watchdog will repair", exception);
        } catch (RuntimeException exception) {
            Log.d(TAG, "app-side accessibility repair failed; shell watchdog will repair", exception);
        }
        return "";
    }

    private static boolean containsService(String services, String service) {
        if (TextUtils.isEmpty(services)) {
            return false;
        }
        TextUtils.SimpleStringSplitter splitter = new TextUtils.SimpleStringSplitter(':');
        splitter.setString(services);
        while (splitter.hasNext()) {
            if (service.equalsIgnoreCase(splitter.next())) {
                return true;
            }
        }
        return false;
    }

    private static synchronized void configureCert(Context context) throws KeyMissingException {
        if (certConfigured) {
            return;
        }
        File key = resolveSelfArmKey(internalKeyFile(context), provisionedKeyFile(context));
        KadbCert.INSTANCE.configure(
                new OkioFilePrivateKeyStore(
                        okio.Path.Companion.get(key.getAbsolutePath()),
                        okio.FileSystem.SYSTEM),
                new KadbCertPolicy(),
                Collections.emptyList());
        certConfigured = true;
    }

    /**
     * Picks the private key the maintenance self-arm authenticates with.
     *
     * A key provisioned into external storage is imported into app-private storage when it can
     * be read. Older phone companions wrote it as the shell user with mode 600, which the app
     * cannot read through FUSE; that copy must not block a key the app already holds.
     */
    static File resolveSelfArmKey(File internal, File provisioned) throws KeyMissingException {
        if (provisioned.isFile()) {
            try {
                File dir = internal.getParentFile();
                if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
                    throw new IOException("cannot create self-arm key dir");
                }
                // Android can remove the destination before opening the source when copying
                // with REPLACE_EXISTING. Stage the import so an unreadable shell-owned key
                // cannot delete the app-private key we need for the fallback.
                Path staged = Files.createTempFile(dir.toPath(), "adbkey-import-", ".pem");
                try {
                    Files.copy(provisioned.toPath(), staged, StandardCopyOption.REPLACE_EXISTING);
                    Files.move(staged, internal.toPath(), StandardCopyOption.REPLACE_EXISTING);
                } finally {
                    Files.deleteIfExists(staged);
                }
                Log.i(TAG, "imported provisioned adb key from external storage");
            } catch (IOException | SecurityException exception) {
                if (!internal.isFile()) {
                    throw new KeyMissingException("provisioned adb key unreadable: "
                            + firstLine(shortMessage(exception)));
                }
                Log.w(TAG, "provisioned adb key unreadable; using the app-private key", exception);
            }
        }
        if (!internal.isFile()) {
            throw new KeyMissingException("self-arm adb key missing");
        }
        return internal;
    }

    private static boolean isBridgeArmed(Context context) {
        return context.getSharedPreferences(BridgeProtocol.PREFS_BRIDGE, Context.MODE_PRIVATE)
                .getBoolean(BridgeProtocol.PREF_BRIDGE_ARMED, false);
    }

    private static File internalKeyFile(Context context) {
        return new File(new File(context.getFilesDir(), "kadb"), BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
    }

    private static File provisionedKeyFile(Context context) {
        File dir = context.getExternalFilesDir(BridgeProtocol.SELF_ARM_DIR_NAME);
        if (dir == null) {
            return new File("/sdcard/Android/data/" + context.getPackageName()
                    + "/files/" + BridgeProtocol.SELF_ARM_DIR_NAME,
                    BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
        }
        return new File(dir, BridgeProtocol.SELF_ARM_ADB_KEY_FILE);
    }

    private static byte[] readRawResource(Context context, int resourceId) throws Exception {
        try (InputStream input = context.getResources().openRawResource(resourceId);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toByteArray();
        }
    }

    private static String shortMessage(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.trim().isEmpty()
                ? throwable.getClass().getSimpleName()
                : message.trim();
    }

    private static String firstLine(String value) {
        if (value == null) {
            return "";
        }
        int newline = value.indexOf('\n');
        return newline >= 0 ? value.substring(0, newline).trim() : value.trim();
    }
}
