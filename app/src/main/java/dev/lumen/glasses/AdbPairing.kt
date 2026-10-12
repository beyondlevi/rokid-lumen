package dev.lumen.glasses

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import dev.lumen.protocol.DebugStatus
import dev.lumen.protocol.SettingsOps
import java.util.concurrent.Executors

/**
 * A new adb client trusted by pairing ([SettingsOps.ACTION_ADB_PAIR]): these glasses never show
 * Android's "allow debugging" prompt (adbd asks for it, no screen appears, measured), so a
 * computer, or the phone itself, can only get in with Wireless Debugging's pairing.
 *
 * As the shell user (the self-arm's loopback adb, [SelfArmController.runShell]) this trusts the
 * Wi-Fi network and opens a pairing window with the code the phone chose (IAdbManager's
 * enablePairingByQrCode, transaction 9 on API 32, as Rokid Nexus does), then reports the window's
 * port in [DebugStatus.pairingPort]. A paired key is also accepted on the fixed port 5555, so the
 * window closes after [SettingsOps.ADB_PAIRING_WINDOW_MS], once used, or when the phone cancels,
 * and Wireless Debugging goes back off if it was off.
 */
object AdbPairing {
    private const val TAG = "BandAdbPairing"
    private const val TRUST_AND_ENABLE = 4
    private const val WIRELESS_PORT = 10
    private const val PAIR_WITH_CODE = 9
    private const val STOP_PAIRING = 11
    private const val OPEN_WAIT_MS = 6_000L
    private const val POLL_MS = 300L
    private const val WATCH_MS = 2_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-adb-pairing").apply { isDaemon = true } }

    /** The open window's port (0: none). */
    @Volatile
    var port = 0
        private set

    /** Why the last window couldn't open ("" when it did). */
    @Volatile
    var error = ""
        private set

    @Volatile private var wifiAdbWasOff = false
    @Volatile private var generation = 0

    /** Opens a window for [code] (six digits); the outcome comes back in the debug state. */
    @JvmStatic
    fun start(context: Context, code: String): String? {
        if (!SettingsOps.isPairingCode(code)) return "bad code"
        val ctx = context.applicationContext
        io.execute { open(ctx, code) }
        return null
    }

    @JvmStatic
    fun stop(context: Context) {
        val ctx = context.applicationContext
        io.execute { close(ctx, "cancelled") }
    }

    private fun open(ctx: Context, code: String) {
        if (port != 0) close(ctx, "replaced")
        val mine = ++generation
        error = ""
        val outcome = runCatching { openWindow(ctx, code) }
        outcome.onFailure { Log.w(TAG, "pairing didn't open: ${it.message}") }
        val opened = outcome.getOrNull()
        when {
            outcome.isFailure -> error = DebugStatus.PAIRING_NOT_ARMED
            opened == null -> {
                error = DebugStatus.PAIRING_FAILED
                // Android may have opened it without our seeing the port: don't leave it open.
                runCatching { shell(ctx, "service call adb $STOP_PAIRING") }
                if (wifiAdbWasOff) runCatching { shell(ctx, "settings put global adb_wifi_enabled 0") }
            }
            opened < 0 -> error = DebugStatus.PAIRING_NO_WIFI
            else -> {
                port = opened
                Log.d(TAG, "pairing window open on $opened")
                main.postDelayed({ io.execute { if (generation == mine) close(ctx, "expired") } }, SettingsOps.ADB_PAIRING_WINDOW_MS)
                main.postDelayed({ watch(ctx, mine) }, WATCH_MS)
            }
        }
        WirelessDebug.check()
    }

