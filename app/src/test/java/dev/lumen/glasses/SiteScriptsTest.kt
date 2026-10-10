package dev.lumen.glasses

import dev.lumen.protocol.GridItem
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class SiteScriptsTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun manifest(scripts: String = SCRIPTS, startUrl: String = "https://www.instagram.com/", version: String = "1.0.0") =
        """{"id":"cloud.bynd.lumen.site.instagram","name":"Instagram","version":"$version","start_url":"$startUrl",
           "lumen_scripts":$scripts,
           "lumen_gestures":{"title":{"en":"Gestures on Instagram","pt":"Gestos no Instagram"},
             "rows":[{"gestures":["down","up"],"text":{"en":"Next reel · previous","pt":"Reel seguinte · anterior"}},
                     {"gestures":["index"],"text":"Pause or play"}]}}"""

    private fun stage(vararg entries: Pair<String, String>) =
        WebAppPackages.stage(context, ByteArrayInputStream(zip(*entries)), "instagram.mrbd.zip")

    private fun assertProblem(problem: WebAppPackages.Problem, block: () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (e: WebAppPackages.InvalidPackage) {
            assertEquals(problem, e.problem)
        }
    }

    @Test
    fun anHttpsStartUrlMakesAnOnlinePackageWithoutIndexHtml() {
        val staged = stage("manifest.webmanifest" to manifest(), "site/instagram.js" to "window.ig = 1", "site/instagram.css" to "a{}")
        assertTrue(staged.online)
        assertEquals("https://www.instagram.com/", staged.startUrl)
        assertEquals(listOf("www.instagram.com", "*.cdninstagram.com"), staged.scriptHosts)
        val preview = InstallPreview.forPackage(context, staged, "https://dl.example/instagram.mrbd.zip")
        assertFalse(preview.offline)
        assertTrue(preview.internet)
        assertEquals("1.0.0", preview.version)
        assertEquals(staged.scriptHosts, preview.scriptHosts)

        val app = WebAppPackages.commit(context, staged, trusted = true)
        assertFalse(app.offline)
        assertTrue(app.packaged)
        assertEquals(0, app.port)
        assertEquals("https://www.instagram.com/", app.url)
        assertEquals(staged.scriptHosts, app.scriptHosts)
        assertEquals(app, WebAppLibrary.find(context, app.id))
        // The package's files stay, for the scripts and the gesture card.
        val dir = WebAppPackages.dir(context, app.id)
        assertEquals("window.ig = 1", File(dir, "site/instagram.js").readText())
        val registration = SiteScripts.registrationFor(context, app)
        assertEquals(1, registration.scripts.size)
        assertEquals("window.ig = 1", registration.scripts[0].js)
        assertEquals("a{}", registration.scripts[0].css)
        assertTrue(registration.key.startsWith(app.id + "@1.0.0#"))

        // A package from the phone may replace it, and its copies follow the update.
        val copy = WebAppLibrary.copy(context, app.id, "Instagram 2")!!
        assertTrue(File(WebAppPackages.dir(context, copy.id), "site/instagram.js").isFile)
        val updated = WebAppPackages.commit(context, stage(
            "dist/manifest.json" to manifest(version = "1.1.0", startUrl = "https://www.instagram.com/reels/"),
            "dist/site/instagram.js" to "window.ig = 2", "dist/site/instagram.css" to "",
        ), trusted = true)
        assertEquals(app.id, updated.id)
        assertEquals("1.1.0", updated.version)
        val copied = WebAppLibrary.find(context, copy.id)!!
        assertEquals("https://www.instagram.com/reels/", copied.remoteUrl)
        assertTrue(copied.packaged)
        assertEquals("window.ig = 2", File(WebAppPackages.dir(context, copy.id), "site/instagram.js").readText())
        assertNotEquals(registration.key, SiteScripts.registrationFor(context, updated).key)

        WebAppLibrary.remove(context, app.id)
        assertFalse(dir.exists())
        WebAppLibrary.remove(context, copy.id)
        assertFalse(WebAppPackages.dir(context, copy.id).exists())
    }

    @Test
    fun anOnlinePackageTakesOverTheAppAddedForItsSite() {
        // Signed in on the app added by address, placed in the grid.
        val byAddress = WebAppLibrary.add(context, "https://www.instagram.com/", null)!!
        GridStore.set(context, listOf("web:other", GridItem.WEB_PREFIX + byAddress.id, GridItem.SETTINGS_ID), emptyList())
        WebAppContexts.takePending(context)
        val staged = stage("manifest.webmanifest" to manifest(), "site/instagram.js" to "1", "site/instagram.css" to "")
        assertEquals("www.instagram.com", InstallPreview.forPackage(context, staged, "https://dl.example/ig.mrbd.zip").updates)

        val app = WebAppPackages.commit(context, staged, trusted = true)
        // Its cookies and storage (the sign-ins) and its place in the grid; the old entry is gone.
        assertEquals(byAddress.id, app.contextOf)
        assertEquals(byAddress.id, app.contextKey)
        assertNull(WebAppLibrary.find(context, byAddress.id))
        // Where the library had it too (its order is the grid's until the phone arranges it).
        assertEquals(app.id, WebAppLibrary.all(context).first().id)
        val order = GridStore.layout(context)
        assertTrue(order.indexOf(GridItem.WEB_PREFIX + app.id) in 0 until order.indexOf(GridItem.SETTINGS_ID))
        assertFalse(GridItem.WEB_PREFIX + byAddress.id in order)
        assertTrue(WebAppContexts.takePending(context).isEmpty())

        // An update keeps that context; a copy gets its own.
        val updated = WebAppPackages.commit(context, stage("manifest.webmanifest" to manifest(version = "1.1.0"), "site/instagram.js" to "2", "site/instagram.css" to ""), trusted = true)
        assertEquals(byAddress.id, updated.contextOf)
        val copy = WebAppLibrary.copy(context, app.id, "Instagram 2")!!
        assertEquals(copy.id, copy.contextKey)
        WebAppLibrary.remove(context, copy.id)
        WebAppContexts.takePending(context)

        // Removing the app clears the context it took over.
        WebAppLibrary.remove(context, app.id)
        assertEquals(setOf(byAddress.id), WebAppContexts.takePending(context))
    }

    @Test
    fun onlyAnAppAddedByAddressForTheSameSiteIsTakenOver() {
        fun app(id: String, url: String, offline: Boolean = false, packaged: Boolean = false, copyOf: String = "") =
            WebApp(id, id, offline, if (offline) "" else url, 0, WebEngineKind.GECKO, null, "", packaged = packaged, copyOf = copyOf)
        val hosts = listOf("m.youtube.com", "*.youtube.com")
        // Another host of the site, covered by the scripts' *.youtube.com.
        assertEquals("www", WebAppPackages.adoptable(listOf(app("www", "https://www.youtube.com/")), "https://m.youtube.com/", hosts, "pkg")?.id)
        assertEquals("bare", WebAppPackages.adoptable(listOf(app("bare", "https://youtube.com/feed")), "https://m.youtube.com/", hosts, "pkg")?.id)
        // Not: another site, a copy, a packaged app, the package itself, an offline app.
        assertNull(WebAppPackages.adoptable(listOf(app("x", "https://notyoutube.com/")), "https://m.youtube.com/", hosts, "pkg"))
        assertNull(WebAppPackages.adoptable(listOf(app("c", "https://m.youtube.com/", copyOf = "www")), "https://m.youtube.com/", hosts, "pkg"))
        assertNull(WebAppPackages.adoptable(listOf(app("p", "https://m.youtube.com/", packaged = true)), "https://m.youtube.com/", hosts, "pkg"))
        assertNull(WebAppPackages.adoptable(listOf(app("pkg", "https://m.youtube.com/")), "https://m.youtube.com/", hosts, "pkg"))
        assertNull(WebAppPackages.adoptable(listOf(app("o", "", offline = true)), "https://m.youtube.com/", hosts, "pkg"))
        // Added without www. or m. (as on the glasses: https://instagram.com, https://youtube.com).
        assertEquals("ig", WebAppPackages.adoptable(listOf(app("ig", "https://instagram.com")), "https://www.instagram.com/", listOf("www.instagram.com"), "pkg")?.id)
        assertEquals("yt", WebAppPackages.adoptable(listOf(app("yt", "https://youtube.com")), "https://m.youtube.com/", listOf("m.youtube.com", "www.youtube.com"), "pkg")?.id)
        assertEquals("www", WebAppPackages.adoptable(listOf(app("www", "https://www.youtube.com/")), "https://m.youtube.com/", emptyList(), "pkg")?.id)
        // Another site that merely ends the same way isn't.
        assertNull(WebAppPackages.adoptable(listOf(app("ig2", "https://notinstagram.com")), "https://www.instagram.com/", listOf("www.instagram.com"), "pkg"))
        assertNull(WebAppPackages.adoptable(listOf(app("m2", "https://music.youtube.com/")), "https://m.youtube.com/", listOf("m.youtube.com"), "pkg"))
    }

    @Test
    fun anOfflinePackageIsUnchanged() {
        // A relative start_url, and lumen_scripts that only an online package would run.
        val staged = stage("index.html" to "<p>", "manifest.webmanifest" to manifest(startUrl = "./", scripts = """[{"matches":["http://x/*"],"js":["../x.js"]}]"""))
        assertFalse(staged.online)
        assertTrue(staged.scriptHosts.isEmpty())
        val app = WebAppPackages.commit(context, staged)
        assertTrue(app.offline)
        assertFalse(app.packaged)
        assertTrue(app.port >= WebAppLibrary.FIRST_PORT)
        assertTrue(SiteScripts.registrationFor(context, app).isEmpty)
        WebAppLibrary.remove(context, app.id)

        // Its own page keeps it offline even when its manifest names an HTTPS address (where
        // the same app is also hosted), and its scripts don't run.
        val hosted = stage("index.html" to "<p>", "manifest.webmanifest" to manifest(startUrl = "https://notes.example/"), "site/instagram.js" to "", "site/instagram.css" to "")
        assertFalse(hosted.online)
        assertTrue(hosted.scriptHosts.isEmpty())
        val kept = WebAppPackages.commit(context, hosted)
        assertTrue(kept.offline)
        assertFalse(kept.packaged)
        assertTrue(SiteScripts.registrationFor(context, kept).isEmpty)
        WebAppLibrary.remove(context, kept.id)
    }

    @Test
    fun badPackagesAreRefused() {
        // An address that isn't HTTPS, and no index.html.
        assertProblem(WebAppPackages.Problem.START_URL) { stage("manifest.json" to manifest(startUrl = "http://www.instagram.com/")) }
        assertProblem(WebAppPackages.Problem.NO_INDEX) { stage("manifest.json" to """{"name":"x"}""") }
        // Pages that aren't https sites by name.
        listOf("http://www.instagram.com/*", "<all_urls>", "*://*/*", "https://*/*", "https://*.com/*", "https://x.example:8443/*", "https://x.example")
            .forEach { match ->
                assertProblem(WebAppPackages.Problem.SCRIPT_MATCH) {
                    stage("manifest.json" to manifest(scripts = """[{"matches":["$match"],"js":["a.js"]}]"""), "a.js" to "")
                }
            }
        // Paths out of the package, absolute, or missing.
        listOf("../a.js", "site/../../a.js", "/a.js", "missing.js").forEach { path ->
            assertProblem(WebAppPackages.Problem.SCRIPT_PATH) {
                stage("manifest.json" to manifest(scripts = """[{"matches":["https://x.example/*"],"js":["$path"]}]"""), "a.js" to "")
            }
        }
        // Over 1 MiB of scripts.
        assertProblem(WebAppPackages.Problem.SCRIPTS_TOO_BIG) {
            stage(
                "manifest.json" to manifest(scripts = """[{"matches":["https://x.example/*"],"js":["a.js"],"css":["b.css"]}]"""),
                "a.js" to "a".repeat(1024 * 1024), "b.css" to "b",
            )
        }
        // Malformed: not an array, too many entries, an entry with nothing to run.
        val nine = (1..9).joinToString(",", "[", "]") { """{"matches":["https://x.example/*"],"js":["a.js"]}""" }
        listOf("{}", nine, """[{"matches":["https://x.example/*"]}]""", """[{"matches":[],"js":["a.js"]}]""", """[{"matches":["https://x.example/*"],"js":"a.js"}]""")
            .forEach { scripts ->
                assertProblem(WebAppPackages.Problem.SCRIPTS_INVALID) { stage("manifest.json" to manifest(scripts = scripts), "a.js" to "") }
            }
        // Nothing staged is left behind.
        assertTrue(WebAppPackages.root(context).listFiles().orEmpty().none { it.name.startsWith(".staging-") && it.isDirectory })
    }

    @Test
    fun matchPatternsNameTheirSite() {
        assertEquals("www.instagram.com", SiteScripts.hostOf("https://www.instagram.com/*"))
        assertEquals("*.youtube.com", SiteScripts.hostOf("https://*.YouTube.com/watch*"))
        assertNull(SiteScripts.hostOf("https://www.instagram.com"))
        assertTrue(SiteScripts.isPackagePath("site/a.js"))
        assertFalse(SiteScripts.isPackagePath("site\\a.js"))
        assertFalse(SiteScripts.isPackagePath("file:///a.js"))
    }

    @Test
    fun theKeyChangesWithTheCodeTheAppAndTheVersion() {
        val one = listOf(SiteScripts.Script(listOf("https://x.example/*"), "a()", ""))
        val key = SiteScripts.key("app", "1", one)
        assertEquals(key, SiteScripts.key("app", "1", listOf(SiteScripts.Script(listOf("https://x.example/*"), "a()", ""))))
        assertNotEquals(key, SiteScripts.key("app", "1", listOf(SiteScripts.Script(listOf("https://x.example/*"), "b()", ""))))
        assertNotEquals(key, SiteScripts.key("app", "1", listOf(SiteScripts.Script(listOf("https://x.example/*"), "a()", "p{}"))))
        assertNotEquals(key, SiteScripts.key("app", "1", listOf(SiteScripts.Script(listOf("https://y.example/*"), "a()", ""))))
        assertNotEquals(key, SiteScripts.key("app", "2", one))
        assertNotEquals(key, SiteScripts.key("copy", "1", one))
        // The message background.js takes.
        val message = SiteScripts.Registration(key, one).toMessage()
        assertEquals("siteScripts", message.getString("type"))
        assertEquals(key, message.getString("key"))
        assertFalse(message.has("tabId"))
        assertEquals("https://x.example/*", message.getJSONArray("scripts").getJSONObject(0).getJSONArray("matches").getString(0))
        assertEquals("a()", message.getJSONArray("scripts").getJSONObject(0).getString("js"))
        assertEquals(0, SiteScripts.Registration.NONE.toMessage().getJSONArray("scripts").length())
    }

    @Test
    fun theGestureCardFollowsTheDeviceLanguage() {
        val json = JSONObject(manifest())
        val pt = SiteScripts.gestureCard(json, "pt")!!
        assertEquals("Gestos no Instagram", pt.title)
        assertEquals(listOf(SiteScripts.Gesture.DOWN, SiteScripts.Gesture.UP), pt.rows[0].gestures)
        assertEquals("Reel seguinte · anterior", pt.rows[0].text)
        // A plain string is the same in every language.
        assertEquals("Pause or play", pt.rows[1].text)
        assertEquals("Gestos no Instagram", SiteScripts.gestureCard(json, "pt-BR")!!.title)
        assertEquals("Gestures on Instagram", SiteScripts.gestureCard(json, "en")!!.title)
        // Another language: English, then the first text given.
        assertEquals("Next reel · previous", SiteScripts.gestureCard(json, "fr")!!.rows[0].text)
        assertEquals("Hola", SiteScripts.localized(JSONObject("""{"es":"Hola"}"""), "de"))
        assertEquals("", SiteScripts.localized(null, "en"))

        // Unknown gestures and empty rows are skipped; a card without rows is none.
        val odd = JSONObject("""{"lumen_gestures":{"rows":[{"gestures":["wink"],"text":"x"},{"gestures":["middle","nod"],"text":"Back"},{"gestures":["index"]}]}}""")
        val card = SiteScripts.gestureCard(odd, "en")!!
        assertEquals("", card.title)
        assertEquals(listOf(SiteScripts.GestureRow(listOf(SiteScripts.Gesture.MIDDLE), "Back")), card.rows)
        assertNull(SiteScripts.gestureCard(JSONObject("""{"lumen_gestures":{"rows":[{"gestures":["wink"],"text":"x"}]}}"""), "en"))
        assertNull(SiteScripts.gestureCard(JSONObject("{}"), "en"))
        val many = (1..9).joinToString(",", """{"lumen_gestures":{"rows":[""", "]}}") { """{"gestures":["up"],"text":"$it"}""" }
        assertEquals(SiteScripts.MAX_ROWS, SiteScripts.gestureCard(JSONObject(many), "en")!!.rows.size)
    }

    @Test
    fun theNewFieldsSurviveTheLibrary() {
        val app = WebApp(
            "pkg-ig", "Instagram", false, "https://www.instagram.com/", 0, WebEngineKind.GECKO, null, "1.0.0",
            packaged = true, scriptHosts = listOf("www.instagram.com", "*.cdninstagram.com"),
        )
        assertEquals(listOf(app), WebAppLibrary.parse(WebAppLibrary.serialize(listOf(app))))
        assertTrue(app.hasPackage)
        // An entry from before them.
        val old = WebAppLibrary.parse("""[{"id":"a","name":"A","offline":false,"url":"https://a.example/"}]""").single()
        assertFalse(old.packaged)
        assertTrue(old.scriptHosts.isEmpty())
        assertFalse(old.hasPackage)
    }

    companion object {
        private const val SCRIPTS = """[{"matches":["https://www.instagram.com/*","https://*.cdninstagram.com/*"],"js":["site/instagram.js"],"css":["site/instagram.css"]}]"""
    }
}
