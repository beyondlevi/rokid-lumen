package dev.lumen.companion

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicLong

/**
 * The glasses' way out to the internet: an HTTP proxy the glasses' web engines use while they
 * are on the phone's local-only hotspot, which has no internet of its own. `CONNECT` tunnels
 * HTTPS (TLS stays end to end); plain `http://` requests are forwarded with `Connection: close`.
 *
 * It listens only on the hotspot address, and goes to the internet only: a destination is
 * resolved once, every address it resolves to is checked ([isSpecial]: private, loopback,
 * link-local, carrier NAT, multicast and the like, plus every address of the phone's own
 * interfaces), and the connection goes to a checked address, never to a second lookup (DNS
 * rebinding). At most [maxConnections] clients at a time; a tunnel with no traffic either way
 * for [idleTimeoutMs] is closed, and [stop] closes every open one.
 *
 * Besides proxying, it answers a plain `GET <path>` addressed to itself with the file [local]
 * gives for that path (an offline package handed over to the glasses), 404 otherwise.
 */
class WebProxy(
    private val bind: InetAddress,
    private val port: Int = 0,
    private val connectTimeoutMs: Int = 10_000,
    private val resolve: (String) -> Array<InetAddress> = { InetAddress.getAllByName(it) },
    /** Only tests turn this off, to reach an origin server on loopback. */
    private val blockLocal: Boolean = true,
    /** The phone's own addresses, refused as destinations. */
    private val ownAddresses: () -> Set<InetAddress> = ::interfaceAddresses,
    private val maxConnections: Int = MAX_CONNECTIONS,
    private val idleTimeoutMs: Long = IDLE_TIMEOUT_MS,
    /** A file this phone serves itself, by request path; null for none (404). */
    private val local: (String) -> File? = { null },
) {
    private var server: ServerSocket? = null
    private var pool: ExecutorService? = null
    /** Every socket of a client being served and of its destination, for [stop]. */
    private val open = ConcurrentHashMap.newKeySet<Socket>()
    private val slots = Semaphore(maxConnections)
    @Volatile private var own: Pair<Long, Set<InetAddress>>? = null

    /** The bound port; valid after [start]. */
    val localPort: Int get() = server?.localPort ?: -1

    /** Client connections being served now (for tests). */
    val openConnections: Int get() = maxConnections - slots.availablePermits()

    @Synchronized
    fun start(): Int {
        server?.let { return it.localPort }
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bind, port), 32)
        }
        val workers = Executors.newCachedThreadPool { r -> Thread(r, "nb-proxy").apply { isDaemon = true } }
        server = socket
        pool = workers
        workers.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                if (!slots.tryAcquire()) {
                    log("busy: $maxConnections connections open")
                    runCatching { reply(client, client.getOutputStream(), "503 Service Unavailable") }
                    runCatching { client.close() }
                    continue
                }
                open += client
                val served = runCatching {
                    workers.execute {
                        try {
                            serve(client, workers)
                        } finally {
                            forget(client)
                            slots.release()
                        }
                    }
                }
                if (served.isFailure) {
                    forget(client)
                    slots.release()
                }
            }
        }
        log("listening on ${bind.hostAddress}:${socket.localPort}")
        return socket.localPort
    }

    /** Stops listening and closes every open tunnel and client. */
    @Synchronized
    fun stop() {
        runCatching { server?.close() }
        open.toList().forEach { runCatching { it.close() } }
        open.clear()
        pool?.shutdownNow()
        server = null
        pool = null
    }

    private fun forget(socket: Socket) {
        runCatching { socket.close() }
        open -= socket
    }

    private fun serve(client: Socket, workers: ExecutorService) {
        client.soTimeout = 30_000
        val input = client.getInputStream()
        val output = client.getOutputStream()
        val head = runCatching { readHead(input) }.getOrNull() ?: return
        localPath(head)?.let { path ->
            serveLocal(client, output, path)
            return
        }
        val request = parse(head)
        if (request == null) {
            reply(client, output, "400 Bad Request")
            return
        }
        val upstream = runCatching { open(request.host, request.port) }.getOrElse {
            log("refused ${request.host}:${request.port}: ${it.message}")
            reply(client, output, if (it is SecurityException) "403 Forbidden" else "502 Bad Gateway")
            return
        }
        open += upstream
        // A client that went away (or [stop]) while connecting.
        if (client.isClosed) return forget(upstream)
        try {
            val tick = minOf(idleTimeoutMs, 30_000L).toInt().coerceAtLeast(1)
            client.soTimeout = tick
            upstream.soTimeout = tick
            if (request.method == "CONNECT") {
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            } else {
                upstream.getOutputStream().apply {
                    write(request.forwardHead().toByteArray(Charsets.ISO_8859_1))
                    flush()
                }
            }
            val lastActivity = AtomicLong(System.nanoTime())
            workers.execute { pump(input, upstream.getOutputStream(), upstream, lastActivity) }
            pump(upstream.getInputStream(), output, client, lastActivity)
        } catch (_: Exception) {
        } finally {
            runCatching { client.close() }
            forget(upstream)
        }
    }

    /** A file of [local]'s, whole, then the connection closes. */
    private fun serveLocal(client: Socket, output: OutputStream, path: String) {
        val file = runCatching { local(path) }.getOrNull()?.takeIf { it.isFile }
        if (file == null) {
            log("no local file for a request")
            return reply(client, output, "404 Not Found")
        }
        runCatching {
            output.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${file.length()}\r\n" +
                    "Cache-Control: no-store\r\nConnection: close\r\n\r\n").toByteArray(Charsets.ISO_8859_1),
            )
            file.inputStream().use { it.copyTo(output, 16 * 1024) }
            output.flush()
        }.onFailure { log("serving a local file failed: ${it.message}") }
        runCatching { client.close() }
    }

    private fun open(host: String, port: Int): Socket {
        val addresses = resolve(host)
        if (addresses.isEmpty()) throw IllegalStateException("no address")
        if (blockLocal) {
            val own = ownNow()
            addresses.firstOrNull { !isAllowed(it, own) }?.let { throw SecurityException("private or local destination") }
        }
        // The addresses checked above, never a new lookup of the name.
        var failure: Exception? = null
        for (address in addresses) {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(address, port), connectTimeoutMs)
                return socket
            } catch (e: Exception) {
                runCatching { socket.close() }
                failure = e
            }
        }
        throw failure ?: IllegalStateException("no address")
    }

    /** The phone's interfaces' addresses, enumerated at most every few seconds. */
    private fun ownNow(): Set<InetAddress> {
        val now = System.nanoTime()
        own?.let { (at, addresses) -> if (now - at < OWN_TTL_NS) return addresses }
        return runCatching { ownAddresses() }.getOrDefault(emptySet()).also { own = now to it }
    }

    /** Destinations on the phone itself, on the hotspot or on any private network are off limits. */
    private fun isAllowed(address: InetAddress, own: Set<InetAddress>): Boolean =
        !isSpecial(address) && address !in own &&
            !(address.address.size == bind.address.size && sameSubnet24(address, bind))

    private fun reply(client: Socket, output: OutputStream, status: String) {
        runCatching {
            output.write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
        runCatching { client.close() }
    }

    /**
     * Copies until the end of [from], then closes [closeWhenDone] (which ends the other
     * direction too). Reads wake up every tick; the tunnel ends once neither direction moved
     * for [idleTimeoutMs].
     */
    private fun pump(from: InputStream, to: OutputStream, closeWhenDone: Socket, lastActivity: AtomicLong) {
        val buffer = ByteArray(16 * 1024)
        val idleNs = idleTimeoutMs * 1_000_000
        try {
            while (true) {
                val n = try {
                    from.read(buffer)
                } catch (_: SocketTimeoutException) {
                    if (System.nanoTime() - lastActivity.get() >= idleNs) {
                        log("closing an idle tunnel")
                        break
                    }
                    continue
                }
                if (n < 0) break
                lastActivity.set(System.nanoTime())
                to.write(buffer, 0, n)
                to.flush()
            }
        } catch (_: Exception) {
        } finally {
            runCatching { closeWhenDone.shutdownOutput() }
            runCatching { closeWhenDone.close() }
        }
    }

    private fun log(line: String) {
        runCatching { Log.d(TAG, line) }
    }

    data class Request(val method: String, val host: String, val port: Int, val path: String, val version: String, val headers: List<String>) {
        /** The request as the origin server expects it: origin-form, no proxy headers, one exchange. */
        fun forwardHead(): String = buildString {
            append("$method $path $version\r\n")
            headers.filterNot {
                val name = it.substringBefore(':').trim().lowercase()
                name.startsWith("proxy-") || name == "connection" || name == "keep-alive"
            }.forEach { append(it).append("\r\n") }
            append("Connection: close\r\n\r\n")
        }
    }

    companion object {
        private const val TAG = "NbProxy"
        private const val MAX_HEAD = 64 * 1024
        /** Clients served at once: a page opens a handful; beyond this, 503. */
        const val MAX_CONNECTIONS = 64
        /** A tunnel with no traffic either way for this long is closed. */
        const val IDLE_TIMEOUT_MS = 5 * 60_000L
        private const val OWN_TTL_NS = 5_000_000_000L

        /** Reads up to the blank line that ends the request head; null if the client hung up. */
        fun readHead(input: InputStream): String? {
            val out = ByteArrayOutputStream()
            var last4 = 0
            while (out.size() < MAX_HEAD) {
                val b = input.read()
                if (b < 0) return null
                out.write(b)
                last4 = (last4 shl 8) or b
                if (last4 == 0x0d0a0d0a) return out.toString(Charsets.ISO_8859_1.name())
            }
            return null
        }

        /** The path of a `GET /…` (origin-form: addressed to this server, not proxied), or null. */
        fun localPath(head: String): String? {
            val parts = head.substringBefore("\r\n").split(' ')
            if (parts.size != 3 || parts[0] != "GET" || !parts[1].startsWith("/")) return null
            return parts[1]
        }

        fun parse(head: String): Request? {
            val lines = head.split("\r\n").filter { it.isNotEmpty() }
            val parts = lines.firstOrNull()?.split(' ') ?: return null
            if (parts.size != 3) return null
            val (method, target, version) = parts
            if (method == "CONNECT") {
                val host = target.substringBeforeLast(':').removePrefix("[").removeSuffix("]")
                val port = target.substringAfterLast(':', "").toIntOrNull() ?: return null
                if (host.isEmpty()) return null
                return Request(method, host, port, target, version, lines.drop(1))
            }
            if (!target.startsWith("http://", ignoreCase = true)) return null
            val rest = target.substring(7)
            val authority = rest.substringBefore('/')
            val path = "/" + rest.substringAfter('/', "")
            val host = authority.substringBeforeLast(':').takeIf { authority.contains(':') } ?: authority
            val port = if (authority.contains(':')) authority.substringAfterLast(':').toIntOrNull() ?: return null else 80
            if (host.isEmpty()) return null
            return Request(method, host, port, path, version, lines.drop(1))
        }

        /**
         * Addresses that aren't the public internet: IPv4 0/8, 10/8, 100.64/10 (carrier NAT),
         * 127/8, 169.254/16, 172.16/12, 192.168/16, 224/4 (multicast) and everything above it;
         * IPv6 ::, ::1, fc00::/7, fe80::/10 (and the old fec0::/10), ff00::/8, and IPv4-mapped,
         * IPv4-compatible or NAT64 (64:ff9b::/96) forms of the IPv4 ones.
         */
        @JvmStatic
        fun isSpecial(address: InetAddress): Boolean {
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
            ) {
                return true
            }
            val bytes = address.address
            return when (bytes.size) {
                4 -> isSpecialV4(bytes)
                16 -> isSpecialV6(bytes)
                else -> true
            }
        }

        private fun isSpecialV4(b: ByteArray): Boolean {
            val a = b[0].toInt() and 0xff
            val c = b[1].toInt() and 0xff
            return a == 0 || a == 10 || a == 127 ||
                (a == 100 && c in 64..127) ||
                (a == 169 && c == 254) ||
                (a == 172 && c in 16..31) ||
                (a == 192 && c == 168) ||
                a >= 224
        }

        private fun isSpecialV6(b: ByteArray): Boolean {
            val first = b[0].toInt() and 0xff
            val second = b[1].toInt() and 0xff
            if ((0 until 15).all { b[it] == 0.toByte() } && (b[15] == 0.toByte() || b[15] == 1.toByte())) return true
            if ((first and 0xfe) == 0xfc) return true
            if (first == 0xfe && (second and 0x80) == 0x80) return true
            if (first == 0xff) return true
            val zeroPrefix = (0 until 10).all { b[it] == 0.toByte() }
            val mapped = zeroPrefix && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
            val compatible = zeroPrefix && b[10] == 0.toByte() && b[11] == 0.toByte()
            val nat64 = first == 0 && second == 0x64 && b[2] == 0xff.toByte() && b[3] == 0x9b.toByte() && (4 until 12).all { b[it] == 0.toByte() }
            return (mapped || compatible || nat64) && isSpecialV4(b.copyOfRange(12, 16))
        }

        /** Every address of the phone's interfaces (Wi-Fi, mobile data, hotspot, VPN, loopback). */
        @JvmStatic
        fun interfaceAddresses(): Set<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().flatMap { it.inetAddresses.toList() }.toSet()
        }.getOrDefault(emptySet())

        private fun sameSubnet24(a: InetAddress, b: InetAddress): Boolean {
            val x = a.address
            val y = b.address
            return x.size == 4 && x[0] == y[0] && x[1] == y[1] && x[2] == y[2]
        }
    }
}
