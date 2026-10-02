package dev.lumen.glasses

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.Link
import dev.lumen.protocol.NetCommand
import dev.lumen.protocol.NetEvent
import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors

/**
 * Internet for online web apps, in tiers: a network that already has internet (a saved Wi-Fi in
 * range) first; otherwise the phone's: the companion opens a local-only hotspot and an HTTP proxy
 * ([Link.NET]), the glasses join it as the shell user (a normal app can't join a given
 * network silently) and the web engines use the proxy. Web apps hold it while open; the last
 * release, after [GRACE_MS], leaves the phone's network and restores the Wi-Fi as it was.
 *
 * Measured (Samsung, Android 17 → glasses on the phone's hotspot): hotspot up in 1.2 s with the
 * phone's own Wi-Fi kept, glasses joined in ~3 s, ~2.9 MB/s through the proxy.
 */
object PhoneInternet {
    /** How a web app reaches the internet; `proxy` is `host:port`, or null for direct. */
    interface Listener {
        fun onStatus(text: String)
        fun onReady(proxy: String?)
        fun onFailed(text: String)
    }

    private enum class State { IDLE, KNOWN, ASKING, JOINING, READY, FAILED }

    private const val TAG = "BandInternet"
    const val GRACE_MS = 30_000L
    private const val KNOWN_WAIT_MS = 8_000L
    private const val SCAN_SETTLE_MS = 1_000L
    /** Long: right after the glasses boot, Rokid's link took 33 s to deliver the phone's answer (measured). */
    private const val PHONE_WAIT_MS = 45_000L
    private const val JOIN_WAIT_MS = 25_000L
    private const val RENEW_MS = 60_000L
    private const val HEALTH_MS = 15_000L
    private const val REASK_MS = 10_000L
    private const val WIFI_ON_WAIT_MS = 6_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-internet").apply { isDaemon = true } }
    private val listeners = LinkedHashSet<Listener>()
    private var context: Context? = null
    @Volatile private var state = State.IDLE
    private var holders = 0
    private var wifiWasOn = true
    private var joinedSsid: String? = null

    /** A text in the glasses' language (empty before the first acquire, which can't happen here). */
    private fun text(id: Int): String = context?.getString(id).orEmpty()
    /** The phone was asked (in parallel with the saved-network wait); its answer, if early. */
    private var phoneRequested = false
    private var phoneOffer: NetEvent.Ready? = null
    private var phoneFailure: String? = null

    /** The proxy the web engines use now (`host:port`), or null to go direct. */
    @JvmStatic
    @Volatile
    var proxy: String? = null
        private set


    /** Debug builds (WebAppActivity's `force_phone` extra): skip the saved networks, test the phone's. */
    @JvmStatic
    var forcePhone = false

    /**
     * [viaPhone]: this holder needs the phone's network itself, not just the internet (a package
     * handed over from the phone, served on its hotspot): saved networks are skipped. While
     * other apps hold a direct connection it fails instead of moving them.
     */
    @JvmStatic
    @JvmOverloads
    fun acquire(context: Context, listener: Listener, viaPhone: Boolean = false) {
        this.context = context.applicationContext
        if (viaPhone && state == State.READY && proxy == null) {
            listener.onFailed(text(R.string.net_phone_busy))
            return
        }
        holders++
        listeners += listener
        main.removeCallbacks(teardown)
        when (state) {
            State.READY -> listener.onReady(proxy)
            State.IDLE, State.FAILED -> start(viaPhone = viaPhone)
            // A new app joining a wait already under way gets the full wait, not what is left of
            // it (measured: an open late in the wait failed 2 s later), and the phone is asked again.
            State.ASKING -> {
                PhoneLink.send(Link.NET, NetCommand.UP.toJson())
                armPhoneTimeout()
            }
            else -> Unit
        }
    }

    @JvmStatic
    fun release(listener: Listener) {
        if (!listeners.remove(listener)) return
        holders = (holders - 1).coerceAtLeast(0)
        if (holders == 0) main.postDelayed(teardown, GRACE_MS)
    }

