package com.example.volumebooster

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity(), SharedPreferences.OnSharedPreferenceChangeListener {

    private lateinit var audio: AudioManager
    private lateinit var boostLabel: TextView
    private lateinit var gainLabel: TextView
    private lateinit var boostBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var enableSwitch: Switch
    private lateinit var volumeKeysSwitch: Switch
    private lateinit var warning: TextView

    private lateinit var bassBar: SeekBar
    private lateinit var midBar: SeekBar
    private lateinit var trebleBar: SeekBar
    private lateinit var bassBoostBar: SeekBar
    private lateinit var bassValue: TextView
    private lateinit var midValue: TextView
    private lateinit var trebleValue: TextView
    private lateinit var bassBoostValue: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AudioManager::class.java)

        boostLabel = findViewById(R.id.boostLabel)
        gainLabel = findViewById(R.id.gainLabel)
        boostBar = findViewById(R.id.boostBar)
        volumeBar = findViewById(R.id.volumeBar)
        enableSwitch = findViewById(R.id.enableSwitch)
        volumeKeysSwitch = findViewById(R.id.volumeKeysSwitch)
        warning = findViewById(R.id.warning)
        bassBar = findViewById(R.id.bassBar)
        midBar = findViewById(R.id.midBar)
        trebleBar = findViewById(R.id.trebleBar)
        bassBoostBar = findViewById(R.id.bassBoostBar)
        bassValue = findViewById(R.id.bassValue)
        midValue = findViewById(R.id.midValue)
        trebleValue = findViewById(R.id.trebleValue)
        bassBoostValue = findViewById(R.id.bassBoostValue)

        findViewById<TextView>(R.id.versionLabel).text =
            getString(R.string.version, packageManager.getPackageInfo(packageName, 0).versionName)

        // SeekBar has no min below API 26, so offset by MIN_PERCENT.
        boostBar.max = BoostService.MAX_PERCENT - BoostService.MIN_PERCENT
        boostBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) Prefs.setBoost(this@MainActivity, progress + BoostService.MIN_PERCENT)
            }
        })

        mapOf(
            R.id.preset100 to 100, R.id.preset150 to 150,
            R.id.preset200 to 200, R.id.preset400 to 400, R.id.preset600 to 600
        ).forEach { (id, percent) ->
            findViewById<Button>(id).setOnClickListener { Prefs.setBoost(this, percent) }
        }

        setUpEqualizer()

        volumeBar.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
        })

        enableSwitch.setOnCheckedChangeListener { _, on ->
            if (on == Prefs.enabled(this)) return@setOnCheckedChangeListener
            if (on && Prefs.boost(this) > 200 && !warned) {
                confirmLoud()
                enableSwitch.isChecked = false
            } else {
                toggle(on)
            }
        }

        // The accessibility service can only be switched in system settings.
        volumeKeysSwitch.setOnClickListener {
            val on = VolumeKeyService.isEnabled(this)
            volumeKeysSwitch.isChecked = on
            AlertDialog.Builder(this)
                .setTitle(R.string.volume_keys_dialog_title)
                .setMessage(if (on) R.string.volume_keys_dialog_off else R.string.volume_keys_dialog_on)
                .setPositiveButton(R.string.open_settings) { _, _ ->
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        if (Prefs.enabled(this) && !BoostService.running) BoostService.start(this)
    }

    override fun onResume() {
        super.onResume()
        Prefs.prefs(this).registerOnSharedPreferenceChangeListener(this)
        volumeBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeKeysSwitch.isChecked = VolumeKeyService.isEnabled(this)
        refresh()
    }

    override fun onPause() {
        Prefs.prefs(this).unregisterOnSharedPreferenceChangeListener(this)
        super.onPause()
    }

    /** Keeps the screen in step with changes from the notification or volume keys. */
    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) = refresh()

    private fun setUpEqualizer() {
        listOf(bassBar, midBar, trebleBar).forEach { bar ->
            bar.max = EQ_MAX_DB * 2
            bar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
                override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        Prefs.setEq(
                            this@MainActivity,
                            bassBar.progress - EQ_MAX_DB,
                            midBar.progress - EQ_MAX_DB,
                            trebleBar.progress - EQ_MAX_DB,
                        )
                    }
                }
            })
        }
        bassBoostBar.max = 100
        bassBoostBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) Prefs.setBassBoost(this@MainActivity, progress)
            }
        })

        // Preset: bass, mid, treble (dB) and bass boost (%).
        mapOf(
            R.id.eqFlat to intArrayOf(0, 0, 0, 0),
            R.id.eqVoice to intArrayOf(-3, 5, 3, 0),
            R.id.eqMovies to intArrayOf(4, 2, 3, 30),
            R.id.eqMusic to intArrayOf(4, 0, 3, 20),
            R.id.eqBass to intArrayOf(8, 0, -1, 70),
        ).forEach { (id, p) ->
            findViewById<Button>(id).setOnClickListener {
                Prefs.setEq(this, p[0], p[1], p[2])
                Prefs.setBassBoost(this, p[3])
            }
        }
    }

    private var warned = false

    private fun confirmLoud() {
        AlertDialog.Builder(this)
            .setTitle(R.string.warning_title)
            .setMessage(R.string.warning_text)
            .setPositiveButton(R.string.understand) { _, _ ->
                warned = true
                enableSwitch.isChecked = true // listener then starts the boost
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun toggle(on: Boolean) {
        Prefs.setEnabled(this, on)
        if (on) BoostService.start(this) else BoostService.stop(this)
        enableSwitch.postDelayed({ refresh() }, 300)
    }

    private fun refresh() {
        val percent = Prefs.boost(this)
        boostBar.progress = percent - BoostService.MIN_PERCENT
        boostLabel.text = getString(R.string.percent, percent)
        gainLabel.text = getString(
            R.string.gain_db, BoostService.percentToMillibels(percent) / 100f
        )
        enableSwitch.isChecked = Prefs.enabled(this)
        warning.visibility =
            if (Prefs.enabled(this) && BoostService.attachFailed) View.VISIBLE else View.GONE

        val bass = Prefs.bass(this)
        val mid = Prefs.mid(this)
        val treble = Prefs.treble(this)
        bassBar.progress = bass + EQ_MAX_DB
        midBar.progress = mid + EQ_MAX_DB
        trebleBar.progress = treble + EQ_MAX_DB
        bassValue.text = getString(R.string.db_value, bass)
        midValue.text = getString(R.string.db_value, mid)
        trebleValue.text = getString(R.string.db_value, treble)
        bassBoostBar.progress = Prefs.bassBoost(this)
        bassBoostValue.text = getString(R.string.percent, Prefs.bassBoost(this))
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    }

    companion object {
        private const val EQ_MAX_DB = 12
    }
}
