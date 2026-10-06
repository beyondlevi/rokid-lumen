package dev.lumen.companion.ime

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PasswordFieldTest {
    @Test
    fun `every password kind is kept from the band`() {
        val text = InputType.TYPE_CLASS_TEXT
        assertTrue(HandwritingKeyboard.isPassword(text or InputType.TYPE_TEXT_VARIATION_PASSWORD))
        assertTrue(HandwritingKeyboard.isPassword(text or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD))
        assertTrue(HandwritingKeyboard.isPassword(text or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD))
        assertTrue(HandwritingKeyboard.isPassword(InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD))
    }

    @Test
    fun `ordinary fields take writing`() {
        assertFalse(HandwritingKeyboard.isPassword(InputType.TYPE_CLASS_TEXT))
        assertFalse(HandwritingKeyboard.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS))
        assertFalse(HandwritingKeyboard.isPassword(InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE))
        assertFalse(HandwritingKeyboard.isPassword(InputType.TYPE_CLASS_NUMBER))
    }
}
