package dev.lumen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {
    private val action = Setting(
        "index_double", Setting.Kind.CHOICE, "Index double tap", "launch_app",
        listOf(SettingOption("none", "No action"), SettingOption("launch_app", "Launch app")), section = "gestures",
    )
    private val app = Setting(
        "index_double_app", Setting.Kind.CHOICE, "App", "com.example",
        listOf(SettingOption("com.example", "Example")), section = "gestures", visibleWhen = "index_double" to "launch_app",
    )
    private val paused = Setting("paused", Setting.Kind.TOGGLE, "Pause the band", "false", section = "band")

    @Test
    fun `a schema round-trips with its settings, actions and status`() {
        val schema = SettingsEvent.Schema(
            listOf(action, app, paused),
            listOf(SettingsAction("reconnect", "Reconnect"), SettingsAction("forget", "Forget the band", destructive = true)),
            BandStatus(BandStatus.PHASE_CONNECTED, "Meta Band 00C0", 83, charging = false, paused = false),
        )
        val request = SettingsOps.describe()
        val json = Link.parse(schema.toJson(request).toString())
        assertEquals(request.getLong("id"), json.getLong("re"))
        assertEquals(schema, SettingsEvent.from(json))
    }

    @Test
    fun `visibility follows the other setting's value`() {
        assertTrue(app.isVisible(listOf(action, app)))
        assertFalse(app.isVisible(listOf(action.copy(value = "none"), app)))
        assertTrue(paused.isVisible(emptyList()))
        assertFalse(paused.checked)
        assertTrue(paused.copy(value = "true").checked)
    }

    @Test
    fun `requests, results and status pushes`() {
        val set = SettingsOps.set("dial", "navigation")
        assertEquals(SettingsOps.SET, set.getString("op"))
        val result = SettingsEvent.Result(false, "dial", "unknown value")
        assertEquals(result, SettingsEvent.from(result.toJson(set)))
        assertEquals(set.getLong("id"), result.toJson(set).getLong("re"))
        val status = SettingsEvent.Status(BandStatus(BandStatus.PHASE_SEARCHING))
        assertEquals(status, SettingsEvent.from(status.toJson()))
        assertEquals(-1, BandStatus.from(null).battery)
        assertFalse(BandStatus().connected)
    }

    @Test
    fun `the status says when the band is with the phone`() {
        val status = BandStatus(BandStatus.PHASE_STOPPED, "Meta Band", onPhone = true)
        assertEquals(status, BandStatus.from(status.toJson()))
        assertFalse(BandStatus.from(org.json.JSONObject()).onPhone)
        assertEquals(SettingsOps.ACTION_TO_PHONE, SettingsOps.action(SettingsOps.ACTION_TO_PHONE).getString("name"))
    }

    @Test
    fun `wireless debugging travels in the schema and on its own`() {
        val debug = DebugStatus(enabled = true, wifiOn = true, ssid = "Home", address = "192.168.0.92", listening = true)
        assertEquals(debug, DebugStatus.from(debug.toJson()))
        assertEquals("adb connect 192.168.0.92:5555", debug.command)
        assertEquals(true, debug.ready)
        assertEquals(false, debug.copy(onPhoneHotspot = true).ready)
        assertEquals(false, DebugStatus().ready)
        val event = SettingsEvent.from(SettingsEvent.Debug(debug).toJson())
        assertEquals(SettingsEvent.Debug(debug), event)
        val schema = SettingsEvent.Schema(emptyList(), emptyList(), BandStatus(), debug)
        assertEquals(schema, SettingsEvent.from(schema.toJson()))
        // A schema from older glasses has no debug: off.
        assertEquals(DebugStatus(), (SettingsEvent.from(org.json.JSONObject().put("type", "schema")) as SettingsEvent.Schema).debug)
    }
}
