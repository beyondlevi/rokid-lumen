package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
class InstallPreviewTest {
    private val context = RuntimeEnvironment.getApplication()

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private val manifest = """{"id":"notes","short_name":"Notes","version":"2.1","lumen_internet":true,
        "lumen_config":[{"key":"server.url","label":"Server URL","type":"url"},{"key":"server.key","type":"secret"}]}"""

    @Test
    fun anOnlineAppShowsItsHostAndWhatItUpdates() {
        val url = "https://notes.example/app/"
        val fresh = InstallPreview.forWebApp(context, url, " ")
        assertEquals("notes.example", fresh.name)
        assertEquals("notes.example", fresh.source)
        assertFalse(fresh.offline)
        assertTrue(fresh.internet)
        assertNull(fresh.updates)
        WebAppLibrary.add(context, url, "My notes")
        assertEquals("My notes", InstallPreview.forWebApp(context, url, "Notes 2").updates)
        assertEquals("Notes 2", InstallPreview.forWebApp(context, url, "Notes 2").name)
        assertNull(InstallPreview.forWebApp(context, "https://notes.example/other/", null).updates)
    }

    @Test
    fun aStagedPackageShowsItsManifestAndInstallsNothingUntilCommitted() {
        val staged = WebAppPackages.stage(context, ByteArrayInputStream(zip("index.html" to "v1", "manifest.webmanifest" to manifest)), "notes.mrbd.zip")
        val preview = InstallPreview.forPackage(context, staged, "https://dl.example/notes.mrbd.zip")
        assertEquals(InstallPreview("Notes", "dl.example", true, "2.1", true, listOf("Server URL", "server.key"), null), preview)
        // Staged only: not in the library, not in place.
        assertTrue(WebAppLibrary.all(context).isEmpty())
        assertFalse(WebAppPackages.dir(context, staged.id).exists())
        assertTrue(staged.base.isDirectory)

        val app = WebAppPackages.commit(context, staged)
        assertEquals(staged.id, app.id)
        assertFalse(staged.base.exists())
        assertEquals("v1", java.io.File(WebAppPackages.dir(context, app.id), "index.html").readText())

        // The same manifest id again: it updates Notes (and would keep its port).
        val update = WebAppPackages.stage(context, ByteArrayInputStream(zip("dist/index.html" to "v2", "dist/manifest.webmanifest" to manifest)), "other.mrbd.zip")
        assertEquals("Notes", InstallPreview.forPackage(context, update, "https://dl.example/other.mrbd.zip").updates)
        // Cancelled: the staging goes, the installed version stays.
        WebAppPackages.discard(update)
        assertFalse(update.base.exists())
        assertEquals("v1", java.io.File(WebAppPackages.dir(context, app.id), "index.html").readText())
        assertEquals(1, WebAppLibrary.all(context).size)
        WebAppLibrary.remove(context, app.id)
    }

    @Test
    fun anUpdateFromAnotherOriginForgetsTheSecretsAndSaysSo() {
        fun staged(origin: String) = WebAppPackages.stage(
            context, ByteArrayInputStream(zip("index.html" to "v", "manifest.webmanifest" to manifest)), "notes.mrbd.zip", origin,
        )
        val app = WebAppPackages.commit(context, staged("https://dl.example"))
        assertEquals("https://dl.example", app.source)
        assertNull(WebAppConfig.set(context, app, "server.url", "https://notes.example"))
        assertNull(WebAppConfig.set(context, app, "server.key", "s3cret"))

        // Same origin: an ordinary update, the secrets stay.
        val same = staged("https://dl.example")
        assertFalse(InstallPreview.forPackage(context, same, "https://dl.example/notes.mrbd.zip").clearsSecrets)
        WebAppPackages.commit(context, same)
        assertEquals("s3cret", WebAppConfig.values(context, app).optString("server.key"))

        // The owner's channels (adb, the phone) keep them whatever the origin.
        WebAppPackages.commit(context, staged("https://mirror.example"), trusted = true)
        assertEquals("s3cret", WebAppConfig.values(context, app).optString("server.key"))

        // Another origin claiming the same manifest id: the confirmation warns, the secret goes,
        // the other settings stay.
        val other = staged("https://evil.example")
        assertTrue(InstallPreview.forPackage(context, other, "https://evil.example/notes.mrbd.zip").clearsSecrets)
        val updated = WebAppPackages.commit(context, other)
        val values = WebAppConfig.values(context, updated)
        assertFalse(values.has("server.key"))
        assertEquals("https://notes.example", values.optString("server.url"))
        assertEquals("https://evil.example", updated.source)
        WebAppLibrary.remove(context, app.id)
    }

    @Test
    fun aPackageWithoutManifestIsNamedAfterItsFile() {
        val staged = WebAppPackages.stage(context, ByteArrayInputStream(zip("index.html" to "x")), "plain.mrbd.zip")
        val preview = InstallPreview.forPackage(context, staged, "https://dl.example/plain.mrbd.zip")
        assertEquals("plain", preview.name)
        assertFalse(preview.internet)
        assertTrue(preview.settings.isEmpty())
        WebAppPackages.discard(staged)
        assertFalse(staged.base.exists())
    }

    @Test
    fun aBrokenPackageLeavesNoStagingBehind() {
        try {
            WebAppPackages.stage(context, ByteArrayInputStream(zip("readme.txt" to "no index")), "broken.mrbd.zip")
            throw AssertionError("staged a package without index.html")
        } catch (e: WebAppPackages.InvalidPackage) {
            assertEquals(WebAppPackages.Problem.NO_INDEX, e.problem)
        }
        assertTrue(WebAppPackages.root(context).listFiles().orEmpty().none { it.name.startsWith(".staging-") })
    }
}
