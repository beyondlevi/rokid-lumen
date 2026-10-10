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
 * a message from any other origin is ignored, except the page an online app shows on another
 * site (a sign-in page) saying it's ready: it gets the band navigation's state, nothing else.
 * What goes to the page goes to its tab only. Typing needs no bridge: Gecko's keyboard requests
 * go to the glasses' input method ([LumenKeyboard]), on any page.
 *
 * A packaged online app's site scripts ([SiteScripts]) are registered in the extension while
 * the app is in front ([HostLink.want]); its page waits for them before loading (at most
 * [SCRIPTS_WAIT_MS]), so they run at the start of its first page too.
 */
class GeckoWebEngine(
    activity: Activity,
    side: Int,
    private val host: WebEngine.Host,
    private val app: WebApp,
) : WebEngine {
    private val geckoView = GeckoView(activity)
    private val session = GeckoSession(
        GeckoSessionSettings.Builder().contextId(WebAppContexts.idFor(app.contextKey)).build(),
    )
    private val appOrigin = WebOrigin.ofApp(app)
    private var canGoBack = false
    /** The page's address as Gecko last reported it (for the origin checks). */
    private var pageUrl: String? = null
    /** The extension's id for this session's tab, learned from its page's first message. */
    private var tabId: Int? = null
    private var extensionReady = false
    private var pendingUrl: String? = null
    /** The address last loaded ([load]), for a reload when the page's process is gone. */
    private var loadedUrl: String? = null
    /** The session's history and scroll as Gecko last saved them, to restore after a kill. */
    private var savedState: GeckoSession.SessionState? = null
    /** The page's content process died (killed or crashed): load it again when shown. */
    private var pageLost = false
    /**
     * When the page's process died while it was shown: on the glasses that's lmkd, and a page
     * that runs them out of memory (Instagram's Reels, measured) dies again right after each
     * reload. The second death within [LOSS_WINDOW_MS] holds the page until [retry].
     */
    private val shownLosses = ArrayDeque<Long>()
    private var isHeld = false
    private var lastLossAt = -SAME_EVENT_MS
    private var visible = false
    private val geckoRuntime = runtime(activity, side.toFloat() / WebEngine.MRBD_VIEWPORT)
    private val startedAt = SystemClock.elapsedRealtime()
    /** The site scripts registered while this app is in front; none for most apps. */
    private val siteScripts = SiteScripts.registrationFor(activity, app)
    private var destroyed = false

    override val view: View get() = geckoView

    init {
        val runtime = geckoRuntime
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
                post(JSONObject().put("type", "canGoBack").put("value", value), navigation = true)
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

            override fun onSessionStateChange(s: GeckoSession, state: GeckoSession.SessionState) {
                savedState = state
            }
        }
        session.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onFirstContentfulPaint(s: GeckoSession) {
                Log.d(TAG, "First contentful paint after ${SystemClock.elapsedRealtime() - startedAt} ms")
            }

            // Without a page the view stays black: load it again (now if it's on screen).
            override fun onKill(s: GeckoSession) = pageGone("killed")
            override fun onCrash(s: GeckoSession) = pageGone("crashed")
        }
        // Gecko's own text input delegate stays: a focused field's keyboard request (measured: also
        // a programmatic focus up to ~5 s after a tap) reaches the input method, Lumen's keyboard,
        // which then gets the field's real EditorInfo (intercepted, the field stayed inputType 0
        // for it and deleting did nothing, measured on the glasses).
        // A TextureView, not the default SurfaceView: Gecko's surface otherwise covers the
        // notices drawn over it (measured: the old composer opened, invisible).
        geckoView.setViewBackend(GeckoView.BACKEND_TEXTURE_VIEW)
        session.open(runtime)
        geckoView.setSession(session)
        // Gecko paints white until the page's first frame: on the glasses' additive display
        // that's a full-screen flash. Black is transparent there.
        geckoView.coverUntilFirstPaint(android.graphics.Color.BLACK)
        geckoView.isFocusable = true
        geckoView.isFocusableInTouchMode = true

        HostLink.current = this
        HostLink.want(siteScripts)
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
        afterScripts {
            loadedUrl = url
            session.loadUri(url)
            focus()
        }
    }

    /**
     * Runs [action] once this app's site scripts are in place in the extension (right away for
     * an app without any), so the page's first document gets them; after [SCRIPTS_WAIT_MS]
     * without the extension's word, anyway.
     */
    private fun afterScripts(action: () -> Unit) {
        if (siteScripts.isEmpty) return action()
        HostLink.whenRegistered(siteScripts.key) { if (!destroyed) action() }
    }

    private fun pageGone(how: String) {
        val now = SystemClock.elapsedRealtime()
        // One shortage can take a page and its other-site frames' processes together: Gecko then
        // reports each (measured: two kills 20 ms apart). They count as one.
        // Hidden apps' pages go first: they hold memory the page in front needs (WebAppActivity).
        if (visible) host.onPageLost()
        if (visible && now - lastLossAt < SAME_EVENT_MS) {
            Log.w(TAG, "The page's process was $how (the same shortage)")
            pageLost = true
            if (!isHeld) reloadLost()
            return
        }
        if (visible) lastLossAt = now
        if (visible) {
            shownLosses.addLast(now)
            while (now - shownLosses.first() > LOSS_WINDOW_MS) shownLosses.removeFirst()
        }
        pageLost = true
        isHeld = shownLosses.size >= LOSSES_TO_HOLD
        Log.w(TAG, "The page's process was $how (" + when {
            isHeld -> "again within ${LOSS_WINDOW_MS / 1000} s: held"
            visible -> "reloading"
            else -> "reload when shown"
        } + ")")
        if (isHeld) host.onPageHeld() else if (visible) reloadLost()
    }

    override val held: Boolean get() = isHeld

    override fun retry() {
        if (!isHeld) return
        isHeld = false
        shownLosses.clear()
        if (visible) reloadLost()
    }

    /**
     * GeckoView closes a session whose process died: open it again, attach it to the view and
     * restore its history (the page reloads; what it kept only in memory is gone).
     */
    private fun reloadLost() {
        if (!pageLost) return
        pageLost = false
        geckoView.releaseSession()
        session.open(geckoRuntime)
        geckoView.setSession(session)
        geckoView.coverUntilFirstPaint(android.graphics.Color.BLACK)
        session.setActive(true)
        val state = savedState
        if (state != null) session.restoreState(state) else (pageUrl ?: loadedUrl)?.let { session.loadUri(it) }
        Log.d(TAG, "Page reopened (${if (state != null) "history restored" else "loaded again"})")
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

    override fun speechEvent(id: String, type: String, code: String?) {
        post(JSONObject().put("type", "speech").put("id", id.toIntOrNull() ?: 0).put("event", type).put("code", code ?: JSONObject.NULL))
    }

    // To this session's tab only; content.js also drops a message whose origin isn't its page's.
    override fun configResult(id: Int, values: JSONObject, origin: String) {
        post(JSONObject().put("type", "config").put("id", id).put("values", values).put("origin", origin))
    }

    override fun audioEvent(event: JSONObject, origin: String) {
        post(JSONObject().put("type", "audio").put("event", event).put("origin", origin))
    }

    override fun configChanged(values: JSONObject, origin: String) {
        post(JSONObject().put("type", "configChanged").put("values", values).put("origin", origin))
    }

    override fun onResume() {
        HostLink.current = this
        HostLink.want(siteScripts)
        MemoryWatch.start()
        visible = true
        session.setActive(true)
        // A page lost while hidden loads again once this app's scripts are back in place.
        if (!isHeld && pageLost) afterScripts { if (!isHeld) reloadLost() }
        focus()
    }

    override fun onPause() {
        session.setFocused(false)
    }

    // An inactive session is hidden to the page (visibilitychange): Gecko stops its frames, and
    // on Android suspends it. Measured before, with the session left active: the main thread on
    // Gecko's vsync (~6% of a core, ~12% with a CSS animation) and, for an animated page, the GPU
    // process at ~23%, all with the display off. onResume makes it active again.
    override fun onHidden() {
        MemoryWatch.stop()
        visible = false
        session.setActive(false)
    }

    override fun destroy() {
        destroyed = true
        if (HostLink.current === this) MemoryWatch.stop()
        if (HostLink.current === this) {
            HostLink.current = null
            // No app in front: no site's pages change (a hidden app's included).
            HostLink.want(SiteScripts.Registration.NONE)
        }
        session.close()
    }

    /**
     * A page's message, relayed by background.js with its sender's `tabId` and `sender` (the
     * page's address, set there, not by the page). The app's own origin is heard, and another
     * site's page an online app shows saying it's ready ([WebOrigin.hears]).
     */
    private fun onHostMessage(message: Any) {
        val json = message as? JSONObject ?: return
        val type = json.optString("type")
        val sender = json.optString("sender")
        if (!WebOrigin.hears(type, sender, pageUrl, app)) {
            Log.w(TAG, "Page message $type ignored from ${WebOrigin.of(sender).ifEmpty { "a page off any origin" }} (the app is $appOrigin)")
            return
        }
        json.optInt("tabId", -1).takeIf { it >= 0 }?.let { tabId = it }
        when (type) {
            // A page's content script is ready: it hasn't heard canGoBack yet.
            "hello" -> {
                Log.d(TAG, "Page ready on ${WebOrigin.of(sender).ifEmpty { "no origin" }}")
                post(JSONObject().put("type", "canGoBack").put("value", canGoBack), navigation = true)
                // A site made for a mouse gets the shim's band navigation; an MRBD app has its own.
                post(JSONObject().put("type", "bandNavigation").put("value", !app.offline), navigation = true)
            }
            "backResult" -> if (!json.optBoolean("handled")) host.onBackUnhandled()
            "install" -> host.onInstall(json.optString("url"), json.optString("name"))
            "speak" -> host.onSpeak(
                json.optInt("id"), json.optString("text"), json.optString("lang"),
                json.optDouble("rate", 1.0).toFloat(), json.optDouble("pitch", 1.0).toFloat(),
            )
            "cancelSpeech" -> host.onCancelSpeech()
            "getConfig" -> host.onGetConfig(json.optInt("id"), sender)
            "audio" -> json.optJSONObject("message")?.let { host.onAudio(it, sender) }
            else -> Log.d(TAG, "Host message $type")
        }
    }

    /**
     * Whether this session is on the app's origin. A location without an origin (none yet, or
     * the about:blank Gecko reports before the first page) doesn't rule the page out: the
     * message's sender, checked too, says where it comes from.
     */
    private fun onAppPage(): Boolean = WebOrigin.of(pageUrl).let { it.isEmpty() || it == appOrigin }

    /**
     * To this session's page, when it's the app's own and has spoken (so its tab is known). With
     * [navigation], also to another site's page an online app shows ([WebOrigin.navigationPage]):
     * the band navigation's state (whether there's history behind, the navigation on).
     */
    private fun post(message: JSONObject, navigation: Boolean = false): Boolean {
        val current = HostLink.port ?: return false
        val tab = tabId ?: return false
        if (if (navigation) !WebOrigin.navigationPage(pageUrl, app) else !onAppPage()) return false
        current.postMessage(message.put("tabId", tab))
        return true
    }

    /**
     * The built-in extension and its one native port, shared by every engine of the process: the
     * extension's background keeps the port it opened first, so a delegate per engine left the
     * second app's page talking to the first app's closed screen (measured: the old composer
     * opened there, unseen, and the focused field got no answer). The page's messages go to the engine
     * in front ([current]), which hears only its own origin's.
     *
     * It also keeps the extension's site scripts those of the app in front ([want]): the set goes
     * to background.js (`{type: "siteScripts", key, scripts}`, no tab) when the app in front
     * changes and on every new port, and comes back acknowledged (`siteScriptsReady`, no tab);
     * a page load waits for its app's key ([whenRegistered]).
     */
    private object HostLink {
        var port: WebExtension.Port? = null
        var current: GeckoWebEngine? = null
        private var ready = false
        private var loading = false
        private val waiting = mutableListOf<() -> Unit>()
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        /** The site scripts the extension should have: the app in front's. */
        private var wanted = SiteScripts.Registration.NONE
        /** The set last sent on [port]; null on a new port, which gets [wanted] again. */
        private var sentKey: String? = null
        /** The set the extension last said is in place. */
        private var readyKey: String? = null
        private class Waiter(val key: String, val then: () -> Unit) {
            lateinit var timeout: Runnable
        }
        private val scriptWaiters = mutableListOf<Waiter>()

        /** Makes [registration] the extension's site scripts (replacing the previous app's). Main thread. */
        fun want(registration: SiteScripts.Registration) {
            wanted = registration
            sendScripts()
        }

        private fun sendScripts() {
            val target = port ?: return
            if (sentKey == wanted.key) return
            sentKey = wanted.key
            Log.d(TAG, if (wanted.isEmpty) "Site scripts cleared" else "Site scripts ${wanted.key} sent (${wanted.scripts.size})")
            target.postMessage(wanted.toMessage())
        }

        /** Runs [then] when the extension confirms the set [key], or after [SCRIPTS_WAIT_MS]. Main thread. */
        fun whenRegistered(key: String, then: () -> Unit) {
            if (readyKey == key) return then()
            val waiter = Waiter(key, then)
            waiter.timeout = Runnable {
                if (scriptWaiters.remove(waiter)) {
                    Log.w(TAG, "Site scripts $key not confirmed within $SCRIPTS_WAIT_MS ms: loading anyway")
                    then()
                }
            }
            scriptWaiters += waiter
            main.postDelayed(waiter.timeout, SCRIPTS_WAIT_MS)
        }

        /** A message from background.js itself, not from a page. */
        private fun onExtensionMessage(json: JSONObject) {
            when (val type = json.optString("type")) {
                "siteScriptsReady" -> {
                    val key = json.optString("key")
                    if (json.optBoolean("ok")) Log.d(TAG, "Site scripts ${key.ifEmpty { "(none)" }} in place")
                    else Log.w(TAG, "Site scripts $key: ${json.optString("error")}")
                    readyKey = key
                    scriptWaiters.filter { it.key == key }.forEach { waiter ->
                        scriptWaiters.remove(waiter)
                        main.removeCallbacks(waiter.timeout)
                        waiter.then()
                    }
                }
                else -> Log.d(TAG, "Extension message $type")
            }
        }

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
                            // Page messages always carry their tab (background.js adds it).
                            if (message is JSONObject && !message.has("tabId")) return@post onExtensionMessage(message)
                            val engine = current
                            if (engine == null) Log.d(TAG, "Host message with no app in front: ${(message as? JSONObject)?.optString("type")}")
                            engine?.onHostMessage(message)
                        }
                    }

                    override fun onDisconnect(from: WebExtension.Port) {
                        // The next port gets the site scripts again (onConnect).
                        if (port === from) port = null
                    }
                })
                main.post {
                    // A new port may be a new background (no scripts registered): the set goes
                    // again, and only its answer says what's in place. Sent first, so background.js
                    // knows the port is taken.
                    sentKey = null
                    readyKey = null
                    sendScripts()
                    // A page that loaded before the port existed never heard canGoBack.
                    current?.let { it.post(JSONObject().put("type", "canGoBack").put("value", it.canGoBack)) }
                }
            }

            /** The extension's proxy question (background.js): PhoneInternet's answer. */
            override fun onMessage(nativeApp: String, message: Any, sender: WebExtension.MessageSender): GeckoResult<Any>? {
                val type = (message as? JSONObject)?.optString("type")
                if (type != "proxy") return null
                return GeckoResult.fromValue(PhoneInternet.proxy ?: "")
            }
        }
    }

    /**
     * Gecko frees memory on its "memory-pressure" notification (a shrinking GC in every page,
     * image and font caches dropped), which GeckoView sends only when Android trims memory: on
     * the glasses that came after lmkd had already killed the page (measured: trim level 15 a
     * second after Instagram's Reels page was gone). While a page is shown this reads the free
     * memory every [EVERY_MS] and sends it below [LOW_KB], at most every [AGAIN_MS].
     */
    private object MemoryWatch {
        private const val EVERY_MS = 2_000L
        private const val AGAIN_MS = 10_000L
        /** Above lmkd's kills on the RG glasses (MemAvailable ~200 MB when it took the page). */
        private const val LOW_KB = 350L * 1024
        private val main = android.os.Handler(android.os.Looper.getMainLooper())
        private var running = false
        private var sentAt = 0L
        private val check = object : Runnable {
            override fun run() {
                if (!running) return
                val available = availableKb()
                val now = SystemClock.elapsedRealtime()
                if (available in 1 until LOW_KB && now - sentAt >= AGAIN_MS) {
                    sentAt = now
                    runCatching { org.mozilla.gecko.GeckoAppShell.notifyObservers("memory-pressure", "low-memory") }
                        .onSuccess { Log.i(TAG, "Memory low (${available / 1024} MB free): asked Gecko to free memory") }
                        .onFailure { Log.w(TAG, "Couldn't ask Gecko to free memory", it) }
                }
                main.postDelayed(this, EVERY_MS)
            }
        }

        fun start() {
            if (running) return
            running = true
            main.postDelayed(check, EVERY_MS)
        }

        fun stop() {
            running = false
            main.removeCallbacks(check)
        }

        /** MemAvailable from /proc/meminfo, in kB; 0 when it can't be read. */
        private fun availableKb(): Long = runCatching {
            java.io.File("/proc/meminfo").useLines { lines ->
                lines.firstOrNull { it.startsWith("MemAvailable:") }?.split(Regex("\\s+"))?.getOrNull(1)?.toLong()
            } ?: 0L
        }.getOrDefault(0L)
    }

    companion object {
        private const val TAG = "BandGecko"
        /** The longest a page load waits for its app's site scripts to be in place. */
        private const val SCRIPTS_WAIT_MS = 3_000L
        private const val EXTENSION_URI = "resource://android/assets/mrbd-ext/"
        private const val EXTENSION_ID = "mrbd-host@lumen.dev"
        /** Two deaths of a shown page within this hold it ([held]). */
        private const val LOSS_WINDOW_MS = 120_000L
        private const val LOSSES_TO_HOLD = 2
        /** Deaths reported this close to the previous one are the same shortage. */
        private const val SAME_EVENT_MS = 5_000L
        /** Every id the shim has had starts with this (the earlier one: mrbd-host@airgestures.dev). */
        private const val EXTENSION_PREFIX = "mrbd-host@"
        private var runtime: GeckoRuntime? = null

        /**
         * Gecko preferences for a battery-powered HUD, read when the runtime starts (from an explicit
         * path Gecko reads in release builds too). They apply to every app:
         * - `layout.frame_rate`: animations, scrolling and requestAnimationFrame at 30 fps, not 60;
         *   a still page draws nothing either way.
         * - `image.animation_mode`: an animated GIF plays once instead of looping forever.
         * - no speculative connections, link prefetch or DNS prefetch over the phone's hotspot.
         * - no Safe Browsing list updates.
         * - no process priority manager: it lowers an inactive session's content process to idle,
         *   and Android killed it within seconds on these 1.8 GB glasses (measured; the page then
         *   loads again). The process stays at the app's priority, suspended while hidden.
         */
        private val PREFS = linkedMapOf<String, Any>(
            "layout.frame_rate" to 30,
            "image.animation_mode" to "once",
            "network.prefetch-next" to false,
            "network.dns.disablePrefetch" to true,
            "network.http.speculative-parallel-limit" to 0,
            "browser.safebrowsing.malware.enabled" to false,
            "browser.safebrowsing.phishing.enabled" to false,
            "dom.ipc.processPriorityManager.enabled" to false,
            // Memory (RG glasses, 1.8 GB, measured 2026-10-09): scrolling Instagram's Reels ran
            // the glasses out of memory, and lmkd killed the page and then Lumen's own process
            // (all at oom_score_adj 0), so the whole app went down. What Gecko keeps for later:
            // no spare content process waiting for the next page (~130 MB with its swap),
            // no pages kept alive for Back, smaller caches for decoded images and the network,
            // and a cap on what a streamed video or audio track keeps buffered.
            "dom.ipc.processPrelaunch.enabled" to false,
            "browser.sessionhistory.max_total_viewers" to 0,
            "browser.cache.memory.capacity" to 8192,
            "image.mem.surfacecache.max_size_kb" to 65536,
            "media.mediasource.eviction_threshold.video" to 25 * 1024 * 1024,
            "media.mediasource.eviction_threshold.audio" to 3 * 1024 * 1024,
            // Gecko builds its accessibility trees because Android reports an accessibility
            // service on (Lumen's own, for the band), but nothing reads a page through them:
            // the band reaches pages as keys. They cost memory on a big page, and tearing one
            // down when a page's process died crashed the parent process (measured: SIGSEGV in
            // a11y::SessionAccessibility::GetInstanceFor from DocAccessibleParent::Destroy).
            "accessibility.force_disabled" to 1,
        )

        /** Writes [PREFS] as GeckoView's config file (YAML, `prefs:`) and returns its path. */
        private fun preferencesFile(context: Context): String {
            val yaml = buildString {
                appendLine("prefs:")
                PREFS.forEach { (key, value) ->
                    appendLine("  $key: ${if (value is String) "\"$value\"" else value}")
                }
            }
            val file = java.io.File(context.filesDir, "geckoview-config.yaml")
            if (!file.exists() || file.readText() != yaml) file.writeText(yaml)
            return file.absolutePath
        }

        /**
         * One runtime per process (Gecko allows no more); its density fixes the 600 CSS px
         * viewport. Once it exists, removed apps' contexts are cleared right away, and those
         * removed before it are cleared now.
         */
        /**
         * Gecko binds its child processes (pages, GPU, media) with BIND_IMPORTANT while in front,
         * which gives them the app's own oom_score_adj (0). Out of memory, lmkd then took the page
         * and Lumen's process together (measured, Instagram's Reels): the whole app went down.
         * Bound without it, a child stays at VISIBLE_APP_ADJ (100) while Lumen is in front, so
         * lmkd takes the page first and Lumen reloads it (or holds it, [held]). Nothing changes
         * in the background, where the client's own adj is already higher. GeckoView has no API
         * for this: its PriorityLevel.FOREGROUND flag is set before any child process starts.
         */
        private fun bindChildrenBelowTheApp() {
            runCatching {
                val level = Class.forName("org.mozilla.gecko.process.ServiceAllocator\$PriorityLevel")
                val foreground = level.getField("FOREGROUND").get(null)
                level.getDeclaredField("mAndroidFlag").apply { isAccessible = true }.setInt(foreground, 0)
            }.onSuccess { Log.d(TAG, "Child processes bound below the app") }
                .onFailure { Log.w(TAG, "Couldn't bind the child processes below the app", it) }
        }

        @Synchronized
        private fun runtime(context: Context, density: Float): GeckoRuntime = runtime ?: run {
            bindChildrenBelowTheApp()
            createRuntime(context, density)
        }

        private fun createRuntime(context: Context, density: Float): GeckoRuntime = GeckoRuntime.create(
            context.applicationContext,
            GeckoRuntimeSettings.Builder()
                .configFilePath(preferencesFile(context))
                .displayDensityOverride(density)
                // The HUD is additive: black is see-through and a white page washes out the view.
                // Sites with a dark theme (YouTube, Instagram) take it from prefers-color-scheme.
                .preferredColorScheme(GeckoRuntimeSettings.COLOR_SCHEME_DARK)
                // A page's console.* goes to logcat in debug builds only.
                .consoleOutput(BuildConfigDebug.debuggable(context))
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
