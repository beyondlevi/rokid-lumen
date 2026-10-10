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
        assertEquals(state.copy(re = request.getLong("id")), GridEvent.from(json))
        assertFalse((GridEvent.from(json) as GridEvent.State).items.last().removable)
    }

    @Test
    fun `a state shows the changes on their way`() {
        val chat = GridItem("web:chat", GridItem.Kind.WEB, "Chat", config = listOf(
            AppConfigField("server.url", "Server", AppConfigField.TYPE_URL),
            AppConfigField("server.key", "Key", AppConfigField.TYPE_SECRET),
        ))
        val state = GridEvent.State(items + chat, emptyList())
        val after = state.with(listOf(
            GridOps.config("web:chat", "server.url", "https://example.test"),
            GridOps.config("web:chat", "server.key", "secret"),
            GridOps.rename("web:chat", "  Work chat "),
            GridOps.remove("web:abc"),
            GridOps.set(listOf("web:chat", GridItem.SETTINGS_ID), emptyList()),
            GridOps.addWeb("https://example.test"),
        ))
        assertEquals(listOf("web:chat", GridItem.SETTINGS_ID), after.items.map { it.id })
        assertEquals(listOf(GridItem.NOTIFICATIONS_ID, "app:com.example"), after.available.map { it.id })
        val changed = after.items.first()
        assertEquals("Work chat", changed.name)
        assertEquals(AppConfigField("server.url", "Server", AppConfigField.TYPE_URL, "https://example.test", set = true), changed.config[0])
        // A secret shows as set, never with its value.
        assertEquals(AppConfigField("server.key", "Key", AppConfigField.TYPE_SECRET, "", set = true), changed.config[1])
        // An empty value clears a field.
        assertFalse(after.with(listOf(GridOps.config("web:chat", "server.url", ""))).items.first().config[0].set)
        assertEquals(state, state.with(emptyList()))
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
        // The item's id took the envelope's `id`: the answer still names the request.
        assertEquals("web:abc", request.getString("id"))
        assertEquals("web:abc", GridOps.item(request))
        assertEquals(result.copy(re = GridOps.requestId(request)), GridEvent.from(result.toJson(request)))
        assertEquals(true, GridOps.requestId(request) > 0)
        assertEquals("", GridOps.item(GridOps.describe()))
        assertEquals(0L, (GridEvent.from(GridEvent.State(items, emptyList()).toJson()) as GridEvent.State).re)
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

    @Test
    fun `a package handed over from the phone, and the version in the state`() {
        val request = GridOps.installFile("tok", "chat.mrbd.zip", 1234, "ab12", replace = "web:pkg")
        assertEquals(GridOps.INSTALL_FILE, request.getString("op"))
        assertEquals(1234L, request.getLong("size"))
        assertEquals("web:pkg", request.getString("replace"))
        assertEquals("", GridOps.installFile("tok", "a.zip", 1, "00").getString("replace"))
        val item = GridItem("web:pkg", GridItem.Kind.WEB, "Chat", offline = true, version = "0.3.0")
        assertEquals(item, GridItem.from(item.toJson()))
        assertFalse(GridItem("web:x", GridItem.Kind.WEB, "X").toJson().has("version"))
    }
}
