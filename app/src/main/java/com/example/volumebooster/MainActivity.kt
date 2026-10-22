package com.example.volumebooster

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var audio: AudioManager
    private lateinit var boostLabel: TextView
    private lateinit var gainLabel: TextView
    private lateinit var boostBar: SeekBar
    private lateinit var volumeBar: SeekBar
    private lateinit var enableSwitch: Switch
    private lateinit var warning: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        audio = getSystemService(AudioManager::class.java)

        boostLabel = findViewById(R.id.boostLabel)
        gainLabel = findViewById(R.id.gainLabel)
        boostBar = findViewById(R.id.boostBar)
        volumeBar = findViewById(R.id.volumeBar)
        enableSwitch = findViewById(R.id.enableSwitch)
        warning = findViewById(R.id.warning)

        // SeekBar has no min below API 26, so offset by MIN_PERCENT.
        boostBar.max = BoostService.MAX_PERCENT - BoostService.MIN_PERCENT
        boostBar.progress = Prefs.boost(this) - BoostService.MIN_PERCENT
        boostBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) setBoost(progress + BoostService.MIN_PERCENT)
            }
        })

        mapOf(
            R.id.preset100 to 100, R.id.preset150 to 150,
            R.id.preset200 to 200, R.id.preset400 to 400, R.id.preset600 to 600
        ).forEach { (id, percent) ->
            findViewById<Button>(id).setOnClickListener {
                boostBar.progress = percent - BoostService.MIN_PERCENT
                setBoost(percent)
            }
        }

        volumeBar.max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        volumeBar.setOnSeekBarChangeListener(object : SimpleSeekListener() {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) audio.setStreamVolume(AudioManager.STREAM_MUSIC, progress, 0)
            }
        })

        enableSwitch.isChecked = Prefs.enabled(this)
        enableSwitch.setOnCheckedChangeListener { _, on ->
            if (on && Prefs.boost(this) > 200 && !warned) {
                confirmLoud()
                enableSwitch.isChecked = false
            } else {
                toggle(on)
            }
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }

        updateLabels()
        if (Prefs.enabled(this) && !BoostService.running) BoostService.start(this)
    }

    override fun onResume() {
        super.onResume()
        volumeBar.progress = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        enableSwitch.isChecked = Prefs.enabled(this)
        updateLabels()
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
        enableSwitch.postDelayed({ updateLabels() }, 300)
    }

    private fun setBoost(percent: Int) {
        Prefs.setBoost(this, percent)
        if (Prefs.enabled(this)) BoostService.start(this) // re-applies the new gain
        updateLabels()
    }

    private fun updateLabels() {
        val percent = Prefs.boost(this)
        boostLabel.text = getString(R.string.percent, percent)
        gainLabel.text = getString(
            R.string.gain_db, BoostService.percentToMillibels(percent) / 100f
        )
        warning.visibility =
            if (Prefs.enabled(this) && BoostService.attachFailed) View.VISIBLE else View.GONE
    }

    private abstract class SimpleSeekListener : SeekBar.OnSeekBarChangeListener {
        override fun onStartTrackingTouch(bar: SeekBar) {}
        override fun onStopTrackingTouch(bar: SeekBar) {}
    }
}
