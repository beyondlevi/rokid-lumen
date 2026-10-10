package dev.lumen.glasses

import java.net.URI
import java.util.Locale

/**
 * Web origins (scheme, host and port), for what a web app's page may do: the host bridge
 * (`MrbdHost`) acts only for a page on the app's own origin, and an offline app's page stays on
 * its origin. An offline app's origin is its loopback server (`http://127.0.0.1:<port>`), an
 * online app's the origin of its URL.
 */
object WebOrigin {
    /**
     * `scheme://host[:port]` of an http(s) [url], in lower case, without the scheme's default
     * port; "" when it has none (about:blank, data:, a malformed address).
     */
    @JvmStatic
    fun of(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        val schemeEnd = url.indexOf("://")
        if (schemeEnd <= 0) return ""
        val scheme = url.substring(0, schemeEnd).lowercase(Locale.ROOT)
        if (scheme != "http" && scheme != "https") return ""
        // The authority ends where browsers end it (a backslash counts as a slash in http(s)).
        val authority = url.substring(schemeEnd + 3).takeWhile { it != '/' && it != '\\' && it != '?' && it != '#' }
        val uri = runCatching { URI("$scheme://$authority") }.getOrNull() ?: return ""
        val host = uri.host?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() } ?: return ""
        val port = uri.port.takeIf { it >= 0 && it != defaultPort(scheme) }
        return "$scheme://$host" + (port?.let { ":$it" } ?: "")
    }

    /** Whether [url] is on [appOrigin]; never for an empty origin. */
    @JvmStatic
    fun matches(url: String?, appOrigin: String): Boolean = appOrigin.isNotEmpty() && of(url) == appOrigin

    /** The origin a web app's own pages have. */
    @JvmStatic
    fun ofApp(app: WebApp): String = of(app.url)

    /**
     * Where the page's top-level navigation may go: an offline app stays on its own origin, an
     * online one goes anywhere over HTTPS (not to the offline apps' loopback servers).
     */
    @JvmStatic
    fun navigationAllowed(url: String?, app: WebApp): Boolean = when {
        url == null -> false
        url == "about:blank" -> true
        app.offline -> matches(url, ofApp(app))
        else -> url.startsWith("https://", ignoreCase = true) && of(url).isNotEmpty()
    }

    /**
     * The one page message another site's page may send: it's ready. An online app's page on
     * another HTTPS site (a Google sign-in page in a YouTube app) then gets the band navigation's
     * state ([navigationPage]), so the band moves through it. Typing there needs nothing from the
     * bridge: Lumen's keyboard is the glasses' input method, on any page.
     */
    private const val READY = "hello"

    /**
     * Whether a page message of [type] from [sender] is heard while the session shows [pageUrl]:
     * any message from the app's own origin (a page with no origin yet doesn't rule it out), and
     * [READY] from the page an online app shows on another HTTPS site. The app's settings (which
     * may hold secrets), the microphone, speech, installing and Back stay with the app's origin.
     */
    @JvmStatic
    fun hears(type: String, sender: String?, pageUrl: String?, app: WebApp): Boolean {
        val appOrigin = ofApp(app)
        val page = of(pageUrl)
        if (matches(sender, appOrigin) && (page.isEmpty() || page == appOrigin)) return true
        return type == READY && navigationPage(pageUrl, app) && of(sender) == page
    }

    /**
     * Whether the page the session shows gets the band navigation's state (whether there's
     * history behind it, the navigation on): the app's own, or for an online app any HTTPS page
     * it went to.
     */
    @JvmStatic
    fun navigationPage(pageUrl: String?, app: WebApp): Boolean {
        val page = of(pageUrl)
        if (page.isEmpty() || page == ofApp(app)) return true
        return !app.offline && page.startsWith("https://")
    }

    private fun defaultPort(scheme: String) = if (scheme == "https") 443 else 80
}
