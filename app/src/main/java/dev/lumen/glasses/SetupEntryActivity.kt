package dev.lumen.glasses

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * What the phone opens on the glasses during setup (the companion's `appStart`, over Rokid's
 * link): no screen of its own. With the accessibility service off it hands the wearer to its
 * switch in Settings ([AccessibilityHandoff]); with it on, Lumen's home opens.
 */
class SetupEntryActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AccessibilityHandoff.isEnabled(this)) {
            startActivity(Intent(this, LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } else {
            Log.d(TAG, "accessibility off: to its switch (${AccessibilityHandoff.open(this)})")
        }
        finish()
    }

    private companion object {
        const val TAG = "BandSetup"
    }
}
