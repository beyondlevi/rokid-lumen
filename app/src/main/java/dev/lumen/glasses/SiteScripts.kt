package dev.lumen.glasses

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * Site scripts: the JavaScript and CSS an online app's package ([WebAppPackages]) declares in its
 * manifest's `lumen_scripts`, so a site it opens works with the band on the glasses (Instagram's
 * Reels, YouTube's player). GeckoView runs them in the pages of the sites they name, in the
 * page's own world at document_start, only while their app is in front ([GeckoWebEngine]
 * registers them through the built-in extension); the system WebView runs none. The install
 * confirmation names the sites they change ([hosts]).
 *
 * What a script may name is kept narrow: `https://` sites by name (a `*.` subdomain wildcard at
 * most), files inside the package, [MAX_BYTES] in all. The package's gesture card
 * (`lumen_gestures`, [gestureCard]) is read here too.
 *
 * The parsing functions take the manifest's JSON and files only, so they're tested without a device.
 */
object SiteScripts {
    private const val TAG = "BandSiteScripts"
    const val MAX_ENTRIES = 8
    /** Every script and style sheet together; they're held in memory and sent to the extension at each app switch. */
    const val MAX_BYTES = 1024L * 1024
    /** The rows a gesture card shows: more wouldn't fit the HUD's square. */
    const val MAX_ROWS = 6

    /** A `https://<host>/<path>` match pattern; the host has a `*.` wildcard at most and two labels or more. */
    private val MATCH = Regex("""^https://((?:\*\.)?(?:[a-z0-9-]+\.)+[a-z0-9-]+)(/.*)$""", RegexOption.IGNORE_CASE)

    /** One `lumen_scripts` entry as declared: the pages, and the package's files. */
    data class Declared(val matches: List<String>, val js: List<String>, val css: List<String>)

    /** One registration: the files' code, each kind joined. */
    data class Script(val matches: List<String>, val js: String, val css: String)

    /**
     * What the extension registers while an app is in front; [key] names it (the app, its
     * version and its code), so the extension's acknowledgement says which set is in place.
     */
    data class Registration(val key: String, val scripts: List<Script>) {
        val isEmpty: Boolean get() = scripts.isEmpty()

        /** The message background.js takes (`siteScripts`); no tabId: it's for the background. */
        fun toMessage(): JSONObject = JSONObject()
            .put("type", "siteScripts")
            .put("key", key)
            .put("scripts", JSONArray().apply {
                scripts.forEach { put(JSONObject().put("matches", JSONArray(it.matches)).put("js", it.js).put("css", it.css)) }
            })

        companion object {
            /** No scripts: an app without them clears the previous app's. */
            @JvmField
            val NONE = Registration("", emptyList())
        }
    }

    /**
     * The manifest's `lumen_scripts`, checked: an array of at most [MAX_ENTRIES] objects, each
     * with `matches` ([hostOf]) and `js` and/or `css` package paths ([isPackagePath]). Empty
     * without the field; throws [WebAppPackages.InvalidPackage] for anything else.
     */
    @JvmStatic
    fun declared(manifest: JSONObject?): List<Declared> {
        if (manifest == null || !manifest.has("lumen_scripts")) return emptyList()
        val array = manifest.opt("lumen_scripts") as? JSONArray ?: throw invalid("lumen_scripts is not an array")
        if (array.length() > MAX_ENTRIES) throw invalid("more than $MAX_ENTRIES entries")
        return (0 until array.length()).map { i ->
            val entry = array.opt(i) as? JSONObject ?: throw invalid("entry $i is not an object")
            val matches = strings(entry, "matches", i)
            if (matches.isEmpty()) throw invalid("entry $i has no matches")
            matches.firstOrNull { hostOf(it) == null }?.let { throw WebAppPackages.InvalidPackage(WebAppPackages.Problem.SCRIPT_MATCH, it) }
            val js = strings(entry, "js", i)
            val css = strings(entry, "css", i)
            if (js.isEmpty() && css.isEmpty()) throw invalid("entry $i has no js or css")
            (js + css).firstOrNull { !isPackagePath(it) }?.let { throw WebAppPackages.InvalidPackage(WebAppPackages.Problem.SCRIPT_PATH, it) }
            Declared(matches, js, css)
        }
    }

    private fun strings(entry: JSONObject, key: String, index: Int): List<String> {
        if (!entry.has(key)) return emptyList()
        val array = entry.opt(key) as? JSONArray ?: throw invalid("entry $index: $key is not an array")
        return (0 until array.length()).map { array.opt(it) as? String ?: throw invalid("entry $index: $key holds a non-string") }
    }

    private fun invalid(detail: String) = WebAppPackages.InvalidPackage(WebAppPackages.Problem.SCRIPTS_INVALID, detail)

    /**
     * The site a match pattern names (`www.instagram.com`, `*.youtube.com`), or null when it isn't
     * one Lumen accepts: another scheme, `<all_urls>`, a bare `*`, a port, a one-label host
     * (`*.com` would be every .com site).
     */
    @JvmStatic
    fun hostOf(match: String): String? = MATCH.matchEntire(match)?.groupValues?.get(1)?.lowercase(Locale.ROOT)

    /** The distinct sites [declared] scripts change, in order: what the install confirmation shows. */
    @JvmStatic
    fun hosts(declared: List<Declared>): List<String> = declared.flatMap { it.matches }.mapNotNull { hostOf(it) }.distinct()

