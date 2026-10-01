package dev.lumen.glasses

import org.junit.Assert.assertEquals
import org.junit.Test

class PhoneInternetTest {
    /** As the glasses' Android 12 prints it (measured), one row per security type. */
    private val list = """
        Network Id      SSID                         Security type
        0            Cafe Downtown                    wpa2-psk
        0            Cafe Downtown                    wpa3-sae^
        5            AndroidShare_4321                wpa2-psk
        5            AndroidShare_4321                wpa3-sae^
        6            AndroidShare_43210               wpa2-psk
    """.trimIndent()

    @Test
    fun `finds the ids of one network by its exact name`() {
        assertEquals(setOf("5"), PhoneInternet.networkIds(list, "AndroidShare_4321"))
        assertEquals(setOf("0"), PhoneInternet.networkIds(list, "Cafe Downtown"))
        assertEquals(emptySet<String>(), PhoneInternet.networkIds(list, "Missing"))
    }

    @Test
    fun `quotes shell words, single quotes included`() {
        assertEquals("'abc def'", PhoneInternet.quote("abc def"))
        assertEquals("'it'\\''s'", PhoneInternet.quote("it's"))
        assertEquals("'\$(reboot)'", PhoneInternet.quote("\$(reboot)"))
    }

    /** As the glasses print them (measured). */
    private val scan = """
            BSSID              Frequency      RSSI           Age(sec)     SSID                                 Flags
          02:00:00:00:00:01       5180    -83(0:-83/1:-105)    47,180                                      [WPA2-PSK-CCMP][ESS]
          02:00:00:00:00:02       5220    -87(0:-87/1:-103)    47,026    Office_7                         [WPA2-PSK-CCMP+TKIP][ESS][WPS]
    """.trimIndent()

    @Test
    fun `saved networks in range, hotspots aside`() {
        assertEquals(false, PhoneInternet.savedInRange(list, scan))
        assertEquals(true, PhoneInternet.savedInRange(list, scan.replace("Office_7", "Cafe Downtown")))
        assertEquals(false, PhoneInternet.savedInRange(list, scan.replace("Office_7", "AndroidShare_4321")))
        assertEquals(true, PhoneInternet.savedInRange(list, scan.lineSequence().first()))
    }
}
