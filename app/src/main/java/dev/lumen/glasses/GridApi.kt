package dev.lumen.glasses

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
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

    @JvmStatic
    fun start(context: Context) {
        this.context = context.applicationContext
    }

    /** A request from the phone. Main thread. */
    fun onPhoneRequest(request: JSONObject) {
        val ctx = context ?: return
        when (request.optString("op")) {
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
            GridOps.ADD_PACKAGE -> installPackage(ctx, request)
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
        send(GridEvent.Result(error == null, subject, error.orEmpty()).toJson(request))
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
