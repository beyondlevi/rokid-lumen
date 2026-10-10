package dev.lumen.companion

import android.app.Application

/**
 * The companion's process. Only what has to answer the glasses with no screen open and no
 * context of its own starts here: [KeyboardLink], whose notice opens the keyboard when the
 * glasses ask for it (the link's service hands it the glasses' messages, not a context).
 */
class CompanionApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        KeyboardLink.init(this)
    }
}
