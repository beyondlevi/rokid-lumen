package dev.lumen.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.Link
import dev.lumen.protocol.SettingsEvent
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Wireless debugging, switched on from the phone: the glasses keep their Wi-Fi on (the phone's
 * internet no longer turns it off afterwards) and awake (a high-performance Wi-Fi lock, so the
 * radio doesn't doze with the screen off), and tell the phone where `adb connect` finds them
 * ([DebugStatus]) whenever that changes: a new network, a new address, the port opening or
 * closing. adb itself listens on [DebugStatus.DEFAULT_PORT] since the self-arm; nothing here
 * opens it.
 */
object WirelessDebug {
    private const val TAG = "BandWirelessDebug"
    private const val PREFS = "lumen_wireless_debug"
    private const val KEY_ENABLED = "enabled"
    /** Looked at again this often while on, for what no callback reports (the port, say). */
    private const val CHECK_MS = 60_000L
    /** A network callback burst (lost, available, link change) settles first. */
    private const val SETTLE_MS = 1_500L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-wireless-debug").apply { isDaemon = true } }
    private var context: Context? = null
    private var lock: WifiManager.WifiLock? = null
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile
    var current = DebugStatus()
        private set

    @JvmStatic
    fun isEnabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

    /** Once per process, from [PhoneLink]: resumes what the phone switched on before. */
    @JvmStatic
    fun start(context: Context) {
        if (this.context != null) return
        this.context = context.applicationContext
        apply(context.applicationContext)
    }

    /** From the phone ([BandSettings]). */
    @JvmStatic
    fun setEnabled(context: Context, on: Boolean) {
        val ctx = context.applicationContext
        this.context = ctx
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        // The schema the phone gets back right away says so; the rest follows from check().
        current = if (on) current.copy(enabled = true) else DebugStatus()
        Log.d(TAG, "wireless debugging ${if (on) "on" else "off"}")
        apply(ctx)
    }

    private fun apply(ctx: Context) {
        val on = isEnabled(ctx)
        main.removeCallbacks(periodic)
        if (on) {
            holdWifi(ctx)
            watchNetworks(ctx)
            io.execute { turnWifiOn(ctx) }
            main.postDelayed(periodic, CHECK_MS)
        } else {
            lock?.takeIf { it.isHeld }?.release()
            lock = null
            callback?.let { runCatching { ctx.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(it) } }
            callback = null
        }
        check()
    }

    private val periodic: Runnable = Runnable {
        check()
        main.postDelayed(periodic, CHECK_MS)
    }

    private val settled = Runnable { check() }

    /** Re-reads the state off the main thread and tells the phone if it changed. */
    @JvmStatic
    fun check() {
        val ctx = context ?: return
        io.execute {
            val now = read(ctx)
            main.post {
                if (now == current) return@post
                current = now
                Log.d(TAG, "status ${now.toJson()}")
                PhoneLink.send(Link.SETTINGS_EVENT, SettingsEvent.Debug(now).toJson())
            }
        }
    }

    private fun read(ctx: Context): DebugStatus {
        if (!isEnabled(ctx)) return DebugStatus()
        val wifi = ctx.getSystemService(WifiManager::class.java)
        val address = wifiAddress()
        val ssid = if (address.isEmpty()) "" else ssid(ctx, wifi)
        val hotspot = PhoneInternet.phoneHotspot
        return DebugStatus(
            enabled = true,
            wifiOn = wifi?.isWifiEnabled == true,
            ssid = ssid,
            address = address,
            listening = address.isNotEmpty() && listens(address, DebugStatus.DEFAULT_PORT),
            // The network it is on now: the platform can leave the hotspot (no internet) for a saved
            // network before PhoneInternet notices.
            onPhoneHotspot = hotspot != null && (ssid.isEmpty() || ssid == hotspot),
        )
    }

    /** The radio stays up with the screen off; held while this is on. */
    private fun holdWifi(ctx: Context) {
        if (lock?.isHeld == true) return
        val wifi = ctx.getSystemService(WifiManager::class.java) ?: return
        @Suppress("DEPRECATION")
        lock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "lumen:wireless-debug").apply {
            setReferenceCounted(false)
            runCatching { acquire() }.onFailure { Log.w(TAG, "no Wi-Fi lock: ${it.message}") }
        }
    }

    private fun watchNetworks(ctx: Context) {
        if (callback != null) return
        val manager = ctx.getSystemService(ConnectivityManager::class.java) ?: return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            override fun onLinkPropertiesChanged(network: Network, linkProperties: android.net.LinkProperties) = changed()

            private fun changed() {
                main.removeCallbacks(settled)
                main.postDelayed(settled, SETTLE_MS)
            }
        }
        runCatching {
            manager.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), cb)
            callback = cb
        }.onFailure { Log.w(TAG, "no network callback: ${it.message}") }
    }

    /** Wi-Fi on when it's off: the self-arm's shell, else the shortcut bridge. Off the main thread. */
    private fun turnWifiOn(ctx: Context) {
        val wifi = ctx.getSystemService(WifiManager::class.java) ?: return
        if (wifi.isWifiEnabled) return
        val shell = runCatching { SelfArmController.runShell(ctx, "svc wifi enable") }.getOrNull()
        if (shell == null) PrivilegedShortcutBridge.requestWifiEnabled(ctx, true)
        Log.d(TAG, "turned the Wi-Fi on (${if (shell != null) "shell" else "bridge"})")
    }

    /** The IPv4 address of the Wi-Fi interface (wlan0), or "". */
    @JvmStatic
    fun wifiAddress(): String = runCatching {
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && it.name.startsWith("wlan") }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }.getOrNull().orEmpty()

    /** The network's name: the platform's (needs location), else `cmd wifi status` as the shell. */
    private fun ssid(ctx: Context, wifi: WifiManager?): String {
        @Suppress("DEPRECATION")
        val own = wifi?.connectionInfo?.ssid?.removeSurrounding("\"")
        if (!own.isNullOrEmpty() && own != "<unknown ssid>") return own
        val status = runCatching { SelfArmController.runShell(ctx, "cmd wifi status") }.getOrNull() ?: return ""
        return parseSsid(status)
    }

    /** The SSID in `cmd wifi status` ("Wifi is connected to "Home""), or "". */
    @JvmStatic
    fun parseSsid(status: String): String =
        Regex("connected to \"([^\"]*)\"").find(status)?.groupValues?.get(1).orEmpty()

    private fun listens(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 800) }
        true
    }.getOrDefault(false)
}
