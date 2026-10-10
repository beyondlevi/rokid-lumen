/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.display.DisplayManager;
import android.media.AudioManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;
import dev.lumen.band.ScreenPointer;
import java.io.File;

/**
 * Keeps the band connected and turns its gestures into glasses navigation. The navigation,
 * the screen wake, the launcher acceleration and the self-arm hooks follow R08 Access Bridge's
 * RingControlAccessibilityService; the input is the band's recognised gestures instead of the
 * ring's key events, so there is no key filtering and no tap-count recognizer here (the band's
 * bridge tells single, double and middle taps apart itself).
 */
public final class BandAccessibilityService extends AccessibilityService {
    public static final String ACTION_SIMULATE = BridgeProtocol.APP_PACKAGE + ".SIMULATE";
    public static final String EXTRA_GESTURE = "gesture";
    /**
     * Debug builds: an action name as the bridge hands it back (`nav.down`, `nav.activate`...),
     * routed as the band's own, while the real band stays connected.
     */
    public static final String EXTRA_COMMAND = "command";
    /**
     * Debug builds: a notification put in the inbox as if the phone sent it (title, then text).
     * Optional: `notify_app` (the app's name), `notify_pkg`, `notify_icon` (a PNG's file name in the
     * app's external files folder), `notify_time` (minutes ago), `notify_reply`, `notify_shortcut`
     * `notify_key` (the same key updates the notification), `notify_alert` (its banner shows, as
     * for news from the phone) and `notify_picture` (a drawn test picture, captioned with the
     * text's last line) or `notify_pictures` (that many). For screenshots and demos.
     * `grid_order` and `grid_hidden` (comma-separated item ids) arrange the apps grid instead.
     */
    public static final String EXTRA_NOTIFY_TITLE = "notify_title";
    public static final String EXTRA_NOTIFY_TEXT = "notify_text";

    private static final String TAG = "BandService";
    private static final long SELF_ARM_WAKE_DELAY_MS = 800L;
    private static final long SELF_ARM_SCREEN_MS = 120_000L;
    private static final long DIRECTION_DEBOUNCE_MS = 55L;
    private static final long LAUNCHER_DIRECTION_DEBOUNCE_MS = 150L;
    private static final long BACK_DEBOUNCE_MS = 350L;
    private static final long SCREEN_WAKE_GRACE_MS = 600L;
    private static final long LAUNCHER_ACCELERATION_WINDOW_MS = 900L;
    private static final int LAUNCHER_ACCELERATION_START_STREAK = 3;
    private static final int LAUNCHER_ACCELERATED_STEPS = 2;

    /** The app's own screen takes band input while it's in front (see MainActivity). */
    interface InputTarget {
        boolean onBandCommand(String command);

        /**
         * The air mouse tapped at x, y (screen pixels; the touch itself already went to the
         * window): a screen that moves a focus instead of taking touches acts on it here.
         */
        default void onPointerTap(float x, float y) {
        }
    }

    private static BandAccessibilityService activeService;
    private static InputTarget inputTarget;

    private AccessibilityNavigator navigator;
    private NotificationBanner banner;
    private BandBatteryOverlay batteryOverlay;
    private SelfArmWirelessDebuggingAutomator selfArmWirelessDebuggingAutomator;
    private PowerManager powerManager;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private long lastDirectionalAt;
    private String lastDirectionalCommand = "";
    private int launcherDirectionStreak;
    private String launcherDirectionStreakCommand = "";
    private long launcherDirectionStreakAt;
    private long lastBackAt;
    private long suppressGesturesUntil;
    private boolean screenStateReceiverRegistered;
    private boolean simulateReceiverRegistered;
    private boolean screenWakeGraceActive;
    private final Runnable screenWakeGraceClear = () -> screenWakeGraceActive = false;

