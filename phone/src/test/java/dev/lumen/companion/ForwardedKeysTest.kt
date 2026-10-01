package dev.lumen.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardedKeysTest {
    @Test
    fun `only keys sent to the glasses can be dismissed`() {
        val keys = ForwardedKeys(capacity = 10)
        keys.add("0|chat|1|null|10001")
        keys.add("0|mail|7|null|10002")
        // A key the glasses were never sent (another app's, say) is dropped from the request.
        assertEquals(listOf("0|mail|7|null|10002"), keys.sentOf(listOf("0|bank|3|null|10003", "0|mail|7|null|10002")))
        assertEquals(emptyList<String>(), keys.sentOf(listOf("0|bank|3|null|10003")))
        // Gone from the phone: no longer dismissable.
        keys.remove("0|chat|1|null|10001")
        assertFalse("0|chat|1|null|10001" in keys)
        keys.clear()
        assertEquals(0, keys.size)
    }

    @Test
    fun `the set is bounded, oldest out, and a resend makes a key newest`() {
        val keys = ForwardedKeys(capacity = 3)
        listOf("a", "b", "c").forEach(keys::add)
        keys.add("a")
        keys.add("d")
        assertEquals(3, keys.size)
        assertFalse("b" in keys)
        assertTrue("a" in keys && "c" in keys && "d" in keys)
        repeat(1_000) { keys.add("k$it") }
        assertEquals(3, keys.size)
        assertEquals(listOf("k998", "k999"), keys.sentOf(listOf("k0", "k998", "k999")))
    }
}
