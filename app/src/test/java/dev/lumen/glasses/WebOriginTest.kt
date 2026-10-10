package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebOriginTest {
    private val offline = WebApp("pkg-a", "A", true, "", 47_100, WebEngineKind.GECKO, null, "")
    private val online = WebApp("b", "B", false, "https://app.example/start?x=1", 0, WebEngineKind.GECKO, null, "")

    @Test
    fun originsAreSchemeHostAndPort() {
        assertEquals("http://127.0.0.1:47100", WebOrigin.of("http://127.0.0.1:47100/a/b?c=1#d"))
        assertEquals("https://app.example", WebOrigin.of("https://App.Example:443/x"))
        assertEquals("https://app.example:8443", WebOrigin.of("https://app.example:8443"))
        assertEquals("http://app.example", WebOrigin.of("HTTP://app.example:80/"))
        assertEquals("https://app.example", WebOrigin.of("https://user:pw@app.example/"))
        // Browsers end the authority at a backslash: this is evil.example's page.
        assertEquals("https://evil.example", WebOrigin.of("https://evil.example\\@app.example/"))
        assertEquals("", WebOrigin.of("about:blank"))
        assertEquals("", WebOrigin.of("data:text/html,<p>"))
        assertEquals("", WebOrigin.of("file:///sdcard/x.html"))
        assertEquals("", WebOrigin.of("https://"))
        assertEquals("", WebOrigin.of(""))
        assertEquals("", WebOrigin.of(null))
        assertEquals("http://127.0.0.1:47100", WebOrigin.ofApp(offline))
        assertEquals("https://app.example", WebOrigin.ofApp(online))
    }

    @Test
    fun onlyTheAppsOwnOriginMatches() {
        val origin = WebOrigin.ofApp(offline)
        assertTrue(WebOrigin.matches("http://127.0.0.1:47100/settings", origin))
        assertTrue(WebOrigin.matches("http://127.0.0.1:47100", origin))
        // Another offline app, the same port by name, and the internet.
        assertFalse(WebOrigin.matches("http://127.0.0.1:47101/", origin))
        assertFalse(WebOrigin.matches("http://localhost:47100/", origin))
        assertFalse(WebOrigin.matches("https://127.0.0.1:47100/", origin))
        assertFalse(WebOrigin.matches("https://evil.example/", origin))
        assertFalse(WebOrigin.matches(null, origin))
        assertTrue(WebOrigin.matches("https://app.example/other/page", WebOrigin.ofApp(online)))
        assertFalse(WebOrigin.matches("https://app.example.evil.example/", WebOrigin.ofApp(online)))
        assertFalse(WebOrigin.matches("https://sub.app.example/", WebOrigin.ofApp(online)))
        // An app with no origin matches nothing, not even another page without one.
        assertFalse(WebOrigin.matches("about:blank", ""))
        assertFalse(WebOrigin.matches("", ""))
    }

    @Test
    fun offlineAppsStayOnTheirOriginOnlineAppsOnHttps() {
        assertTrue(WebOrigin.navigationAllowed("http://127.0.0.1:47100/route", offline))
        assertTrue(WebOrigin.navigationAllowed("about:blank", offline))
        assertFalse(WebOrigin.navigationAllowed("https://evil.example/", offline))
        assertFalse(WebOrigin.navigationAllowed("http://127.0.0.1:47101/", offline))
        assertFalse(WebOrigin.navigationAllowed("javascript:alert(1)", offline))
        assertFalse(WebOrigin.navigationAllowed(null, offline))

        assertTrue(WebOrigin.navigationAllowed("https://elsewhere.example/login", online))
        assertFalse(WebOrigin.navigationAllowed("http://elsewhere.example/", online))
        // An online page can't step into an offline app's server.
        assertFalse(WebOrigin.navigationAllowed("http://127.0.0.1:47100/", online))
        assertFalse(WebOrigin.navigationAllowed("file:///sdcard/x.html", online))
    }

    @Test
    fun theAppsOwnPagesAreHeardInFull() {
        for (type in listOf("getConfig", "audio", "speak", "install", "backResult", "hello")) {
            assertTrue(type, WebOrigin.hears(type, "https://app.example/a", "https://app.example/b", online))
            assertTrue(type, WebOrigin.hears(type, "http://127.0.0.1:47100/", "http://127.0.0.1:47100/x", offline))
            // Before the session reports a location (about:blank first).
            assertTrue(type, WebOrigin.hears(type, "https://app.example/", "about:blank", online))
        }
    }

    @Test
    fun anotherSitesPageInAnOnlineAppOnlySaysItsReady() {
        val signIn = "https://accounts.google.com/v3/signin/identifier?continue=x"
        // Ready: it gets the band navigation's state.
        assertTrue(WebOrigin.hears("hello", signIn, signIn, online))
        // Typing went to the input method: the old composer and field messages are heard from no other site.
        for (type in listOf("textFocus", "textBlur", "openComposer", "noTextField")) {
            assertFalse(type, WebOrigin.hears(type, signIn, signIn, online))
        }
        // The app's settings, the microphone, speech, installing and Back stay with the app.
        for (type in listOf("getConfig", "audio", "speak", "cancelSpeech", "install", "backResult")) {
            assertFalse(type, WebOrigin.hears(type, signIn, signIn, online))
        }
        // Only from the page the session shows: not from a page left behind, not over plain HTTP.
        assertFalse(WebOrigin.hears("hello", signIn, "https://app.example/", online))
        assertFalse(WebOrigin.hears("hello", "https://evil.example/", signIn, online))
        assertFalse(WebOrigin.hears("hello", "http://plain.example/", "http://plain.example/", online))
        // An offline app's page never hears another origin.
        assertFalse(WebOrigin.hears("hello", signIn, signIn, offline))
        assertTrue(WebOrigin.navigationPage(signIn, online))
        assertFalse(WebOrigin.navigationPage("http://plain.example/", online))
        assertFalse(WebOrigin.navigationPage(signIn, offline))
        assertTrue(WebOrigin.navigationPage("about:blank", offline))
    }
}
