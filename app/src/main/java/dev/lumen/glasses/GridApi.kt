package dev.lumen.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.widget.Toast
import dev.lumen.protocol.GridEvent
import dev.lumen.protocol.GridItem
import dev.lumen.protocol.GridOps
import dev.lumen.protocol.Link
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors

/**
 * The phone's side of the apps grid ([Link.GRID]): the grid's state, changes to it (order,
 * hidden items, web apps added or removed, engines, offline packages) and the items' icons,
 * one per message. Answers go on [Link.GRID_EVENT]; every change sends the whole state back.
 *
 * Web apps and packages added from here install without a confirmation on the glasses (unlike
 * [InstallConfirmActivity]'s): the user asked for them on the phone, in the companion that Hi
 * Rokid authorized for this link.
 */
object GridApi {
    private const val TAG = "BandGrid"
    private const val ICON_PX = 64

    private val main by lazy { Handler(Looper.getMainLooper()) }
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "nb-grid").apply { isDaemon = true } }
    private var context: Context? = null

    /**
     * The answers to requests already done, by request id. The phone sends a request again until
     * its answer arrives (Rokid's link loses and delays messages): a repeat gets the same answer
     * instead of a second run (a second copy, a "not a web app" for a second remove).
     */
    private val answered = object : LinkedHashMap<Long, JSONObject>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, JSONObject>?) = size > ANSWERS_KEPT
    }

    /** Requests still running (a package downloading): a repeat waits for the first one's answer. */
    private val running = HashSet<Long>()

    private const val ANSWERS_KEPT = 64

    @JvmStatic
    fun start(context: Context) {
        this.context = context.applicationContext
    }

    /** A request from the phone. Main thread. */
    fun onPhoneRequest(request: JSONObject) {
        val ctx = context ?: return
        val op = request.optString("op")
        val id = GridOps.requestId(request)
        Log.d(TAG, "← $op $id")
        if (id != 0L && op != GridOps.DESCRIBE && op != GridOps.ICONS) {
            answered[id]?.let { answer ->
                Log.d(TAG, "$op $id again: the same answer")
                send(answer)
                send(state(ctx).toJson())
                return
            }
            if (id in running) return
            running += id
        }
        when (op) {
            GridOps.DESCRIBE -> send(state(ctx).toJson(request))
            GridOps.SET -> {
                GridStore.set(ctx, GridOps.strings(request, "order"), GridOps.strings(request, "hidden"))
                changed(ctx, request, "grid", null)
            }
            GridOps.ADD_WEB -> {
                val added = WebAppLibrary.add(ctx, request.optString("url"), request.optString("name").ifEmpty { null })
                changed(ctx, request, request.optString("url"), if (added == null) ctx.getString(R.string.webapp_https_apps_only) else null)
            }
            GridOps.REMOVE -> {
                val id = request.optString("id")
                val app = WebAppLibrary.find(ctx, id.removePrefix(GridItem.WEB_PREFIX))
                if (app != null) {
                    WebAppLibrary.remove(ctx, app.id)
                    GridStore.forget(ctx, id)
                }
                changed(ctx, request, id, if (app == null) "not a web app: $id" else null)
            }
            GridOps.ENGINE -> {
                val id = request.optString("id")
                val app = WebAppLibrary.find(ctx, id.removePrefix(GridItem.WEB_PREFIX))
                if (app != null) WebAppLibrary.setEngine(ctx, app.id, WebEngineKind.of(request.optString("engine")))
                changed(ctx, request, id, if (app == null) "not a web app: $id" else null)
            }
            GridOps.CONFIG -> {
                val id = request.optString("id")
                val key = request.optString("key")
                val app = WebAppLibrary.find(ctx, id.removePrefix(GridItem.WEB_PREFIX))
                val error = if (app == null) "not a web app: $id" else WebAppConfig.set(ctx, app, key, request.optString("value"))
                // The key only: the value may be a secret.
                Log.d(TAG, "config $id $key: ${error ?: "set"}")
                changed(ctx, request, id, error)
            }
            GridOps.RENAME -> {
                val id = request.optString("id")
                val app = WebAppLibrary.rename(ctx, id.removePrefix(GridItem.WEB_PREFIX), request.optString("name"))
                changed(ctx, request, id, if (app == null) "not a web app, or an empty name: $id" else null)
            }
            GridOps.COPY -> {
                val id = request.optString("id")
                val copy = runCatching { WebAppLibrary.copy(ctx, id.removePrefix(GridItem.WEB_PREFIX), request.optString("name")) }
                    .onFailure { Log.w(TAG, "copy of $id failed", it) }.getOrNull()
                Log.d(TAG, "copy of $id: ${copy?.id ?: "failed"}")
                changed(ctx, request, copy?.let { GridItem.WEB_PREFIX + it.id } ?: id, if (copy == null) "couldn't copy $id" else null)
            }
            GridOps.ADD_PACKAGE -> installPackage(ctx, request)
            GridOps.INSTALL_FILE -> installFile(ctx, request)
            GridOps.ICONS -> {
                val ids = GridOps.strings(request, "ids")
                io.execute { ids.forEach { id -> icon(ctx, id)?.let { png -> main.post { send(GridEvent.Icon(id, png).toJson()) } } } }
            }
        }
    }

    /** Tells the phone the grid changed on the glasses (a package installed there, say). */
    @JvmStatic
    fun pushState() {
        val ctx = context ?: return
        send(state(ctx).toJson())
    }

    private fun changed(ctx: Context, request: JSONObject, subject: String, error: String?) {
        val answer = GridEvent.Result(error == null, subject, error.orEmpty()).toJson(request)
        GridOps.requestId(request).takeIf { it != 0L }?.let {
            running -= it
            answered[it] = answer
        }
        send(answer)
        send(state(ctx).toJson())
        GridStore.notifyChanged()
    }

    @JvmStatic
    fun state(ctx: Context): GridEvent.State {
        val all = GridStore.items(ctx)
        val layout = GridStore.layout(ctx)
        return GridEvent.State(layout.mapNotNull { all[it] }, all.filterKeys { it !in layout }.values.toList())
    }

    /**
     * Downloads and installs an offline package. Without Wi-Fi of their own the glasses borrow the
     * phone's internet for the download (as a web app would) and give it back afterwards.
     */
    private fun installPackage(ctx: Context, request: JSONObject) {
        val url = request.optString("url")
        val listener = object : PhoneInternet.Listener {
            override fun onStatus(text: String) = Unit

            override fun onReady(proxy: String?) {
                val holder = this
                io.execute {
                    val result = runCatching { WebAppPackages.installFromUrl(ctx, url, proxy) }
                    main.post {
                        PhoneInternet.release(holder)
                        Log.d(TAG, "package $url: ${result.exceptionOrNull()?.message ?: "installed"}")
                        changed(ctx, request, url, result.exceptionOrNull()?.let { WebAppPackages.describe(ctx, it) })
                    }
                }
            }

            override fun onFailed(text: String) {
                PhoneInternet.release(this)
                changed(ctx, request, url, text)
            }
        }
        PhoneInternet.acquire(ctx, listener)
    }

    /** Packages handed over from the phone, by token: null while installing, then the error ("" = installed). */
    private val handedOver = object : LinkedHashMap<String, String?>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 20
    }

    /**
     * Installs (or, with `replace`, updates) a package picked on the phone. The phone repeats the
     * request until it gets an answer (Rokid's link loses messages): a repeat while installing is
     * ignored, one after gets the same answer again. The answer's subject is the token.
     */
    private fun installFile(ctx: Context, request: JSONObject) {
        val token = request.optString("token")
        if (token.isEmpty() || !token.all { it.isLetterOrDigit() }) return changed(ctx, request, token, "invalid token")
        if (handedOver.containsKey(token)) {
            // The same package asked for again under another id: the first one answers.
            running -= GridOps.requestId(request)
            handedOver[token]?.let { error -> send(GridEvent.Result(error.isEmpty(), token, error).toJson(request)) }
            return
        }
        handedOver[token] = null
        val name = request.optString("name").substringAfterLast('/').ifEmpty { "package.zip" }
        val replace = request.optString("replace").takeIf { it.isNotEmpty() }?.removePrefix(GridItem.WEB_PREFIX)
        val listener = object : PhoneInternet.Listener {
            override fun onStatus(text: String) = Unit

            override fun onReady(proxy: String?) {
                val holder = this
                if (proxy == null) {
                    PhoneInternet.release(holder)
                    return finish(ctx.getString(R.string.net_phone_busy))
                }
                io.execute {
                    val result = runCatching {
                        WebAppPackages.installFromPhone(ctx, proxy, token, request.optLong("size"), request.optString("sha256"), name, replace)
                    }
                    main.post {
                        PhoneInternet.release(holder)
                        result.onSuccess { app ->
                            Toast.makeText(ctx, ctx.getString(if (replace != null) R.string.launcher_updated else R.string.launcher_installed, app.name), Toast.LENGTH_SHORT).show()
                        }
                        Log.d(TAG, "package from the phone ($name): ${result.exceptionOrNull()?.message ?: "installed"}")
                        finish(result.exceptionOrNull()?.let { WebAppPackages.describe(ctx, it).ifEmpty { it.javaClass.simpleName } })
                    }
                }
            }

            override fun onFailed(text: String) {
                PhoneInternet.release(this)
                finish(text)
            }

            private fun finish(error: String?) {
                handedOver[token] = error.orEmpty()
                changed(ctx, request, token, error)
            }
        }
        PhoneInternet.acquire(ctx, listener, viaPhone = true)
    }

    /** A small PNG of the item's icon, base64; null for items the phone draws itself. */
    private fun icon(ctx: Context, id: String): String? {
        val bitmap: Bitmap = when {
            id.startsWith(GridItem.WEB_PREFIX) ->
                WebAppLibrary.find(ctx, id.removePrefix(GridItem.WEB_PREFIX))?.let { WebAppIcons.load(it) } ?: return null
            id.startsWith(GridItem.APP_PREFIX) -> runCatching {
                val drawable = ctx.packageManager.getApplicationIcon(id.removePrefix(GridItem.APP_PREFIX))
                Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888).also { bmp ->
                    drawable.setBounds(0, 0, ICON_PX, ICON_PX)
                    drawable.draw(Canvas(bmp))
                }
            }.getOrNull() ?: return null
            else -> return null
        }
        val scaled = Bitmap.createScaledBitmap(bitmap, ICON_PX, ICON_PX, true)
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun send(json: JSONObject) {
        PhoneLink.send(Link.GRID_EVENT, json)
    }
}
