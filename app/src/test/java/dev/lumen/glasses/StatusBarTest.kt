package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StatusBarTest {
    @Test
    fun theBatteryIsAPercentOfItsScale() {
        assertEquals(StatusBar.Battery(82, false), StatusBar.battery(82, 100, 0))
        assertEquals(StatusBar.Battery(50, true), StatusBar.battery(128, 256, 2))
        assertEquals(100, StatusBar.battery(105, 100, 0)?.percent)
        assertNull(StatusBar.battery(-1, 100, 0))
        assertNull(StatusBar.battery(50, 0, 0))
    }

    @Test
    fun lowAtTwentyOrLessUnlessCharging() {
        assertTrue(StatusBar.Battery(20, false).low)
        assertTrue(StatusBar.Battery(3, false).low)
        assertFalse(StatusBar.Battery(21, false).low)
        assertFalse(StatusBar.Battery(15, true).low)
    }

    @Test
    fun everyAppButTheRokidLauncher() {
        assertFalse(StatusBar.showsOver(BandBatteryOverlay.ROKID_LAUNCHER))
        assertTrue(StatusBar.showsOver("dev.lumen.glasses"))
        assertTrue(StatusBar.showsOver("com.rokid.settings"))
        assertTrue(StatusBar.showsOver(null))
    }
}
