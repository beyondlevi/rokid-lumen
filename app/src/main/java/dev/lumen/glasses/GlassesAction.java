package dev.lumen.glasses;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.SystemClock;
import android.view.KeyEvent;

/**
 * What a mappable band gesture can do on the glasses. The Rokid entries and their wording come
 * from R08 Access Bridge's tap actions; Back, Home and Play or pause are added because the band
 * has spare gestures for them.
 */
public enum GlassesAction {
    NONE("none", "No action", "Ignore this gesture", "No action"),
    BACK("back", "Back", "Android Back", "Back"),
    HOME("home", "Home", "Return to the Rokid launcher", "Home"),
    PLAY_PAUSE("play_pause", "Play or pause", "Media key to the playing app", "Play/pause"),
    LAUNCH_APP("launch_app", "Launch app", "Open a chosen installed app", "Launch app"),
    OPEN_APPS_GRID("open_apps_grid", "MRBD apps", "Open the web apps grid", "Apps"),
    AI_ASSIST("ai_assist", "Rokid AI", "Open the AI assistant scene", "Rokid AI"),
    HI_ROKID_SHORTCUT(BridgeProtocol.ACTION_HI_ROKID_SHORTCUT, "Hi Rokid Shortcut", "The real two-finger AI shortcut, needs Self-arm", "Hi shortcut"),
    TAKE_PHOTO("take_photo", "Take photo", "Capture a normal camera photo", "Take photo"),
    VIDEO_RECORD_TOGGLE("toggle_video", "Video toggle", "Start video, then stop on next trigger", "Video"),
    AR_SCREENSHOT("ar_screenshot", "AR screenshot", "Capture the AR/HUD view", "AR screenshot"),
    AR_RECORD_TOGGLE("toggle_ar_record", "AR video toggle", "Start AR video, then stop on next trigger", "AR video"),
    /** Hands the band to the phone companion (it controls the phone until handed back). */
    TO_PHONE("to_phone", "Use the band on the phone", "Hand the band to the phone companion", "Band to the phone");

    private final String id;
    private final String title;
    private final String detail;
    private final String feedback;

    GlassesAction(String id, String title, String detail, String feedback) {
        this.id = id;
        this.title = title;
        this.detail = detail;
        this.feedback = feedback;
    }

    String id() {
        return id;
    }

    String title() {
        return title;
    }

    String detail() {
        return detail;
    }

    String feedback(Context context) {
        switch (this) {
            case HI_ROKID_SHORTCUT:
                return context.getString(PrivilegedShortcutBridge.isArmed(context) ? R.string.action_hi_shortcut : R.string.feedback_self_arm_needed);
            case VIDEO_RECORD_TOGGLE:
                return context.getString(GestureMappings.isVideoRecordingRequested(context) ? R.string.feedback_video_stop : R.string.feedback_video_start);
            case AR_RECORD_TOGGLE:
                return context.getString(GestureMappings.isArRecordingRequested(context) ? R.string.feedback_ar_video_stop : R.string.feedback_ar_video_start);
            default:
                Integer res = FEEDBACK.get(this);
                return res != null ? context.getString(res) : feedback;
        }
    }

    /** The on-screen feedback, translated; the English [feedback] stays for the rest. */
    private static final java.util.Map<GlassesAction, Integer> FEEDBACK = new java.util.EnumMap<>(GlassesAction.class);

    static {
        FEEDBACK.put(NONE, R.string.action_none);
        FEEDBACK.put(BACK, R.string.feedback_back);
        FEEDBACK.put(HOME, R.string.action_home);
        FEEDBACK.put(PLAY_PAUSE, R.string.action_play_pause);
        FEEDBACK.put(LAUNCH_APP, R.string.action_launch_app);
        FEEDBACK.put(OPEN_APPS_GRID, R.string.action_apps);
        FEEDBACK.put(TAKE_PHOTO, R.string.action_take_photo);
        FEEDBACK.put(AR_SCREENSHOT, R.string.action_ar_screenshot);
        FEEDBACK.put(TO_PHONE, R.string.action_to_phone);
    }

    boolean execute(AccessibilityService service, AccessibilityNavigator navigator, String launchPackage) {
        switch (this) {
            case BACK:
                if (navigator != null) {
                    navigator.back();
                    return true;
                }
                return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK);
            case HOME:
                return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME);
            case PLAY_PAUSE:
                mediaKey(service, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE);
                return true;
            case LAUNCH_APP:
                return launchApp(service, launchPackage);
            case OPEN_APPS_GRID:
                try {
                    service.startActivity(new Intent(service, LauncherActivity.class)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                    return true;
                } catch (RuntimeException e) {
                    return false;
                }
            case AI_ASSIST:
                if (RokidSystemActions.openAiAssist(service)) {
                    return true;
                }
                if (navigator != null) {
                    navigator.longPress();
                    return true;
                }
                return false;
            case HI_ROKID_SHORTCUT:
                if (PrivilegedShortcutBridge.requestHiRokidShortcut(service)) {
                    return true;
                }
                return RokidSystemActions.openAiAssist(service);
            case TAKE_PHOTO:
                return RokidSystemActions.takePhoto(service);
            case VIDEO_RECORD_TOGGLE:
                return toggleVideoRecord(service);
            case AR_SCREENSHOT:
                return RokidSystemActions.takeArScreenshot(service);
            case AR_RECORD_TOGGLE:
                return toggleArRecord(service);
            case TO_PHONE:
                // The glasses let go here; their status tells the phone, which connects.
                return BandSettings.action(service, dev.lumen.protocol.SettingsOps.ACTION_TO_PHONE) == null;
            case NONE:
            default:
                return true;
        }
    }

    private static void mediaKey(Context context, int code) {
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) {
            return;
        }
        long time = SystemClock.uptimeMillis();
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0));
        audio.dispatchMediaKeyEvent(new KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0));
    }

    private static boolean launchApp(Context context, String launchPackage) {
        if (launchPackage == null) {
            return false;
        }
        String packageName = launchPackage.trim();
        if (packageName.isEmpty()) {
            return false;
        }
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent == null) {
            return false;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            context.startActivity(intent);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean toggleVideoRecord(Context context) {
        boolean start = !GestureMappings.isVideoRecordingRequested(context);
        boolean sent = start
                ? RokidSystemActions.startVideoRecord(context)
                : RokidSystemActions.stopVideoRecord(context);
        if (sent) {
            GestureMappings.setVideoRecordingRequested(context, start);
        }
        return sent;
    }

    private static boolean toggleArRecord(Context context) {
        boolean start = !GestureMappings.isArRecordingRequested(context);
        boolean sent = start
                ? RokidSystemActions.startArRecord(context)
                : RokidSystemActions.stopArRecord(context);
        if (sent) {
            GestureMappings.setArRecordingRequested(context, start);
        }
        return sent;
    }

    static GlassesAction fromId(String id, GlassesAction fallback) {
        if (id == null) {
            return fallback;
        }
        for (GlassesAction action : values()) {
            if (action.id.equals(id)) {
                return action;
            }
        }
        return fallback;
    }
}
