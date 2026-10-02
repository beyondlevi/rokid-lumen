package dev.lumen.companion

import android.content.Context
import dev.lumen.protocol.NetEvent
import android.content.pm.ApplicationInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/**
 * The glasses' internet through this phone: a local-only hotspot (which any app may open, unlike
 * tethering) plus [WebProxy] on its address, which goes out over the phone's own connection.
 * The glasses ask for it while an online web app is open and renew the request every minute; a
 * request that isn't renewed within [LEASE_MS] closes everything, so a lost "down" can't leave
 * the hotspot on.
 */
class PhoneNetwork(private val context: Context, private val onEvent: (NetEvent) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val wifi = context.applicationContext.getSystemService(WifiManager::class.java)
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var proxy: WebProxy? = null
    private var ready: NetEvent.Ready? = null
    private var starting = false

    /** [leaseMs]: how long without another up() before closing (debug builds may ask for longer). */
    fun up(leaseMs: Long = LEASE_MS) {
        main.removeCallbacks(expire)
        main.postDelayed(expire, leaseMs)
        ready?.let { return onEvent(it) }
        if (starting) return
        starting = true
        val before = ipv4Addresses()
        Log.d(TAG, "starting local-only hotspot")
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(r: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = r
                    awaitAddress(r, before, attempt = 0)
                }

                override fun onStopped() {
                    Log.d(TAG, "hotspot stopped by the system")
                    close(NetEvent.Down(ready?.ssid ?: ""))
                }

                override fun onFailed(reason: Int) {
                    Log.w(TAG, "hotspot failed: $reason")
                    close(NetEvent.Failed(failure(reason)))
                }
            }, main)
        } catch (e: SecurityException) {
            close(NetEvent.Failed(context.getString(R.string.net_error_nearby)))
        } catch (e: Exception) {
            close(NetEvent.Failed(context.getString(R.string.net_error_unavailable, e.message.orEmpty())))
        }
    }

    fun down() = close(NetEvent.Down(ready?.ssid ?: ""))

    private val expire = Runnable {
        Log.d(TAG, "lease expired")
        close(NetEvent.Down(ready?.ssid ?: ""))
    }

    /** The hotspot's address shows up on its interface a moment after onStarted. */
    private fun awaitAddress(r: WifiManager.LocalOnlyHotspotReservation, before: Set<InetAddress>, attempt: Int) {
        if (reservation !== r) return
        val address = (ipv4Addresses() - before).firstOrNull() ?: hotspotInterfaceAddress()
        if (address == null) {
            if (attempt < 30) main.postDelayed({ awaitAddress(r, before, attempt + 1) }, 100)
            else close(NetEvent.Failed(context.getString(R.string.net_error_no_address)))
            return
        }
        val config = r.softApConfiguration
        val ssid = if (Build.VERSION.SDK_INT >= 33) config.wifiSsid?.toString()?.removeSurrounding("\"") else @Suppress("DEPRECATION") config.ssid
        val passphrase = config.passphrase
        if (ssid.isNullOrEmpty() || passphrase.isNullOrEmpty()) {
            close(NetEvent.Failed(context.getString(R.string.net_error_no_password)))
            return
        }
        val server = WebProxy(address, local = PackageShare::file)
        val port = runCatching { server.start() }.getOrElse {
            close(NetEvent.Failed(context.getString(R.string.net_error_proxy, it.message.orEmpty())))
            return
        }
        proxy = server
        starting = false
        Log.d(TAG, "ready: $ssid ${address.hostAddress}:$port")
        ready = NetEvent.Ready(ssid, passphrase, address.hostAddress!!, port).also(onEvent)
        // Debug builds: the credentials for `adb shell run-as … cat files/debug-hotspot.txt`, to join
        // by hand (glasses setup before the self-arm). Private storage, never the log.
        if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            runCatching { debugFile().writeText("$ssid\n$passphrase\n") }
        }
    }

    private fun debugFile() = File(context.filesDir, "debug-hotspot.txt")

    private fun close(event: NetEvent) {
        debugFile().delete()
        main.removeCallbacks(expire)
        val wasActive = starting || ready != null || reservation != null
        starting = false
        ready = null
        proxy?.stop()
        proxy = null
        runCatching { reservation?.close() }
        reservation = null
        if (wasActive || event is NetEvent.Failed) onEvent(event)
    }

    private fun failure(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> context.getString(R.string.net_error_incompatible)
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> context.getString(R.string.net_error_disallowed)
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> context.getString(R.string.net_error_no_channel)
        else -> context.getString(R.string.net_error_failed, reason)
    }

    companion object {
        private const val TAG = "NbNetwork"
        const val LEASE_MS = 3 * 60_000L

        private fun ipv4Addresses(): Set<InetAddress> = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .toSet()
        }.getOrDefault(emptySet())

        private fun hotspotInterfaceAddress(): InetAddress? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && (it.name.startsWith("swlan") || it.name.startsWith("ap") || it.name.startsWith("wlan1")) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address }
        }.getOrNull()
    }
}
