package com.example.volumebooster

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Keeps a LoudnessEnhancer attached to the global output mix (session 0) and to every
 * audio session that players announce via ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION.
 * A foreground service is needed so Android doesn't kill the effect in the background.
 */
class BoostService : Service() {

    private val enhancers = mutableMapOf<Int, LoudnessEnhancer>()
    private var gainMb = 0

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    enhancers.remove(session)?.release()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter().apply {
            addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
            addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
        }
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(sessionReceiver, filter)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            Prefs.setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }

        val percent = Prefs.boost(this)
        startForegroundCompat(buildNotification(percent))

        gainMb = percentToMillibels(percent)
        attach(0) // global output mix
        enhancers.values.forEach { applyGain(it) }

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(sessionReceiver)
        enhancers.values.forEach { it.release() }
        enhancers.clear()
        running = false
        super.onDestroy()
    }

    private fun attach(session: Int) {
        val existing = enhancers[session]
        if (existing != null) {
            applyGain(existing)
            return
        }
        try {
            val enhancer = LoudnessEnhancer(session)
            applyGain(enhancer)
            enhancers[session] = enhancer
        } catch (e: Exception) {
            // Some devices refuse effects on session 0 or on specific sessions.
            Log.w(TAG, "Could not attach LoudnessEnhancer to session $session", e)
            if (session == 0) attachFailed = true
        }
    }

    private fun applyGain(enhancer: LoudnessEnhancer) {
        try {
            enhancer.setTargetGain(gainMb)
            enhancer.enabled = gainMb > 0
        } catch (e: Exception) {
            Log.w(TAG, "Could not set gain", e)
        }
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun buildNotification(percent: Int): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID, getString(R.string.channel_name),
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, BoostService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setSmallIcon(R.drawable.ic_speaker)
            .setContentTitle(getString(R.string.notif_title, percent))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openApp)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build())
            .build()
    }

    companion object {
        private const val TAG = "BoostService"
        private const val CHANNEL_ID = "boost"
        private const val NOTIFICATION_ID = 1
        const val ACTION_STOP = "com.example.volumebooster.STOP"

        const val MIN_PERCENT = 100
        const val MAX_PERCENT = 600

        @Volatile var running = false
            private set
        @Volatile var attachFailed = false

        /** 100% = 0 dB, 200% ≈ +6 dB, 300% ≈ +9.5 dB, 600% ≈ +15.6 dB (amplitude ratio). */
        fun percentToMillibels(percent: Int): Int =
            if (percent <= 100) 0 else (2000 * log10(percent / 100.0)).roundToInt()

        fun start(context: Context) {
            val intent = Intent(context, BoostService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, BoostService::class.java))
        }
    }
}
