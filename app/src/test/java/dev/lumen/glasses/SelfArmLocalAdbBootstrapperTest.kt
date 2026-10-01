package dev.lumen.glasses

import android.content.Context
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SelfArmLocalAdbBootstrapperTest {
    @Test
    fun bootstrapCommandInstallsBothHelpers() {
        val context = RuntimeEnvironment.getApplication() as Context
        val bootstrapper = SelfArmLocalAdbBootstrapper(context)
        val command = bootstrapper.buildBootstrapCommand()

        assertTrue(command.contains("pm grant \$PKG android.permission.WRITE_SECURE_SETTINGS"))
        assertTrue(command.contains(BridgeProtocol.REMOTE_SCRIPT))
        assertTrue(command.contains(BridgeProtocol.REMOTE_WATCHDOG_SCRIPT))
        assertTrue(command.contains(BridgeProtocol.selfArmWatchdogScriptPath()))
        assertTrue(command.contains("sh \"\$REMOTE_SCRIPT\" start"))
        assertTrue(command.contains("sh \"\$REMOTE_WATCHDOG\" start"))
        assertTrue(command.contains("setprop persist.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT}"))
        // No Nexus coexistence here: nothing may write R08's or Nexus's global settings.
        assertFalse(command.contains("r08_access_bridge_armed"))
        assertFalse(command.contains("rokidbus"))
        // The paired key must outlive the default 7-day Wireless Debugging key expiry.
        assertTrue(command.contains("settings put global adb_allowed_connection_time 0"))
        // USB debugging is switched on by the app after the bootstrap, never from inside the
        // adbd shell: toggling it can restart adbd and kill this very script and its helpers.
        assertFalse(command.contains("adb_enabled"))
        assertTrue(command.contains(BridgeProtocol.EXTRA_INIT_SHORTCUT_BRIDGE))
        assertTrue(command.contains("\$PKG/${BridgeProtocol.COMMAND_ACTIVITY}"))
        assertTrue(command.contains(BridgeProtocol.COMMAND_WIFI_DISABLE))
        assertTrue(command.contains("BAND_LOCAL_SELF_ARM_RESULT"))
        // The local self-arm must never restart adbd: the watchdog it just started is a
        // descendant of adbd and a restart would kill it. persist.adb.tcp.port covers 5555
        // after the next reboot.
        assertFalse(command.contains("setprop service.adb.tcp.port ${BridgeProtocol.DEFAULT_ADB_PORT}"))
        assertFalse(command.contains("ctl.restart adbd"))
        // Every helper path is this app's own, so R08 Access Bridge's helpers keep running.
        assertFalse(command.contains("/data/local/tmp/r08-"))
        assertTrue(command.contains("PKG='dev.lumen.glasses'"))
    }
}
