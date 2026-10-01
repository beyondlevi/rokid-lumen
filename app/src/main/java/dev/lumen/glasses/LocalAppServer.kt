package dev.lumen.glasses

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Serves offline web apps over HTTP on the loopback interface, one port per app, so each app
 * has its own origin (localStorage, service workers) and `127.0.0.1` counts as a secure context
 * in both engines. GET and HEAD only; paths that aren't files get index.html (SPA routes).
 *
 * Google Fonts requests in the app's HTML, CSS and JS are pointed at a bundled Noto Sans
 * (`/__mrbd/gfonts.css`), which MRBD's UI library downloads: the app works with no network.
 *
 * Other pages can't use an app's server: a request must name the server itself in its `Host`
 * (a DNS-rebound name is refused), responses can't be embedded by another origin
 * (`Cross-Origin-Resource-Policy`, `X-Frame-Options`), and a server runs only while a screen
 * uses it ([acquire], [release]), plus [idleMs].
 */
object LocalAppServer {
    private const val TAG = "BandLocalServer"
    const val HOST = "127.0.0.1"
    const val FONTS_CSS = "/__mrbd/gfonts.css"
    const val FONTS_DIR = "/__mrbd/fonts/"
    /** How long a server nobody uses keeps running: an app reopened soon finds it up. */
    const val IDLE_MS = 60_000L