    /** Whether the glasses reach the internet directly (no proxy needed). */
    @JvmStatic
    fun hasDirectInternet(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val caps = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** [fresh]: a new acquire (not a reconnect), so the Wi-Fi's state now is the one to restore. */
    private fun start(fresh: Boolean = true, viaPhone: Boolean = false) {
        val ctx = context ?: return
        phoneRequested = false
        phoneOffer = null
        phoneFailure = null
        if (forcePhone) {
            state = State.KNOWN
            return askPhone()
        }
        if (viaPhone) {
            // The phone's network or nothing: Wi-Fi on (it may be off), then straight to the phone.
            state = State.KNOWN
            val wifi = ctx.getSystemService(WifiManager::class.java)
            turnWifiOn(ctx, fresh)
            return waitFor(WIFI_ON_WAIT_MS, { wifi?.isWifiEnabled == true }, State.KNOWN, onDone = { askPhone() }, onTimeout = { askPhone() })
        }
        if (hasDirectInternet(ctx)) return ready(null)
        state = State.KNOWN
        turnWifiOn(ctx, fresh)
        status(text(R.string.net_known))
        // Ask the phone now, not after the wait: its hotspot is ready by the time the scan
        // rules out the saved networks (measured: 4.5 s saved on a 13 s open). A saved network
        // that connects first sends it back down.
        requestPhone()
        waitForKnown(ctx)
    }

    /**
     * Up to [KNOWN_WAIT_MS] for a saved network to connect; from [SCAN_SETTLE_MS] on, if the
     * scan shows none of the saved networks in range, asks the phone at once (measured: the full
     * wait was half of a 17.5 s open with no saved network around).
     */
    private fun waitForKnown(ctx: Context) {
        val start = System.currentTimeMillis()
        io.execute {
            while (System.currentTimeMillis() - start < KNOWN_WAIT_MS) {
                if (state != State.KNOWN) return@execute
                if (hasDirectInternet(ctx)) {
                    main.post {
                        if (state != State.KNOWN) return@post
                        releasePhone()
                        ready(null)
                    }
                    return@execute
                }
                if (System.currentTimeMillis() - start >= SCAN_SETTLE_MS) {
                    val saved = shell("cmd wifi list-networks")
                    val scan = shell("cmd wifi list-scan-results")
                    if (saved != null && scan != null && !savedInRange(saved, scan)) {
                        Log.d(TAG, "no saved network in range")
                        break
                    }
                }
                Thread.sleep(500)
            }
            main.post { if (state == State.KNOWN) askPhone() }
        }
    }

    /** Turns the Wi-Fi on if it's off; on a [fresh] acquire, notes how it was, for the teardown. */
    private fun turnWifiOn(ctx: Context, fresh: Boolean) {
        val wifi = ctx.getSystemService(WifiManager::class.java)
        val wifiOn = wifi?.isWifiEnabled == true
        if (fresh) wifiWasOn = wifiOn
        if (wifiOn) return
        status(text(R.string.net_wifi_on))
        io.execute {
            // Without the self-arm's ADB key, the shortcut bridge (if armed) can do this one.
            if (shell("svc wifi enable") == null) PrivilegedShortcutBridge.requestWifiEnabled(ctx, true)
        }
    }

    private fun askPhone() {
        val wifi = context?.getSystemService(WifiManager::class.java)
        if (wifi?.isWifiEnabled != true) return fail(text(R.string.net_wifi_failed))
        state = State.ASKING
        status(text(R.string.net_asking))
        phoneFailure?.let { return fail(it) }
        phoneOffer?.let {
            phoneOffer = null
            return join(it)
        }
        requestPhone()
        armPhoneTimeout()
    }

    private val phoneTimeout = Runnable { if (state == State.ASKING) fail(text(R.string.net_no_answer)) }

    /**
     * Rokid's link loses messages (measured: the phone answered in 1 s and the glasses never got
     * it): while waiting, ask again every [REASK_MS]. The companion answers a repeat with the
     * hotspot it already has.
     */
    private val reask: Runnable = Runnable {
        if (state != State.ASKING) return@Runnable
        PhoneLink.send(Link.NET, NetCommand.UP.toJson())
        main.postDelayed(reask, REASK_MS)
    }

    /** (Re)starts the wait for the phone's answer, and the repeats while it lasts. */
    private fun armPhoneTimeout() {
        main.removeCallbacks(phoneTimeout)
        main.postDelayed(phoneTimeout, PHONE_WAIT_MS)
        main.removeCallbacks(reask)
        main.postDelayed(reask, REASK_MS)
    }

    private fun requestPhone() {
        if (phoneRequested) return
        phoneRequested = true
        if (!PhoneLink.send(Link.NET, NetCommand.UP.toJson())) {
            // Rokid's service can refuse right after boot (-3); one more try covers it.
            main.postDelayed({
                if ((state == State.ASKING || state == State.KNOWN) && !PhoneLink.send(Link.NET, NetCommand.UP.toJson())) {
                    phoneFailure = text(R.string.net_no_link)
                    if (state == State.ASKING) fail(phoneFailure!!)
                }
            }, 2_000)
        }
    }

    /** A saved network won: the phone can close its hotspot. */
    private fun releasePhone() {
        if (phoneRequested) PhoneLink.send(Link.NET, NetCommand.DOWN.toJson())
        phoneRequested = false
        phoneOffer = null
        phoneFailure = null
    }

    /** nb.net.event from the companion: {type: ready, ssid, passphrase, address, port} | failed | down. */
    fun onPhoneEvent(json: JSONObject) {
        val event = NetEvent.from(json)
        Log.d(TAG, "← phone net ${json.optString("type")} in $state")
        when (event) {
            // READY with another address: the companion restarted and opened a new hotspot.
            is NetEvent.Ready -> when {
                state == State.KNOWN -> phoneOffer = event
                // A late answer (the link can lag half a minute after boot) while an app still waits.
                state == State.FAILED && holders > 0 -> join(event)
                state == State.ASKING || (state == State.READY && proxy != null && proxy != event.proxy) -> join(event)
                // Rokid's link can deliver an answer minutes late, ahead of the current one
                // (measured: joined a hotspot gone 9 min earlier while the right answer arrived 8 s
                // later): while joining, a different network wins.
                state == State.JOINING && event.ssid != joinedSsid -> join(event)
            }
            is NetEvent.Failed -> {
                val text = event.text.ifEmpty { text(R.string.net_phone_failed) }
                if (state == State.KNOWN) phoneFailure = text else if (state != State.IDLE) fail(text)
            }
            // Only the hotspot we're on: a late "down" from an earlier one must not end this one.
            is NetEvent.Down -> if (state == State.READY && proxy != null && (event.ssid.isEmpty() || event.ssid == joinedSsid)) fail(text(R.string.net_closed))
            // An unknown type, or a "ready" missing a field.
            null -> if (json.optString("type") == "ready" && state == State.ASKING) fail(text(R.string.net_incomplete))
        }
    }

    private fun join(offer: NetEvent.Ready) {
        val ssid = offer.ssid
        val passphrase = offer.passphrase
        val address = offer.address
        val port = offer.port
        state = State.JOINING
        joinedSsid = ssid
        val seq = ++joinSeq
        val current = { seq == joinSeq }
        status(text(R.string.net_joining))
        io.execute {
            if (!current()) return@execute
            val out = shell("cmd wifi connect-network ${quote(ssid)} wpa2 ${quote(passphrase)}")
            Log.d(TAG, "connect-network: ${out?.trim()}")
            if (out == null) main.post { if (state == State.JOINING && current()) fail(text(R.string.net_self_arm)) }
        }
        waitFor(JOIN_WAIT_MS, { reachable(address, port) }, State.JOINING, onDone = {
            main.removeCallbacks(renew)
            main.removeCallbacks(health)
            main.postDelayed(renew, RENEW_MS)
            main.postDelayed(health, HEALTH_MS)
            ready("$address:$port")
        }, onTimeout = { fail(text(R.string.net_not_answering)) }, current = current)
    }

    private val renew: Runnable = Runnable {
        if (state == State.READY && proxy != null) {
            PhoneLink.send(Link.NET, NetCommand.UP.toJson())
            main.postDelayed(renew, RENEW_MS)
        }
    }

    /**
     * While on the phone's hotspot: is the proxy still there? If not (Wi-Fi switched off from Hi
     * Rokid, the hotspot gone), start over while apps still hold the internet.
     */
    private val health: Runnable = Runnable {
        val current = proxy
        if (state != State.READY || current == null) return@Runnable
        io.execute {
            val ok = reachable(current.substringBefore(':'), current.substringAfter(':').toIntOrNull() ?: 0)
            main.post {
                if (state != State.READY || proxy != current) return@post
                if (ok) {
                    main.postDelayed(health, HEALTH_MS)
                    return@post
                }
                Log.w(TAG, "lost the phone's network; reconnecting")
                main.removeCallbacks(renew)
                proxy = null
                if (holders > 0) start(fresh = false) else state = State.IDLE
            }
        }
    }

    private val teardown = Runnable {
        if (holders > 0) return@Runnable
        Log.d(TAG, "teardown (was $state, wifi was ${if (wifiWasOn) "on" else "off"})")
        main.removeCallbacks(renew)
        main.removeCallbacks(health)
        val ssid = joinedSsid
        if (ssid != null || phoneRequested) PhoneLink.send(Link.NET, NetCommand.DOWN.toJson())
        phoneRequested = false
        phoneOffer = null
        val turnOff = !wifiWasOn && state != State.IDLE
        val ctx = context
        state = State.IDLE
        proxy = null
        joinedSsid = null
        io.execute {
            if (ssid != null) forget(ssid)
            if (turnOff && shell("svc wifi disable") == null && ctx != null) PrivilegedShortcutBridge.requestWifiEnabled(ctx, false)
        }
    }

    /** Polls [check] off the main thread until it holds, [timeoutMs] passes, or the state moves on. */
    /** [current]: false once the attempt this wait belongs to was replaced (a newer join). */
    private fun waitFor(
        timeoutMs: Long,
        check: () -> Boolean,
        whileIn: State,
        onDone: () -> Unit,
        onTimeout: () -> Unit,
        current: () -> Boolean = { true },
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        io.execute {
            while (System.currentTimeMillis() < deadline) {
                if (state != whileIn || !current()) return@execute
                if (runCatching(check).getOrDefault(false)) {
                    main.post { if (state == whileIn && current()) onDone() }
                    return@execute
                }
                Thread.sleep(500)
            }
            main.post { if (state == whileIn && current()) onTimeout() }
        }
    }

    /** Counts joins, so a replaced join's wait stops (and frees the single [io] thread). */
    @Volatile private var joinSeq = 0

    private fun ready(proxy: String?) {
        state = State.READY
        this.proxy = proxy
        Log.d(TAG, "ready ${proxy ?: "direct"}")
        listeners.toList().forEach { it.onReady(proxy) }
    }

    private fun fail(text: String) {
        Log.w(TAG, "failed in $state: $text")
        state = State.FAILED
        proxy = null
        main.removeCallbacks(renew)
        listeners.toList().forEach { it.onFailed(text) }
    }

    private fun status(text: String) = listeners.toList().forEach { it.onStatus(text) }

    private fun reachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { it.connect(InetSocketAddress(host, port), 1_000) }
        true
    }.getOrDefault(false)

