/*
 * The sign-in follows kinesis (https://github.com/callbacked/kinesis), Copyright (c) 2026
 * callbacked, MIT License (LICENSE-kinesis): Sources/Kinesis/MetaLoginView.swift.
 */
package dev.lumen.companion.meta

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Gravity
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import dev.lumen.companion.R

/**
 * Meta's own sign-in page in a clean WebView (no cookies kept, nothing cached): email,
 * password and two-factor stay on Meta's page, the app never sees them. The page ends with a
 * `fb-viewapp://…frl_login?token&blob` redirect, which is checked against the tokens query and
 * exchanged for the account session ([MetaAuth]). The session is handed to [onSession] in
 * memory; the activity finishes with RESULT_OK.
 */
class MetaLoginActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var web: WebView
    private lateinit var status: TextView
    private var tokens: MetaAuth.SsoTokens? = null
    private var exchanging = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CookieManager.getInstance().removeAllCookies(null)
        web = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            settings.setSupportMultipleWindows(false)
            webViewClient = client
        }
        status = TextView(this).apply {
            text = getString(R.string.meta_login_loading)
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(0xFF000000.toInt())
            addView(web)
            addView(status, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        })
        Thread({
            val result = runCatching { MetaAuth.tokensQuery() }
            main.post {
                result.onSuccess {
                    tokens = it
                    status.visibility = TextView.GONE
                    web.loadUrl(it.authEntryUrl)
                }.onFailure { fail(it) }
            }
        }, "meta-tokens").start()
    }

    private val client = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val url = request.url
            val text = url.toString()
            // The sign-in ends with a custom-scheme redirect carrying the token and the blob.
            if (url.scheme.equals("fb-viewapp", ignoreCase = true) || text.contains("frl_login")) {
                onCallback(url)
                return true
            }
            return !(url.scheme.equals("https", ignoreCase = true) || text == "about:blank")
        }
    }

    private fun onCallback(url: Uri) {
        val tokens = tokens ?: return
        if (exchanging) return
        val blob = url.getQueryParameter("blob")
        if (blob.isNullOrEmpty() || !MetaAuth.callbackMatches(url.getQueryParameter("token"), tokens.nativeSsoToken)) {
            fail(MetaException(getString(R.string.meta_login_unconfirmed)))
            return
        }
        exchanging = true
        status.text = getString(R.string.meta_login_exchanging)
        status.visibility = TextView.VISIBLE
        web.visibility = WebView.INVISIBLE
        Thread({
            val result = runCatching { MetaAuth.login(MetaAuth.decryptBlob(blob, tokens.nativeSsoToken)) }
            main.post {
                result.onSuccess {
                    onSession?.invoke(it)
                    onSession = null
                    setResult(RESULT_OK)
                    finish()
                }.onFailure { fail(it) }
            }
        }, "meta-login").start()
    }

    private fun fail(error: Throwable) {
        Log.w(TAG, "sign-in: ${error.message}")
        lastError = error.message
        setResult(RESULT_CANCELED)
        finish()
    }

    override fun onDestroy() {
        web.destroy()
        CookieManager.getInstance().removeAllCookies(null)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "NbMeta"

        /** Who gets the session (in memory only); set before starting the activity. */
        @Volatile var onSession: ((MetaSession) -> Unit)? = null

        /** Why the last sign-in failed, for the screen. */
        @Volatile var lastError: String? = null
    }
}
