package dev.lumen.glasses

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WebAppCopiesTest {
    private val context: Context = org.robolectric.RuntimeEnvironment.getApplication()

    private fun offlineApp(): WebApp {
        val port = WebAppLibrary.allocatePort(context)
        val app = WebApp("pkg-wa", "WhatsApp", true, "", port, WebEngineKind.GECKO, null, "0.4.2")
        WebAppPackages.dir(context, app.id).apply { mkdirs() }.resolve("index.html").writeText("<html>wa</html>")
        WebAppLibrary.put(context, app)
        return app
    }

    @Test
    fun aCopyHasItsOwnIdPortFilesAndName() {
        val original = offlineApp()
        val copy = WebAppLibrary.copy(context, original.id, "  WhatsApp Work  ")!!
        assertNotEquals(original.id, copy.id)
        assertTrue(copy.id.startsWith(original.id + "-"))
        assertNotEquals(original.port, copy.port)
        assertEquals("WhatsApp Work", copy.name)
        assertEquals(original.id, copy.copyOf)
        assertTrue(copy.renamed)
        assertEquals("<html>wa</html>", WebAppPackages.dir(context, copy.id).resolve("index.html").readText())
        // A copy of the copy belongs to the same original.
        assertEquals(original.id, WebAppLibrary.copy(context, copy.id, "Third")!!.copyOf)
        assertEquals(2, WebAppLibrary.copiesOf(context, original.id).size)
        // It survives the library's storage.
        assertEquals(copy, WebAppLibrary.find(context, copy.id))
    }

    @Test
    fun anOnlineCopyKeepsTheAddressButNotTheId() {
        val online = WebAppLibrary.add(context, "https://web.telegram.org/", "Telegram")!!
        val copy = WebAppLibrary.copy(context, online.id, "Telegram Work")!!
        assertEquals(online.remoteUrl, copy.remoteUrl)
        assertNotEquals(online.id, copy.id)
        assertEquals(0, copy.port)
    }

    @Test
    fun renamingIsKeptAndEmptyNamesAreRefused() {
        val original = offlineApp()
        assertNull(WebAppLibrary.rename(context, original.id, "   "))
        assertNull(WebAppLibrary.copy(context, original.id, ""))
        val renamed = WebAppLibrary.rename(context, original.id, "Personal")!!
        assertEquals("Personal", renamed.name)
        assertTrue(WebAppLibrary.find(context, original.id)!!.renamed)
    }
}
