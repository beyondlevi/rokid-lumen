package dev.lumen.companion.update

/**
 * A release version, `MAJOR.MINOR.PATCH[-PRE]` as the tags are (`v0.2.0-beta.4`), ordered as
 * SemVer orders them: a pre-release comes before its release, `beta.10` after `beta.9`.
 */
data class SemVer(val major: Int, val minor: Int, val patch: Int, val pre: List<String> = emptyList()) : Comparable<SemVer> {
    val isPrerelease: Boolean get() = pre.isNotEmpty()

    override fun compareTo(other: SemVer): Int {
        compareValues(major, other.major).takeIf { it != 0 }?.let { return it }
        compareValues(minor, other.minor).takeIf { it != 0 }?.let { return it }
        compareValues(patch, other.patch).takeIf { it != 0 }?.let { return it }
        // A release ranks above its own pre-releases.
        if (pre.isEmpty() && other.pre.isEmpty()) return 0
        if (pre.isEmpty()) return 1
        if (other.pre.isEmpty()) return -1
        for (i in 0 until minOf(pre.size, other.pre.size)) {
            val a = pre[i]
            val b = other.pre[i]
            val an = a.toIntOrNull()
            val bn = b.toIntOrNull()
            val c = when {
                an != null && bn != null -> an.compareTo(bn)
                an != null -> -1
                bn != null -> 1
                else -> a.compareTo(b)
            }
            if (c != 0) return c
        }
        return pre.size.compareTo(other.pre.size)
    }

    override fun toString(): String = "$major.$minor.$patch" + if (pre.isEmpty()) "" else "-" + pre.joinToString(".")

    companion object {
        private val PATTERN = Regex("""^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z]+(?:\.[0-9A-Za-z]+)*))?$""")

        /** [text] as a version ("v" allowed), or null when it isn't one (a dev build's name is). */
        @JvmStatic
        fun parse(text: String?): SemVer? {
            val match = PATTERN.matchEntire(text?.trim() ?: return null) ?: return null
            val (major, minor, patch, pre) = match.destructured
            return SemVer(major.toInt(), minor.toInt(), patch.toInt(), if (pre.isEmpty()) emptyList() else pre.split('.'))
        }
    }
}