    private final BroadcastReceiver screenStateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_SCREEN_OFF.equals(action)) {
                mainHandler.removeCallbacks(screenWakeGraceClear);
                screenWakeGraceActive = true;
                // Nothing needs pinch and turn with the screen off: the band stops its motion
                // streams (power), and keeps the gestures for the middle double tap unless power
                // saving is on (then it stops everything until the glasses' button).
                BandRuntime.setScreenOn(context, false);
                if (banner != null) {
                    banner.onScreenOff();
                }
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                mainHandler.removeCallbacks(screenWakeGraceClear);
                mainHandler.postDelayed(screenWakeGraceClear, SCREEN_WAKE_GRACE_MS);
                BandRuntime.setScreenOn(context, true);
            }
        }
    };

    private final BroadcastReceiver simulateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String notifyTitle = intent.getStringExtra(EXTRA_NOTIFY_TITLE);
            if (notifyTitle != null) {
                String text = intent.getStringExtra(EXTRA_NOTIFY_TEXT);
                String pkg = intent.getStringExtra("notify_pkg");
                String app = intent.getStringExtra("notify_app");
                String key = intent.getStringExtra("notify_key");
                long postedAt = System.currentTimeMillis() - intent.getIntExtra("notify_time", 0) * 60_000L;
                String body = text == null ? "" : text.replace("\\n", "\n");
                int pictureCount = Math.min(dev.lumen.protocol.PictureOps.MAX_PICTURES,
                        intent.getIntExtra("notify_pictures", intent.getBooleanExtra("notify_picture", false) ? 1 : 0));
                java.util.List<dev.lumen.protocol.NotificationPicture> pictures = new java.util.ArrayList<>();
                String[] lines = body.trim().split("\n");
                for (int i = 0; i < pictureCount; i++) {
                    // The newest takes the text's last line as its caption, as a chat's photo would.
                    String caption = i == pictureCount - 1 ? lines[lines.length - 1].trim() : "";
                    pictures.add(new dev.lumen.protocol.NotificationPicture(postedAt + i, caption));
                }
                PhoneNotification notification = new PhoneNotification("debug|" + (key == null ? notifyTitle : key),
                        app == null ? "Lumen debug" : app,
                        pkg == null ? getPackageName() : pkg, notifyTitle, body,
                        postedAt, false, debugIcon(intent.getStringExtra("notify_icon")), false,
                        intent.getBooleanExtra("notify_reply", false),
                        intent.getStringExtra("notify_shortcut") == null ? "" : intent.getStringExtra("notify_shortcut"),
                        pictures);
                boolean alert = intent.getBooleanExtra("notify_alert", false);
                mainHandler.post(() -> {
                    NotificationInbox.put(notification, true);
                    if (alert) {
                        PhoneLink.debugAlert(notification);
                    }
                });
                return;
            }
            String gridOrder = intent.getStringExtra("grid_order");
            if (gridOrder != null) {
                // Debug builds: the grid's order and hidden items (comma-separated ids), for demos.
                String gridHidden = intent.getStringExtra("grid_hidden");
                mainHandler.post(() -> {
                    GridStore.set(context, splitIds(gridOrder), splitIds(gridHidden));
                    GridStore.notifyChanged();
                    GridApi.pushState();
                });
                return;
            }
            String paused = intent.getStringExtra("paused");
            if (paused != null) {
                // Debug builds: pause or resume the band's controls.
                mainHandler.post(() -> BandRuntime.setPaused(context, "true".equals(paused)));
                return;
            }
            String simulated = intent.getStringExtra("simulated");
            if (simulated != null) {
                // Debug builds: Band > Simulated band on or off, as the settings screen does.
                mainHandler.post(() -> {
                    GestureMappings.setSimulated(context, "true".equals(simulated));
                    Log.d(TAG, "Simulated band " + simulated + ": " + BandRuntime.restart(context));
                });
                return;
            }
            String handwritingText = intent.getStringExtra("handwriting_text");
            if (handwritingText != null) {
                // Debug builds: what the simulated band writes while its handwriting is on.
                mainHandler.post(() -> Log.d(TAG, "Simulated writing: "
                        + BandRuntime.simulateWriting(handwritingText)));
                return;
            }
            String handwriting = intent.getStringExtra("handwriting");
            if (handwriting != null) {
                // Debug builds: the band's handwriting model on ("start") or off ("stop").
                mainHandler.post(() -> Log.d(TAG, "Simulated handwriting " + handwriting + ": "
                        + BandRuntime.setHandwriting("start".equals(handwriting))));
                return;
            }
            String command = intent.getStringExtra(EXTRA_COMMAND);
            if (command != null) {
                Log.d(TAG, "Simulated band command=" + command);
                mainHandler.post(() -> onBandAction(command));
                return;
            }
            String gesture = intent.getStringExtra(EXTRA_GESTURE);
            if (gesture == null || !BandRuntime.simulate(gesture)) {
                Log.d(TAG, "Simulated gesture ignored (simulator off?) gesture=" + gesture);
            }
        }
    };

    private static java.util.List<String> splitIds(String ids) {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (ids == null) {
            return out;
        }
        for (String id : ids.split(",")) {
            if (!id.trim().isEmpty()) {
                out.add(id.trim());
            }
        }
        return out;
    }

    /** A simulated notification's icon: a PNG in the app's external files folder, by name only. */
    private Bitmap debugIcon(String name) {
        if (name == null || name.contains("/")) {
            return null;
        }
        File dir = getExternalFilesDir(null);
        return dir == null ? null : BitmapFactory.decodeFile(new File(dir, name).getPath());
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        activeService = this;
        PrivilegedShortcutBridge.ensureReady(this);
        // Fallback boot trigger: a package the firmware force-stopped does not receive
        // BOOT_COMPLETED, but Android still binds its enabled accessibility service.
        SelfArmController.armOnServiceConnected(this);
        navigator = new AccessibilityNavigator(this);
        selfArmWirelessDebuggingAutomator = new SelfArmWirelessDebuggingAutomator(this, mainHandler);
        configureServiceInfo();
        registerScreenStateReceiver();
        registerSimulateReceiver();
        ScreenTimeout.apply(this);
        // The phone companion's notifications: a banner over any app, the inbox in the grid.
        banner = new NotificationBanner(this);
        batteryOverlay = new BandBatteryOverlay(this);
        batteryOverlay.start();
        PhoneLink.setAlertListener(notification -> banner.show(notification));
        AppUpdateNotice.showIfUpdated(this, banner);
        PhoneLink.start(this);
        MetaToast.attach(this);
        BandSwitch.load(this);
        String problem = BandRuntime.start(this, this::onBandAction);
        Log.d(TAG, "Accessibility service connected band=" + (problem == null ? "starting" : problem));
    }

    @Override
    public void onDestroy() {
        if (activeService == this) {
            activeService = null;
        }
        GlassesPointer.stop();
        MetaToast.detach(this);
        BandRuntime.shutdown();
        PhoneLink.setAlertListener(null);
        if (banner != null) {
            banner.dismiss();
            banner = null;
        }
        if (batteryOverlay != null) {
            batteryOverlay.stop();
            batteryOverlay = null;
        }
        unregisterScreenStateReceiver();
        unregisterSimulateReceiver();
        if (selfArmWirelessDebuggingAutomator != null) {
            selfArmWirelessDebuggingAutomator.stop();
        }
        selfArmWirelessDebuggingAutomator = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        AccessibilityWindowRoots.noteEvent(event, getPackageName());
        SystemControls.onAccessibilityEvent(this, event);
        if (batteryOverlay != null) {
            batteryOverlay.onAccessibilityEvent(event);
        }
        if (selfArmWirelessDebuggingAutomator != null) {
            selfArmWirelessDebuggingAutomator.onAccessibilityEvent(event);
        }
        // The glasses' touchpad and button reach apps, not us: a click, scroll or selection
        // they cause is the user's activity (a banner that woke the display leaves it on).
        if (banner != null && isUserInteraction(event)) {
            banner.onScreenInteraction();
        }
    }

    private static boolean isUserInteraction(AccessibilityEvent event) {
        int type = event.getEventType();
        return type == AccessibilityEvent.TYPE_VIEW_CLICKED
                || type == AccessibilityEvent.TYPE_VIEW_SCROLLED
                || type == AccessibilityEvent.TYPE_VIEW_SELECTED;
    }

    @Override
    public void onInterrupt() {
        Log.d(TAG, "Accessibility service interrupted");
    }

    static boolean isServiceActive() {
        return activeService != null;
    }

    static void setInputTarget(InputTarget target) {
        inputTarget = target;
    }

    static void clearInputTarget(InputTarget target) {
        if (inputTarget == target) {
            inputTarget = null;
        }
    }

    static boolean requestLocalSelfArm(Context context) {
        Context appContext = context == null ? null : context.getApplicationContext();
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            if (appContext != null) {
                LocalSelfArmStatus.reportSimple(appContext, "api_30_required");
            }
            return false;
        }
        if (NexusGuard.isPresent(context)) {
            LocalSelfArmStatus.reportSimple(context.getApplicationContext(), "nexus_present");
            return false;
        }
        BandAccessibilityService service = activeService;
        if (service != null && service.selfArmWirelessDebuggingAutomator != null) {
            service.mainHandler.post(() -> {
                LocalSelfArmStatus.reportSimple(service, "requested");
                // Started from the phone the display is often off, and the automator can't
                // touch Settings then (measured: the Wi-Fi step timed out): on, for its length.
                service.keepScreenOnForSelfArm();
                service.mainHandler.postDelayed(service.selfArmWirelessDebuggingAutomator::start, SELF_ARM_WAKE_DELAY_MS);
            });
            return true;
        }
        if (appContext != null) {
            LocalSelfArmStatus.reportSimple(appContext, "accessibility_service_needed");
            openAccessibilitySettings(appContext);
        }
        return false;
    }

    static void returnHome(Context context, String reason) {
        BandAccessibilityService service = activeService;
        if (service != null) {
            service.mainHandler.post(() -> {
                Log.d(TAG, "Returning to Home via accessibility reason=" + reason);
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            });
            return;
        }
        if (context == null) {
            return;
        }
        try {
            context.startActivity(new Intent(Intent.ACTION_MAIN)
                    .addCategory(Intent.CATEGORY_HOME)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not return to Home reason=" + reason, exception);
        }
    }

    static void openAccessibilitySettings(Context context) {
        if (context == null) {
            return;
        }
        try {
            context.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not open Accessibility settings", exception);
        }
    }

    void suppressInjectedGestures(long durationMs) {
        suppressGesturesUntil = Math.max(suppressGesturesUntil, SystemClock.uptimeMillis() + durationMs);
    }

    void showFeedback(String text) {
        mainHandler.post(() -> Toast.makeText(this, text, Toast.LENGTH_SHORT).show());
    }

    /** The air mouse's middle pinch: the navigation's Back, as the middle tap. */
    void onPointerBack() {
        onBandAction(BandCommand.BACK);
    }

    /** The air mouse's tap, for our own screens in front ([InputTarget#onPointerTap]). */
    void onPointerTap(float x, float y) {
        InputTarget target = inputTarget;
        if (target != null && navigator != null && navigator.isPackageActive(getPackageName())) {
            target.onPointerTap(x, y);
        }
    }

    /** An action name from the band's bridge (see GestureMappings), on the main thread. */
    private void onBandAction(String command) {
        if (command == null || command.isEmpty() || command.startsWith("dial.changed.")) {
            return;
        }
        // The band turned the air mouse off (its gesture, a pause, the mapping changed).
        if (ScreenPointer.OFF.equals(command)) {
            GlassesPointer.stop();
            return;
        }
        if (BandCommand.DIAL_UP.equals(command) || BandCommand.DIAL_DOWN.equals(command)) {
            // Pinch and turn: the volume while audio plays, otherwise the chosen mode.
            AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
            command = GestureMappings.dial(this).resolve(BandCommand.DIAL_UP.equals(command),
                    audio != null && audio.isMusicActive());
            if (command == null) {
                return;
            }
        }
        // As on Meta's glasses: the middle double tap turns the screen off and on, and with the
        // screen off it's the only gesture that does anything.
        if (BandCommand.SCREEN.equals(command)) {
            if (isScreenOn()) {
                lockScreen();
            } else {
                wakeScreenForBandInput(command);
            }
            return;
        }
        if (!isScreenOn()) {
            Log.d(TAG, "Ignored band input with the screen off command=" + command);
            return;
        }
        // The band is input the system doesn't see: without this the display would go off
        // mid-use (ScreenTimeout).
        ScreenTimeout.onUserActivity(this);
        // While a banner is up it says which gestures are the user's (its swipes are swallowed:
        // an involuntary one mustn't keep a display it woke on).
        if (banner != null && !banner.isShowing()) {
            banner.onUserActivity();
        }
        if (screenWakeGraceActive) {
            Log.d(TAG, "Ignored band input during screen wake grace command=" + command);
            return;
        }
        if (GestureChoices.POINTER.equals(command)) {
            if (!GlassesPointer.start(this)) {
                Log.d(TAG, "The air mouse can't start (the band isn't connected)");
            }
            return;
        }
        if (GestureChoices.DEVICES.equals(command)) {
            BandDeviceActivity.open(this);
            return;
        }
        if (banner != null && banner.onBandCommand(command)) {
            return;
        }
        if (banner != null) {
            // Past the banner (volume, a mapped action): that is using the glasses.
            banner.onUserActivity();
        }
        InputTarget target = inputTarget;
        boolean active = target != null && navigator != null && navigator.isPackageActive(getPackageName());
        if (target != null) {
            // Which screen of ours got the band, or why it didn't (another app in front).
            Log.d(TAG, "Band " + command + " → " + target.getClass().getSimpleName() + (active ? "" : " (not in front)"));
        }
        if (active && target.onBandCommand(command)) {
            return;
        }
        execute(BandCommand.axis(command));
    }

    private void execute(String command) {
        if (navigator == null) {
            return;
        }
        switch (command) {
            case BandCommand.FORWARD:
            case BandCommand.BACKWARD:
                move(command);
                return;
            case BandCommand.ACTIVATE:
                resetLauncherDirectionStreak();
                Log.d(TAG, "Band activate");
                navigator.activate();
                return;
            case BandCommand.BACK:
                resetLauncherDirectionStreak();
                long now = SystemClock.uptimeMillis();
                if (now - lastBackAt < BACK_DEBOUNCE_MS) {
                    return;
                }
                lastBackAt = now;
                showFeedback(getString(R.string.feedback_back));
                navigator.back();
                return;
            case BandCommand.VOLUME_UP:
            case BandCommand.VOLUME_DOWN:
                adjustVolume(BandCommand.VOLUME_UP.equals(command));
                return;
            case BandCommand.BRIGHTNESS_UP:
            case BandCommand.BRIGHTNESS_DOWN:
                SystemControls.stepBrightness(this, SystemControls.brightness(this),
                        BandCommand.BRIGHTNESS_UP.equals(command),
                        () -> {
                            Log.d(TAG, "Brightness: no way to write it (no self-arm)");
                            return kotlin.Unit.INSTANCE;
                        });
                return;
            default:
                break;
        }
        resetLauncherDirectionStreak();
        if (command.startsWith(BandCommand.APP_PREFIX)) {
            runMapped(GlassesAction.LAUNCH_APP, command.substring(BandCommand.APP_PREFIX.length()));
        } else if (command.startsWith(BandCommand.GLASSES_PREFIX)) {
            GlassesAction action = GlassesAction.fromId(
                    command.substring(BandCommand.GLASSES_PREFIX.length()), GlassesAction.NONE);
            runMapped(action, null);
        } else {
            Log.d(TAG, "Unknown band action " + command);
        }
    }

    private void move(String command) {
        boolean forward = BandCommand.FORWARD.equals(command);
        boolean launcherActive = navigator.isRokidLauncherActive();
        long now = SystemClock.uptimeMillis();
        long cooldown = launcherActive ? LAUNCHER_DIRECTION_DEBOUNCE_MS : DIRECTION_DEBOUNCE_MS;
        if (now - lastDirectionalAt < cooldown && command.equals(lastDirectionalCommand)) {
            Log.d(TAG, "Throttled band direction cooldown=" + cooldown);
            return;
        }
        lastDirectionalAt = now;
        lastDirectionalCommand = command;
        int steps = 1;
        if (launcherActive && GestureMappings.isFastNavigation(this)) {
            steps = recordLauncherDirectionStreak(command, now);
        } else {
            resetLauncherDirectionStreak();
        }
        Log.d(TAG, "Band " + (forward ? "forward" : "backward") + " launcherSteps=" + steps);
        if (forward) {
            navigator.moveForward(steps);
        } else {
            navigator.moveBackward(steps);
        }
    }

    private void runMapped(GlassesAction action, String launchPackage) {
        if (action == GlassesAction.NONE) {
            return;
        }
        String feedback = action == GlassesAction.LAUNCH_APP
                ? appLabel(launchPackage)
                : action.feedback(this);
        if (feedback != null) {
            showFeedback(feedback);
        }
        if (!action.execute(this, navigator, launchPackage)) {
            Log.w(TAG, "Mapped action failed action=" + action.id());
        }
    }

    private String appLabel(String packageName) {
        if (packageName == null || packageName.trim().isEmpty()) {
            return null;
        }
        try {
            ApplicationInfo info = getPackageManager().getApplicationInfo(packageName.trim(), 0);
            CharSequence label = getPackageManager().getApplicationLabel(info);
            return label == null || label.length() == 0 ? packageName : label.toString();
        } catch (PackageManager.NameNotFoundException e) {
            return packageName;
        }
    }

    /** The system volume panel is the on-screen feedback (the Rokid launcher draws its own). */
    private void adjustVolume(boolean up) {
        AudioManager audio = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        if (audio == null) {
            return;
        }
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                up ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER,
                AudioManager.FLAG_SHOW_UI);
    }

    private int recordLauncherDirectionStreak(String command, long now) {
        if (command.equals(launcherDirectionStreakCommand)
                && now - launcherDirectionStreakAt <= LAUNCHER_ACCELERATION_WINDOW_MS) {
            launcherDirectionStreak++;
        } else {
            launcherDirectionStreak = 1;
            launcherDirectionStreakCommand = command;
        }
        launcherDirectionStreakAt = now;
        return launcherDirectionStreak >= LAUNCHER_ACCELERATION_START_STREAK ? LAUNCHER_ACCELERATED_STEPS : 1;
    }

    private void resetLauncherDirectionStreak() {
        launcherDirectionStreak = 0;
        launcherDirectionStreakCommand = "";
        launcherDirectionStreakAt = 0L;
    }

    /**
     * A gesture on a sleeping display wakes it and is swallowed, so nothing runs blindly on a dark
     * screen (R08 Access Bridge does the same for the ring).
     */
    @SuppressWarnings("deprecation")
    private boolean isScreenOn() {
        if (powerManager == null) {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }
        return powerManager == null || (powerManager.isInteractive() && isDefaultDisplayOn());
    }

    /** The screen off, as the glasses' power button does (the accessibility lock-screen action). */
    private void lockScreen() {
        boolean locked = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        Log.d(TAG, "Screen off from the band: " + locked);
    }

    /** Wakes the display and keeps it on while the self-arm walks Settings ([SELF_ARM_SCREEN_MS]). */
    private void keepScreenOnForSelfArm() {
        if (powerManager == null) {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }
        if (powerManager == null) {
            return;
        }
        try {
            @SuppressWarnings("deprecation")
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "Lumen:selfArm");
            wakeLock.acquire(SELF_ARM_SCREEN_MS);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not keep the screen on for the self-arm", exception);
        }
    }

    private boolean wakeScreenForBandInput(String source) {
        if (powerManager == null) {
            powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
        }
        if (powerManager == null) {
            return false;
        }
        boolean interactive = powerManager.isInteractive();
        if (interactive && isDefaultDisplayOn()) {
            return false;
        }
        if (interactive) {
            Log.d(TAG, "Ignored band input while display waking from " + source);
            return true;
        }
        try {
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(
                    PowerManager.FULL_WAKE_LOCK
                            | PowerManager.ACQUIRE_CAUSES_WAKEUP
                            | PowerManager.ON_AFTER_RELEASE,
                    "Lumen:bandWake");
            wakeLock.acquire(1000L);
            Log.d(TAG, "Woke screen for band input from " + source);
        } catch (RuntimeException exception) {
            Log.w(TAG, "Could not wake screen for band input from " + source, exception);
        }
        return true;
    }

    private boolean isDefaultDisplayOn() {
        DisplayManager displayManager = (DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
        if (displayManager == null) {
            return true;
        }
        Display display = displayManager.getDisplay(Display.DEFAULT_DISPLAY);
        return display == null || display.getState() == Display.STATE_ON;
    }

    private void configureServiceInfo() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            info = new AccessibilityServiceInfo();
        }
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;
        info.notificationTimeout = 40;
        info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        setServiceInfo(info);
    }

    private void registerScreenStateReceiver() {
        if (screenStateReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        registerReceiver(screenStateReceiver, filter);
        screenStateReceiverRegistered = true;
        BandRuntime.setScreenOn(this, isScreenOn());
    }

    private void unregisterScreenStateReceiver() {
        if (!screenStateReceiverRegistered) {
            return;
        }
        screenStateReceiverRegistered = false;
        mainHandler.removeCallbacks(screenWakeGraceClear);
        screenWakeGraceActive = false;
        try {
            unregisterReceiver(screenStateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    /** Only a debug build takes simulated gestures from `adb shell am broadcast`. */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerSimulateReceiver() {
        if (simulateReceiverRegistered || (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) == 0) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_SIMULATE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(simulateReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(simulateReceiver, filter);
        }
        simulateReceiverRegistered = true;
    }

    private void unregisterSimulateReceiver() {
        if (!simulateReceiverRegistered) {
            return;
        }
        simulateReceiverRegistered = false;
        try {
            unregisterReceiver(simulateReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }
}
