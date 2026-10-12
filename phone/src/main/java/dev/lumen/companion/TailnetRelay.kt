package dev.lumen.companion

import android.util.Log
import dev.lumen.protocol.DebugStatus
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections

/**
 * The glasses' adb, reachable from anywhere through this phone: while "Also through Tailscale"
 * is on, the phone listens on its Tailscale address only (never on Wi-Fi or mobile data) and
 * forwards each connection to the glasses, [ADB_PORT] to their adb and, while a pairing window is
 * open, [PAIRING_PORT] to it. A computer on the same tailnet then runs `adb connect` (and
 * `adb pair` once) with this phone's Tailscale address.
 */
object TailnetRelay {
    private const val TAG = "NbTailnet"
    const val ADB_PORT = 5556
    const val PAIRING_PORT = 5557
    private const val CONNECT_TIMEOUT_MS = 5_000

    /** [address]: this phone's Tailscale IPv4 (null: Tailscale is off); [error] a port that wouldn't open. */
    data class State(val address: String? = null, val adb: Boolean = false, val pairing: Boolean = false, val error: Boolean = false)

    @Volatile
    var state = State()
        private set

    private var adb: Forward? = null
    private var pairing: Forward? = null

    /** Opens, retargets or closes the forwards for the switch and the glasses' state. */
    @Synchronized
    fun update(wanted: Boolean, debug: DebugStatus) {
        val address = if (wanted) TailnetAddress.current() else null
        val bind = address?.let { runCatching { InetAddress.getByName(it) }.getOrNull() }
        var failed = false
        adb = retarget(adb, bind.takeIf { debug.reachableFromPhone }, ADB_PORT, debug.address, debug.port) { failed = true }
        pairing = retarget(pairing, bind.takeIf { debug.reachableFromPhone && debug.pairingPort > 0 }, PAIRING_PORT, debug.address, debug.pairingPort) { failed = true }
        val next = State(address, adb != null, pairing != null, failed)
        if (next != state) {
            state = next
            Log.d(TAG, "relay $next")
        }
    }

    @Synchronized
    fun stop() = update(false, DebugStatus())

    private fun retarget(current: Forward?, bind: InetAddress?, port: Int, host: String, targetPort: Int, onError: () -> Unit): Forward? {
        if (bind == null) {
            current?.close()
            return null
        }
        if (current != null && current.bind == bind && current.host == host && current.targetPort == targetPort && current.alive) return current
        current?.close()
        return runCatching { Forward(bind, port, host, targetPort) }
            .onFailure {
                Log.w(TAG, "couldn't listen on $port: ${it.message}")
                onError()
            }
            .getOrNull()
    }

    /** One listening port and its connections, each pumped both ways on its own threads. */
    private class Forward(val bind: InetAddress, port: Int, val host: String, val targetPort: Int) {
        private val server = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bind, port), 8)
        }
        private val open = Collections.synchronizedSet(HashSet<Socket>())
        @Volatile var alive = true
            private set

        init {
            Thread({ accept() }, "nb-relay-$port").apply { isDaemon = true }.start()
        }

        private fun accept() {
            while (alive) {
                val client = runCatching { server.accept() }.getOrNull() ?: break
                Thread({ serve(client) }, "nb-relay-conn").apply { isDaemon = true }.start()
            }
            alive = false
        }

        private fun serve(client: Socket) {
            val upstream = Socket()
            try {
                upstream.connect(InetSocketAddress(host, targetPort), CONNECT_TIMEOUT_MS)
            } catch (e: Exception) {
                Log.w(TAG, "glasses unreachable at $host:$targetPort: ${e.message}")
                runCatching { client.close() }
                return
            }
            client.tcpNoDelay = true
            upstream.tcpNoDelay = true
            open += client
            open += upstream
            val back = Thread({ pump(upstream.getInputStream(), client.getOutputStream(), client, upstream) }, "nb-relay-back")
            back.isDaemon = true
            back.start()
            pump(client.getInputStream(), upstream.getOutputStream(), client, upstream)
        }

        private fun pump(input: InputStream, output: OutputStream, a: Socket, b: Socket) {
            val buffer = ByteArray(16 * 1024)
            try {
                while (true) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    output.write(buffer, 0, n)
                    output.flush()
                }
            } catch (_: Exception) {
                // One side closed: both go.
            } finally {
                runCatching { a.close() }
                runCatching { b.close() }
                open -= a
                open -= b
            }
        }

        fun close() {
            alive = false
            runCatching { server.close() }
            synchronized(open) { open.toList() }.forEach { runCatching { it.close() } }
            open.clear()
        }
    }
}

/** This phone's address on Tailscale: an IPv4 in 100.64.0.0/10 (CGNAT space, where Tailscale assigns them). */
object TailnetAddress {
    fun current(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
            .firstOrNull(::isTailnet)
    }.getOrNull()

    @JvmStatic
    fun isTailnet(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return parts[0] == 100 && parts[1] in 64..127
    }
}
