package dev.lumen.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class KeyboardTest {
    @Test
    fun `keyboard commands and fields round-trip`() {
        val text = KeyboardCommand(KeyboardCommand.TEXT, "café\nworld", 7)
        assertEquals(text, KeyboardCommand.from(text.toJson()))
        val field = KeyboardField(true, "WhatsApp", "Mensagem", "text", true, "oi", KeyboardField.SYNC)
        assertEquals(field, KeyboardField.from(field.toJson()))
        // The glasses asking the phone to open its keyboard.
        val ask = KeyboardField(true, "Settings", "Password", "password", false, "", KeyboardField.ASK)
        assertEquals(KeyboardField.ASK, KeyboardField.from(ask.toJson()).reason)
    }

    @Test
    fun `an empty field message is no field`() {
        val field = KeyboardField.from(JSONObject())
        assertFalse(field.focused)
        assertEquals(KeyboardField.BLUR, field.reason)
        assertEquals("text", field.type)
    }

    @Test
    fun `the keyboard travels both ways`() {
        assert(Link.KEYBOARD in Link.TO_GLASSES)
        assert(Link.KEYBOARD_FIELD in Link.TO_PHONE)
    }
}
