package dev.lumen.glasses

import android.content.Context

/**
 * Which web apps may use the glasses' camera (the camera canvas): each app asks once, on the
 * glasses ([WebCameraPrompt]); the answer is kept here, by app id, and the phone's Apps tab shows
 * and changes it (`Grid.CAMERA`). An app that never asked has no answer ([State.ASK]).
 */
object CameraAccess {
    enum class State(val id: String) {
        ASK(""), ALLOWED("allowed"), DENIED("denied");

        companion object {
            fun of(id: String?): State = entries.firstOrNull { it.id == id } ?: ASK
        }
    }

    private const val PREFS = "lumen_camera"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @JvmStatic
    fun of(context: Context, appId: String): State = State.of(prefs(context).getString(appId, null))

    @JvmStatic
    fun set(context: Context, appId: String, state: State) {
        prefs(context).edit().apply { if (state == State.ASK) remove(appId) else putString(appId, state.id) }.apply()
    }

    /** The app is gone: its answer goes with it. */
    @JvmStatic
    fun forget(context: Context, appId: String) = set(context, appId, State.ASK)
}
