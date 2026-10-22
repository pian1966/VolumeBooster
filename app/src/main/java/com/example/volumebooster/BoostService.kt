package com.example.volumebooster

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.media.audiofx.AudioEffect
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.IBinder
import android.util.Log
import kotlin.math.log10
import kotlin.math.roundToInt

/**
 * Keeps a LoudnessEnhancer, Equalizer and BassBoost attached to the global output mix
 * (session 0) and to every audio session that players announce via
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION. A foreground service is needed so Android
 * doesn't kill the effects in the background.
 *
 * Settings are read from [Prefs]; any change there (from the app, the notification or
 * the volume keys) is applied immediately through a preference listener.
 */
class BoostService : Service(), SharedPreferences.OnSharedPreferenceChangeListener {

    /** The effects attached to one audio session. Any of them may be unsupported. */
    private class Effects(
        val loudness: LoudnessEnhancer?,
        val eq: Equalizer?,
        val bass: BassBoost?,
    ) {
        fun release() {
            loudness?.release()
            eq?.release()
            bass?.release()
        }
    }

    private val sessions = mutableMapOf<Int, Effects>()

    private val sessionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val session = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1)
            if (session <= 0) return
            when (intent.action) {
                AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION -> attach(session)
                AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION ->
                    sessions.remove(session)?.release()
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
        // Exported: the broadcasts come from other apps' media players.
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(sessionReceiver, filter, RECEIVER_EXPORTED)
        } else {
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            registerReceiver(sessionReceiver, filter)
        }
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Prefs.setEnabled(this, false)
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_UP -> Prefs.adjustBoost(this, STEP)
            ACTION_DOWN -> Prefs.adjustBoost(this, -STEP)
        }

        startForegroundCompat(buildNotification(Prefs.boost(this)))
        attach(0) // global output mix
        applyAll()

        running = true
        return START_STICKY
    }

    override fun onDestroy() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        unregisterReceiver(sessionReceiver)
        sessions.values.forEach { it.release() }
        sessions.clear()
        running = false
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        if (key == Prefs.KEY_ENABLED) return
        applyAll()
        if (key == Prefs.KEY_BOOST) {
            getSystemService(NotificationManager::class.java)
                .notify(NOTIFICATION_ID, buildNotification(Prefs.boost(this)))
        }
    }

    private fun attach(session: Int) {
        val existing = sessions[session]
        if (existing != null) {
            apply(existing)
            return
        }
        val effects = Effects(
            loudness = create(session, "LoudnessEnhancer") { LoudnessEnhancer(session) },
            eq = create(session, "Equalizer") { Equalizer(PRIORITY, session) },
            bass = create(session, "BassBoost") { BassBoost(PRIORITY, session) },
        )
        if (effects.loudness == null && effects.eq == null && effects.bass == null) {
            if (session == 0) attachFailed = true
            return
        }
        sessions[session] = effects
        apply(effects)
    }

    /** Some devices refuse effects on session 0 or on specific sessions. */
    private inline fun <T> create(session: Int, name: String, block: () -> T): T? = try {
        block()
    } catch (e: Exception) {
        Log.w(TAG, "Could not attach $name to session $session", e)
        null
    }

    private fun applyAll() = sessions.values.forEach { apply(it) }

    private fun apply(effects: Effects) {
        val gainMb = percentToMillibels(Prefs.boost(this))
        effects.loudness?.let {
            safely {
                it.setTargetGain(gainMb)
                it.enabled = gainMb > 0
            }
        }

        effects.eq?.let { eq ->
            safely {
                val bass = Prefs.bass(this)
                val mid = Prefs.mid(this)
                val treble = Prefs.treble(this)
                val (minMb, maxMb) = eq.bandLevelRange.let { it[0].toInt() to it[1].toInt() }
                for (band in 0 until eq.numberOfBands) {
                    val centerHz = eq.getCenterFreq(band.toShort()) / 1000
                    val db = when {
                        centerHz < BASS_MAX_HZ -> bass
                        centerHz < MID_MAX_HZ -> mid
                        else -> treble
                    }
                    eq.setBandLevel(band.toShort(), (db * 100).coerceIn(minMb, maxMb).toShort())
                }
                eq.enabled = bass != 0 || mid != 0 || treble != 0
            }
        }

        effects.bass?.let {
            safely {
                val strength = Prefs.bassBoost(this)
                if (it.strengthSupported) it.setStrength((strength * 10).toShort())
                it.enabled = strength > 0
            }
        }
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "Could not apply effect setting", e)
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

    private fun serviceIntent(action: String, requestCode: Int) = PendingIntent.getService(
        this, requestCode, Intent(this, BoostService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

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
            .setOnlyAlertOnce(true)
            .addAction(action(R.string.minus, serviceIntent(ACTION_DOWN, 2)))
            .addAction(action(R.string.plus, serviceIntent(ACTION_UP, 3)))
            .addAction(action(R.string.stop, serviceIntent(ACTION_STOP, 1)))
            .build()
    }

    private fun action(label: Int, intent: PendingIntent) =
        Notification.Action.Builder(null, getString(label), intent).build()

    companion object {
        private const val TAG = "BoostService"
        private const val CHANNEL_ID = "boost"
        private const val NOTIFICATION_ID = 1
        private const val PRIORITY = 1000
        private const val BASS_MAX_HZ = 250
        private const val MID_MAX_HZ = 4000
        const val ACTION_STOP = "com.example.volumebooster.STOP"
        const val ACTION_UP = "com.example.volumebooster.UP"
        const val ACTION_DOWN = "com.example.volumebooster.DOWN"

        const val MIN_PERCENT = 100
        const val MAX_PERCENT = 600
        /** Boost change per notification button or volume key press. */
        const val STEP = 25

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
