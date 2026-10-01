package dev.lumen.companion

import dev.lumen.companion.speech.SpeechLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class VoskModelTest {
    @get:Rule
    val temp = TemporaryFolder()

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

    private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun `the dictation language picks the model, English covering the rest`() {
        assertEquals(VoskModel.PT, VoskModel.forLanguage(SpeechLanguage.PT, phoneLanguage = "en"))
        assertEquals(VoskModel.EN, VoskModel.forLanguage(SpeechLanguage.EN, phoneLanguage = "pt"))
        assertEquals(VoskModel.PT, VoskModel.forLanguage(SpeechLanguage.AUTO, phoneLanguage = "pt"))
        assertEquals(VoskModel.EN, VoskModel.forLanguage(SpeechLanguage.AUTO, phoneLanguage = "fr"))
        assertEquals(VoskModel.EN, VoskModel.forLanguage(SpeechLanguage.FR, phoneLanguage = "pt"))
        assertEquals(listOf(SpeechLanguage.AUTO, SpeechLanguage.EN, SpeechLanguage.PT), VoskModel.languages)
    }

    @Test
    fun `a zip with the pinned checksum is unpacked without its top folder`() {
        val bytes = zip("model/am/final.mdl" to "weights", "model/conf/model.conf" to "conf")
        val into = File(temp.root, "model.partial")
        ModelZip.unpack(ByteArrayInputStream(bytes), into, sha256(bytes))
        assertEquals("weights", File(into, "am/final.mdl").readText())
        assertEquals("conf", File(into, "conf/model.conf").readText())
    }

    @Test
    fun `a zip that doesn't match is thrown away`() {
        val bytes = zip("model/final.mdl" to "weights")
        val into = File(temp.root, "model.partial")
        try {
            // The checksum of the same files with the central directory left out: the whole
            // download counts, not just the entries.
            ModelZip.unpack(ByteArrayInputStream(bytes), into, sha256(bytes.copyOf(bytes.size - 1)))
            fail("unpacked a model with the wrong checksum")
        } catch (e: SecurityException) {
            assertTrue(e.message.orEmpty().contains("checksum"))
        }
        assertFalse(into.exists())
    }
}
