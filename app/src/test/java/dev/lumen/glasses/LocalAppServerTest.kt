package dev.lumen.glasses

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

@RunWith(RobolectricTestRunner::class)
class LocalAppServerTest {
    private val context = RuntimeEnvironment.getApplication()
    private lateinit var app: WebApp

    @Before
    fun setUp() {
        val port = ServerSocket(0, 1, InetAddress.getByName(LocalAppServer.HOST)).use { it.localPort }
        app = WebApp("pkg-test", "Test", true, "", port, WebEngineKind.GECKO, null, "")
        WebAppPackages.dir(context, app.id).apply { mkdirs() }.let { File(it, "index.html").writeText("<p>hi</p>") }
    }

    @After
    fun tearDown() {
        LocalAppServer.stop(app.port)
        LocalAppServer.idleMs = LocalAppServer.IDLE_MS
        WebAppPackages.dir(context, app.id).deleteRecursively()
    }

    @Test
    fun onlyTheServersOwnAddressesPassTheHostCheck() {
        assertTrue(LocalAppServer.hostAllowed("127.0.0.1:47100", 47100))
        assertTrue(LocalAppServer.hostAllowed(" LocalHost:47100 ", 47100))
        // Another port, a rebound name, a bare address, nothing at all.
        assertFalse(LocalAppServer.hostAllowed("127.0.0.1:47101", 47100))
        assertFalse(LocalAppServer.hostAllowed("evil.example:47100", 47100))
        assertFalse(LocalAppServer.hostAllowed("127.0.0.1.evil.example:47100", 47100))
        assertFalse(LocalAppServer.hostAllowed("127.0.0.1", 47100))
        assertFalse(LocalAppServer.hostAllowed("", 47100))
        assertFalse(LocalAppServer.hostAllowed(null, 47100))
    }

    @Test
    fun aRequestNamingAnotherHostGets421() {
        assertTrue(LocalAppServer.acquire(context, app))
        val ok = get("Host: 127.0.0.1:${app.port}")
        assertTrue(ok, ok.startsWith("HTTP/1.1 200"))
        assertTrue(ok, ok.contains("Cross-Origin-Resource-Policy: same-origin"))
        assertTrue(ok, ok.contains("X-Frame-Options: SAMEORIGIN"))
        assertTrue(ok, ok.endsWith("<p>hi</p>"))
        assertTrue(get("Host: localhost:${app.port}").startsWith("HTTP/1.1 200"))

        val rebound = get("Host: attacker.example:${app.port}")
        assertTrue(rebound, rebound.startsWith("HTTP/1.1 421"))
        assertFalse(rebound.contains("<p>hi</p>"))
        assertTrue(get("").startsWith("HTTP/1.1 421"))
        assertTrue(get("Host: 127.0.0.1:${app.port}\r\nHost: attacker.example").startsWith("HTTP/1.1 421"))
        LocalAppServer.release(app.port)
    }

    @Test
    fun theServerStopsOnceNoScreenUsesIt() {
        LocalAppServer.idleMs = 100
        assertTrue(LocalAppServer.acquire(context, app))
        assertTrue(LocalAppServer.acquire(context, app))
        LocalAppServer.release(app.port)
        Thread.sleep(400)
        // One screen still has it open.
        assertTrue(LocalAppServer.isRunning(app.port))
        LocalAppServer.release(app.port)
        // Reopened within the idle time: it stays up.
        assertTrue(LocalAppServer.acquire(context, app))
        Thread.sleep(400)
        assertTrue(LocalAppServer.isRunning(app.port))
        LocalAppServer.release(app.port)
        val until = System.currentTimeMillis() + 5_000
        while (LocalAppServer.isRunning(app.port) && System.currentTimeMillis() < until) Thread.sleep(20)
        assertFalse(LocalAppServer.isRunning(app.port))
        // A release too many changes nothing.
        LocalAppServer.release(app.port)
        assertFalse(LocalAppServer.isRunning(app.port))
    }

    private fun get(hostHeaders: String): String =
        Socket(LocalAppServer.HOST, app.port).use { socket ->
            socket.soTimeout = 5_000
            val head = "GET / HTTP/1.1\r\n" + (if (hostHeaders.isEmpty()) "" else "$hostHeaders\r\n") + "\r\n"
            socket.getOutputStream().write(head.toByteArray())
            socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }.also { assertEquals(true, it.isNotEmpty()) }
}
