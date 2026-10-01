package dev.lumen.companion

import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent

/**
 * Runs the bridge's action names on the phone, from the original phone app (neuralband-air-gestures).
 * The screen, arrow-key and open-an-app actions go through [PhoneTouchService] when it's on.
 * Returns are log lines (English), not UI text.
 */
class PhoneActions(private val context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    /** Where the last seek aimed, so fast dial steps add up before the player reports back. */
    private var seekTarget = 0L
    private var seekAt = 0L
    private var seekSession: android.media.session.MediaSession.Token? = null
    private val cameras = context.getSystemService(CameraManager::class.java)

    /** The back camera's flash, if the phone has one. */
    private val torchCamera: String? = runCatching {
        cameras.cameraIdList.firstOrNull { id ->
            val info = cameras.getCameraCharacteristics(id)
            info.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                info.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }
    }.getOrNull()

    /** Kept in step by the system, so the quick-settings tile and the band agree. */
    private var torchOn = false
    private val torchWatch = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchCamera) torchOn = enabled
        }
    }

    init {
        if (torchCamera != null) cameras.registerTorchCallback(torchWatch, Handler(Looper.getMainLooper()))
    }

    /** Stop following the flash (the service is going away). */
    fun close() = cameras.unregisterTorchCallback(torchWatch)

    /** Returns a line for the activity log when the action couldn't run. */
    fun run(action: String): String? {
        when (action) {
            "media.play_pause" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "media.next" -> mediaKey(KeyEvent.KEYCODE_MEDIA_NEXT)
            "media.previous" -> mediaKey(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "volume.up" -> volume(AudioManager.ADJUST_RAISE)
            "volume.down" -> volume(AudioManager.ADJUST_LOWER)
            "volume.mute" -> volume(AudioManager.ADJUST_TOGGLE_MUTE)
            "brightness.up" -> return brightness(+1)
            "brightness.down" -> return brightness(-1)
            "screen.swipe_up", "screen.swipe_down", "screen.swipe_left", "screen.swipe_right",
            "screen.back", "screen.home", "screen.recents" ->
                return screen(action)
            "key.dpad_up", "key.dpad_down", "key.dpad_left", "key.dpad_right", "key.enter" -> return key(action)
            "torch.toggle" -> return torch()
            "dial.seek_forward" -> return seek(+SEEK_STEP)
            "dial.seek_back" -> return seek(-SEEK_STEP)
            else -> return if (action.startsWith(PhoneSettings.APP_PREFIX)) open(action.removePrefix(PhoneSettings.APP_PREFIX)) else "unknown action $action"
        }
        return null
    }

    /** A key press to whichever media session is active. */
    private fun mediaKey(code: Int) {
        val time = SystemClock.uptimeMillis()
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
        audio.dispatchMediaKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
    }

    /** Screen gestures through the accessibility service, when it's switched on. */
    private fun screen(action: String): String? {
        val touch = PhoneTouchService.instance ?: return "screen gestures are off"
        when (action) {
            "screen.swipe_up" -> touch.swipe(up = true)
            "screen.swipe_down" -> touch.swipe(up = false)
            "screen.swipe_left" -> touch.swipeSideways(left = true)
            "screen.swipe_right" -> touch.swipeSideways(left = false)
            "screen.back" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
            "screen.home" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            "screen.recents" -> touch.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        }
        return null
    }

    /**
     * An arrow key or Enter to the focused app, through the accessibility
     * service: an app can't inject key events into another one, but the
     * service can send the system's D-pad keys. Enter is the D-pad centre,
     * which clicks the focused element as a remote's OK button does.
     */
    private fun key(action: String): String? {
        val touch = PhoneTouchService.instance ?: return "arrow keys need screen gestures"
        if (!PhoneTouchService.keysSupported) return "arrow keys need Android 13"
        val sent = when (action) {
            "key.dpad_up" -> touch.dpad(PhoneTouchService.Key.UP)
            "key.dpad_down" -> touch.dpad(PhoneTouchService.Key.DOWN)
            "key.dpad_left" -> touch.dpad(PhoneTouchService.Key.LEFT)
            "key.dpad_right" -> touch.dpad(PhoneTouchService.Key.RIGHT)
            else -> touch.dpad(PhoneTouchService.Key.CENTER)
        }
        return if (sent) null else "the system didn't take $action"
    }

    /**
     * Move the playing track by [delta] ms, through its media session (a key
     * event can't seek). Prefers the session that's playing.
     */
    private fun seek(delta: Long): String? {
        // The notification listener's access is what lets an app steer other apps' sessions.
        val listener = android.content.ComponentName(context, NotificationForwarder::class.java)
        val sessions = runCatching {
            context.getSystemService(MediaSessionManager::class.java).getActiveSessions(listener)
        }.getOrNull() ?: return "playback position needs notification access"
        val player = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull() ?: return "nothing is playing"
        val state = player.playbackState ?: return "nothing is playing"
        if (state.actions and PlaybackState.ACTION_SEEK_TO == 0L) return "this player can't seek"
        val now = SystemClock.elapsedRealtime()
        val from = if (player.sessionToken == seekSession && now - seekAt < 1500) seekTarget else {
            val running = state.state == PlaybackState.STATE_PLAYING
            state.position + if (running) ((now - state.lastPositionUpdateTime) * state.playbackSpeed).toLong() else 0
        }
        val duration = player.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION)?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val to = (from + delta).coerceIn(0, duration)
        player.transportControls.seekTo(to)
        seekSession = player.sessionToken
        seekTarget = to
        seekAt = now
        return null
    }

    /** The flash on or off; needs no permission, and works with the screen off. */
    private fun torch(): String? {
        val camera = torchCamera ?: return "this phone has no flashlight"
        return runCatching { cameras.setTorchMode(camera, !torchOn) }
            .exceptionOrNull()?.let { "flashlight busy (the camera is open?)" }
    }

    /**
     * Open an app. Android lets only a few kinds of app start one from the
     * background; an enabled accessibility service is one of them.
     */
    private fun open(packageName: String): String? {
        val touch = PhoneTouchService.instance ?: return "opening apps needs screen gestures"
        val launch = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: return "$packageName isn't installed any more"
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        return runCatching { touch.startActivity(launch) }.exceptionOrNull()?.let { "couldn't open $packageName" }
    }

    /** Media volume, with the system volume panel as the on-screen feedback. */
    private fun volume(direction: Int) =
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, AudioManager.FLAG_SHOW_UI)

    /**
     * Screen brightness in 5% steps (as the desktop). Needs "Modify system
     * settings"; turns adaptive brightness off, or the system would undo the step.
     */
    private fun brightness(direction: Int): String? {
        if (!Settings.System.canWrite(context)) return "brightness needs Modify system settings"
        val resolver = context.contentResolver
        Settings.System.putInt(
            resolver,
            Settings.System.SCREEN_BRIGHTNESS_MODE,
            Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
        )
        val current = Settings.System.getInt(resolver, Settings.System.SCREEN_BRIGHTNESS, 128)
        val next = (current + direction * STEP).coerceIn(1, 255)
        Settings.System.putInt(resolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return null
    }

    companion object {
        /** 5% of the 0–255 range. */
        const val STEP = 13

        /** One dial step of playback position. */
        const val SEEK_STEP = 5_000L
    }
}
