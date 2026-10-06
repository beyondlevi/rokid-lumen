package dev.lumen.companion.computer

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executor

/**
 * The phone as a computer's Bluetooth keyboard and mouse (Android's HID device profile): the
 * band's "other device". Registered while the band works for a computer, so the phone keeps its
 * own Bluetooth keyboards and mice the rest of the time (Android turns them off while an app is
 * a keyboard). Android also drops the registration once the app leaves the foreground, which is
 * why it runs inside [dev.lumen.companion.CompanionService], a foreground service.
 *
 * The computer pairs with the phone from its own Bluetooth settings while the phone is
 * registered (so it sees a keyboard); after that the phone reconnects to the last one by itself.
 * Main thread.
 */
@SuppressLint("MissingPermission")
object ComputerLink {
    private const val TAG = "NbComputer"
    private const val KEYBOARD = 1
    private const val MOUSE = 2
    private const val CONSUMER = 3
    /** How long the phone stays visible for pairing (the system's own limit is 300 s). */
    const val PAIRING_SECONDS = 120

    enum class Status { OFF, STARTING, READY, CONNECTING, CONNECTED, UNAVAILABLE }

    data class Computer(val address: String, val name: String)

    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = Executor { main.post(it) }
    private var app: Context? = null
    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private var registered = false
    private var wanted = false

    @Volatile var status: Status = Status.OFF
        private set

    /** The computer it's connected to, or the one it's connecting to. */
    @Volatile var computer: Computer? = null
        private set

    /** The phone is visible for pairing until then (elapsed realtime), 0 when not. */
    @Volatile var pairingUntil = 0L
        private set

    val listeners = mutableSetOf<() -> Unit>()

    val connected get() = status == Status.CONNECTED

    /** Registers the phone as a keyboard and mouse and connects to the last computer. */
    fun start(context: Context) {
        val app = context.applicationContext.also { this.app = it }
        wanted = true
        if (hid != null) return register()
        val adapter = adapter(app) ?: return set(Status.UNAVAILABLE, "no Bluetooth")
        set(Status.STARTING, "starting")
        val asked = adapter.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                main.post {
                    hid = proxy as BluetoothHidDevice
                    if (wanted) register() else close()
                }
            }