    /** The window's port; -1 without a Wi-Fi network to trust, null when Android didn't open one. */
    private fun openWindow(ctx: Context, code: String): Int? {
        val bssid = AdbShellParsing.bssid(shell(ctx, "cmd wifi status")) ?: return -1
        wifiAdbWasOff = shell(ctx, "settings get global adb_wifi_enabled").trim() != "1"
        shell(ctx, "service call adb $TRUST_AND_ENABLE i32 1 s16 $bssid")
        val wireless = waitFor { AdbShellParsing.parcelInt(shell(ctx, "service call adb $WIRELESS_PORT"))?.takeIf { it > 0 } }
        val before = AdbShellParsing.listeningPorts(shell(ctx, "netstat -ltn"))
        val name = "lumen-" + java.security.SecureRandom().nextInt().toUInt().toString(16).padStart(8, '0')
        shell(ctx, "service call adb $PAIR_WITH_CODE s16 $name s16 $code")
        val skip = setOfNotNull(DebugStatus.DEFAULT_PORT, wireless)
        return waitFor { AdbShellParsing.newPort(before, AdbShellParsing.listeningPorts(shell(ctx, "netstat -ltn")), skip) }
    }

    /** A window that stopped listening was used (or refused a wrong code): close it now. */
    private fun watch(ctx: Context, mine: Int) {
        if (generation != mine || port == 0) return
        io.execute {
            if (generation != mine || port == 0) return@execute
            val open = runCatching { port in AdbShellParsing.listeningPorts(shell(ctx, "netstat -ltn")) }.getOrDefault(true)
            if (open) main.postDelayed({ watch(ctx, mine) }, WATCH_MS) else close(ctx, "used")
        }
    }

    private fun close(ctx: Context, why: String) {
        if (port == 0) return
        generation++
        runCatching { shell(ctx, "service call adb $STOP_PAIRING") }
        if (wifiAdbWasOff) runCatching { shell(ctx, "settings put global adb_wifi_enabled 0") }
        Log.d(TAG, "pairing window closed ($why)")
        port = 0
        WirelessDebug.check()
    }

    private fun <T : Any> waitFor(read: () -> T?): T? {
        val until = System.currentTimeMillis() + OPEN_WAIT_MS
        while (System.currentTimeMillis() < until) {
            read()?.let { return it }
            Thread.sleep(POLL_MS)
        }
        return null
    }

    /** Rokid's adbd refuses a shell command that starts with cmd, pm, dumpsys or logcat unless it's allowed: `true;` first. */
    private fun shell(ctx: Context, command: String): String = SelfArmController.runShell(ctx, "true; $command")
}

/** What the shell commands above print, read without a device (unit-tested). */
object AdbShellParsing {
    /** The connected network's BSSID in `cmd wifi status`, or null. */
    @JvmStatic
    fun bssid(status: String): String? =
        Regex("BSSID[:=]\\s*([0-9A-Fa-f]{2}(?::[0-9A-Fa-f]{2}){5})").find(status)?.groupValues?.get(1)
            ?.takeIf { it != "00:00:00:00:00:00" && it.lowercase() != "02:00:00:00:00:00" }

    /** The int a `service call` returned ("Result: Parcel(00000000 00009b21   '....!...')" is 39713), or null. */
    @JvmStatic
    fun parcelInt(output: String): Int? {
        val words = Regex("Parcel\\(([0-9a-fA-F]{8})\\s+([0-9a-fA-F]{8})").find(output)?.groupValues ?: return null
        if (words[1] != "00000000") return null
        return words[2].toLong(16).toInt()
    }

    /** The TCP ports listening in `netstat -ltn`. */
    @JvmStatic
    fun listeningPorts(netstat: String): Set<Int> = netstat.lineSequence()
        .filter { "LISTEN" in it }
        .mapNotNull { line -> line.trim().split(Regex("\\s+")).getOrNull(3)?.substringAfterLast(':')?.toIntOrNull() }
        .toSet()

    /** The port that started listening between the two reads, [skip] aside (the pairing server's). */
    @JvmStatic
    fun newPort(before: Set<Int>, after: Set<Int>, skip: Set<Int>): Int? = (after - before - skip).minOrNull()
}
