package dev.lumen.glasses

import android.content.Context
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import com.rokid.cxr.CXRServiceBridge
import com.rokid.cxr.Caps
import dev.lumen.protocol.Link
import dev.lumen.protocol.NotifyCommand
import dev.lumen.protocol.NotifyEvent
import org.json.JSONObject

/**
 * The glasses' end of the link with the phone companion (Rokid Lumen Companion, the :phone
 * module): CXR-S messages through Rokid's own service, which the companion reaches over CXR-L
 * through the Hi Rokid app. One JSON string per Caps. Measured: messages from the phone arrive
 * with the Rokid launcher in front too, so the link runs from the accessibility service.
 *
 * The messages (names, envelope, types) are the shared :protocol module's ([Link]), which the
 * companion uses too; this object is the glasses' transport and dispatch.
 */
object PhoneLink {
    private var appContext: Context? = null
    private const val TAG = "BandPhoneLink"
    private const val ROKID_SERVICE = "com.rokid.cxrservice"

    /** A notification the banner should show (new, or its text changed, and the phone says alert). */
    fun interface AlertListener {
        fun onAlert(notification: PhoneNotification)
    }

    private val main = Handler(Looper.getMainLooper())
    private var bridge: CXRServiceBridge? = null
    var dictationListener: ((String, JSONObject) -> Unit)? = null
    @JvmStatic var alertListener: AlertListener? = null

    /** On Rokid glasses the phone link exists (Rokid's CXR service is there). */
    @JvmStatic
    fun applies(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo(ROKID_SERVICE, 0)
        true
    }.getOrDefault(false)

    /** Subscribes to the phone's events (once per process) and asks for the notifications. */
    @JvmStatic
    fun start(context: Context) {
        if (!applies(context)) return
        appContext = context.applicationContext
        BandSettings.start(context)
        GridApi.start(context)
        if (ensure() != null) requestSync(attempt = 1)
    }

    /**
     * Right after boot or an update Rokid's service can refuse the message (-3, measured), and
     * the inbox would stay empty until the phone's next notification: retry a few times.
     */
    private fun requestSync(attempt: Int) {
        if (send(Link.NOTIFY, NotifyCommand.sync())) {
            appContext?.let { NotificationSnooze.publish(it) }
            return
        }
        if (attempt >= SYNC_ATTEMPTS) return
        main.postDelayed({ requestSync(attempt + 1) }, SYNC_RETRY_MS)
    }

    private const val SYNC_ATTEMPTS = 6
    private const val SYNC_RETRY_MS = 5_000L

    /** Tells Rokid's service our app is the one in front (the companion's CUSTOMAPP scene). */
    @JvmStatic
    fun appLaunched() {
        runCatching { ensure()?.appLaunch() }.onFailure { Log.w(TAG, "appLaunch failed", it) }
    }

    @Synchronized
    fun ensure(): CXRServiceBridge? {
        bridge?.let { return it }
        return runCatching {
            CXRServiceBridge().also { cxr ->
                val callback = CXRServiceBridge.MsgCallback { name, args, bytes ->
                    if (name == Link.BENCH) return@MsgCallback onBench(args, bytes)
                    val json = runCatching { JSONObject(args.at(0).string) }.getOrDefault(JSONObject())
                    main.post { onMessage(name, json) }
                }
                Log.d(TAG, "subscribe " + Link.TO_GLASSES.joinToString(" ") { "$it=${cxr.subscribe(it, callback)}" })
                bridge = cxr
            }
        }.onFailure { Log.w(TAG, "CXR bridge unavailable", it) }.getOrNull()
    }

    fun send(name: String, json: JSONObject): Boolean {
        val cxr = bridge ?: return false
        val caps = Caps().apply { write(json.toString()) }
        val code = runCatching { cxr.sendMessage(name, caps) }.getOrElse { -99 }
        Log.d(TAG, "→ phone $name ${json.optString("action")} = $code")
        return code == 0
    }

    private fun onMessage(name: String, json: JSONObject) {
        when (name) {
            Link.DICTATION_EVENT -> {
                Log.d(TAG, "← phone $name $json")
                dictationListener?.invoke(name, json)
            }
            Link.NOTIFY_EVENT -> onNotify(json)
            Link.NET_EVENT -> PhoneInternet.onPhoneEvent(json)
            Link.SETTINGS -> BandSettings.onPhoneRequest(json)
            Link.GRID -> GridApi.onPhoneRequest(json)
        }
    }

    private var benchStart = 0L
    private var benchBytes = 0L

    /** {seq, of}: logs what arrived and how fast, on the binder thread (no main-thread delay). */
    private fun onBench(args: Caps, bytes: ByteArray?) {
        val json = runCatching { JSONObject(args.at(0).string) }.getOrDefault(JSONObject())
        val seq = json.optInt("seq")
        val now = System.nanoTime()
        if (seq == 0) {
            benchStart = now
            benchBytes = 0
        }
        benchBytes += bytes?.size ?: 0
        if (seq == json.optInt("of") - 1) {
            val ms = (now - benchStart) / 1_000_000.0
            Log.d(TAG, "bench ${seq + 1} msgs ${bytes?.size}B each, $benchBytes B in ${"%.0f".format(ms)} ms")
        }
    }

    /**
     * post   {key, app, pkg, title, text, when, redacted, live, alert, icon (base64 PNG, optional)}
     * remove {key}
     * reset  {} (a full sync follows as posts with alert false)
     */
    private fun onNotify(json: JSONObject) {
        Log.d(TAG, "← phone notify ${json.optString("type")} ${json.optString("key").takeLast(24)}")
        when (json.optString("type")) {
            NotifyEvent.POST -> {
                val notification = parse(json) ?: return
                // The phone decides what's news (NotificationAlerts there): recent content, newer
                // than what that key said before. A re-post of an old chat is neither.
                val live = json.optBoolean("live")
                NotificationInbox.put(notification, live)
                if (live && json.optBoolean("alert")) alertListener?.onAlert(notification)
            }
            NotifyEvent.REMOVE -> NotificationInbox.remove(json.optString("key"))
            // A full sync starts (the phone's link came up): it hears the snooze as it stands.
            NotifyEvent.RESET -> {
                NotificationInbox.clear()
                appContext?.let { NotificationSnooze.publish(it) }
            }
            NotifyEvent.SNOOZE -> appContext?.let { NotificationSnooze.set(it, json.optBoolean("on")) }
        }
    }

    @JvmStatic
    fun parse(json: JSONObject): PhoneNotification? {
        val key = json.optString("key").ifEmpty { return null }
        val icon = json.optString("icon").takeIf { it.isNotEmpty() }?.let { encoded ->
            runCatching {
                val bytes = Base64.decode(encoded, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }.getOrNull()
        }
        return PhoneNotification(
            key = key,
            appName = json.optString("app"),
            packageName = json.optString("pkg"),
            title = json.optString("title"),
            text = json.optString("text"),
            postedAt = json.optLong("when", System.currentTimeMillis()),
            redacted = json.optBoolean("redacted"),
            icon = icon,
            focus = json.optBoolean("focus"),
        )
    }
}