    private val servers = ConcurrentHashMap<Int, ServerSocket>()
    private val workers = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "local-app-server").apply { isDaemon = true }
    }
    private val timer = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "local-app-server-idle").apply { isDaemon = true }
    }
    /** Screens using each port's server, and the idle stops waiting to run. Guarded by this object. */
    private val users = HashMap<Int, Int>()
    private val idleStops = HashMap<Int, ScheduledFuture<*>>()

    /** [IDLE_MS]; tests shorten it. */
    @Volatile
    @JvmStatic
    var idleMs = IDLE_MS

    @JvmStatic
    fun origin(port: Int) = "http://$HOST:$port"

    /**
     * A screen opens the app: its server starts (or stays up) until the matching [release].
     * Returns false when the port can't be bound. Online apps have no server.
     */
    @JvmStatic
    @Synchronized
    fun acquire(context: Context, app: WebApp): Boolean {
        if (!app.offline) return true
        if (!ensure(context, app)) return false
        users[app.port] = (users[app.port] ?: 0) + 1
        idleStops.remove(app.port)?.cancel(false)
        return true
    }

    /** The screen that [acquire]d the server closed; the last one out stops it after [idleMs]. */
    @JvmStatic
    @Synchronized
    fun release(port: Int) {
        val left = (users[port] ?: return) - 1
        if (left > 0) {
            users[port] = left
            return
        }
        users.remove(port)
        idleStops.remove(port)?.cancel(false)
        idleStops[port] = timer.schedule({ stopIfIdle(port) }, idleMs, TimeUnit.MILLISECONDS)
    }

    @Synchronized
    private fun stopIfIdle(port: Int) {
        idleStops.remove(port)
        if (users[port] == null) {
            Log.d(TAG, "Stopping the idle server on $port")
            stop(port)
        }
    }

    @JvmStatic
    fun isRunning(port: Int): Boolean = servers[port]?.isClosed == false

    /** Starts the app's server if it isn't running. Returns false when the port can't be bound. */
    @Synchronized
    private fun ensure(context: Context, app: WebApp): Boolean {
        if (!app.offline) return true
        servers[app.port]?.let { if (!it.isClosed) return true }
        val root = WebAppPackages.dir(context, app.id)
        val assets = context.applicationContext.assets
        val socket = runCatching {
            ServerSocket(app.port, 16, InetAddress.getByName(HOST)).apply { reuseAddress = true }
        }.getOrElse {
            Log.w(TAG, "Can't listen on ${app.port}", it)
            return false
        }
        servers[app.port] = socket
        workers.execute {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                workers.execute { serve(client, app.port, root, assets) }
            }
        }
        Log.d(TAG, "Serving ${app.name} from $root on ${app.port}")
        return true
    }

    /** Stops the server on [port] now (the app was removed, say). */
    @JvmStatic
    @Synchronized
    fun stop(port: Int) {
        idleStops.remove(port)?.cancel(false)
        servers.remove(port)?.let { runCatching { it.close() } }
    }

    /**
     * DNS-rebinding defence: a page on another name that resolves to 127.0.0.1 still sends that
     * name as its Host, so only the server's own addresses pass (`127.0.0.1:<port>`,
     * `localhost:<port>`). Public for tests.
     */
    @JvmStatic
    fun hostAllowed(host: String?, port: Int): Boolean {
        val value = host?.trim()?.lowercase(Locale.ROOT) ?: return false
        return value == "$HOST:$port" || value == "localhost:$port"
    }

    private fun serve(client: Socket, port: Int, root: File, assets: AssetManager) {
        client.use { socket ->
            socket.soTimeout = 15_000
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            val requestLine = readLine(input) ?: return
            val hosts = mutableListOf<String>()
            var headers = 0
            while (true) {
                val header = readLine(input) ?: break
                if (header.isEmpty()) break
                if (++headers > MAX_HEADERS) return
                if (header.substringBefore(':').trim().equals("Host", ignoreCase = true)) hosts += header.substringAfter(':')
            }
            val parts = requestLine.split(' ')
            if (parts.size < 2) return
            val method = parts[0]
            if (hosts.size != 1 || !hostAllowed(hosts[0], port)) {
                Log.w(TAG, "Refused a request for ${hosts.joinToString().take(80)} on $port")
                respond(output, 421, "text/plain", "Misdirected request".toByteArray(), method == "HEAD")
                return
            }
            if (method != "GET" && method != "HEAD") {
                respond(output, 405, "text/plain", "Method not allowed".toByteArray(), method == "HEAD")
                return
            }
            val response = resolve(parts[1], root, assets)
            respond(output, response.status, response.type, response.body, method == "HEAD", response.cache)
        }
    }

    class Response(val status: Int, val type: String, val body: ByteArray, val cache: String = "no-cache")

    /** What a request path gets: the bundled fonts, a file of the app, or index.html. Public for tests. */
    @JvmStatic
    fun resolve(rawPath: String, root: File, assets: AssetManager?): Response {
        val path = runCatching { URLDecoder.decode(rawPath.substringBefore('?').substringBefore('#'), "UTF-8") }
            .getOrElse { return Response(400, "text/plain", "Bad request".toByteArray()) }
        if (path == FONTS_CSS) {
            return asset(assets, "fonts/fonts.css", "text/css; charset=utf-8")
        }
        if (path.startsWith(FONTS_DIR)) {
            val name = path.removePrefix(FONTS_DIR)
            if (name.contains('/') || !name.endsWith(".woff2")) return notFound()
            return asset(assets, "fonts/$name", "font/woff2", "max-age=31536000")
        }
        val relative = path.trimStart('/')
        val canonicalRoot = root.canonicalFile
        var file = File(canonicalRoot, relative).canonicalFile
        if (file != canonicalRoot && !file.path.startsWith(canonicalRoot.path + File.separator)) {
            return Response(403, "text/plain", "Forbidden".toByteArray())
        }
        if (file.isDirectory) file = File(file, "index.html")
        if (!file.isFile) {
            // A file-looking path that's missing is a 404; anything else is a route of the SPA.
            if (relative.substringAfterLast('/').contains('.')) return notFound()
            file = File(canonicalRoot, "index.html")
            if (!file.isFile) return notFound()
        }
        val type = mimeType(file.name)
        var body = file.readBytes()
        if (rewritesFonts(type)) body = rewriteFonts(String(body, Charsets.UTF_8)).toByteArray(Charsets.UTF_8)
        val cache = if (file.name == "index.html" || file.name.endsWith(".webmanifest")) "no-cache" else "max-age=3600"
        return Response(200, type, body, cache)
    }

    private fun notFound() = Response(404, "text/plain", "Not found".toByteArray())

    private fun asset(assets: AssetManager?, name: String, type: String, cache: String = "no-cache"): Response =
        runCatching { assets!!.open(name).use { Response(200, type, it.readBytes(), cache) } }.getOrElse { notFound() }

    private fun rewritesFonts(type: String) =
        type.startsWith("text/html") || type.startsWith("text/css") || type.startsWith("text/javascript")

    /** Google Fonts stylesheets become the bundled one; font files would be unreachable anyway. */
    @JvmStatic
    fun rewriteFonts(text: String): String {
        if (!text.contains("fonts.g")) return text
        return text
            .replace(Regex("""https?://fonts\.googleapis\.com/css2?"""), FONTS_CSS)
            .replace(Regex("""https?://fonts\.gstatic\.com/"""), "/__mrbd/gstatic/")
    }

    @JvmStatic
    fun mimeType(name: String): String = when (name.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "html", "htm" -> "text/html; charset=utf-8"
        "js", "mjs", "cjs" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json", "map" -> "application/json; charset=utf-8"
        "webmanifest" -> "application/manifest+json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "gif" -> "image/gif"
        "webp" -> "image/webp"
        "avif" -> "image/avif"
        "ico" -> "image/x-icon"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "otf" -> "font/otf"
        "wasm" -> "application/wasm"
        "mp3" -> "audio/mpeg"
        "ogg", "oga" -> "audio/ogg"
        "wav" -> "audio/wav"
        "mp4" -> "video/mp4"
        "webm" -> "video/webm"
        "txt" -> "text/plain; charset=utf-8"
        "xml" -> "application/xml"
        else -> "application/octet-stream"
    }

    private const val MAX_HEADERS = 100

    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == '\n'.code) return line.toString().trimEnd('\r')
            if (line.length > 8192) return null
            line.append(c.toChar())
        }
    }

    private fun respond(out: OutputStream, status: Int, type: String, body: ByteArray, head: Boolean, cache: String = "no-cache") {
        val reason = when (status) {
            200 -> "OK"; 400 -> "Bad Request"; 403 -> "Forbidden"; 404 -> "Not Found"; 405 -> "Method Not Allowed"
            421 -> "Misdirected Request"
            else -> "Error"
        }
        val headers = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: $type\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Cache-Control: $cache\r\n" +
            "X-Content-Type-Options: nosniff\r\n" +
            // Only the app's own pages may load or frame what its server returns.
            "Cross-Origin-Resource-Policy: same-origin\r\n" +
            "X-Frame-Options: SAMEORIGIN\r\n" +
            "Connection: close\r\n\r\n"
        out.write(headers.toByteArray(Charsets.US_ASCII))
        if (!head) out.write(body)
        out.flush()
    }
}
