package dev.lumen.band

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.concurrent.thread

/** Where the link is. */
enum class Phase { STOPPED, SEARCHING, CONNECTING, CONNECTED }

/**
 * Keeps one band connected, as the desktop daemon does: read the input-channel
 * PSM over GATT, open an L2CAP channel on it and run the band-core session.
 * Once the phone is bonded to the band it reconnects with `autoConnect`, so the
 * Bluetooth stack waits for the band (no pairing mode); before that it scans for
 * a band in pairing mode. Permissions are checked by the caller.
 */
@SuppressLint("MissingPermission")
class BandLink(
    private val context: Context,
    private val listener: GestureDevice.Listener,
    private val config: Config,
    /** Claims a band in pairing mode instead of signing in with the stored key ([Claim]). */
    private val claim: Claim? = null,
) : GestureDevice {
    /**
     * Claiming a band: the ownership ceremony (kinesis', in rust/band-core) runs on a band in
     * pairing mode with a fresh key; its two Meta exchanges are the app's. Called off the main
     * thread. The new key goes to [Identity]'s files (pending before the band commits, then
     * confirmed), and the connection carries on as a normal one.
     */
    interface Claim {
        /** A step for the screen ("reading the band identity", …). */
        fun onStage(text: String)

        /**
         * Meta's `pair_request` for this band; [request] has device_cert, serial,
         * secondary_cert, nonce and app_pubkey (bytes in hex). Returns the pending receipt's
         * signature and the receipt itself, or throws.
         */
        fun pairRequest(request: JSONObject): Pair<ByteArray, String>

        /** Meta's `pair` for the band's receipt ([pair]: receipt, signature in hex): signature, final receipt and the band's key (or null). */
        fun pair(pair: JSONObject): Triple<ByteArray, String, ByteArray?>

        /** The band committed the new key; it's in [Identity.keyFile]. */
        fun onClaimed()

        fun onFailed(message: String)

        /**
         * A test that touches nothing: stop right after the band's identity read, before any
         * Meta exchange ([onDryRun] then reports the band's serial).
         */
        val dryRun: Boolean get() = false

        fun onDryRun(serial: String) = Unit
    }
    /** What the app wants of the band when a connection opens: paused, and the mapping. */
    interface Config {
        fun paused(): Boolean

        /** `gesture=action;…`: which action name each gesture reports. */
        fun mapping(): String
    }

    // Looked up on every use: after Bluetooth is switched off and on, an adapter held
    // from before can keep reporting "off" (seen on HyperOS).
    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter
    private val main = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var gatt: BluetoothGatt? = null
    private var socket: BluetoothSocket? = null
    private var handle = 0L
    private var lastStatus = ""
    /** This connection uses owner.pending.key: confirm it once the band accepts it. */
    @Volatile private var pending = false
    @Volatile private var wanted = false
    @Volatile private var generation = 0
    /** Failed attempts in a row (see [retry]). */
    @Volatile private var failures = 0
    /** When the band last sent anything (elapsedRealtime). */
    @Volatile private var lastInput = 0L
    /** When [drain] last asked the bridge for its status (elapsedRealtime). */
    private var lastStatusCheck = 0L
    @Volatile private var motion = true
    /** The air mouse runs: [drain] fetches its records. */
    @Volatile private var pointer = false
    @Volatile private var gestures = true
    /** The input-channel read of the current attempt answered. */
    @Volatile private var psmRead = false
    /** Writes to the open channel (the claim's answers come from other threads). */
    /** A handwriting capture runs (switching on, writing or restoring). */
    @Volatile private var handwritingActive = false
    /** [stop] waits for the band's handwriting restore before letting go. */
    @Volatile private var stopAfterRestore = false
    @Volatile private var writer: ((ByteArray) -> Unit)? = null
    /** The claim finished: later connections sign in with the new key. */
    @Volatile private var claimed = false
    @Volatile private var claimDevice: BluetoothDevice? = null

    private val bondedBands: List<BluetoothDevice>
        get() = adapter?.bondedDevices?.filter { it.name?.lowercase()?.startsWith("meta band") == true }.orEmpty()

    /** The bonded band band.json names (any bonded band without one): not a bond from before a reset. */
    val bondedBand: BluetoothDevice?
        get() {
            val known = Identity.bandAddress(context) ?: return bondedBands.firstOrNull()
            return bondedBands.firstOrNull { it.address.equals(known, ignoreCase = true) }
        }

    /**
     * Bonds to bands other than band.json's (the band before a factory reset, which changed its
     * address): the stack would keep waiting on them, and they can't come back.
     */
    private fun forgetStaleBonds() {
        val known = Identity.bandAddress(context) ?: return
        bondedBands.filterNot { it.address.equals(known, ignoreCase = true) }.forEach { stale ->
            // Removing a bond is a hidden API for ordinary apps; many builds still allow it.
            val removed = runCatching { stale.javaClass.getMethod("removeBond").invoke(stale) as Boolean }.getOrDefault(false)
            log("an old bond to ${stale.name} (before a reset): ${if (removed) "forgotten" else "couldn't be removed"}")
        }
    }

    override fun start() {
        // Started again while a stop waited on the band's restore: the link simply stays.
        if (stopAfterRestore) {
            stopAfterRestore = false
            main.removeCallbacks(finishStop)
        }
        if (wanted) return
        wanted = true
        connect()
    }

    /**
     * Lets go of the band. With handwriting on it puts the band's settings back first (at most
     * [RESTORE_WAIT_MS]): the next device the band goes to doesn't know they were changed.
     */
    override fun stop() {
        if (handwritingActive && handle != 0L && !stopAfterRestore) {
            stopAfterRestore = true
            setHandwriting(false)
            main.postDelayed(finishStop, RESTORE_WAIT_MS)
            return
        }
        stopNow()
    }

    private val finishStop = Runnable {
        if (stopAfterRestore) {
            stopAfterRestore = false
            stopNow()
        }
    }

    private fun stopNow() {
        wanted = false
        teardown()
        listener.onPhase(Phase.STOPPED, null)
    }

    /** Motion streams (for pinch and turn) on or off on the band; kept for the next connection. */
    override fun setMotion(enabled: Boolean) = synchronized(lock) {
        motion = enabled
        if (handle != 0L) Bridge.setMotion(handle, enabled)
    }

    /** The gesture stream on or off on the band; kept for the next connection. */
    override fun setGestures(enabled: Boolean) = synchronized(lock) {
        gestures = enabled
        if (handle != 0L) Bridge.setGestures(handle, enabled)
    }

    override fun setPointer(enabled: Boolean, tuning: String): Boolean = synchronized(lock) {
        if (handle == 0L) {
            pointer = false
            return false
        }
        try {
            Bridge.setPointer(handle, enabled, tuning, now())
        } catch (_: UnsatisfiedLinkError) {
            // A band library from before the air mouse.
            pointer = false
            return false
        }
        pointer = enabled
        true
    }

    override fun setPaused(paused: Boolean) = synchronized(lock) {
        if (handle != 0L) Bridge.setPaused(handle, paused, now())
        publishStatus()
    }

    override fun setMapping(mapping: String) = synchronized(lock) {
        if (handle != 0L) Bridge.setMapping(handle, mapping)
    }

    override fun setHandwriting(enabled: Boolean): Boolean {
        if (enabled) handwritingActive = true
        val bytes = try {
            synchronized(lock) {
                if (handle == 0L) return false
                // Before anything is written: a capture cut short is put back at the next connection.
                if (enabled) Identity.handwritingMarker(context).writeText("1")
                val (collection, model) = Identity.handwritingIds(context) ?: (0 to 0)
                Bridge.setHandwriting(handle, enabled, collection, model, now())
            }
        } catch (e: Exception) {
            log("handwriting: ${e.message}")
            return false
        }
        writer?.invoke(bytes)
        return true
    }

    override fun resetHandwritingText(text: String) = synchronized(lock) {
        if (handle != 0L) Bridge.resetHandwritingText(handle, text)
    }

    /** A capture never finished (the marker survived): put the band's settings back first. */
    private fun recoverHandwriting() {
        if (!Identity.handwritingMarker(context).exists()) return
        val bytes = try {
            synchronized(lock) {
                if (handle == 0L) return
                val (collection, model) = Identity.handwritingIds(context) ?: (0 to 0)
                Bridge.recoverHandwriting(handle, collection, model, now())
            }
        } catch (e: Exception) {
            log("handwriting recovery: ${e.message}")
            return
        }
        log("handwriting: a capture never finished; putting the band back")
        writer?.invoke(bytes)
    }

    private fun onHandwritingEvent(event: JSONObject) {
        if (event.optString("type") == "state") {
            handwritingActive = event.optString("phase") in setOf("preparing", "ready", "restoring")
            if (!handwritingActive && stopAfterRestore) main.post(finishStop)
            val collection = event.optInt("collection_id", 0)
            val model = event.optInt("model_id", 0)
            if (collection > 0 && model > 0) Identity.saveHandwritingIds(context, collection, model)
            if (event.optString("phase") == "finished" && event.optBoolean("verified")) {
                Identity.handwritingMarker(context).delete()
            }
        }
        listener.onHandwriting(event)
    }

    private fun connect() {
        if (!wanted) return
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            log("Bluetooth is off. Retrying in 5 s.")
            listener.onPhase(Phase.SEARCHING, null)
            retry(5000)
            return
        }
        val bonded = bondedBand.takeIf { claim == null || claimed }
        if (bonded == null && claim == null) forgetStaleBonds()
        if (bonded != null) {
            listener.onPhase(Phase.SEARCHING, bonded.name)
            log("waiting for ${bonded.name}")
            gatt = bonded.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } else {
            listener.onPhase(Phase.SEARCHING, null)
            log(if (claim != null && !claimed) "scanning for a band in pairing mode to claim" else "scanning for a band in pairing mode (hold its button 3 s)")
            scanner.startScan(
                null,
                ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                scan,
            )
        }
    }

    /** Drop everything from the current attempt; `connect` starts a fresh one. */
    private fun teardown() {
        generation++
        main.removeCallbacksAndMessages(null)
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scan) }
        runCatching { socket?.close() }
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        socket = null
        gatt = null
        synchronized(lock) {
            if (handle != 0L) Bridge.close(handle)
            handle = 0L
            lastStatus = ""
            pointer = false
        }
    }

    /**
     * Try again after [delayMs], doubled for each failure in a row up to [MAX_RETRY_MS]: every
     * attempt wakes the band's radio (a band held by another device turns each one down), so a
     * band that stays away isn't asked every 2 s. A connection resets it.
     */
    private fun retry(delayMs: Long) {
        if (stopAfterRestore) {
            // The link dropped while a stop waited on the restore: stop now (the marker makes
            // the next connection here restore the band).
            stopAfterRestore = false
            stopNow()
            return
        }
        teardown()
        // A claim that can't even connect: most often a band that wasn't factory reset (it
        // shows up, then turns each connection down). Stop and say so instead of trying forever.
        val claim = claim
        if (claim != null && !claimed && failures >= CLAIM_MAX_FAILURES) {
            log("claim: no connection after $failures attempts")
            claim.onFailed(CLAIM_NO_CONNECTION)
            main.post { stop() }
            return
        }
        val delay = (delayMs shl failures.coerceAtMost(4)).coerceAtMost(MAX_RETRY_MS)
        failures++
        if (wanted) main.postDelayed({ connect() }, delay)
    }

    private val scan = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.scanRecord?.deviceName ?: result.device.name ?: return
            if (!name.lowercase().startsWith("meta band") || gatt != null) return
            adapter?.bluetoothLeScanner?.stopScan(this)
            log("found $name; connecting")
            listener.onPhase(Phase.CONNECTING, name)
            gatt = result.device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        }

        override fun onScanFailed(errorCode: Int) {
            log("scan failed ($errorCode)")
            retry(5000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt != this@BandLink.gatt) return
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    listener.onPhase(Phase.CONNECTING, gatt.device.name)
                    gatt.discoverServices()
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    log("the band disconnected (status $status)")
                    retry(2000)
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val characteristic = gatt.getService(BAND_SERVICE)?.getCharacteristic(PSM_CHARACTERISTIC)
            if (characteristic == null) {
                log("this device has no Neural Band input service")
                retry(5000)
                return
            }
            // The first read may trigger Bluetooth pairing (a system prompt). A read can be
            // lost while the bond completes: without an answer in time, start over.
            psmRead = false
            val attempt = generation
            main.postDelayed({
                if (attempt == generation && !psmRead) {
                    log("the band didn't answer the input channel read; reconnecting")
                    retry(2000)
                }
            }, PSM_READ_TIMEOUT_MS)
            if (!gatt.readCharacteristic(characteristic)) {
                log("couldn't read the input channel; reconnecting")
                retry(3000)
            }
        }

        // Android 13+ calls this one.
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
            status: Int,
        ) = onPsmRead(gatt, value, status)

        // Android 12 and older call only this one (the Rokid glasses run 12).
        @Deprecated("Replaced by the value overload on Android 13")
        @Suppress("DEPRECATION")
        override fun onCharacteristicRead(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onPsmRead(gatt, characteristic.value ?: ByteArray(0), status)
            }
        }

        private fun onPsmRead(gatt: BluetoothGatt, value: ByteArray, status: Int) {
            if (gatt != this@BandLink.gatt) return
            psmRead = true
            if (status != BluetoothGatt.GATT_SUCCESS || value.size != 2) {
                log("reading the input channel failed (status $status)")
                retry(5000)
                return
            }
            val psm = (value[0].toInt() and 0xff) or ((value[1].toInt() and 0xff) shl 8)
            val attempt = generation
            thread(name = "band-l2cap") { runChannel(gatt.device, psm, attempt) }
        }
    }

    private fun now() = SystemClock.elapsedRealtimeNanos() / 1e9

    private fun openSession(): Boolean {
        if (claim != null && !claimed) {
            synchronized(lock) { handle = Bridge.openClaim(0, config.paused(), "", config.mapping()) }
            return true
        }
        val dir = context.filesDir
        // A pending key is always newer: the band may have committed a claim whose
        // confirmation was lost (the desktop's rule).
        val key = Identity.pendingFile(context).takeIf { it.exists() } ?: Identity.keyFile(context)
        if (!key.exists()) {
            log("no owner key yet: import the band's key (see the README)")
            return false
        }
        pending = key == Identity.pendingFile(context)
        val guess = File(dir, "band.json").takeIf { it.exists() }
            ?.let { JSONObject(it.readText()).optInt("scheme_guess", 0) } ?: 0
        return try {
            synchronized(lock) {
                handle = Bridge.open(key.readBytes(), guess, config.paused(), "", config.mapping())
                if (!motion) Bridge.setMotion(handle, false)
                if (!gestures) Bridge.setGestures(handle, false)
            }
            true
        } catch (e: Exception) {
            log("owner key: ${e.message}")
            false
        }
    }

    private fun runChannel(device: BluetoothDevice, psm: Int, attempt: Int) {
        if (!openSession()) {
            main.post { stop() }
            return
        }
        val channel = try {
            device.createInsecureL2capChannel(psm).also { it.connect() }
        } catch (e: Exception) {
            log("opening the input channel failed: ${e.message}")
            main.post { if (attempt == generation) retry(3000) }
            return
        }
        socket = channel
        val input = channel.inputStream
        val output = channel.outputStream
        val write = { bytes: ByteArray -> if (bytes.isNotEmpty()) synchronized(output) { output.write(bytes) } }
        writer = write
        claimDevice = device
        try {
            write(synchronized(lock) { Bridge.request(handle) })
            thread(name = "band-tick") {
                while (attempt == generation) {
                    // Fast only while the band is talking: a held tap, a double tap or the dial
                    // need 50 ms; idle, the tick has only request deadlines (seconds) to keep.
                    // Before, it woke 20 times a second for the whole connection.
                    val talking = SystemClock.elapsedRealtime() - lastInput < FAST_TICK_WINDOW_MS
                    SystemClock.sleep(if (talking) FAST_TICK_MS else IDLE_TICK_MS)
                    try {
                        val out = synchronized(lock) { if (handle == 0L) null else Bridge.tick(handle, now()) }
                        out?.let(write)
                        drain()
                    } catch (e: Exception) {
                        if (attempt == generation) log("link: ${e.message}")
                        break
                    }
                }
            }
            val buffer = ByteArray(8192)
            while (attempt == generation) {
                val count = input.read(buffer)
                if (count < 0) break
                lastInput = SystemClock.elapsedRealtime()
                val out = synchronized(lock) {
                    if (handle == 0L) null else Bridge.feed(handle, buffer.copyOf(count), now())
                }
                out?.let(write)
                drain()
            }
            if (attempt == generation) log("the band closed the input channel")
        } catch (e: Exception) {
            if (attempt == generation) log("link: ${e.message}")
            // The band turned the claim down (another account's band, a rejected receipt):
            // trying again wouldn't change its answer.
            val claim = claim
            if (claim != null && !claimed && attempt == generation && e.message.orEmpty().contains("rejected|belongs to|ownership".toRegex())) {
                claimFailed(claim, e)
                return
            }
        }
        main.post { if (attempt == generation) retry(2000) }
    }

    /** Hand actions, log lines and a changed status to the listener. */
    private fun drain() {
        val (actions, lines) = synchronized(lock) {
            if (handle == 0L) return
            Bridge.actions(handle) to Bridge.log(handle)
        }
        if (lines.isNotEmpty()) lines.lines().forEach(::log)
        if (pending && "connected" in lines.lines()) {
            pending = false
            if (Identity.pendingFile(context).renameTo(Identity.keyFile(context))) log("the band accepted the claimed key")
        }
        if ("connected" in lines.lines()) recoverHandwriting()
        if (actions.isNotEmpty()) listener.onActions(actions.lines())
        if (pointer) {
            val records = synchronized(lock) { if (handle == 0L) null else Bridge.pointer(handle, now()) }
            if (records != null && records.isNotEmpty()) listener.onPointer(records)
        }
        val handwriting = synchronized(lock) { if (handle == 0L) "" else Bridge.handwritingEvents(handle) }
        if (handwriting.isNotEmpty()) handwriting.lines().forEach { onHandwritingEvent(JSONObject(it)) }
        if (claim != null && !claimed) {
            val events = synchronized(lock) { if (handle == 0L) "" else Bridge.claimEvents(handle) }
            if (events.isNotEmpty()) events.lines().forEach { onClaimEvent(claim, JSONObject(it)) }
        }
        // The status is a JSON the bridge builds on request: at most every STATUS_EVERY_MS while
        // samples stream in, right away when something happened (an action or a log line).
        val now = SystemClock.elapsedRealtime()
        if (actions.isEmpty() && lines.isEmpty() && now - lastStatusCheck < STATUS_EVERY_MS) return
        lastStatusCheck = now
        synchronized(lock) { publishStatus() }
    }

    /** One ceremony event; the Meta exchanges run on their own thread and answer the band. */
    private fun onClaimEvent(claim: Claim, event: JSONObject) {
        val attempt = generation
        when (event.optString("type")) {
            "stage" -> claim.onStage(event.optString("text"))
            "pair_request" -> {
                if (claim.dryRun) {
                    claim.onDryRun(event.optString("serial"))
                    main.post { stop() }
                    return
                }
                thread(name = "band-claim") {
                    try {
                        val (signature, receipt) = claim.pairRequest(event)
                        val out = synchronized(lock) { if (handle == 0L || attempt != generation) null else Bridge.claimPairRequestCompleted(handle, signature, receipt) }
                        out?.let { writer?.invoke(it) }
                    } catch (e: Exception) {
                        claimFailed(claim, e)
                    }
                }
            }
            "pair" -> thread(name = "band-claim") {
                try {
                    val (signature, receipt, deviceKey) = claim.pair(event)
                    val (out, pendingKey) = synchronized(lock) {
                        if (handle == 0L || attempt != generation) return@thread
                        Bridge.claimPairCompleted(handle, signature, receipt, deviceKey) to Bridge.claimPendingKey(handle)
                    }
                    // The band may commit even if its confirmation never arrives: the key is kept
                    // as pending first (BandLink tries it next time and confirms it).
                    pendingKey?.let { Identity.pendingFile(context).writeBytes(it) }
                    writer?.invoke(out)
                } catch (e: Exception) {
                    claimFailed(claim, e)
                }
            }
            "completed" -> {
                val key = hexBytes(event.optString("owner_key"))
                Identity.keyFile(context).writeBytes(key)
                Identity.pendingFile(context).delete()
                claimDevice?.let { device ->
                    Identity.bandFile(context).writeText(
                        JSONObject().put("address", device.address).put("name", device.name.orEmpty()).put("scheme_guess", 0).toString(2),
                    )
                }
                claimed = true
                log("the band is claimed with a new key")
                claim.onClaimed()
            }
        }
    }

    private fun claimFailed(claim: Claim, error: Exception) {
        log("claim: ${error.message}")
        claim.onFailed(error.message ?: error.javaClass.simpleName)
        main.post { stop() }
    }

    private fun hexBytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    /** Call with `lock` held. */
    private fun publishStatus() {
        if (handle == 0L) return
        val json = Bridge.status(handle)
        if (json == lastStatus) return
        lastStatus = json
        val status = JSONObject(json)
        if (status.optBoolean("connected")) {
            failures = 0
            gatt?.device?.let { rememberAddress(it) }
            listener.onPhase(Phase.CONNECTED, gatt?.device?.name)
        }
        listener.onStatus(status)
    }

    /**
     * The band answered with the key: its address here goes to band.json. A band in pairing
     * mode shows a new address each time, so the one the claim saw (on another device) isn't
     * this device's bond; [bondedBand] then finds this one, and [forgetStaleBonds] keeps it.
     */
    private fun rememberAddress(device: BluetoothDevice) {
        if (Identity.bandAddress(context).equals(device.address, ignoreCase = true)) return
        val band = runCatching { JSONObject(Identity.bandFile(context).readText()) }.getOrDefault(JSONObject())
        band.put("address", device.address)
        if (!band.has("name")) device.name?.let { band.put("name", it) }
        runCatching { Identity.bandFile(context).writeText(band.toString(2)) }
        log("the band's address here is now ${device.address}")
    }

    private fun log(line: String) = listener.onLog(line)

    companion object {
        /** [Claim.onFailed]'s message when the band never let the claim connect. */
        const val CLAIM_NO_CONNECTION = "no_connection"
        private const val CLAIM_MAX_FAILURES = 4
        /** How long a stop waits for the band's handwriting restore (it takes ~0.2 s). */
        private const val RESTORE_WAIT_MS = 2_500L
        val BAND_SERVICE: UUID = UUID.fromString("0000feb8-0000-1000-8000-00805f9b34fb")
        val PSM_CHARACTERISTIC: UUID = UUID.fromString("2d41da7c-82b6-42aa-b34e-e2e01df8cc1a")

        /** Long enough for a first read that waits out Bluetooth pairing. */
        const val PSM_READ_TIMEOUT_MS = 12_000L

        /** The longest wait between attempts. */
        const val MAX_RETRY_MS = 30_000L

        /** The tick while the band talks, and how long after its last message that lasts. */
        const val FAST_TICK_MS = 50L
        const val FAST_TICK_WINDOW_MS = 1_500L

        /** The tick while the band is quiet: request deadlines are seconds long. */
        const val IDLE_TICK_MS = 250L

        /** The least time between two status reads while samples stream in. */
        const val STATUS_EVERY_MS = 200L
    }
}
