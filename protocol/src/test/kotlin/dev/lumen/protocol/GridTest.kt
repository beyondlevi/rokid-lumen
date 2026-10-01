package dev.lumen.protocol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GridTest {
    private val items = listOf(
        GridItem(GridItem.NOTIFICATIONS_ID, GridItem.Kind.NOTIFICATIONS, "Notifications"),
        GridItem("web:abc", GridItem.Kind.WEB, "Rokid Compat", "http://127.0.0.1:47100/", offline = true, engine = "GECKO"),
        GridItem("app:com.example", GridItem.Kind.NATIVE, "Example", "com.example"),
        GridItem(GridItem.SETTINGS_ID, GridItem.Kind.SETTINGS, "Settings", removable = false),
    )

    @Test
    fun `a state round-trips, the answer echoing the request`() {
        val state = GridEvent.State(items, listOf(GridItem("app:com.other", GridItem.Kind.NATIVE, "Other", "com.other")))
        val request = GridOps.describe()
        val json = Link.parse(state.toJson(request).toString())
        assertEquals(request.getLong("id"), json.getLong("re"))
        assertEquals(state, GridEvent.from(json))
        assertFalse((GridEvent.from(json) as GridEvent.State).items.last().removable)
    }

    @Test
    fun `set carries the order and the hidden ids`() {
        val set = GridOps.set(listOf("web:abc", "notifications"), listOf("app:com.example"))
        assertEquals(listOf("web:abc", "notifications"), GridOps.strings(set, "order"))
        assertEquals(listOf("app:com.example"), GridOps.strings(set, "hidden"))
        assertEquals(emptyList<String>(), GridOps.strings(set, "missing"))
    }

    @Test
    fun `icons and results`() {
        val icon = GridEvent.Icon("web:abc", "iVBORw0KGgo=")
        assertEquals(icon, GridEvent.from(icon.toJson()))
        val request = GridOps.remove("web:abc")
        val result = GridEvent.Result(false, "web:abc", "not found")
        assertEquals(result, GridEvent.from(result.toJson(request)))
        assertEquals(GridOps.ENGINE, GridOps.engine("web:abc", "SYSTEM").getString("op"))
    }

    @Test
    fun `a web app's configuration round-trips without secret values`() {
        val fields = listOf(
            AppConfigField("server.url", "Server URL", AppConfigField.TYPE_URL, "https://example.test", set = true),
            AppConfigField("server.key", "API key", AppConfigField.TYPE_SECRET, set = true),
        )
        val item = GridItem("web:pkg", GridItem.Kind.WEB, "Chat", offline = true, config = fields)
        assertEquals(item, GridItem.from(item.toJson()))
        assertEquals(true, GridItem.from(item.toJson()).config[1].secret)
        val set = GridOps.config("web:pkg", "server.key", "value")
        assertEquals(GridOps.CONFIG, set.getString("op"))
        assertEquals("server.key", set.getString("key"))
    }

    @Test
    fun `an optional field survives the round trip and is never missing`() {
        val json = org.json.JSONObject().put("key", "demo").put("label", "Demo").put("type", "text").put("optional", true)
        val field = AppConfigField.from(json)
        assertEquals(true, field.optional)
        assertFalse(field.missing)
        assertEquals(field, AppConfigField.from(field.toJson()))
        // A required field without a value is missing; an older message without the flag is required.
        assertEquals(true, AppConfigField("server.url", "Server URL").missing)
        assertFalse(AppConfigField.from(org.json.JSONObject().put("key", "x")).optional)
        assertFalse(AppConfigField("x", "X").toJson().has("optional"))
    }
}
