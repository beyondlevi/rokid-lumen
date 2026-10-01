package dev.lumen.protocol

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LinkTest {
    @Test
    fun `every message carries the version, and requests an id the reply echoes`() {
        assertEquals(Link.VERSION, Link.version(Link.message()))
        val a = Link.request()
        val b = Link.request()
        assertNotEquals(a.getLong("id"), b.getLong("id"))
        assertEquals(a.getLong("id"), Link.reply(a).getLong("re"))
    }

    @Test
    fun `messages from before the envelope are version 0 and read the same`() {
        val legacy = JSONObject().put("action", "up")
        assertEquals(0, Link.version(legacy))
        assertEquals(NetCommand.UP, NetCommand.from(legacy))
        assertEquals(DictationCommand.START, DictationCommand.from(JSONObject().put("action", "start")))
        assertEquals(true, NotifyCommand.isSync(JSONObject().put("action", "sync")))
    }

    @Test
    fun `net events round-trip, and an incomplete ready is refused`() {
        val ready = NetEvent.Ready("AndroidShare_0001", "secret", "10.10.10.1", 41553)
        assertEquals(ready, NetEvent.from(Link.parse(ready.toJson().toString())))
        assertEquals("10.10.10.1:41553", ready.proxy)
        assertEquals(NetEvent.Down("AndroidShare_0001"), NetEvent.from(NetEvent.Down("AndroidShare_0001").toJson()))
        assertEquals(NetEvent.Down(""), NetEvent.from(JSONObject().put("type", "down")))
        assertEquals(NetEvent.Failed("x"), NetEvent.from(NetEvent.Failed("x").toJson()))
        assertNull(NetEvent.from(JSONObject().put("type", "ready").put("ssid", "a")))
        assertNull(NetEvent.from(JSONObject().put("type", "other")))
    }

    @Test
    fun `dictation events and bad input`() {
        val event = DictationEvent(DictationEvent.PHRASE, "olá")
        assertEquals(event, DictationEvent.from(Link.parse(event.toJson().toString())))
        assertEquals(0, Link.parse("not json").length())
        assertEquals(0, Link.parse(null).length())
        assertNull(DictationCommand.from(JSONObject().put("action", "dance")))
    }

    @Test
    fun `the two directions don't share a channel`() {
        assertEquals(emptySet<String>(), Link.TO_GLASSES.toSet() intersect Link.TO_PHONE.toSet())
        assertEquals(NotifyEvent.REMOVE, NotifyEvent.remove("k").getString("type"))
    }

    @Test
    fun `the snooze travels both ways`() {
        val state = Link.parse(NotifyCommand.snooze(1_234L).toString())
        assertEquals(1_234L, NotifyCommand.snoozeUntil(state))
        assertEquals(0L, NotifyCommand.snoozeUntil(NotifyCommand.snooze(0)))
        assertNull(NotifyCommand.snoozeUntil(NotifyCommand.sync()))
        val ask = NotifyEvent.snooze(false)
        assertEquals(NotifyEvent.SNOOZE, ask.getString("type"))
        assertEquals(false, ask.getBoolean("on"))
    }
}
