package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AdbShellParsingTest {
    @Test
    fun `the wireless debugging port comes out of a service call`() {
        // Seen on the glasses: Wireless Debugging on (39713), then off.
        assertEquals(39713, AdbShellParsing.parcelInt("Result: Parcel(00000000 00009b21   '....!...')"))
        assertEquals(-1, AdbShellParsing.parcelInt("Result: Parcel(00000000 ffffffff   '........')"))
        // An exception in the reply (a non-zero first word) or no reply at all: nothing.
        assertNull(AdbShellParsing.parcelInt("Result: Parcel(ffffffb5 00000000 '........')"))
        assertNull(AdbShellParsing.parcelInt("service: Service adb does not exist"))
    }

    @Test
    fun `the pairing server is the port that started listening`() {
        val before = AdbShellParsing.listeningPorts(
            """
            Active Internet connections (only servers)
            Proto Recv-Q Send-Q Local Address           Foreign Address         State
            tcp        0      0 0.0.0.0:8341            0.0.0.0:*               LISTEN
            tcp6       0      0 [::]:5555               [::]:*                  LISTEN
            """.trimIndent(),
        )
        assertEquals(setOf(8341, 5555), before)
        val after = AdbShellParsing.listeningPorts(
            """
            tcp        0      0 0.0.0.0:8341            0.0.0.0:*               LISTEN
            tcp6       0      0 [::]:36225              [::]:*                  LISTEN
            tcp6       0      0 [::]:39713              [::]:*                  LISTEN
            tcp6       0      0 [::]:5555               [::]:*                  LISTEN
            tcp        0      0 192.168.68.63:41000     192.168.68.62:5555      ESTABLISHED
            """.trimIndent(),
        )
        // Wireless Debugging's own port (39713) opened too; the pairing server is the other one.
        assertEquals(36225, AdbShellParsing.newPort(before, after, setOf(5555, 39713)))
        assertNull(AdbShellParsing.newPort(before, before, setOf(5555)))
    }

    @Test
    fun `the BSSID to trust comes from the Wi-Fi status`() {
        val status = "Wifi is enabled\nWifi is connected to \"Home\"\n" +
            "WifiInfo: SSID: \"Home\", BSSID: 3c:6a:d2:5b:1c:24, MAC: 02:00:00:00:00:00, Security type: 2, Supplicant state: COMPLETED"
        assertEquals("3c:6a:d2:5b:1c:24", AdbShellParsing.bssid(status))
        // Not connected: Android reports a placeholder.
        assertNull(AdbShellParsing.bssid("Wifi is enabled\nWifiInfo: SSID: <unknown ssid>, BSSID: 02:00:00:00:00:00"))
        assertNull(AdbShellParsing.bssid("Wifi is disabled"))
    }
}
