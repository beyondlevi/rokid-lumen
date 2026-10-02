package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class WebAppPlatformTest {
    @get:Rule val temp = TemporaryFolder()

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

    @Test
    fun theProofOfConceptListBecomesOnlineGeckoApps() {
        val apps = WebAppLibrary.parse("""[{"url":"https://demo.example/","name":"Demo"}]""")
        assertEquals(1, apps.size)
        val app = apps[0]
        assertFalse(app.offline)
        assertEquals(WebEngineKind.GECKO, app.engine)
        assertEquals("https://demo.example/", app.url)
        assertEquals(WebAppLibrary.idForUrl("https://demo.example/"), app.id)
        assertEquals(apps, WebAppLibrary.parse(WebAppLibrary.serialize(apps)))
    }

    @Test
    fun offlineAppsGetPortsPastTheCounterAndEveryInstalledOne() {
        val first = WebAppLibrary.FIRST_PORT
        val taken = WebApp("a", "A", true, "", first, WebEngineKind.GECKO, null, "")
        val online = WebApp("b", "B", false, "https://b.example/", 0, WebEngineKind.SYSTEM, null, "")
        assertEquals(first, WebAppLibrary.nextPort(first, emptyList()))
        // A library from before the counter: past its highest port, a gap below is not reused.
        val later = taken.copy(id = "c", port = first + 5)
        assertEquals(first + 6, WebAppLibrary.nextPort(first, listOf(taken, later, online)))
        // The counter remembers ports whose apps are gone.
        assertEquals(first + 9, WebAppLibrary.nextPort(first + 9, listOf(taken)))
        assertEquals(first, WebAppLibrary.nextPort(0, emptyList()))
        assertEquals("http://127.0.0.1:$first/", taken.url)
    }

    @Test
    fun aRemovedAppsPortIsNeverGivenOutAgain() {
        val context = RuntimeEnvironment.getApplication()
        val one = WebAppLibrary.allocatePort(context)
        val two = WebAppLibrary.allocatePort(context)
        assertEquals(one + 1, two)
        val app = WebAppPackages.install(context, ByteArrayInputStream(zip("index.html" to "x", "manifest.webmanifest" to """{"id":"gone"}""")), "gone.mrbd.zip")
        assertEquals(two + 1, app.port)
        WebAppLibrary.remove(context, app.id)
        val next = WebAppPackages.install(context, ByteArrayInputStream(zip("index.html" to "y", "manifest.webmanifest" to """{"id":"new"}""")), "new.mrbd.zip")
        assertEquals(app.port + 1, next.port)
        // The same app installed again after its removal gets a new port (and so an empty origin).
        val again = WebAppPackages.install(context, ByteArrayInputStream(zip("index.html" to "x", "manifest.webmanifest" to """{"id":"gone"}""")), "gone.mrbd.zip")
        assertEquals(app.id, again.id)
        assertEquals(next.port + 1, again.port)
        WebAppLibrary.remove(context, next.id)
        WebAppLibrary.remove(context, again.id)
    }

    @Test
    fun aRemovedAppsGeckoContextIsCleared() {
        val context = RuntimeEnvironment.getApplication()
        WebAppContexts.clearer = null
        WebAppContexts.takePending(context)
        val app = WebAppLibrary.add(context, "https://ctx.example/", "Ctx")!!
        // No Gecko runtime yet: the clearing waits for it.
        WebAppLibrary.remove(context, app.id)
        assertEquals(setOf(WebAppContexts.idFor(app.id)), WebAppContexts.takePending(context))
        assertTrue(WebAppContexts.takePending(context).isEmpty())
        // With a runtime, right away.
        val cleared = mutableListOf<String>()
        WebAppContexts.clearer = { cleared += it }
        try {
            val other = WebAppLibrary.add(context, "https://ctx2.example/", null)!!
            WebAppLibrary.remove(context, other.id)
            assertEquals(listOf(WebAppContexts.idFor(other.id)), cleared)
            assertTrue(WebAppContexts.takePending(context).isEmpty())
        } finally {
            WebAppContexts.clearer = null
        }
    }

    @Test
    fun onlyHttpsIsAcceptedOnline() {
        assertTrue(WebAppLibrary.isAcceptable("https://x.example/app"))
        assertFalse(WebAppLibrary.isAcceptable("http://x.example/app"))
        assertFalse(WebAppLibrary.isAcceptable("file:///sdcard/x.html"))
        assertTrue(SystemWebEngine.isAllowed("http://127.0.0.1:47100/"))
        assertFalse(SystemWebEngine.isAllowed("http://127.0.0.2/"))
    }

    @Test
    fun zipSlipIsRefused() {
        try {
            WebAppPackages.extract(ByteArrayInputStream(zip("../evil.txt" to "x")), temp.newFolder("out"))
            fail("zip slip accepted")
        } catch (e: WebAppPackages.InvalidPackage) {
            assertTrue(e.problem == WebAppPackages.Problem.BAD_PATH)
        }
        assertFalse(File(temp.root, "evil.txt").exists())
    }

    @Test
    fun notAZipIsRefused() {
        try {
            WebAppPackages.extract(ByteArrayInputStream("hello".toByteArray()), temp.newFolder("bad"))
            fail("garbage accepted")
        } catch (e: WebAppPackages.InvalidPackage) {
            // expected
        }
    }

    @Test
    fun contentRootIsTheRootOrTheOnlyFolder() {
        val flat = temp.newFolder("flat")
        WebAppPackages.extract(ByteArrayInputStream(zip("index.html" to "<p>")), flat)
        assertEquals(flat, WebAppPackages.contentRoot(flat))
        val nested = temp.newFolder("nested")
        WebAppPackages.extract(ByteArrayInputStream(zip("dist/index.html" to "<p>", "__MACOSX/x" to "")), nested)
        assertEquals(File(nested, "dist"), WebAppPackages.contentRoot(nested))
        val none = temp.newFolder("none")
        WebAppPackages.extract(ByteArrayInputStream(zip("a/readme.txt" to "")), none)
        assertNull(WebAppPackages.contentRoot(none))
    }

    @Test
    fun installReadsTheManifestAndKeepsThePortOnUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = """{"id":"demo","name":"Demo App","short_name":"Demo","version":"1"}"""
        val first = WebAppPackages.install(context, ByteArrayInputStream(zip("index.html" to "v1", "manifest.webmanifest" to manifest)), "demo.mrbd.zip")
        assertTrue(first.offline)
        assertEquals("Demo", first.name)
        WebAppLibrary.setEngine(context, first.id, WebEngineKind.SYSTEM)
        val second = WebAppPackages.install(context, ByteArrayInputStream(zip("dist/index.html" to "v2", "dist/manifest.webmanifest" to manifest)), "other-name.mrbd.zip")
        assertEquals(first.id, second.id)
        assertEquals(first.port, second.port)
        assertEquals(WebEngineKind.SYSTEM, second.engine)
        assertEquals("v2", File(WebAppPackages.dir(context, second.id), "index.html").readText())
        assertEquals(1, WebAppLibrary.all(context).size)
        WebAppLibrary.remove(context, second.id)
        assertFalse(WebAppPackages.dir(context, second.id).exists())
    }

    @Test
    fun theServerServesFilesRoutesAndNothingOutside() {
        val root = temp.newFolder("site")
        File(root, "index.html").writeText("""<link href="https://fonts.googleapis.com/css2?family=Noto+Sans" rel="stylesheet">""")
        File(root, "assets").mkdirs()
        File(root, "assets/app.js").writeText("console.log(1)")
        temp.newFile("secret.txt").writeText("no")

        val index = LocalAppServer.resolve("/", root, null)
        assertEquals(200, index.status)
        assertTrue(String(index.body).contains(LocalAppServer.FONTS_CSS))
        assertFalse(String(index.body).contains("googleapis"))

        val js = LocalAppServer.resolve("/assets/app.js?v=2", root, null)
        assertEquals(200, js.status)
        assertTrue(js.type.startsWith("text/javascript"))

        // A route of the single-page app gets index.html; a missing file is a 404.
        assertEquals(200, LocalAppServer.resolve("/ditado", root, null).status)
        assertEquals(404, LocalAppServer.resolve("/assets/missing.js", root, null).status)
        assertEquals(403, LocalAppServer.resolve("/../secret.txt", root, null).status)
        assertEquals(403, LocalAppServer.resolve("/%2e%2e/secret.txt", root, null).status)
        assertEquals(404, LocalAppServer.resolve("/__mrbd/fonts/../x.woff2", root, null).status)
    }

    @Test
    fun mimeTypes() {
        assertEquals("application/wasm", LocalAppServer.mimeType("a.wasm"))
        assertTrue(LocalAppServer.mimeType("m.webmanifest").startsWith("application/manifest+json"))
        assertEquals("application/octet-stream", LocalAppServer.mimeType("noext"))
    }

    @Test
    fun linkHrefFindsTheRelInAnyOrder() {
        val html = """<link href="/m.webmanifest" rel="manifest"><link rel="apple-touch-icon icon" href='/i.png'>"""
        assertEquals("/m.webmanifest", WebAppIcons.linkHref(html, "manifest"))
        assertEquals("/i.png", WebAppIcons.linkHref(html, "apple-touch-icon"))
        assertNull(WebAppIcons.linkHref(html, "stylesheet"))
    }

    @Test
    fun phoneNotificationsParseAndReplace() {
        val json = org.json.JSONObject("""{"type":"post","key":"k1","app":"Chat","pkg":"x.chat","title":"Ana","text":"Oi","when":10,"redacted":false,"alert":true}""")
        val parsed = PhoneLink.parse(json)
        assertNotNull(parsed)
        assertEquals("Ana", parsed!!.title)
        // The banner darkens the HUD only when the phone asks.
        assertFalse(parsed.focus)
        assertTrue(PhoneLink.parse(json.put("focus", true))!!.focus)
        assertNull(PhoneLink.parse(org.json.JSONObject("{}")))

        NotificationInbox.clear()
        assertTrue(NotificationInbox.put(parsed))
        assertEquals(0, NotificationInbox.unread())
        // Unread follows the phone's news flag (it knows what's new), not a text change.
        assertFalse(NotificationInbox.put(parsed.copy(postedAt = 11), false))
        assertEquals(0, NotificationInbox.unread())
        assertTrue(NotificationInbox.put(parsed.copy(text = "Oi de novo", postedAt = 12), true))
        assertEquals(1, NotificationInbox.unread())
        NotificationInbox.markSeen()
        assertEquals(0, NotificationInbox.unread())
        assertEquals(1, NotificationInbox.all().size)
        NotificationInbox.put(parsed.copy(key = "k2", postedAt = 20))
        assertEquals("k2", NotificationInbox.all().first().key)
        NotificationInbox.remove("k1")
        assertEquals(listOf("k2"), NotificationInbox.all().map { it.key })
        NotificationInbox.clear()
    }

    @Test
    fun iconCandidatesComeFromTheManifestThePageAndTheFavicon() {
        val html = """<link rel="icon" href="/favicon-32.png" sizes="32x32"><link rel="icon" href="/logo.svg" type="image/svg+xml">""" +
            """<link rel="apple-touch-icon" href="touch.png"><link rel="shortcut icon" href="/old.ico">"""
        val manifest = org.json.JSONObject("""{"icons":[{"src":"icons/192.png","sizes":"192x192"},{"src":"icons/512.png","sizes":"512x512"},""" +
            """{"src":"mono.png","sizes":"1024x1024","purpose":"monochrome"},{"src":"vector.svg","sizes":"any"}]}""")
        val list = WebAppIcons.candidates("https://app.example/chat/", html, manifest, "https://app.example/m/manifest.json").map { it.url }
        assertEquals(
            listOf(
                "https://app.example/m/icons/512.png",
                "https://app.example/m/icons/192.png",
                "https://app.example/chat/touch.png",
                "https://app.example/favicon-32.png",
                "https://app.example/old.ico",
                "https://app.example/favicon.ico",
            ),
            list,
        )
        // A page with nothing declared still has the conventional favicon.
        assertEquals(listOf("https://app.example/favicon.ico"), WebAppIcons.candidates("https://app.example/", "<p>hi</p>", null, null).map { it.url })
        assertEquals(48, WebAppIcons.largest("16x16 48x48 any"))
    }

    private fun servePackage(body: ByteArray, requests: MutableList<String>): Int {
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        kotlin.concurrent.thread(isDaemon = true) {
            server.use {
                while (true) {
                    val client = runCatching { server.accept() }.getOrNull() ?: break
                    client.use { c ->
                        val head = StringBuilder()
                        val input = c.getInputStream()
                        while (!head.endsWith("\r\n\r\n")) head.append(input.read().toChar())
                        requests += head.lineSequence().first()
                        val ok = head.startsWith("GET /lumen/package/tok1 ")
                        val out = c.getOutputStream()
                        if (ok) {
                            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            out.write(body)
                        } else {
                            out.write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\n\r\n".toByteArray())
                        }
                        out.flush()
                    }
                }
            }
        }
        return server.localPort
    }

    private fun sha256(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun aPackageFromThePhoneInstallsAndUpdatesInPlace() {
        val context = RuntimeEnvironment.getApplication()
        val manifest = """{"id":"chat","name":"Chat","version":"1.0","lumen_config":[{"key":"k","label":"K","type":"secret"}]}"""
        val v1 = zip("index.html" to "v1", "manifest.webmanifest" to manifest)
        val requests = mutableListOf<String>()
        val first = WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(v1, requests)}", "tok1", v1.size.toLong(), sha256(v1), "chat.mrbd.zip", null)
        assertEquals("1.0", first.version)
        assertEquals(listOf("GET /lumen/package/tok1 HTTP/1.1"), requests)
        WebAppConfig.set(context, first, "k", "secret-value")

        // An update from the phone keeps the port, the engine and the secret.
        WebAppLibrary.setEngine(context, first.id, WebEngineKind.SYSTEM)
        val v2 = zip("index.html" to "v2", "manifest.webmanifest" to manifest.replace("1.0", "2.0"))
        val second = WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(v2, mutableListOf())}", "tok1", v2.size.toLong(), sha256(v2), "chat-2.zip", first.id)
        assertEquals(first.id, second.id)
        assertEquals(first.port, second.port)
        assertEquals(WebEngineKind.SYSTEM, second.engine)
        assertEquals("2.0", second.version)
        assertTrue(WebAppConfig.fields(context, second).single().set)
        assertEquals("v2", File(WebAppPackages.dir(context, first.id), "index.html").readText())

        // A package without an id takes the app's when it replaces it.
        val bare = zip("index.html" to "v3")
        val third = WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(bare, mutableListOf())}", "tok1", bare.size.toLong(), sha256(bare), "whatever.zip", first.id)
        assertEquals(first.id, third.id)
        assertEquals(1, WebAppLibrary.all(context).size)

        // Another app's package, a damaged download, or an online target are refused.
        val other = zip("index.html" to "o", "manifest.webmanifest" to """{"id":"other","name":"Other"}""")
        assertProblem(WebAppPackages.Problem.OTHER_APP) {
            WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(other, mutableListOf())}", "tok1", other.size.toLong(), sha256(other), "o.zip", first.id)
        }
        assertProblem(WebAppPackages.Problem.DAMAGED) {
            WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(v1, mutableListOf())}", "tok1", v1.size.toLong(), sha256(v2), "chat.zip", null)
        }
        assertProblem(WebAppPackages.Problem.DOWNLOAD_FAILED) {
            WebAppPackages.installFromPhone(context, "127.0.0.1:${servePackage(v1, mutableListOf())}", "nope", v1.size.toLong(), sha256(v1), "chat.zip", null)
        }
        val online = WebAppLibrary.add(context, "https://web.example/", "Web")!!
        assertProblem(WebAppPackages.Problem.NOT_OFFLINE) {
            WebAppPackages.installFromPhone(context, "127.0.0.1:1", "tok1", 1, "00", "x.zip", online.id)
        }
        assertEquals("v3", File(WebAppPackages.dir(context, first.id), "index.html").readText())
        assertEquals(2, WebAppLibrary.all(context).size)
    }

    private fun assertProblem(problem: WebAppPackages.Problem, block: () -> Unit) {
        try {
            block()
            fail("expected $problem")
        } catch (e: WebAppPackages.InvalidPackage) {
            assertEquals(problem, e.problem)
        }
    }

    @Test
    fun theNetworkNameComesFromCmdWifiStatus() {
        assertEquals("Alberto", WirelessDebug.parseSsid("Wifi is enabled\nWifi is connected to \"Alberto\"\nWifiInfo: SSID: \"Alberto\""))
        assertEquals("", WirelessDebug.parseSsid("Wifi is disabled"))
    }
}
