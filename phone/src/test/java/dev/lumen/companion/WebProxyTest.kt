package dev.lumen.companion

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

class WebProxyTest {
    private val loopback = InetAddress.getByName("127.0.0.1")
    private val closeables = mutableListOf<AutoCloseable>()

    @After
    fun tearDown() = closeables.forEach { runCatching { it.close() } }

    @Test
    fun `parses CONNECT and absolute http requests`() {
        val connect = WebProxy.parse("CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n")!!
        assertEquals("example.com" to 443, connect.host to connect.port)
        val get = WebProxy.parse("GET http://example.com:8080/a/b?c=1 HTTP/1.1\r\nHost: example.com\r\n\r\n")!!
        assertEquals(Triple("example.com", 8080, "/a/b?c=1"), Triple(get.host, get.port, get.path))
        val bare = WebProxy.parse("GET http://example.com HTTP/1.1\r\n\r\n")!!
        assertEquals(80 to "/", bare.port to bare.path)
        assertNull(WebProxy.parse("GET /relative HTTP/1.1\r\n\r\n"))
        assertNull(WebProxy.parse("CONNECT example.com HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `forwarded head drops proxy headers and closes the connection`() {
        val head = WebProxy.parse(
            "GET http://example.com/x HTTP/1.1\r\nHost: example.com\r\nProxy-Connection: keep-alive\r\n" +
                "Proxy-Authorization: Basic abc\r\nConnection: keep-alive\r\nAccept: */*\r\n\r\n",
        )!!.forwardHead()
        assertEquals("GET /x HTTP/1.1\r\nHost: example.com\r\nAccept: */*\r\nConnection: close\r\n\r\n", head)
    }

    @Test
    fun `reads the head up to the blank line only`() {
        val input = ByteArrayInputStream("CONNECT a:1 HTTP/1.1\r\n\r\nTLS".toByteArray())
        assertEquals("CONNECT a:1 HTTP/1.1\r\n\r\n", WebProxy.readHead(input))
        assertEquals('T'.code, input.read())
    }

    @Test
    fun `tunnels CONNECT both ways`() {
        val echo = echoServer()
        val proxy = proxy(blockLocal = false)
        Socket(loopback, proxy).use { s ->
            s.getOutputStream().write("CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n".toByteArray())
            val head = WebProxy.readHead(s.getInputStream())!!
            assertTrue(head, head.startsWith("HTTP/1.1 200"))
            s.getOutputStream().write("ping".toByteArray())
            val buf = ByteArray(4)
            var n = 0
            while (n < 4) n += s.getInputStream().read(buf, n, 4 - n)
            assertEquals("ping", String(buf))
        }
    }

    @Test
    fun `forwards plain http in origin form`() {
        val origin = ServerSocket(0, 1, loopback).also { closeables += it }
        thread(isDaemon = true) {
            origin.accept().use { c ->
                val head = WebProxy.readHead(c.getInputStream())!!
                val body = head.lineSequence().first()
                c.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body".toByteArray())
            }
        }
        val proxy = proxy(blockLocal = false)
        Socket(loopback, proxy).use { s ->
            s.getOutputStream().write("GET http://127.0.0.1:${origin.localPort}/hello HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray())
            val text = s.getInputStream().readBytes().toString(Charsets.ISO_8859_1)
            assertTrue(text, text.endsWith("GET /hello HTTP/1.1"))
        }
    }

    @Test
    fun `refuses destinations on the phone`() {
        val proxy = proxy(blockLocal = true)
        Socket(loopback, proxy).use { s ->
            s.getOutputStream().write("CONNECT localhost:22 HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 403"))
        }
    }

    @Test
    fun `answers 502 when the destination does not answer`() {
        val closed = ServerSocket(0, 1, loopback).let { val p = it.localPort; it.close(); p }
        val proxy = proxy(blockLocal = false)
        Socket(loopback, proxy).use { s ->
            s.getOutputStream().write("CONNECT 127.0.0.1:$closed HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 502"))
        }
    }

    @Test
    fun `classifies private and special addresses`() {
        val special = listOf(
            "0.0.0.0", "0.1.2.3", "10.0.0.1", "10.255.255.255", "100.64.0.1", "100.127.255.255",
            "127.0.0.1", "127.8.9.10", "169.254.1.1", "172.16.0.1", "172.31.255.255", "192.168.0.1",
            "192.168.43.1", "224.0.0.1", "239.255.255.250", "240.0.0.1", "255.255.255.255",
            "::", "::1", "fc00::1", "fd12:3456::1", "fe80::1", "febf::1", "fec0::1", "ff02::1",
            "::ffff:10.0.0.1", "::ffff:127.0.0.1", "::10.0.0.1", "64:ff9b::a00:1", "64:ff9b::c0a8:1",
        )
        val public = listOf(
            "1.1.1.1", "8.8.8.8", "9.255.255.255", "11.0.0.1", "100.63.255.255", "100.128.0.1",
            "126.255.255.255", "128.0.0.1", "169.253.1.1", "172.15.255.255", "172.32.0.1",
            "192.167.255.255", "192.169.0.1", "223.255.255.255",
            "2606:4700:4700::1111", "2001:4860:4860::8888", "::ffff:8.8.8.8", "64:ff9b::808:808",
        )
        special.forEach { assertTrue("$it is special", WebProxy.isSpecial(InetAddress.getByName(it))) }
        public.forEach { assertFalse("$it is public", WebProxy.isSpecial(InetAddress.getByName(it))) }
    }

    @Test
    fun `refuses a name when any of its addresses is private`() {
        val resolved = arrayOf(InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.7"))
        val proxy = start(WebProxy(loopback, resolve = { resolved }, ownAddresses = { emptySet() }))
        assertTrue(connect(proxy, "CONNECT rebind.example:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
        assertTrue(connect(proxy, "GET http://rebind.example/ HTTP/1.1\r\nHost: rebind.example\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `refuses the phone's own addresses`() {
        // A public address (TEST-NET-3 stands in), the phone's own on its mobile network.
        val phone = InetAddress.getByName("203.0.113.7")
        val proxy = start(WebProxy(loopback, resolve = { arrayOf(phone) }, ownAddresses = { setOf(phone) }))
        assertTrue(connect(proxy, "CONNECT myself.example:443 HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 403"))
    }

    @Test
    fun `connects to the address it resolved, without a second lookup`() {
        val echo = echoServer()
        var lookups = 0
        // A name only this resolver knows: a second lookup by the socket would fail.
        val proxy = start(WebProxy(loopback, blockLocal = false, resolve = {
            lookups++
            arrayOf(InetAddress.getByAddress("only-here.invalid", byteArrayOf(127, 0, 0, 1)))
        }))
        Socket(loopback, proxy).use { s ->
            s.getOutputStream().write("CONNECT only-here.invalid:$echo HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 200"))
            assertEquals("ping", roundTrip(s, "ping"))
        }
        assertEquals(1, lookups)
    }

    @Test
    fun `limits concurrent connections`() {
        val echo = echoServer(clients = 2)
        val proxy = WebProxy(loopback, blockLocal = false, maxConnections = 1)
        val port = start(proxy)
        val first = Socket(loopback, port).also { closeables += it }
        first.getOutputStream().write("CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n".toByteArray())
        assertTrue(WebProxy.readHead(first.getInputStream())!!.startsWith("HTTP/1.1 200"))
        assertTrue(connect(port, "CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n").startsWith("HTTP/1.1 503"))
        first.close()
        waitFor { proxy.openConnections == 0 }
        Socket(loopback, port).use { s ->
            s.getOutputStream().write("CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 200"))
            assertEquals("pong", roundTrip(s, "pong"))
        }
    }

    @Test
    fun `closes a tunnel idle in both directions`() {
        val echo = echoServer()
        val proxy = WebProxy(loopback, blockLocal = false, idleTimeoutMs = 300)
        val port = start(proxy)
        Socket(loopback, port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 200"))
            // Traffic keeps it open past the idle time.
            repeat(4) {
                Thread.sleep(150)
                assertEquals("tick", roundTrip(s, "tick"))
            }
            val started = System.nanoTime()
            assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 3_000)
        }
        waitFor { proxy.openConnections == 0 }
    }

    @Test
    fun `stop closes open tunnels`() {
        val echo = echoServer()
        val proxy = WebProxy(loopback, blockLocal = false)
        val port = start(proxy)
        Socket(loopback, port).use { s ->
            s.soTimeout = 5_000
            s.getOutputStream().write("CONNECT 127.0.0.1:$echo HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(WebProxy.readHead(s.getInputStream())!!.startsWith("HTTP/1.1 200"))
            proxy.stop()
            assertEquals(-1, runCatching { s.getInputStream().read() }.getOrDefault(-1))
        }
    }

    private fun proxy(blockLocal: Boolean): Int = start(WebProxy(loopback, blockLocal = blockLocal))

    private fun start(proxy: WebProxy): Int {
        closeables += AutoCloseable { proxy.stop() }
        return proxy.start()
    }

    /** One request on a new connection; the head of the answer. */
    private fun connect(port: Int, request: String): String = Socket(loopback, port).use { s ->
        s.soTimeout = 5_000
        s.getOutputStream().write(request.toByteArray())
        WebProxy.readHead(s.getInputStream()).orEmpty()
    }

    private fun roundTrip(s: Socket, text: String): String {
        s.getOutputStream().write(text.toByteArray())
        val buf = ByteArray(text.length)
        var n = 0
        while (n < buf.size) n += s.getInputStream().read(buf, n, buf.size - n).also { check(it > 0) }
        return String(buf)
    }

    private fun waitFor(condition: () -> Boolean) {
        val until = System.currentTimeMillis() + 5_000
        while (!condition() && System.currentTimeMillis() < until) Thread.sleep(20)
        assertTrue(condition())
    }

    private fun echoServer(clients: Int = 1): Int {
        val server = ServerSocket(0, clients, loopback).also { closeables += it }
        thread(isDaemon = true) {
            repeat(clients) {
                val c = runCatching { server.accept() }.getOrNull() ?: return@thread
                thread(isDaemon = true) { c.use { it.getInputStream().copyTo(it.getOutputStream()) } }
            }
        }
        return server.localPort
    }
}
