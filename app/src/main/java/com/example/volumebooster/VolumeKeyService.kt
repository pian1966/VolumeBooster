package com.example.volumebooster

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

/**
 * Lets the volume keys continue past the system maximum: once media volume is at max,
 * volume-up raises the boost, and volume-down lowers the boost before the volume.
 * Only acts while the boost is on, media is playing and no call is active.
 */
class VolumeKeyService : AccessibilityService() {

    private lateinit var audio: AudioManager
    private var consumedKey = KeyEvent.KEYCODE_UNKNOWN
    private var toast: Toast? = null

    override fun onServiceConnected() {
        audio = getSystemService(AudioManager::class.java)
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.keyCode
        if (key != KeyEvent.KEYCODE_VOLUME_UP && key != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        // Swallow the key-up of any key-down we consumed, so the system never sees half a press.
        if (event.action == KeyEvent.ACTION_UP) {
            if (consumedKey != key) return false
            consumedKey = KeyEvent.KEYCODE_UNKNOWN
            return true
        }

        if (!Prefs.enabled(this) || audio.mode != AudioManager.MODE_NORMAL || !audio.isMusicActive) {
            return false
        }
        val atMax = audio.getStreamVolume(AudioManager.STREAM_MUSIC) >=
            audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val boost = Prefs.boost(this)
        val delta = when {
            !atMax -> return false
            key == KeyEvent.KEYCODE_VOLUME_UP && boost < BoostService.MAX_PERCENT -> BoostService.STEP
            key == KeyEvent.KEYCODE_VOLUME_DOWN && boost > BoostService.MIN_PERCENT -> -BoostService.STEP
            else -> return false
        }

        consumedKey = key
        showLevel(Prefs.adjustBoost(this, delta))
        return true
    }

    private fun showLevel(percent: Int) {
        toast?.cancel()
        toast = Toast.makeText(this, getString(R.string.notif_title, percent), Toast.LENGTH_SHORT)
            .also { it.show() }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    companion object {
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: return false
            val me = ComponentName(context, VolumeKeyService::class.java)
            return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
        }
    }
}
