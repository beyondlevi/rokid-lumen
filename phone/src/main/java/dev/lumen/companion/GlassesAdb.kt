package dev.lumen.companion

import android.content.Context
import android.util.Log
import com.flyfishxu.kadb.Kadb
import com.flyfishxu.kadb.cert.KadbCert
import com.flyfishxu.kadb.cert.KadbCertPolicy
import com.flyfishxu.kadb.cert.OkioFilePrivateKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import java.io.File
import java.io.IOException

/**
 * This phone as an adb client of the glasses (their screen, [GlassesMirror]), on their Wi-Fi
 * address and fixed port. The glasses never show the "allow debugging" prompt, so the first
 * connection pairs this app's key the way a computer does: a code chosen here, a pairing window
 * the glasses open for it ([GlassesDebug.pair]), then Kadb's pairing. The key is the app's own,
 * kept in its files.
 */
object GlassesAdb {
    private const val TAG = "NbGlassesAdb"
    private const val NAME = "Lumen companion"
    private const val CONNECT_TIMEOUT_MS = 4_000
    private const val SOCKET_TIMEOUT_MS = 8_000
    private const val WINDOW_WAIT_MS = 20_000L
    private const val PAIR_TIMEOUT_MS = 15_000L
    private var configured = false

    /** Why the glasses can't be reached; [GlassesMirror] shows it. */
    class Unreachable(val reason: Reason, message: String) : IOException(message)

    enum class Reason { NO_WIFI, NOT_REACHABLE, NOT_PAIRED }

    /** A connection to the glasses, pairing first when this key isn't trusted yet. Blocking. */
    fun connect(context: Context, socketTimeoutMs: Int = SOCKET_TIMEOUT_MS): Kadb {
        val debug = BandStore.debug
        if (!debug.enabled || debug.address.isEmpty()) throw Unreachable(Reason.NO_WIFI, "the glasses aren't on Wi-Fi")
        configure(context)
        open(debug.address, debug.port, socketTimeoutMs)?.let { return it }
        if (!reachable(debug.address, debug.port)) throw Unreachable(Reason.NOT_REACHABLE, "${debug.address} is out of this phone's reach")
        pair(debug.address)
        return open(debug.address, debug.port, socketTimeoutMs) ?: throw Unreachable(Reason.NOT_PAIRED, "paired but still refused")
    }

    private fun open(host: String, port: Int, socketTimeoutMs: Int): Kadb? {
        val kadb = Kadb(host, port, CONNECT_TIMEOUT_MS, socketTimeoutMs)
        return try {
            if (kadb.shell("echo lumen").output.trim() == "lumen") kadb else null.also { kadb.close() }
        } catch (e: Exception) {
            // An untrusted key: adbd waits for a prompt these glasses never show, until the timeout.
            Log.d(TAG, "adb on $host:$port refused: ${e.javaClass.simpleName}")
            runCatching { kadb.close() }
            null
        }
    }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress(host, port), CONNECT_TIMEOUT_MS) }
        true
    }.getOrDefault(false)

    private fun pair(host: String) {
        val code = PairingCode.next()
        if (!GlassesDebug.pair(code)) throw Unreachable(Reason.NOT_PAIRED, "no link to the glasses")
        try {
            val until = System.currentTimeMillis() + WINDOW_WAIT_MS
            var port = 0
            while (port == 0 && System.currentTimeMillis() < until) {
                Thread.sleep(250)
                port = BandStore.debug.pairingPort
                if (BandStore.debug.pairingError.isNotEmpty()) break
            }
            if (port == 0) throw Unreachable(Reason.NOT_PAIRED, "no pairing window (${BandStore.debug.pairingError})")
            runBlocking { withTimeout(PAIR_TIMEOUT_MS) { Kadb.pair(host, port, code, NAME) } }
            Log.d(TAG, "paired with the glasses")
        } finally {
            GlassesDebug.stopPairing()
        }
    }

    private fun configure(context: Context) = synchronized(this) {
        if (configured) return
        val key = File(File(context.filesDir, "kadb"), "adbkey")
        key.parentFile?.mkdirs()
        KadbCert.configure(OkioFilePrivateKeyStore(okioPath(key.absolutePath), FileSystem.SYSTEM), KadbCertPolicy(), emptyList())
        configured = true
    }

    // Okio's Path.get(String) is a JVM static the Kotlin extension hides; called as the glasses' self-arm does.
    private fun okioPath(value: String): Path = Path::class.java.getMethod("get", String::class.java).invoke(null, value) as Path
}
