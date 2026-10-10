package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class KeyboardDefaultTest {
    private val ours = "dev.lumen.glasses/.LumenKeyboard"
    private val rokid = KeyboardDefault.ROKID_IME
    private val other = "com.example.ime/.Keys"

    @Test
    fun onLumensKeyboardIsEnabledAndTheDefault() {
        // Out of the box: Rokid's only.
        assertEquals(KeyboardDefault.Plan("$rokid:$ours", ours), KeyboardDefault.plan(true, rokid, rokid, ours, null))
        // Nothing enabled yet.
        assertEquals(KeyboardDefault.Plan(ours, ours), KeyboardDefault.plan(true, null, null, ours, null))
        // Enabled but not the default (Rokid's assistant took it back): only the default changes.
        assertEquals(KeyboardDefault.Plan(null, ours), KeyboardDefault.plan(true, "$rokid:$ours", rokid, ours, null))
        // Already so: nothing to write.
        val same = KeyboardDefault.plan(true, "$rokid:$ours", ours, ours, null)
        assertFalse(same.changes)
        // Other input methods' subtypes stay as they were.
        assertEquals(KeyboardDefault.Plan("$other;123;456:$ours", ours), KeyboardDefault.plan(true, "$other;123;456", other, ours, null))
    }

    @Test
    fun offRokidsComesBack() {
        assertEquals(KeyboardDefault.Plan(rokid, rokid), KeyboardDefault.plan(false, "$rokid:$ours", ours, ours, rokid))
        // Rokid's wasn't enabled any more: it is again.
        assertEquals(KeyboardDefault.Plan(rokid, rokid), KeyboardDefault.plan(false, ours, ours, ours, rokid))
        // Already Rokid's: Lumen's only leaves the enabled list.
        assertEquals(KeyboardDefault.Plan("$rokid;1", null), KeyboardDefault.plan(false, "$rokid;1:$ours", rokid, ours, rokid))
        // Lumen's was never there: nothing to do.
        assertFalse(KeyboardDefault.plan(false, rokid, rokid, ours, rokid).changes)
        assertFalse(KeyboardDefault.plan(false, null, null, ours, null).changes)
    }

    @Test
    fun offWithoutAnotherKeyboardLumensStays() {
        // The glasses would be left with no keyboard at all.
        assertFalse(KeyboardDefault.plan(false, ours, ours, ours, null).changes)
        assertFalse(KeyboardDefault.plan(false, ours, ours, ours, ours).changes)
    }

    @Test
    fun theKeyboardToGoBackTo() {
        assertEquals(rokid, KeyboardDefault.restoreFor(listOf(ours, other, rokid), "$other:$ours", ours))
        // Rokid's uninstalled (its assistant goes with another home app): another enabled one.
        assertEquals(other, KeyboardDefault.restoreFor(listOf(ours, other), "$ours:$other;5", ours))
        // Enabled but not installed doesn't count, and Lumen's never does.
        assertNull(KeyboardDefault.restoreFor(listOf(ours), "$ours:$other", ours))
        assertNull(KeyboardDefault.restoreFor(emptyList(), null, ours))
    }
}
