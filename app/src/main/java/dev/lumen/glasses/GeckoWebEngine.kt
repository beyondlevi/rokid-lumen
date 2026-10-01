package dev.lumen.glasses

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import org.json.JSONObject
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.GeckoView
import org.mozilla.geckoview.WebExtension
import org.mozilla.geckoview.WebExtensionController
import org.mozilla.geckoview.WebRequestError

/**
 * GeckoView (Firefox's engine, current and bundled with the app) instead of the glasses'
 * Chromium 95. The 600x600 viewport comes from the runtime's display density override
 * (HUD side / 600 CSS px), so the view needs no scaling. The shim and the host bridge arrive
 * through a built-in WebExtension (assets/mrbd-ext): content script ↔ background ↔ the native
 * app "browser" here.
 *
 * Each app's session lives in its own context ([WebAppContexts]): cookies, storage and cache
 * apart from every other app's, cleared when the app is removed.
 *
 * The bridge acts only for the app's own origin ([WebOrigin]): background.js tags every page
 * message with the tab and address of the page that sent it (which the page can't forge), and
 * a message from any other origin is ignored. What goes to the page goes to its tab only.
 */
class GeckoWebEngine(
    activity: Activity,
    side: Int,
    private val host: WebEngine.Host,
    private val app: WebApp,
) : WebEngine {
    private val geckoView = GeckoView(activity)
    private val session = GeckoSession(
        GeckoSessionSettings.Builder().contextId(WebAppContexts.idFor(app.id)).build(),
    )
    private val appOrigin = WebOrigin.ofApp(app)
    private var canGoBack = false
    /** The page's address as Gecko last reported it (for the origin checks). */
    private var pageUrl: String? = null
    /** The extension's id for this session's tab, learned from its page's first message. */
    private var tabId: Int? = null
    private var extensionReady = false
    private var pendingUrl: String? = null
    private val startedAt = SystemClock.elapsedRealtime()
    /** Gecko's own text input delegate: the system's keyboard. */
    private val keyboard: GeckoSession.TextInputDelegate = session.textInput.delegate

    override val view: View get() = geckoView

    init {
        val runtime = runtime(activity, side.toFloat() / WebEngine.MRBD_VIEWPORT)
        session.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(
                s: GeckoSession,
                url: String?,
                perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>,
                hasUserGesture: Boolean,
            ) {
                pageUrl = url
            }

            override fun onCanGoBack(s: GeckoSession, value: Boolean) {
                canGoBack = value
                post(JSONObject().put("type", "canGoBack").put("value", value))
            }

            /** Top-level loads: an offline app stays on its origin (the rest opens nowhere), an online one on HTTPS. */
            override fun onLoadRequest(s: GeckoSession, request: GeckoSession.NavigationDelegate.LoadRequest): GeckoResult<AllowOrDeny> {
                val uri = request.uri
                val allowed = WebOrigin.navigationAllowed(uri, app) || (!app.offline && uri.startsWith("about:"))
                if (!allowed) {
                    Log.w(TAG, "Navigation of ${app.name} to ${WebOrigin.of(uri).ifEmpty { uri.substringBefore(':') }} blocked")
                    if (app.offline) Toast.makeText(geckoView.context, R.string.webapp_navigation_blocked, Toast.LENGTH_SHORT).show()
                }
                return GeckoResult.fromValue(if (allowed) AllowOrDeny.ALLOW else AllowOrDeny.DENY)
            }

            override fun onLoadError(s: GeckoSession, uri: String?, error: WebRequestError): GeckoResult<String>? {
                host.onLoadFailed(geckoView.context.getString(R.string.webapp_error_code, error.code, error.category))
                return null
            }
        }
        session.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStop(s: GeckoSession, success: Boolean) {
                Log.d(TAG, "Page stop success=$success after ${SystemClock.elapsedRealtime() - startedAt} ms")
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(s: GeckoSession) {
                Log.d(TAG, "First contentful paint after ${SystemClock.elapsedRealtime() - startedAt} ms")
            }
        }
        // Gecko asks for a keyboard when a field gets focus (measured: also a programmatic focus
        // up to ~5 s after a tap). A field the composer takes gets nothing until Enter opens the
        // composer on it; the shim sends any other field (a password) back to the keyboard.
        session.textInput.setDelegate(object : GeckoSession.TextInputDelegate by keyboard {
            override fun showSoftInput(s: GeckoSession) {
                if (!post(JSONObject().put("type", "keyboardWanted"))) keyboard.showSoftInput(s)
            }
        })
        // A TextureView, not the default SurfaceView: Gecko's surface otherwise covers the
        // composer and the notices drawn over it (measured: the composer opened, invisible).
        geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        session.open(runtime)
        geckoView.setSession(session)
        // Gecko paints white until the page's first frame: on the glasses' additive display
        // that's a full-screen flash. Black is transparent there.
        geckoView.coverUntilFirstPaint(android.graphics.Color.BLACK)
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true

        HostLink.current = this
        HostLink.ensure(runtime) { ready() }
    }

    private fun ready() {
        extensionReady = true
        pendingUrl?.let { load(it) }
        pendingUrl = null
    }

    override fun load(url: String) {
        if (!extensionReady) {
            pendingUrl = url
            return
        }
        session.loadUri(url)
        focus()
    }

    /** Without focus Gecko drops the first key (measured): the session and the view both need it. */
    private fun focus() {
        session.setFocused(true)
        if (!geckoView.hasFocus()) geckoView.requestFocus()
    }

    override fun key(keyCode: Int) {
        focus()
        val now = SystemClock.uptimeMillis()
        geckoView.dispatchKeyEvent(KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0))
        geckoView.dispatchKeyEvent(KeyEvent(now, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, keyCode, 0))
    }

    override fun back() {
        // Off the app's origin (or before its page spoke) the page gets no say: history, then close.
        if (!post(JSONObject().put("type", "back").put("value", true))) host.onBackUnhandled()
    }

    override fun historyBack(): Boolean {
        if (!canGoBack) return false
        session.goBack()
        return true
    }

    override fun composerInput(text: String) {
        post(JSONObject().put("type", "composerInput").put("text", text))
    }

    override fun composerClose() {
        post(JSONObject().put("type", "composerClose").put("text", ""))
    }

    override fun speechEvent(id: String, type: String, code: String?) {
        post(JSONObject().put("type", "speech").put("id", id.toIntOrNull() ?: 0).put("event", type).put("code", code ?: JSONObject.NULL))
    }

    // To this session's tab only; content.js also drops a message whose origin isn't its page's.
    override fun configResult(id: Int, values: JSONObject, origin: String) {
        post(JSONObject().put("type", "config").put("id", id).put("values", values).put("origin", origin))
    }

    override fun configChanged(values: JSONObject, origin: String) {
        post(JSONObject().put("type", "configChanged").put("values", values).put("origin", origin))
    }

    override fun onResume() {
        HostLink.current = this
        session.setActive(true)
        focus()
    }

    override fun onPause() {
        session.setFocused(false)
    }

    override fun destroy() {
        if (HostLink.current === this) HostLink.current = null
        session.close()
    }

    /**
     * A page's message, relayed by background.js with its sender's `tabId` and `sender` (the
     * page's address, set there, not by the page). Only the app's own origin is heard.
     */
    private fun onHostMessage(message: Any) {
        val json = message as? JSONObject ?: return
        val type = json.optString("type")
        val sender = json.optString("sender")
        if (!WebOrigin.matches(sender, appOrigin) || !onAppPage()) {
            Log.w(TAG, "Page message $type ignored from ${WebOrigin.of(sender).ifEmpty { "a page off any origin" }} (the app is $appOrigin)")
            return
        }
        json.optInt("tabId", -1).takeIf { it >= 0 }?.let { tabId = it }
        when (type) {
            // A page's content script is ready: it hasn't heard canGoBack yet.
            "hello" -> post(JSONObject().put("type", "canGoBack").put("value", canGoBack))
            "backResult" -> if (!json.optBoolean("handled")) host.onBackUnhandled()
            "openComposer" -> host.onOpenComposer(json.optString("value"), json.optBoolean("multiline"))
            "noTextField" -> keyboard.showSoftInput(session)
            "install" -> host.onInstall(json.optString("url"), json.optString("name"))
            "speak" -> host.onSpeak(
                json.optInt("id"), json.optString("text"), json.optString("lang"),
                json.optDouble("rate", 1.0).toFloat(), json.optDouble("pitch", 1.0).toFloat(),
            )
            "cancelSpeech" -> host.onCancelSpeech()
            "getConfig" -> host.onGetConfig(json.optInt("id"), sender)
            else -> Log.d(TAG, "Host message $type")
        }
    }

    /**
     * Whether this session is on the app's origin. A location without an origin (none yet, or
     * the about:blank Gecko reports before the first page) doesn't rule the page out: the
     * message's sender, checked too, says where it comes from.
     */
    private fun onAppPage(): Boolean = WebOrigin.of(pageUrl).let { it.isEmpty() || it == appOrigin }

    /** To this session's page, when it's the app's own and has spoken (so its tab is known). */
    private fun post(message: JSONObject): Boolean {
        val current = HostLink.port ?: return false
        val tab = tabId ?: return false
        if (!onAppPage()) return false
        current.postMessage(message.put("tabId", tab))
        return true
    }

    /**
     * The built-in extension and its one native port, shared by every engine of the process: the
     * extension's background keeps the port it opened first, so a delegate per engine left the
     * second app's page talking to the first app's closed screen (measured: its composer opened
     * there, unseen, and the focused field got no answer). The page's messages go to the engine
     * in front ([current]), which hears only its own origin's.
     */
    private object HostLink {
        var port: WebExtension.Port? = null
        var current: GeckoWebEngine? = null
        private var ready = false
        private var loading = false
        private val waiting = mutableListOf<() -> Unit>()
        private val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun ensure(runtime: GeckoRuntime, then: () -> Unit) {
            if (ready) return then()
            waiting += then
            if (loading) return
            loading = true
            removeLegacy(runtime)
            runtime.webExtensionController.ensureBuiltIn(EXTENSION_URI, EXTENSION_ID).accept({ extension ->
                extension?.setMessageDelegate(delegate, "browser")
                done()
            }, { error ->
                Log.w(TAG, "Extension failed; loading without the shim", error)
                done()
            })
        }

        /**
         * Removes the shim installed under an earlier id. The profile keeps a built-in
         * extension after its id changes, so after the package rename both ran: the old one's
         * proxy filter asked a native app nobody answers for it, and every online request waited
         * on it forever (measured: pages never finished loading through the phone's proxy).
         */
        private fun removeLegacy(runtime: GeckoRuntime) {
            val controller = runtime.webExtensionController
            controller.list().accept({ extensions ->
                extensions.orEmpty()
                    .filter { it.id != EXTENSION_ID && it.id.startsWith(EXTENSION_PREFIX) }
                    .forEach { old ->
                        Log.i(TAG, "Removing the old shim ${old.id}")
                        controller.uninstall(old).accept({ Log.i(TAG, "Removed ${old.id}") }, { error ->
                            Log.w(TAG, "Couldn't uninstall ${old.id}; disabling it", error)
                            controller.disable(old, WebExtensionController.EnableSource.APP)
                        })
                    }
            }, { error -> Log.w(TAG, "Couldn't list the extensions", error) })
        }

        private fun done() {
            ready = true
            waiting.toList().forEach { it() }
            waiting.clear()
        }

        private val delegate = object : WebExtension.MessageDelegate {
            override fun onConnect(newPort: WebExtension.Port) {
                port = newPort
                newPort.setDelegate(object : WebExtension.PortDelegate {
                    override fun onPortMessage(message: Any, from: WebExtension.Port) {
                        main.post {
                            val engine = current
                            if (engine == null) Log.d(TAG, "Host message with no app in front: ${(message as? JSONObject)?.optString("type")}")
                            engine?.onHostMessage(message)
                        }
                    }

                    override fun onDisconnect(from: WebExtension.Port) {
                        if (port === from) port = null
                    }
                })
                // A page that loaded before the port existed never heard canGoBack.
                main.post { current?.let { it.post(JSONObject().put("type", "canGoBack").put("value", it.canGoBack)) } }
            }

            /** The extension's proxy question (background.js): PhoneInternet's answer. */
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val type = (message as? JSONObject)?.optString("type")
                if (type != "proxy") return null
                return GeckoResult.fromValue(PhoneInternet.proxy ?: "")
            }
        }
    }

    companion object {
        private const val TAG = "BandGecko"
        private const val EXTENSION_URI = "resource://android/assets/mrbd-ext/"
        private const val EXTENSION_ID = "mrbd-host@lumen.dev"
        /** Every id the shim has had starts with this (the earlier one: mrbd-host@airgestures.dev). */
        private const val EXTENSION_PREFIX = "mrbd-host@"
        private var runtime: GeckoRuntime? = null

        /**
         * One runtime per process (Gecko allows no more); its density fixes the 600 CSS px
         * viewport. Once it exists, removed apps' contexts are cleared right away, and those
         * removed before it are cleared now.
         */
        @Synchronized
        private fun runtime(context: Context, density: Float): GeckoRuntime = runtime ?: GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder()
                .displayDensityOverride(density)
                .consoleOutput(true)
                .remoteDebuggingEnabled(BuildConfigDebug.debuggable(context))
                .build(),
        ).also { created ->
            runtime = created
            val main = android.os.Handler(android.os.Looper.getMainLooper())
            val clear = { contextId: String ->
                main.post {
                    Log.d(TAG, "Clearing the data of context $contextId")
                    created.storageController.clearDataForSessionContext(contextId)
                }
                Unit
            }
            WebAppContexts.takePending(context).forEach(clear)
            WebAppContexts.clearer = clear
        }
    }
}

/** Debug builds let `adb forward` reach Gecko's remote debugger; release builds don't. */
internal object BuildConfigDebug {
    fun debuggable(context: Context) =
        context.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0
}
