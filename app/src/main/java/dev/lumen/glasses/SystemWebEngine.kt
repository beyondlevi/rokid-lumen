package dev.lumen.glasses

import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * The system WebView (Chromium 95 on the Rokid, not updatable). It's laid out at 600 CSS px
 * (900 physical px at the glasses' density) and scaled down to the HUD's square: sized to the
 * HUD instead, a device-width page saw 320x320 (measured). The shim reaches the app through
 * `addJavascriptInterface` as `MrbdHost`.
 *
 * Isolation between apps is weaker here than on GeckoView: WebView keeps one cookie jar for the
 * whole process (`CookieManager`), shared by every app on this engine, and has no per-app
 * contexts to clear when an app is removed. Storage (localStorage, IndexedDB) is per origin, so
 * offline apps (a loopback port each, never reused) keep theirs apart. An app that needs its
 * cookies kept from the others should run on GeckoView ([WebAppContexts]).
 *
 * The bridge acts only while the WebView shows a page on the app's origin ([WebOrigin]): the
 * checks read the main frame's address, since `addJavascriptInterface` can't tell which frame
 * called (a cross-origin iframe inside the app's own page still reaches `MrbdHost`; GeckoView's
 * bridge, top frame only, doesn't have that gap).
 */
class SystemWebEngine(
    private val activity: Activity,
    private val shim: String,
    private val side: Int,
    private val host: WebEngine.Host,
    private val app: WebApp,
) : WebEngine {
    private val main = Handler(Looper.getMainLooper())
    private val web = WebView(activity)
    private val appOrigin = WebOrigin.ofApp(app)
    @Volatile private var canGoBack = false
    private val documentStart = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    override val view: View = FrameLayout(activity).apply {
        val layoutSide = Math.round(WebEngine.MRBD_VIEWPORT * activity.resources.displayMetrics.density)
        val scale = side.toFloat() / layoutSide
        addView(web, FrameLayout.LayoutParams(layoutSide, layoutSide))
        web.pivotX = 0f
        web.pivotY = 0f
        web.scaleX = scale
        web.scaleY = scale
        clipChildren = true
    }

    init {
        configure()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun configure() {
        web.setBackgroundColor(Color.BLACK)
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            setSupportZoom(false)
            builtInZoomControls = false
            textZoom = 100
            // A width=600 viewport tag and device-width both come out at 600 CSS px.
            useWideViewPort = true
            loadWithOverviewMode = false
        }
        web.addJavascriptInterface(Bridge(), "MrbdHost")
        if (documentStart) WebViewCompat.addDocumentStartJavaScript(web, shim, setOf("*"))
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (!request.isForMainFrame) return !isAllowed(url)
                if (WebOrigin.navigationAllowed(url, app)) return false
                Log.w(TAG, "Navigation of ${app.name} to ${WebOrigin.of(url).ifEmpty { url.substringBefore(':') }} blocked")
                // An offline app never leaves its own origin: the address opens nowhere.
                if (app.offline) Toast.makeText(activity, R.string.webapp_navigation_blocked, Toast.LENGTH_SHORT).show()
                return true
            }

            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                // Chromium 95 has no document-start scripts: this is as early as it gets.
                if (!documentStart) view.evaluateJavascript(shim, null)
            }

            override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                canGoBack = view.canGoBack()
            }

            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                if (request.isForMainFrame) host.onLoadFailed(error.description?.toString().orEmpty())
            }

            /** Google Fonts → the bundled Noto Sans, so online apps get their font with no network too. */
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                val uri = request.url
                return when (uri.host) {
                    "fonts.googleapis.com" -> {
                        val path = uri.path.orEmpty()
                        if (path.startsWith("/css")) asset("fonts/fonts.css", "text/css")
                        else if (path.startsWith("/fonts/")) asset("fonts/" + path.substringAfterLast('/'), "font/woff2")
                        else null
                    }
                    else -> null
                }
            }
        }
        web.webChromeClient = object : WebChromeClient() {
            override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                Log.d(TAG, "console ${message.messageLevel()}: ${message.message()} (${message.sourceId()}:${message.lineNumber()})")
                return true
            }
        }
    }

    private fun asset(name: String, type: String): WebResourceResponse? = runCatching {
        WebResourceResponse(type, "utf-8", activity.assets.open(name)).apply {
            responseHeaders = mapOf("Access-Control-Allow-Origin" to "*", "Cache-Control" to "max-age=31536000")
        }
    }.getOrNull()

    override fun load(url: String) {
        web.loadUrl(url)
        web.requestFocus()
    }

    override fun key(keyCode: Int) {
        web.requestFocus()
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        web.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
    }

    override fun back() {
        // A page off the app's origin gets no say in Back (its answer would be ignored).
        if (!onAppPage()) return host.onBackUnhandled()
        web.evaluateJavascript("window.__mrbdBack ? (window.__mrbdBack(), 'ok') : 'missing'") { result ->
            if (result != "\"ok\"") host.onBackUnhandled()
        }
    }

    override fun historyBack(): Boolean {
        if (!web.canGoBack()) return false
        web.goBack()
        return true
    }

    override fun composerInput(text: String) =
        evaluateOnAppPage("window.__mrbdComposerInput && window.__mrbdComposerInput(${JSONObject.quote(text)})")

    override fun composerClose() =
        evaluateOnAppPage("window.__mrbdComposerClose && window.__mrbdComposerClose()")

    /** Read by the shim (from a binder thread) when Enter lands on a field. */
    @Volatile private var phoneKeyboard = false

    override fun keyboardState(open: Boolean) {
        phoneKeyboard = open
    }

    override fun keyboardInput(text: String) =
        evaluateOnAppPage("window.__mrbdKeyboardInput && window.__mrbdKeyboardInput(${JSONObject.quote(text)})")

    override fun keyboardSync() =
        evaluateOnAppPage("window.__mrbdKeyboardSync && window.__mrbdKeyboardSync()")

    override fun speechEvent(id: String, type: String, code: String?) {
        val codeJs = if (code == null) "null" else JSONObject.quote(code)
        evaluateOnAppPage("window.__mrbdSpeech && window.__mrbdSpeech(${id.toIntOrNull() ?: 0}, ${JSONObject.quote(type)}, $codeJs)")
    }

    // One page per WebView: only while it's on the app's origin (the values may be secrets).
    override fun configResult(id: Int, values: JSONObject, origin: String) =
        evaluateOnAppPage("window.__lumenConfig && window.__lumenConfig($id, $values)")

    override fun configChanged(values: JSONObject, origin: String) =
        evaluateOnAppPage("window.__lumenConfigChanged && window.__lumenConfigChanged($values)")

    override fun audioEvent(event: JSONObject, origin: String) =
        evaluateOnAppPage("window.__lumenAudio && window.__lumenAudio($event)")

    /** Whether the page in front is the app's own. Main thread (WebView's rule for [WebView.getUrl]). */
    private fun onAppPage(): Boolean = WebOrigin.matches(web.url, appOrigin)

    private fun evaluateOnAppPage(script: String) {
        if (onAppPage()) web.evaluateJavascript(script, null)
    }

    /** A bridge call, run on the main thread only for a page on the app's origin; ignored otherwise. */
    private fun fromAppPage(call: String, action: () -> Unit) {
        main.post {
            if (onAppPage()) {
                action()
            } else {
                Log.w(TAG, "MrbdHost.$call ignored from ${WebOrigin.of(web.url).ifEmpty { "a page off any origin" }} (the app is $appOrigin)")
            }
        }
    }

    override fun onResume() = web.onResume()
    override fun onPause() = web.onPause()
    override fun destroy() = web.destroy()

    /**
     * What mrbd-shim.js calls. Runs on a WebView binder thread; every call but [canGoBack] waits
     * for the main thread, where the page's address is checked against the app's origin.
     */
    private inner class Bridge {
        @JavascriptInterface
        fun canGoBack(): Boolean = canGoBack

        @JavascriptInterface
        fun install(url: String?, name: String?) = fromAppPage("install") {
            host.onInstall(url.orEmpty(), name.orEmpty())
        }

        @JavascriptInterface
        fun speak(id: Int, text: String?, lang: String?, rate: Float, pitch: Float) = fromAppPage("speak") {
            host.onSpeak(id, text.orEmpty(), lang.orEmpty(), rate, pitch)
        }

        @JavascriptInterface
        fun cancelSpeech() = fromAppPage("cancelSpeech") { host.onCancelSpeech() }

        @JavascriptInterface
        fun openComposer(value: String?, multiline: Boolean) = fromAppPage("openComposer") {
            host.onOpenComposer(value.orEmpty(), multiline)
        }

        @JavascriptInterface
        fun phoneKeyboard(): Boolean = phoneKeyboard

        @JavascriptInterface
        fun textFocus(value: String?, type: String?, multiline: Boolean, label: String?, reason: String?) = fromAppPage("textFocus") {
            host.onTextFocus(value.orEmpty(), type.orEmpty(), multiline, label.orEmpty(), reason.orEmpty())
        }

        @JavascriptInterface
        fun textBlur() = fromAppPage("textBlur") { host.onTextBlur() }

        @JavascriptInterface
        fun getConfig(id: Int) = fromAppPage("getConfig") { host.onGetConfig(id, web.url) }

        /** A `window.lumen.audio` request, as JSON (a transcription carries its audio in base64). */
        @JavascriptInterface
        fun audio(message: String?) = fromAppPage("audio") {
            runCatching { JSONObject(message.orEmpty()) }.getOrNull()?.let { host.onAudio(it, web.url) }
        }

        @JavascriptInterface
        fun backResult(handled: Boolean) = fromAppPage("backResult") {
            if (!handled) host.onBackUnhandled()
        }
    }

    companion object {
        private const val TAG = "BandWebView"

        /** HTTPS anywhere, or this app's own loopback servers (offline apps). */
        @JvmStatic
        fun isAllowed(url: String): Boolean =
            url.startsWith("https://") || url.startsWith("http://${LocalAppServer.HOST}:")

        /**
         * Points every WebView of the app at [proxy] (`host:port`), or back to direct when null,
         * then runs [then]. The offline apps' loopback servers always go direct.
         */
        @JvmStatic
        fun useProxy(proxy: String?, then: Runnable) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                Log.w(TAG, "WebView without PROXY_OVERRIDE; loading direct")
                then.run()
                return
            }
            val controller = ProxyController.getInstance()
            val executor = Executor { Handler(Looper.getMainLooper()).post(it) }
            if (proxy == null) {
                controller.clearProxyOverride(executor, then)
                return
            }
            val config = ProxyConfig.Builder()
                .addProxyRule(proxy)
                .addBypassRule(LocalAppServer.HOST)
                .addBypassRule("localhost")
                .build()
            controller.setProxyOverride(config, executor, then)
        }
    }
}