            override fun onServiceDisconnected(profile: Int) {
                main.post {
                    hid = null
                    registered = false
                    host = null
                    if (wanted) set(Status.UNAVAILABLE, "HID device service gone")
                }
            }
        }, BluetoothProfile.HID_DEVICE)
        if (!asked) set(Status.UNAVAILABLE, "no HID device profile on this phone")
    }

    /** Lets go: the computer loses its keyboard, the phone gets its own back. */
    fun stop() {
        wanted = false
        pairingUntil = 0L
        close()
        computer = null
        set(Status.OFF, "stopped")
    }

    private fun close() {
        buttons = 0
        val hid = hid ?: return
        host?.let { runCatching { hid.disconnect(it) } }
        if (registered) runCatching { hid.unregisterApp() }
        registered = false
        host = null
        app?.let { adapter(it)?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid) }
        this.hid = null
    }

    private fun register() {
        val hid = hid ?: return
        if (registered) return connectLast()
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "Rokid Lumen", "Meta Neural Band through Rokid Lumen", "Rokid Lumen",
            BluetoothHidDevice.SUBCLASS1_COMBO, DESCRIPTOR,
        )
        val ok = hid.registerApp(sdp, null, null, mainExecutor, callback)
        Log.d(TAG, "registerApp = $ok")
        if (!ok) set(Status.UNAVAILABLE, "registerApp refused (another app is a keyboard, or the app isn't in front)")
    }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            this@ComputerLink.registered = registered
            Log.d(TAG, "registered=$registered")
            if (!registered) {
                host = null
                if (wanted) set(Status.UNAVAILABLE, "unregistered by the system")
                return
            }
            set(Status.READY, "registered")
            if (pluggedDevice != null) connect(pluggedDevice) else connectLast()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            val app = app ?: return
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    val computer = Computer(device.address, device.name ?: device.address)
                    this@ComputerLink.computer = computer
                    remember(app, computer)
                    pairingUntil = 0L
                    set(Status.CONNECTED, "connected to ${computer.name}")
                }
                BluetoothProfile.STATE_CONNECTING -> set(Status.CONNECTING, "connecting")
                BluetoothProfile.STATE_DISCONNECTED -> if (host == null || host == device) {
                    host = null
                    buttons = 0
                    if (registered) set(Status.READY, "disconnected")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, ByteArray(reportSize(id.toInt())))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            // The computer's keyboard lights (caps lock): nothing to show.
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            if (host == device) host = null
            if (registered) set(Status.READY, "unplugged")
        }
    }

    private fun connectLast() {
        val app = app ?: return
        val last = computers(app).firstOrNull() ?: return
        connect(app, last.address)
    }

    /** Connects to a computer this phone is paired with. */
    fun connect(context: Context, address: String) {
        val adapter = adapter(context) ?: return
        val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return
        connect(device)
    }

    private fun connect(device: BluetoothDevice) {
        val hid = hid ?: return
        if (!registered) return
        if (host != null && host != device) runCatching { hid.disconnect(host!!) }
        computer = Computer(device.address, device.name ?: device.address)
        val ok = hid.connect(device)
        Log.d(TAG, "connect = $ok")
        set(if (ok) Status.CONNECTING else Status.READY, "connecting to ${computer?.name}")
    }

    /** The phone becomes visible for pairing (the system asks the person first). */
    fun pairingStarted() {
        pairingUntil = android.os.SystemClock.elapsedRealtime() + PAIRING_SECONDS * 1000L
        changed()
        main.postDelayed({ if (android.os.SystemClock.elapsedRealtime() >= pairingUntil) { pairingUntil = 0L; changed() } }, PAIRING_SECONDS * 1000L + 500)
    }

    fun cancelPairing() {
        pairingUntil = 0L
        changed()
    }

    /** The phone's name on Bluetooth, the one the computer lists. */
    fun phoneName(context: Context): String = runCatching { adapter(context)?.name }.getOrNull().orEmpty()

    // ---- The computers it has been a keyboard for ----

    /**
     * The computers, the last one first: those it has been a keyboard for, then the other
     * computers paired with the phone (a computer paired before can connect without pairing again).
     */
    fun computers(context: Context): List<Computer> {
        val bonded = runCatching { adapter(context)?.bondedDevices?.toList() }.getOrNull()
            ?: return stored(context)
        val addresses = bonded.map { it.address }.toSet()
        val known = stored(context).filter { it.address in addresses }
        val forgotten = forgotten(context)
        val others = bonded
            .filter { it.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.COMPUTER }
            .filter { device -> known.none { it.address == device.address } && device.address !in forgotten }
            .map { Computer(it.address, it.name ?: it.address) }
            .sortedBy { it.name.lowercase() }
        return known + others
    }

    /** Takes [address] off the list (the pairing stays in the phone's Bluetooth settings). */
    fun forget(context: Context, address: String) {
        if (host?.address == address) {
            runCatching { hid?.disconnect(host!!) }
            host = null
            computer = null
            if (registered) set(Status.READY, "forgot the computer")
        }
        save(context, stored(context).filterNot { it.address == address })
        // A computer still paired with the phone would come back to the list: keep it out.
        prefs(context).edit().putStringSet(KEY_FORGOTTEN, forgotten(context) + address).apply()
        changed()
    }

    private fun forgotten(context: Context): Set<String> = prefs(context).getStringSet(KEY_FORGOTTEN, null).orEmpty()

    private fun remember(context: Context, computer: Computer) {
        save(context, listOf(computer) + stored(context).filterNot { it.address == computer.address })
        if (computer.address in forgotten(context)) prefs(context).edit().putStringSet(KEY_FORGOTTEN, forgotten(context) - computer.address).apply()
    }

    private fun stored(context: Context): List<Computer> = runCatching {
        val array = JSONArray(prefs(context).getString(KEY_COMPUTERS, "[]"))
        (0 until array.length()).map { array.getJSONObject(it).let { item -> Computer(item.getString("address"), item.optString("name")) } }
    }.getOrDefault(emptyList())

    private fun save(context: Context, computers: List<Computer>) = prefs(context).edit()
        .putString(KEY_COMPUTERS, JSONArray(computers.map { JSONObject().put("address", it.address).put("name", it.name) }).toString())
        .apply()

    // ---- Reports ----

    /** Sends [output]; false when no computer is connected. */
    fun send(output: ComputerKeys.Output): Boolean = when (output) {
        is ComputerKeys.Output.Keys -> output.strokes.all { stroke(it) }
        is ComputerKeys.Output.Consumer -> report(CONSUMER, byteArrayOf((output.usage and 0xff).toByte(), (output.usage shr 8).toByte())) &&
            report(CONSUMER, ByteArray(2))
        is ComputerKeys.Output.Wheel -> wheel(output.steps)
    }

    /**
     * Wheel [steps] as a quick run of small ones ([WHEEL_CHUNK] every [WHEEL_INTERVAL_MS]): one
     * big step scrolls a few lines, a run of them gets the computer's scrolling acceleration.
     */
    private fun wheel(steps: Int): Boolean {
        if (host == null) return false
        val sign = if (steps < 0) -1 else 1
        var left = Math.abs(steps)
        var delay = 0L
        while (left > 0) {
            val chunk = minOf(left, WHEEL_CHUNK)
            left -= chunk
            val value = (chunk * sign).toByte()
            // The buttons held stay held: scrolling during a drag.
            if (delay == 0L) report(MOUSE, byteArrayOf(buttons.toByte(), 0, 0, value))
            else main.postDelayed({ report(MOUSE, byteArrayOf(buttons.toByte(), 0, 0, value)) }, delay)
            delay += WHEEL_INTERVAL_MS
        }
        return true
    }

    private const val WHEEL_CHUNK = 2
    private const val WHEEL_INTERVAL_MS = 12L

    // ---- The mouse (the air mouse) ----

    const val LEFT = 1
    const val RIGHT = 2

    /** The mouse buttons held now (bits: [LEFT], [RIGHT]); every mouse report carries them. */
    @Volatile var buttons = 0
        private set

    /** Holds [state] (0 lets go); false when no computer is connected. */
    fun buttons(state: Int): Boolean {
        if (state == buttons) return true
        buttons = state
        return report(MOUSE, byteArrayOf(state.toByte(), 0, 0, 0))
    }

    /** Lets go of any button, so none stays stuck down. */
    fun release() {
        if (buttons != 0) buttons(0)
    }

    /** Moves the pointer by [dx], [dy] counts (right, down), in reports of at most ±127 each. */
    fun move(dx: Int, dy: Int): Boolean =
        ComputerKeys.mouseMoves(dx, dy).all { (x, y) -> report(MOUSE, byteArrayOf(buttons.toByte(), x.toByte(), y.toByte(), 0)) }

    fun type(strokes: List<ComputerKeys.Stroke>): Boolean = strokes.all { stroke(it) }

    private fun stroke(stroke: ComputerKeys.Stroke): Boolean =
        report(KEYBOARD, byteArrayOf(stroke.modifiers.toByte(), 0, stroke.usage.toByte(), 0, 0, 0, 0, 0)) &&
            report(KEYBOARD, ByteArray(8))

    private fun report(id: Int, data: ByteArray): Boolean {
        val hid = hid ?: return false
        val device = host ?: return false
        return hid.sendReport(device, id, data)
    }

    private fun reportSize(id: Int) = when (id) {
        MOUSE -> 4
        CONSUMER -> 2
        else -> 8
    }

    private fun set(status: Status, why: String) {
        Log.d(TAG, "$status: $why")
        this.status = status
        changed()
    }

    private fun changed() = listeners.toList().forEach { it() }

    private fun adapter(context: Context): BluetoothAdapter? = context.getSystemService(BluetoothManager::class.java)?.adapter

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences("computer_link", Context.MODE_PRIVATE)

    private const val KEY_COMPUTERS = "computers"
    private const val KEY_FORGOTTEN = "forgotten"

    /** Keyboard (report 1), a mouse's buttons and wheel (report 2), media keys (report 3). */
    private val DESCRIPTOR = intArrayOf(
        0x05, 0x01, 0x09, 0x06, 0xA1, 0x01, 0x85, KEYBOARD,
        0x05, 0x07, 0x19, 0xE0, 0x29, 0xE7, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x08, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x08, 0x81, 0x01,
        0x95, 0x06, 0x75, 0x08, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x05, 0x07, 0x19, 0x00, 0x2A, 0xFF, 0x00, 0x81, 0x00,
        0xC0,
        0x05, 0x01, 0x09, 0x02, 0xA1, 0x01, 0x85, MOUSE, 0x09, 0x01, 0xA1, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01, 0x95, 0x03, 0x75, 0x01, 0x81, 0x02,
        0x95, 0x01, 0x75, 0x05, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38, 0x15, 0x81, 0x25, 0x7F, 0x75, 0x08, 0x95, 0x03, 0x81, 0x06,
        0xC0, 0xC0,
        0x05, 0x0C, 0x09, 0x01, 0xA1, 0x01, 0x85, CONSUMER,
        0x15, 0x00, 0x26, 0xFF, 0x03, 0x19, 0x00, 0x2A, 0xFF, 0x03, 0x75, 0x10, 0x95, 0x01, 0x81, 0x00,
        0xC0,
    ).map { it.toByte() }.toByteArray()
}