    /** Forgets the phone's hotspot (its name changes each time; they would pile up). */
    private fun forget(ssid: String) {
        val list = shell("cmd wifi list-networks") ?: return
        networkIds(list, ssid).forEach { shell("cmd wifi forget-network $it") }
    }

    /** A command as the shell user (ADB loopback, the self-arm's key); null when that's unavailable. */
    private fun shell(command: String): String? {
        val ctx = context ?: return null
        return runCatching { SelfArmController.runShell(ctx, command) }
            .onFailure { Log.w(TAG, "shell unavailable for '${command.substringBefore(' ')} …': ${it.message}") }
            .getOrNull()
    }

    /** `cmd wifi list-networks` rows are "<id> <ssid…> <security>"; the ids of [ssid]'s rows. */
    @JvmStatic
    fun networkIds(list: String, ssid: String): Set<String> = list.lineSequence()
        .map { it.trim() }
        .filter { line -> line.firstOrNull()?.isDigit() == true }
        .mapNotNull { line ->
            val id = line.substringBefore(' ')
            val rest = line.substringAfter(' ').trim()
            if (rest.startsWith(ssid) && rest.removePrefix(ssid).let { it.isEmpty() || it[0] == ' ' }) id else null
        }
        .toSet()

    /**
     * Whether a scan (`cmd wifi list-scan-results`) shows any saved network (`list-networks`),
     * the phone's hotspots aside. An empty scan says nothing yet: counts as in range.
     */
    @JvmStatic
    fun savedInRange(networks: String, scan: String): Boolean {
        val rows = scan.lineSequence().drop(1).map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (rows.isEmpty()) return true
        val saved = networks.lineSequence().drop(1).map { it.trim() }
            .filter { it.firstOrNull()?.isDigit() == true }
            .map { line -> line.substringAfter(' ').trim().substringBeforeLast(' ').trim() }
            .filter { it.isNotEmpty() && !it.startsWith(HOTSPOT_PREFIX) }
            .toSet()
        return saved.any { ssid -> rows.any { row -> row.contains(" $ssid ") || row.endsWith(" $ssid") } }
    }

    private const val HOTSPOT_PREFIX = "AndroidShare_"

    /** A single-quoted shell word. */
    @JvmStatic
    fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}
