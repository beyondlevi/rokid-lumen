package dev.lumen.glasses;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.json.JSONObject;

/**
 * Runs a Meta Ray-Ban Display web app on the Rokid glasses. MRBD gives an app a 600x600 CSS
 * pixel viewport on an additive display (black is transparent) and drives it with arrow keys,
 * Enter and a history-based Back; this activity reproduces that around a {@link WebEngine}
 * chosen per app (GeckoView or the system WebView): the band's swipes are arrow keys, the index
 * tap is Enter, the middle tap is Back, and a text field opens the dictation composer on Enter.
 *
 * Opens an app of the library (EXTRA_APP_ID). Adding one, from outside or from a page's
 * {@code navigator.install()}, goes through {@link InstallConfirmActivity}.
 */
public final class WebAppActivity extends Activity implements BandAccessibilityService.InputTarget, WebEngine.Host {
    public static final String EXTRA_APP_ID = "app_id";
    /** A page of the app to open instead of its start ("/chat/…"): a phone notification's. */
    public static final String EXTRA_PATH = "path";
    /** Overrides the app's engine for this launch ("GECKO" or "SYSTEM"), for comparisons. */
    public static final String EXTRA_ENGINE = "engine";

    private static final String TAG = "BandWebApp";
    /**
     * How long a hidden app keeps the phone's internet. The display goes off after a couple of
     * minutes without input: a glance back within this finds the app online at once (joining the
     * phone's network again takes 7 to 30 s, measured).
     */
    private static final long HIDDEN_STOP_MS = 5 * 60_000L;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private WebEngine engine;
    private String appId;
    /** The offline app's server this screen holds (LocalAppServer.acquire), or 0. */
    private int serverPort;
    /** Held while an online app is on screen: how it reaches the internet (see PhoneInternet). */
    private PhoneInternet.Listener internet;
    /** Whether [internet] is held now: let go while the app is hidden, taken again when it's back. */
    private boolean internetHeld;
    private final Runnable hiddenStop = this::stopHidden;
    private boolean loaded;
    private WebComposer composer;
    private TextView notice;
    private TextToSpeech tts;
    private boolean ttsReady;

    static void open(Context context, WebApp app) {
        open(context, app, null);
    }

