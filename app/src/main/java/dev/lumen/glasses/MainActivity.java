/*
 * Derived from R08 Access Bridge (https://github.com/Anezium/R08-Access-Bridge),
 * Copyright 2026 Anezium, licensed under the Apache License, Version 2.0
 * (LICENSES/Apache-2.0.txt). Modified for Rokid Lumen; see NOTICE.
 */
package dev.lumen.glasses;

import dev.lumen.band.Identity;
import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The glasses UI: a black HUD list of outlined cards moved through on one axis, built the way
 * R08 Access Bridge builds its screens (same layout, sizes and colours). The band drives it while
 * it's in front: a swipe moves the selection, an index tap opens, a middle tap goes back. The
 * glasses' own touchpad keys work too.
 */
public final class MainActivity extends Activity
        implements BandAccessibilityService.InputTarget, BandRuntime.StateListener {
    private static final long NAV_DEBOUNCE_MS = 220L;
    private static final int PERMISSION_REQUEST = 42;

    private final List<View> actionViews = new ArrayList<>();
    private final ArrayDeque<Screen> backStack = new ArrayDeque<>();
    private LinearLayout content;
    private ScrollView scrollView;
    private Screen screen = Screen.HOME;
    private long lastNavAt;
    private int lastNavDirection;
    private int selectedActionIndex;
    private String lastImportResult;
    private boolean localSelfArmStatusReceiverRegistered;

    private final BroadcastReceiver localSelfArmStatusReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            render();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        PrivilegedShortcutBridge.ensureReady(this);
        // Shell helpers die with a reboot; opening the app restarts them (R08's launch trigger).
        SelfArmController.armOnLaunch(this);
        requestRuntimePermissions();
        setContentView(buildView());
        showHome();
    }

    @Override
    protected void onStart() {
        super.onStart();
        registerLocalSelfArmStatusReceiver();
        BandRuntime.addListener(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        BandAccessibilityService.setInputTarget(this);
        render();
    }

    @Override
    protected void onPause() {
        BandAccessibilityService.clearInputTarget(this);
        super.onPause();
    }

    @Override
    protected void onStop() {
        BandRuntime.removeListener(this);
        unregisterLocalSelfArmStatusReceiver();
        super.onStop();
    }

    @Override
    public void onBandStateChanged() {
        render();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQUEST && BandAccessibilityService.isServiceActive()) {
            BandRuntime.restart(this);
        }
    }

    /** Band gestures while this screen is in front; the rest (volume, mapped actions) run as anywhere. */
    @Override
    public boolean onBandCommand(String command) {
        switch (BandCommand.axis(command)) {
            case BandCommand.FORWARD:
                focusRelativeDebounced(1);
                return true;
            case BandCommand.BACKWARD:
                focusRelativeDebounced(-1);
                return true;
            case BandCommand.ACTIVATE:
                View target = currentAction();
                if (target != null) {
                    target.performClick();
                }
                return true;
            case BandCommand.BACK:
                navigateBack();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (handleNavigationKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    private boolean handleNavigationKey(KeyEvent event) {
        int keyCode = event.getKeyCode();
        if (!isNavigationKey(keyCode)) {
            return false;
        }
        if (event.getAction() != KeyEvent.ACTION_DOWN || event.getRepeatCount() > 0) {
            return true;
        }
        if (isBackKey(keyCode)) {
            navigateBack();
        } else if (isNextKey(keyCode)) {
            focusRelativeDebounced(1);
        } else if (isPreviousKey(keyCode)) {
            focusRelativeDebounced(-1);
        } else if (isSelectKey(keyCode)) {
            View target = currentAction();
            if (target != null) {
                target.performClick();
            }
        }
        return true;
    }

    private View buildView() {
        scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.BLACK);
        scrollView.setFocusable(false);
        scrollView.setVerticalScrollBarEnabled(false);
        scrollView.setOverScrollMode(View.OVER_SCROLL_NEVER);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(18), dp(10), dp(18), dp(10));

        scrollView.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.WRAP_CONTENT));
        return scrollView;
    }

    private void showHome() {
        backStack.clear();
        setScreen(Screen.HOME);
    }

    private void navigateTo(Screen target) {
        if (screen != target) {
            backStack.push(screen);
        }
        setScreen(target);
    }

    private void setScreen(Screen target) {
        screen = target;
        selectedActionIndex = 0;
        render();
    }

    private void render() {
        if (content == null) {
            return;
        }
        content.removeAllViews();
        actionViews.clear();
        addHeader();

        switch (screen) {
            case HOME:
                action(getString(R.string.home_pair), pairDetail(), v -> pairOrReconnect());
                action(getString(R.string.home_self_arm), getString(R.string.home_self_arm_detail),
                        v -> startLocalSelfArm());
                // Gestures, wrist, navigation and pause are set from the phone (BandSettings).
                action(getString(R.string.home_band), getString(R.string.home_band_detail), v -> navigateTo(Screen.BAND));
                action(getString(R.string.home_web_apps), webAppsDetail(), v -> navigateTo(Screen.WEB_APPS));
                action(getString(R.string.home_system), getString(R.string.home_system_detail), v -> navigateTo(Screen.SYSTEM));
                break;
            case BAND:
                action(getString(R.string.band_import), importDetail(), v -> importBandKey());
                action(getString(R.string.band_gesture_guide), getString(R.string.band_gesture_guide_detail),
                        v -> navigateTo(Screen.GESTURES));
                action(getString(R.string.band_log), lastLogLine(), v -> navigateTo(Screen.LOG));
                action(getString(R.string.band_power_saving), getString(GestureMappings.isPowerSaving(this)
                                ? R.string.band_power_saving_on : R.string.band_power_saving_off),
                        v -> togglePowerSaving());
                if (isDebuggable()) {
                    action(getString(R.string.band_simulated), getString(GestureMappings.isSimulated(this)
                                    ? R.string.band_simulated_on : R.string.band_simulated_off),
                            v -> toggleSimulated());
                }
                action(getString(R.string.band_forget), getString(R.string.band_forget_detail),
                        v -> navigateTo(Screen.FORGET_CONFIRM));
                break;
            case GESTURES:
                guide(getString(R.string.guide_next), getString(R.string.guide_next_detail));
                guide(getString(R.string.guide_previous), getString(R.string.guide_previous_detail));
                guide(getString(R.string.guide_index_tap), getString(R.string.guide_index_tap_detail));
                guide(getString(R.string.guide_middle_tap), getString(R.string.guide_middle_tap_detail));
                guide(getString(R.string.guide_middle_hold), getString(R.string.guide_middle_hold_detail));
                guide(getString(R.string.guide_mapped), getString(R.string.guide_mapped_detail));
                break;
            case LOG:
                List<String> lines = BandRuntime.recentLog();
                if (lines.isEmpty()) {
                    guide(getString(R.string.log_empty), getString(R.string.log_empty_detail));
                }
                for (int i = lines.size() - 1; i >= 0; i--) {
                    guide(lines.get(i), "");
                }
                break;
            case WEB_APPS:
                List<WebApp> apps = WebAppLibrary.all(this);
                action(getString(R.string.web_open_grid), getString(R.string.web_open_grid_detail),
                        v -> startActivity(new Intent(this, LauncherActivity.class)));
                action(getString(R.string.web_install), webAppPackagesDetail(), v -> {
                    List<String> installed = WebAppPackages.importFromDropFolder(this);
                    Toast.makeText(this, installed.isEmpty() ? getString(R.string.web_install_none) : TextUtils.join("\n", installed),
                            Toast.LENGTH_LONG).show();
                    render();
                });
                if (apps.isEmpty()) {
                    guide(getString(R.string.web_none), "adb shell am start -n <pkg>/.WebAppActivity -d <https url>");
                }
                for (WebApp app : apps) {
                    action(app.getName(), app.getOffline() ? getString(R.string.web_offline_url, app.getUrl()) : app.getUrl(),
                            v -> WebAppActivity.open(this, app));
                    action(getString(R.string.web_engine, engineLabel(app.getEngine())),
                            getString(R.string.web_engine_detail, app.getName()), v -> {
                        WebAppLibrary.setEngine(this, app.getId(), app.getEngine() == WebEngineKind.GECKO
                                ? WebEngineKind.SYSTEM : WebEngineKind.GECKO);
                        render();
                    });
                    action(getString(R.string.web_remove, app.getName()),
                            getString(app.getOffline() ? R.string.web_remove_offline : R.string.web_remove_online), v -> {
                        WebAppLibrary.remove(this, app.getId());
                        render();
                    });
                }
                break;
            case SYSTEM:
                action(getString(R.string.system_accessibility), getString(R.string.system_accessibility_detail),
                        v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
                action(getString(R.string.system_bluetooth), getString(R.string.system_bluetooth_detail),
                        v -> startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));
                action(getString(R.string.system_app), getString(R.string.system_app_detail), v -> openAppSettings());
                break;
            case FORGET_CONFIRM:
                action(getString(R.string.forget_cancel), getString(R.string.forget_cancel_detail), v -> navigateBack());
                action(getString(R.string.band_forget), getString(R.string.forget_confirm_detail), v -> {
                    forgetBand();
                    showHome();
                });
                break;
            default:
                break;
        }
        if (!actionViews.isEmpty() && selectedActionIndex >= actionViews.size()) {
            selectedActionIndex = actionViews.size() - 1;
        }
        scrollView.post(() -> focusAction(selectedActionIndex));
    }

    private String engineLabel(WebEngineKind kind) {
        return getString(kind == WebEngineKind.SYSTEM ? R.string.engine_system : R.string.engine_gecko);
    }

    private void addHeader() {
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(topBar, fullWidth(dp(32)));

        TextView title = new TextView(this);
        title.setText(titleForScreen());
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(screen == Screen.FORGET_CONFIRM ? 19 : 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        topBar.addView(title, weighted(dp(30), 1f));

        TextView badge = new TextView(this);
        badge.setText(bandBadge());
        badge.setTextColor(bandColor());
        badge.setTextSize(11);
        badge.setTypeface(Typeface.DEFAULT_BOLD);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(compactBadgeOutline(bandColor()));
        topBar.addView(badge, new LinearLayout.LayoutParams(dp(82), dp(24)));

        TextView status = new TextView(this);
        status.setText(statusForScreen());
        status.setTextColor(Color.rgb(197, 218, 208));
        status.setTextSize(12);
        status.setSingleLine(true);
        status.setEllipsize(TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(status, fullWidth(dp(24)));
    }

    private String titleForScreen() {
        switch (screen) {
            case BAND:
                return getString(R.string.title_band);
            case GESTURES:
                return getString(R.string.title_gestures);
            case LOG:
                return getString(R.string.title_log);
            case WEB_APPS:
                return getString(R.string.title_web_apps);
            case SYSTEM:
                return getString(R.string.title_system);
            case FORGET_CONFIRM:
                return getString(R.string.title_forget);
            case HOME:
            default:
                return getString(R.string.settings_title);
        }
    }

    private String statusForScreen() {
        if (screen == Screen.FORGET_CONFIRM) {
            return getString(R.string.status_forget);
        }
        String service = serviceLabel();
        String localSelfArm = LocalSelfArmStatus.summary(this);
        if (!TextUtils.isEmpty(localSelfArm) && (screen == Screen.HOME || screen == Screen.SYSTEM)) {
            return getString(R.string.status_line, service, localSelfArm);
        }
        switch (screen) {
            case WEB_APPS:
                return getString(R.string.status_line, service, getString(R.string.status_web_apps));
            case SYSTEM:
                return getString(R.string.status_line, service, getString(R.string.status_system));
            case HOME:
            case BAND:
            case GESTURES:
            case LOG:
            default:
                return getString(R.string.status_line, service, bandPhrase());
        }
    }

    /**
     * R08's "Service STUCK": Settings lists the service as on while it isn't running, which the
     * firmware's force-stops leave behind. The watchdog and SelfArmController repair it.
     */
    private String serviceLabel() {
        boolean enabled = isAccessibilityEnabled();
        if (enabled && !BandAccessibilityService.isServiceActive()) {
            return getString(R.string.service_stuck);
        }
        return getString(enabled ? R.string.service_on : R.string.service_off);
    }

    private String bandPhrase() {
        if (!GestureMappings.isSimulated(this) && !Identity.present(this)) {
            return getString(R.string.phrase_import);
        }
        switch (BandRuntime.getPhase()) {
            case SEARCHING:
                return getString(R.string.phrase_waiting);
            case CONNECTING:
                return getString(R.string.phrase_connecting);
            case CONNECTED:
                return getString(BandRuntime.isPaused() ? R.string.phrase_connected_paused : R.string.phrase_connected);
            case STOPPED:
            default:
                return getString(BandAccessibilityService.isServiceActive() ? R.string.phrase_stopped : R.string.phrase_enable_service);
        }
    }

    private String bandBadge() {
        switch (BandRuntime.getPhase()) {
            case SEARCHING:
                return getString(R.string.badge_searching);
            case CONNECTING:
                return getString(R.string.badge_connecting);
            case CONNECTED:
                if (BandRuntime.isPaused()) {
                    return getString(R.string.badge_paused);
                }
                int battery = BandRuntime.battery();
                return battery >= 0 ? getString(R.string.badge_battery, battery) : getString(R.string.badge_band);
            case STOPPED:
            default:
                return getString(R.string.badge_none);
        }
    }

    private int bandColor() {
        switch (BandRuntime.getPhase()) {
            case CONNECTED:
                return BandRuntime.isPaused() ? Color.rgb(122, 210, 232) : Color.rgb(102, 242, 165);
            case SEARCHING:
            case CONNECTING:
                return Color.rgb(238, 190, 92);
            case STOPPED:
            default:
                return Color.rgb(117, 142, 130);
        }
    }

    private String pairDetail() {
        if (!GestureMappings.isSimulated(this) && !Identity.present(this)) {
            return getString(R.string.pair_import_first);
        }
        if (GestureMappings.isBandOnPhone(this)) {
            return getString(R.string.pair_on_phone);
        }
        String name = BandRuntime.getBandName();
        if (name == null) {
            name = Identity.bandName(this);
        }
        return name == null ? getString(R.string.pair_scan) : getString(R.string.pair_reconnect, name);
    }

    private String importDetail() {
        if (lastImportResult != null) {
            return lastImportResult;
        }
        if (Identity.present(this)) {
            String name = Identity.bandName(this);
            return name == null ? getString(R.string.import_done) : getString(R.string.import_done_named, name);
        }
        return getString(R.string.import_hint);
    }

    private String webAppPackagesDetail() {
        java.io.File folder = WebAppPackages.dropFolder(this);
        return "adb push <app>.mrbd.zip " + (folder == null ? getString(R.string.web_folder_unavailable) : folder.getAbsolutePath());
    }

    private String webAppsDetail() {
        int count = WebAppLibrary.all(this).size();
        return count == 0 ? getString(R.string.web_apps_none) : getResources().getQuantityString(R.plurals.web_apps_count, count, count);
    }

    private String lastLogLine() {
        List<String> lines = BandRuntime.recentLog();
        return lines.isEmpty() ? getString(R.string.band_log_detail) : lines.get(lines.size() - 1);
    }

    private void togglePowerSaving() {
        BandSettings.setPowerSaving(this, !GestureMappings.isPowerSaving(this));
        // The phone's settings screen follows.
        BandSettings.pushSchema(this);
        render();
    }

    private void toggleSimulated() {
        GestureMappings.setSimulated(this, !GestureMappings.isSimulated(this));
        if (BandAccessibilityService.isServiceActive()) {
            BandRuntime.restart(this);
        }
        render();
    }

    private void importBandKey() {
        lastImportResult = Identity.importFromDropFolder(this);
        Toast.makeText(this, lastImportResult, Toast.LENGTH_LONG).show();
        if (Identity.present(this) && BandAccessibilityService.isServiceActive()) {
            BandRuntime.restart(this);
        }
        render();
    }

    private void pairOrReconnect() {
        if (!BandAccessibilityService.isServiceActive()) {
            Toast.makeText(this, R.string.toast_enable_service, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            return;
        }
        if (!GestureMappings.isSimulated(this) && !Identity.present(this)) {
            navigateTo(Screen.BAND);
            return;
        }
        requestRuntimePermissions();
        // Reconnecting here also takes the band back from the phone, and tells the phone.
        String problem = BandSettings.action(this, BandSettings.ACTION_RECONNECT);
        Toast.makeText(this, problem == null ? getString(R.string.toast_pair_started) : problem, Toast.LENGTH_SHORT).show();
    }

    private void forgetBand() {
        BandRuntime.stop();
        boolean bondKept = Identity.forget(this);
        lastImportResult = null;
        Toast.makeText(this, bondKept
                        ? getString(R.string.toast_key_deleted)
                        : getString(R.string.toast_band_forgotten),
                Toast.LENGTH_LONG).show();
    }

    private void startLocalSelfArm() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            LocalSelfArmStatus.reportSimple(this, "api_30_required");
            Toast.makeText(this, R.string.self_arm_needs_11, Toast.LENGTH_SHORT).show();
            render();
            return;
        }
        if (!isAccessibilityEnabled()) {
            LocalSelfArmStatus.reportSimple(this, "accessibility_service_needed");
            Toast.makeText(this, R.string.enable_accessibility_first, Toast.LENGTH_SHORT).show();
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            render();
            return;
        }
        if (NexusGuard.isPresent(this)) {
            LocalSelfArmStatus.reportSimple(this, "nexus_present");
            Toast.makeText(this, R.string.self_arm_nexus, Toast.LENGTH_LONG).show();
            render();
            return;
        }
        boolean started = BandAccessibilityService.requestLocalSelfArm(this);
        Toast.makeText(this, started ? R.string.self_arm_started : R.string.enable_accessibility_first,
                Toast.LENGTH_SHORT).show();
        render();
    }



    private void action(String titleText, String detailText, View.OnClickListener listener) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), 0, dp(14), 0);
        row.setFocusable(true);
        row.setClickable(true);
        int rowIndex = actionViews.size();
        row.setBackground(outline(rowIndex == selectedActionIndex));
        row.setOnFocusChangeListener((v, focused) -> {
            if (focused) {
                int index = actionViews.indexOf(v);
                if (index >= 0) {
                    selectedActionIndex = index;
                    updateActionSelection();
                }
                reveal(v);
            }
        });
        row.setOnClickListener(listener);

        TextView title = new TextView(this);
        title.setText(titleText);
        title.setTextColor(Color.rgb(248, 250, 249));
        title.setTextSize(15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(title, fullWidth(dp(22)));

        TextView detail = new TextView(this);
        detail.setText(detailText);
        detail.setTextColor(Color.rgb(161, 183, 172));
        detail.setTextSize(10);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        detail.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(detail, fullWidth(dp(18)));

        LinearLayout.LayoutParams params = fullWidth(dp(48));
        params.setMargins(0, dp(3), 0, dp(3));
        content.addView(row, params);
        actionViews.add(row);
    }

    /** A row that only informs; selecting it does nothing. */
    private void guide(String titleText, String detailText) {
        action(titleText, detailText, v -> { });
    }

    private boolean isNextKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_MEDIA_NEXT;
    }

    private boolean isPreviousKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_DPAD_UP
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_MEDIA_PREVIOUS;
    }

    private boolean isSelectKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_DPAD_CENTER
                || keyCode == KeyEvent.KEYCODE_ENTER
                || keyCode == KeyEvent.KEYCODE_SPACE;
    }

    private boolean isBackKey(int keyCode) {
        return keyCode == KeyEvent.KEYCODE_BACK
                || keyCode == KeyEvent.KEYCODE_ESCAPE;
    }

    private boolean isNavigationKey(int keyCode) {
        return isBackKey(keyCode) || isNextKey(keyCode) || isPreviousKey(keyCode) || isSelectKey(keyCode);
    }

    private View currentAction() {
        if (actionViews.isEmpty()) {
            return null;
        }
        if (selectedActionIndex < 0 || selectedActionIndex >= actionViews.size()) {
            selectedActionIndex = 0;
        }
        focusAction(selectedActionIndex);
        return actionViews.get(selectedActionIndex);
    }

    private void navigateBack() {
        if (backStack.isEmpty()) {
            // Settings open from the grid, in its task: Back returns there.
            finish();
        } else {
            setScreen(backStack.pop());
        }
    }


    private void focusRelativeDebounced(int delta) {
        long now = SystemClock.uptimeMillis();
        if (delta == lastNavDirection && now - lastNavAt < NAV_DEBOUNCE_MS) {
            return;
        }
        lastNavAt = now;
        lastNavDirection = delta;
        if (actionViews.isEmpty()) {
            return;
        }
        int current = selectedActionIndex;
        int next = current < 0 ? 0 : (current + delta + actionViews.size()) % actionViews.size();
        focusAction(next);
    }

    private void focusAction(int index) {
        if (index < 0 || index >= actionViews.size()) {
            return;
        }
        selectedActionIndex = index;
        updateActionSelection();
        View target = actionViews.get(index);
        target.requestFocus();
        reveal(target);
    }

    private void updateActionSelection() {
        for (int i = 0; i < actionViews.size(); i++) {
            actionViews.get(i).setBackground(outline(i == selectedActionIndex));
        }
    }

    private void reveal(View target) {
        if (scrollView == null) {
            return;
        }
        Rect rect = new Rect(0, 0, target.getWidth(), target.getHeight());
        target.requestRectangleOnScreen(rect, false);
    }

    private GradientDrawable outline(boolean focused) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(focused ? Color.rgb(18, 24, 21) : Color.TRANSPARENT);
        drawable.setCornerRadius(dp(6));
        drawable.setStroke(focused ? dp(3) : dp(1),
                focused ? Color.rgb(102, 242, 165) : Color.rgb(117, 142, 130));
        return drawable;
    }

    private GradientDrawable compactBadgeOutline(int color) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.TRANSPARENT);
        drawable.setCornerRadius(dp(5));
        drawable.setStroke(dp(1), color);
        return drawable;
    }

    private boolean isAccessibilityEnabled() {
        return AccessibilityHandoff.isEnabled(this);
    }

    private boolean isDebuggable() {
        return (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    }

    /** Bluetooth only; notifications are never asked for (they don't reach the glasses' HUD). */
    private void requestRuntimePermissions() {
        List<String> permissions = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_SCAN);
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT);
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
        }
        List<String> missing = new ArrayList<>();
        for (String permission : permissions) {
            if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
                missing.add(permission);
            }
        }
        if (!missing.isEmpty()) {
            requestPermissions(missing.toArray(new String[0]), PERMISSION_REQUEST);
        }
    }

    private void openAppSettings() {
        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.fromParts("package", getPackageName(), null));
        startActivity(intent);
    }

    // Pre-Tiramisu registerReceiver has no flag parameter; the broadcast is app-internal
    // (LocalSelfArmStatus sends it with setPackage), so NOT_EXPORTED is correct on T+.
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerLocalSelfArmStatusReceiver() {
        if (localSelfArmStatusReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(LocalSelfArmStatus.ACTION_CHANGED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(localSelfArmStatusReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(localSelfArmStatusReceiver, filter);
        }
        localSelfArmStatusReceiverRegistered = true;
    }

    private void unregisterLocalSelfArmStatusReceiver() {
        if (!localSelfArmStatusReceiverRegistered) {
            return;
        }
        localSelfArmStatusReceiverRegistered = false;
        try {
            unregisterReceiver(localSelfArmStatusReceiver);
        } catch (IllegalArgumentException ignored) {
            // Receiver was not registered.
        }
    }

    private LinearLayout.LayoutParams fullWidth(int height) {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, height);
    }

    private LinearLayout.LayoutParams weighted(int height, float weight) {
        return new LinearLayout.LayoutParams(0, height, weight);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }


    private enum Screen {
        HOME,
        BAND,
        GESTURES,
        LOG,
        WEB_APPS,
        SYSTEM,
        FORGET_CONFIRM
    }
}