    /** A relative path inside the package: not absolute, no `..`, no scheme, no backslash. */
    @JvmStatic
    fun isPackagePath(path: String): Boolean {
        if (path.isBlank() || path.startsWith("/") || path.contains('\\') || path.contains(':')) return false
        return path.split('/').none { it == ".." }
    }

    /** The file behind a declared [path] in [base], never outside it; throws when it isn't there. */
    private fun file(base: File, path: String): File {
        val file = File(base, path)
        if (!isPackagePath(path) || !file.isFile || !file.canonicalPath.startsWith(base.canonicalPath + File.separator)) {
            throw WebAppPackages.InvalidPackage(WebAppPackages.Problem.SCRIPT_PATH, path)
        }
        return file
    }

    /** Checks that every file [declared] names is in [base] and that together they fit [MAX_BYTES]. */
    @JvmStatic
    fun check(base: File, declared: List<Declared>) {
        val total = declared.sumOf { entry -> (entry.js + entry.css).sumOf { file(base, it).length() } }
        if (total > MAX_BYTES) throw WebAppPackages.InvalidPackage(WebAppPackages.Problem.SCRIPTS_TOO_BIG)
    }

    /**
     * The code of [declared] scripts in [base], [check]ed first. A file's scripts are joined with
     * a `;` line, so one ending without a semicolon can't run into the next.
     */
    @JvmStatic
    fun load(base: File, declared: List<Declared>): List<Script> {
        check(base, declared)
        return declared.map { entry ->
            Script(
                entry.matches,
                entry.js.joinToString("\n;\n") { file(base, it).readText() },
                entry.css.joinToString("\n") { file(base, it).readText() },
            )
        }
    }

    /** Names a set of scripts: the app, its version and a hash of the code, so any change is a new key. */
    @JvmStatic
    fun key(appId: String, version: String, scripts: List<Script>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        scripts.forEach { script ->
            listOf(script.matches.joinToString("\n"), script.js, script.css).forEach {
                digest.update(it.toByteArray(Charsets.UTF_8))
                digest.update(0)
            }
        }
        val hash = digest.digest().take(8).joinToString("") { "%02x".format(it) }
        return "$appId@$version#$hash"
    }

    /**
     * What to register while [app] is in front: a packaged online app's scripts, read from its
     * package; [Registration.NONE] for any other app, or when the package's scripts can't be read
     * (they were checked at install).
     */
    @JvmStatic
    fun registrationFor(context: Context, app: WebApp): Registration {
        if (app.offline || !app.packaged) return Registration.NONE
        val base = WebAppPackages.dir(context, app.id)
        return runCatching {
            val scripts = load(base, declared(WebAppPackages.readManifest(base)))
            if (scripts.isEmpty()) Registration.NONE else Registration(key(app.id, app.version, scripts), scripts)
        }.getOrElse {
            Log.w(TAG, "No site scripts for ${app.name}: ${it.message}")
            Registration.NONE
        }
    }

    // The gesture card.

    /** The band gestures a card row names (`lumen_gestures` tokens). */
    enum class Gesture(val token: String) {
        UP("up"), DOWN("down"), LEFT("left"), RIGHT("right"), INDEX("index"), MIDDLE("middle");

        companion object {
            fun of(token: String?): Gesture? = entries.firstOrNull { it.token == token?.trim()?.lowercase(Locale.ROOT) }
        }
    }

    data class GestureRow(val gestures: List<Gesture>, val text: String)

    /** What the band does in an app, shown when it opens ([WebAppGuide]); [title] may be empty. */
    data class GestureCard(val title: String, val rows: List<GestureRow>)

    /**
     * The manifest's `lumen_gestures` in [language] (an ISO code, `pt` for pt-BR and pt-PT): a
     * title and rows of gesture tokens and text. Unknown tokens and rows without a gesture or a
     * text are skipped, past [MAX_ROWS] too; null when no row is left.
     */
    @JvmStatic
    fun gestureCard(manifest: JSONObject?, language: String): GestureCard? {
        val card = manifest?.optJSONObject("lumen_gestures") ?: return null
        val rows = card.optJSONArray("rows") ?: return null
        val parsed = (0 until rows.length()).mapNotNull { i ->
            val row = rows.optJSONObject(i) ?: return@mapNotNull null
            val tokens = row.optJSONArray("gestures") ?: return@mapNotNull null
            val gestures = (0 until tokens.length()).mapNotNull { Gesture.of(tokens.optString(it)) }
            val text = localized(row.opt("text"), language)
            if (gestures.isEmpty() || text.isEmpty()) null else GestureRow(gestures, text)
        }.take(MAX_ROWS)
        if (parsed.isEmpty()) return null
        return GestureCard(localized(card.opt("title"), language), parsed)
    }

    /**
     * A text of the manifest: a plain string, or one per language (`{"en": …, "pt": …}`) picked by
     * [language], then English, then the first one given.
     */
    @JvmStatic
    fun localized(value: Any?, language: String): String {
        if (value is String) return value.trim()
        val texts = value as? JSONObject ?: return ""
        val lang = language.lowercase(Locale.ROOT).substringBefore('-').substringBefore('_')
        fun text(key: String) = (texts.opt(key) as? String)?.trim().orEmpty()
        return text(lang).ifEmpty { text("en") }.ifEmpty {
            texts.keys().asSequence().map { text(it) }.firstOrNull { it.isNotEmpty() }.orEmpty()
        }
    }
}
