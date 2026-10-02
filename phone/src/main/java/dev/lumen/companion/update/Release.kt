package dev.lumen.companion.update

import org.json.JSONArray
import org.json.JSONObject

/** One file of a release. [sha256] is GitHub's digest of it, lowercase hex, or null. */
data class ReleaseAsset(val name: String, val url: String, val size: Long, val sha256: String?)

/** A published release of the repository, as GitHub's API describes it. */
data class Release(
    val tag: String,
    val version: SemVer,
    val title: String,
    /** The release notes, Markdown (the CHANGELOG section of that version). */
    val notes: String,
    val prerelease: Boolean,
    /** ISO-8601, or "" when GitHub has none. */
    val publishedAt: String,
    val assets: List<ReleaseAsset>,
) {
    /** The glasses app of this release (`rokid-lumen-glasses-<version>.apk`). */
    val glassesApk: ReleaseAsset? get() = assets.firstOrNull { it.name == "$GLASSES_PREFIX$version.apk" }

    /** The companion of this release (`rokid-lumen-companion-<version>.apk`). */
    val companionApk: ReleaseAsset? get() = assets.firstOrNull { it.name == "$COMPANION_PREFIX$version.apk" }

    companion object {
        const val GLASSES_PREFIX = "rokid-lumen-glasses-"
        const val COMPANION_PREFIX = "rokid-lumen-companion-"

        /**
         * The releases in GitHub's list that count: published (not drafts), tagged `vX.Y.Z[-pre]`,
         * newest version first. Assets must be HTTPS; a digest that isn't `sha256:<64 hex>` is
         * dropped (the download then fails its check rather than skipping it).
         */
        @JvmStatic
        fun parseList(json: String): List<Release> {
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
            return (0 until array.length()).mapNotNull { i -> array.optJSONObject(i)?.let(::parse) }.sortedByDescending { it.version }
        }

        private fun parse(json: JSONObject): Release? {
            if (json.optBoolean("draft")) return null
            val tag = json.optString("tag_name")
            if (!tag.startsWith("v")) return null
            val version = SemVer.parse(tag) ?: return null
            val assets = json.optJSONArray("assets") ?: JSONArray()
            return Release(
                tag = tag,
                version = version,
                title = json.optString("name").ifBlank { tag },
                notes = json.optString("body"),
                prerelease = json.optBoolean("prerelease") || version.isPrerelease,
                publishedAt = if (json.isNull("published_at")) "" else json.optString("published_at"),
                assets = (0 until assets.length()).mapNotNull { j ->
                    val a = assets.optJSONObject(j) ?: return@mapNotNull null
                    val url = a.optString("browser_download_url")
                    if (!url.startsWith("https://")) return@mapNotNull null
                    val digest = a.optString("digest").takeIf { it.matches(Regex("sha256:[0-9a-fA-F]{64}")) }?.substringAfter(':')?.lowercase()
                    ReleaseAsset(a.optString("name"), url, a.optLong("size"), digest)
                },
            )
        }

        /**
         * The newest release above [installed] that has the app ([pick]), betas only when
         * [includeBeta]; null when there's none. An unparsable [installed] (a dev build) is
         * never offered an update.
         */
        @JvmStatic
        fun newest(releases: List<Release>, installed: String?, includeBeta: Boolean, pick: (Release) -> ReleaseAsset?): Release? {
            val current = SemVer.parse(installed) ?: return null
            return releases.filter { includeBeta || !it.prerelease }.filter { it.version > current }.firstOrNull { pick(it) != null }
        }
    }
}