    /** Opens [app] at [path] (a path of the app, "/…"), or at its start when null. */
    static void open(Context context, WebApp app, String path) {
        Intent intent = new Intent(context, WebAppActivity.class).putExtra(EXTRA_APP_ID, app.getId());
        if (path != null) {
            intent.putExtra(EXTRA_PATH, path);
        }
        if (!(context instanceof Activity)) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        }
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        WebApp app = appFrom(getIntent());
        if (app == null) {
            Toast.makeText(this, R.string.webapp_https_only, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if (app.getOffline() && !LocalAppServer.acquire(this, app)) {
            Toast.makeText(this, R.string.webapp_local_server, Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        // Held until onDestroy: the app's server stops once no screen uses it.
        serverPort = app.getOffline() ? app.getPort() : 0;
        appId = app.getId();
        WebEngineKind kind = app.getEngine();
        String override = getIntent().getStringExtra(EXTRA_ENGINE);
        if (override != null) {
            kind = WebEngineKind.of(override);
        }

        DisplayMetrics metrics = getResources().getDisplayMetrics();
        int side = Math.min(metrics.widthPixels, metrics.heightPixels);
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        engine = kind == WebEngineKind.SYSTEM
                ? new SystemWebEngine(this, readAsset("mrbd-shim.js"), side, this, app)
                : new GeckoWebEngine(this, side, this, app);
        root.addView(engine.getView(), new FrameLayout.LayoutParams(side, side, Gravity.CENTER));
        notice = new TextView(this);
        notice.setTextColor(Color.rgb(248, 250, 249));
        notice.setBackgroundColor(Color.argb(230, 16, 22, 19));
        notice.setTextSize(13f);
        notice.setGravity(Gravity.CENTER);
        int pad = Math.round(12 * metrics.density);
        notice.setPadding(pad, pad, pad, pad);
        notice.setVisibility(View.GONE);
        root.addView(notice, new FrameLayout.LayoutParams(side - 2 * pad, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM));
        setContentView(root);
        composer = new WebComposer(this, root, side);

        tts = new TextToSpeech(this, status -> ttsReady = status == TextToSpeech.SUCCESS);
        tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                speechEvent(utteranceId, "start", null);
            }

            @Override
            public void onDone(String utteranceId) {
                speechEvent(utteranceId, "end", null);
            }

            @Override
            public void onError(String utteranceId) {
                speechEvent(utteranceId, "error", "synthesis-failed");
            }
        });
        Log.d(TAG, "Opening " + app.getName() + " (" + app.getUrl() + ") on " + kind + " side=" + side);
        WebAppConfig.addListener(configListener);
        if (app.getOffline()) {
            engine.load(startUrl(app));
            if (app.getInternet()) {
                acquireForOfflineApp(kind == WebEngineKind.SYSTEM);
            }
            return;
        }
        String url = startUrl(app);
        boolean system = kind == WebEngineKind.SYSTEM;
        internet = new PhoneInternet.Listener() {
            @Override
            public void onStatus(String text) {
                if (!loaded) {
                    showNotice(text);
                }
            }

            @Override
            public void onReady(String proxy) {
                hideNotice();
                if (loaded) {
                    return;
                }
                loaded = true;
                // The page's icon may have changed (or never been fetched): looked up again, now that there's internet.
                WebAppIcons.refreshOnOpen(WebAppActivity.this, app, GridApi::pushState);
                // Gecko asks PhoneInternet for the proxy itself (mrbd-ext); WebView's is global.
                if (system) {
                    SystemWebEngine.useProxy(proxy, () -> engine.load(url));
                } else {
                    engine.load(url);
                }
            }

            @Override
            public void onFailed(String text) {
                showNotice(getString(R.string.net_no_internet, text));
            }
        };
        PhoneInternet.setForcePhone((getApplicationInfo().flags & android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
                && getIntent().getBooleanExtra("force_phone", false));
        PhoneInternet.acquire(this, internet);
        internetHeld = true;
    }

    /**
     * An offline app that reaches the internet (manifest `lumen_internet`): it opens right away
     * from its loopback server, and the internet comes up behind it, the phone's when the glasses
     * have none (the app shows its own "connecting" meanwhile).
     */
    private void acquireForOfflineApp(boolean system) {
        internet = new PhoneInternet.Listener() {
            @Override
            public void onStatus(String text) {
                Log.d(TAG, "Internet for the offline app: " + text);
            }

            @Override
            public void onReady(String proxy) {
                Log.d(TAG, "Internet ready for the offline app, proxy=" + proxy);
                // Gecko asks PhoneInternet for the proxy itself (mrbd-ext); WebView's is global.
                if (system) {
                    SystemWebEngine.useProxy(proxy, () -> { });
                }
            }

            @Override
            public void onFailed(String text) {
                Log.w(TAG, "No internet for the offline app: " + text);
            }
        };
        PhoneInternet.acquire(this, internet);
        internetHeld = true;
    }

    /** Scheme, host and port of [url], as a page's location.origin reads; "" when it has none. */
    private static String originOf(String url) {
        return WebOrigin.of(url);
    }

    private final WebAppConfig.Listener configListener = changedId -> {
        if (engine == null || !changedId.equals(appId)) {
            return;
        }
        WebApp current = WebAppLibrary.find(this, appId);
        if (current != null) {
            engine.configChanged(WebAppConfig.values(this, current), originOf(current.getUrl()));
        }
    };

    @Override
    public void onGetConfig(int id, String pageUrl) {
        WebApp current = WebAppLibrary.find(this, appId);
        String origin = current == null ? "" : originOf(current.getUrl());
        JSONObject values = new JSONObject();
        // The values may be secrets: only the app's own page gets them (not a page it navigated to).
        if (current != null && !origin.isEmpty() && origin.equals(originOf(pageUrl))) {
            values = WebAppConfig.values(this, current);
        } else {
            Log.w(TAG, "Configuration refused to " + originOf(pageUrl) + " (the app is " + origin + ")");
        }
        engine.configResult(id, values, origin);
    }

    /**
     * A `window.lumen.audio` request (GlassesAudio). Only the app's own page: what it records and
     * hears are the user's. Events go back to that origin only.
     */
    @Override
    public void onAudio(JSONObject message, String pageUrl) {
        WebApp current = WebAppLibrary.find(this, appId);
        String origin = current == null ? "" : originOf(current.getUrl());
        if (current == null || origin.isEmpty() || !origin.equals(originOf(pageUrl))) {
            Log.w(TAG, "Audio refused to " + originOf(pageUrl) + " (the app is " + origin + ")");
            return;
        }
        GlassesAudio.Page page = event -> {
            if (engine != null) {
                engine.audioEvent(event, origin);
            }
        };
        if (WebRecognition.handles(message)) {
            WebRecognition.request(this, this, message, page);
        } else {
            GlassesAudio.request(this, message, page);
        }
    }

    /** The app to open, by id; nothing is added here (see InstallConfirmActivity). */
    private WebApp appFrom(Intent intent) {
        return intent == null ? null : WebAppLibrary.find(this, intent.getStringExtra(EXTRA_APP_ID));
    }

    @Override
    protected void onResume() {
        super.onResume();
        BandAccessibilityService.setInputTarget(this);
        if (PhoneDictation.applies(this)) {
            PhoneDictation.announce();
        }
        if (engine != null) {
            engine.onResume();
        }
    }

    @Override
    protected void onPause() {
        if (composer != null) {
            composer.closeNow();
        }
        BandAccessibilityService.clearInputTarget(this);
        if (engine != null) {
            engine.onPause();
        }
        super.onPause();
    }

    @Override
    protected void onStart() {
        super.onStart();
        mainHandler.removeCallbacks(hiddenStop);
        if (engine != null) {
            engine.onShown();
        }
        if (internet != null && !internetHeld) {
            PhoneInternet.acquire(this, internet);
            internetHeld = true;
        }
    }

    /**
     * Hidden (another screen covers the app, or the display went off): the microphone closes and
     * the page stops; after HIDDEN_STOP_MS the phone's internet is let go too (after
     * PhoneInternet's grace) until onStart. Before, they were held until the app was closed, all
     * night if it was left open.
     */
    @Override
    protected void onStop() {
        GlassesAudio.closeAll(this);
        WebRecognition.closeAll(this);
        if (engine != null) {
            engine.onHidden();
        }
        mainHandler.removeCallbacks(hiddenStop);
        mainHandler.postDelayed(hiddenStop, HIDDEN_STOP_MS);
        super.onStop();
    }

    private void stopHidden() {
        if (internet != null && internetHeld) {
            Log.d(TAG, "Hidden for " + HIDDEN_STOP_MS / 1000 + " s: letting the phone's internet go");
            PhoneInternet.release(internet);
            internetHeld = false;
        }
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacks(hiddenStop);
        GlassesAudio.closeAll(this);
        WebRecognition.closeAll(this);
        WebAppConfig.removeListener(configListener);
        if (internet != null && internetHeld) {
            PhoneInternet.release(internet);
            internetHeld = false;
        }
        if (tts != null) {
            tts.shutdown();
        }
        if (engine != null) {
            engine.destroy();
        }
        if (serverPort != 0) {
            LocalAppServer.release(serverPort);
        }
        super.onDestroy();
    }

    /** The band, while this app is in front; volume and mapped actions run as anywhere. */
    @Override
    public boolean onBandCommand(String command) {
        Log.d(TAG, "Band " + command + (composer != null && composer.isOpen() ? " to the composer" : " to the page"));
        if (composer != null && composer.onBandCommand(command)) {
            return true;
        }
        if (engine == null) {
            return false;
        }
        hideNotice();
        switch (command) {
            case BandCommand.UP:
            case BandCommand.BACKWARD:
                engine.key(KeyEvent.KEYCODE_DPAD_UP);
                return true;
            case BandCommand.DOWN:
            case BandCommand.FORWARD:
                engine.key(KeyEvent.KEYCODE_DPAD_DOWN);
                return true;
            case BandCommand.LEFT:
                engine.key(KeyEvent.KEYCODE_DPAD_LEFT);
                return true;
            case BandCommand.RIGHT:
                engine.key(KeyEvent.KEYCODE_DPAD_RIGHT);
                return true;
            case BandCommand.ACTIVATE:
                engine.key(KeyEvent.KEYCODE_ENTER);
                return true;
            case BandCommand.BACK:
                engine.back();
                return true;
            default:
                return false;
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (composer != null && composer.isOpen()) {
                    composer.onBandCommand(BandCommand.BACK);
                } else if (engine != null) {
                    engine.back();
                } else {
                    finish();
                }
            }
            return true;
        }
        if (composer != null && composer.isOpen()) {
            // The touchpad drives the open composer as the band does.
            if (event.getAction() == KeyEvent.ACTION_UP) {
                if (code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_DPAD_CENTER) {
                    composer.onBandCommand(BandCommand.ACTIVATE);
                } else if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    composer.onBandCommand(BandCommand.LEFT);
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == WebComposer.REQUEST_MICROPHONE && composer != null) {
            composer.onPermissionResult(grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED);
        }
    }

    // WebEngine.Host

    @Override
    public void onBackUnhandled() {
        if (engine == null || !engine.historyBack()) {
            finish();
        }
    }

    @Override
    public void onOpenComposer(String value, boolean multiline) {
        Log.d(TAG, "Composer opens (multiline=" + multiline + ", " + value.length() + " chars)");
        composer.open(value, new WebComposer.Target() {
            @Override
            public void composerInput(String text) {
                engine.composerInput(text);
            }

            @Override
            public void composerClose() {
                engine.composerClose();
            }
        });
    }

    /** The page's navigator.install(): the user confirms it on the glasses first. */
    @Override
    public void onInstall(String url, String name) {
        InstallConfirmActivity.confirmWebApp(this, url, name);
    }

    @Override
    public void onSpeak(int id, String text, String lang, float rate, float pitch) {
        String utteranceId = String.valueOf(id);
        if (!ttsReady) {
            speechEvent(utteranceId, "error", "synthesis-unavailable");
            return;
        }
        Locale locale = lang == null || lang.isEmpty() ? Locale.US : Locale.forLanguageTag(lang);
        if (tts.isLanguageAvailable(locale) < TextToSpeech.LANG_AVAILABLE) {
            locale = Locale.US;
        }
        tts.setLanguage(locale);
        tts.setSpeechRate(rate);
        tts.setPitch(pitch);
        if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId) != TextToSpeech.SUCCESS) {
            speechEvent(utteranceId, "error", "synthesis-failed");
        }
    }

    @Override
    public void onCancelSpeech() {
        if (tts != null) {
            tts.stop();
        }
    }

    @Override
    public void onLoadFailed(String description) {
        showNotice(PhoneInternet.getProxy() != null || PhoneInternet.hasDirectInternet(this)
                ? getString(R.string.webapp_load_failed, description)
                : getString(R.string.webapp_no_internet));
    }

    private void speechEvent(String utteranceId, String type, String code) {
        mainHandler.post(() -> {
            if (engine != null) {
                engine.speechEvent(utteranceId, type, code);
            }
        });
    }

    private void showNotice(String text) {
        notice.setText(text);
        notice.setVisibility(View.VISIBLE);
        notice.bringToFront();
    }

    private void hideNotice() {
        if (notice != null) {
            notice.setVisibility(View.GONE);
        }
    }

    private String readAsset(String name) {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = input.read(buffer)) != -1) {
                output.write(buffer, 0, read);
            }
            return output.toString(StandardCharsets.UTF_8.name());
        } catch (Exception e) {
            Log.w(TAG, "Could not read " + name, e);
            return "";
        }
    }

    /**
     * Where the app opens: its start, or the page EXTRA_PATH names on its own origin (a path
     * only, never another host: "//x" and anything not starting with "/" are ignored).
     */
    private String startUrl(WebApp app) {
        String path = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !path.startsWith("/") || path.startsWith("//") || path.equals("/")) {
            return app.getUrl();
        }
        return originOf(app.getUrl()) + path;
    }
}
