package dev.lumen.companion.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseTest {
    @Test
    fun versionsOrderAsSemVer() {
        val beta4 = SemVer.parse("v0.2.0-beta.4")!!
        assertTrue(SemVer.parse("0.2.0-beta.10")!! > beta4)
        assertTrue(SemVer.parse("0.2.0")!! > beta4)
        assertTrue(SemVer.parse("0.1.0")!! < beta4)
        assertTrue(SemVer.parse("0.2.0-beta")!! < beta4)
        assertEquals("0.2.0-beta.4", beta4.toString())
        assertNull(SemVer.parse("0.1.0 dev"))
        assertNull(SemVer.parse("latest"))
    }

    private fun release(tag: String, pre: Boolean, draft: Boolean = false, digest: Boolean = true) = """
        {"tag_name":"$tag","name":"Rokid Lumen ${tag.drop(1)}","body":"- one","draft":$draft,"prerelease":$pre,"published_at":"2026-10-02T12:00:00Z",
         "assets":[
          {"name":"rokid-lumen-glasses-${tag.drop(1)}.apk","browser_download_url":"https://github.com/x/$tag/g.apk","size":10${if (digest) ",\"digest\":\"sha256:" + "a".repeat(64) + "\"" else ""}},
          {"name":"rokid-lumen-companion-${tag.drop(1)}.apk","browser_download_url":"http://insecure/c.apk","size":5}
         ]}
    """.trimIndent()

    @Test
    fun theListDropsDraftsAndOtherTags() {
        val json = "[" + listOf(release("v0.2.0-beta.4", true), release("v0.3.0", false, draft = true), release("plugin-1", false), release("v0.1.0", false)).joinToString(",") + "]"
        val list = Release.parseList(json)
        assertEquals(listOf("v0.2.0-beta.4", "v0.1.0"), list.map { it.tag })
        // Only HTTPS assets; the digest becomes plain hex.
        assertNull(list[0].companionApk)
        assertEquals("a".repeat(64), list[0].glassesApk!!.sha256)
    }

    @Test
    fun betasOnlyWhenAsked() {
        val list = Release.parseList("[" + release("v0.2.0-beta.4", true) + "," + release("v0.1.0", false) + "]")
        assertNull(Release.newest(list, "0.1.0", includeBeta = false) { it.glassesApk })
        assertEquals("v0.2.0-beta.4", Release.newest(list, "0.1.0", includeBeta = true) { it.glassesApk }?.tag)
        assertNull(Release.newest(list, "0.2.0-beta.4", includeBeta = true) { it.glassesApk })
        // A dev build is never offered an update.
        assertNull(Release.newest(list, "0.1.0-dev build", includeBeta = true) { it.glassesApk })
    }
}
