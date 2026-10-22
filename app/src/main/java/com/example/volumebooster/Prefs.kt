package com.example.volumebooster

import android.content.Context
import android.content.SharedPreferences

object Prefs {
    private const val FILE = "settings"
    const val KEY_BOOST = "boost_percent"
    const val KEY_ENABLED = "enabled"
    const val KEY_BASS = "eq_bass_db"
    const val KEY_MID = "eq_mid_db"
    const val KEY_TREBLE = "eq_treble_db"
    const val KEY_BASS_BOOST = "bass_boost_percent"

    fun prefs(c: Context): SharedPreferences = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun boost(c: Context) = prefs(c).getInt(KEY_BOOST, 150)
    fun setBoost(c: Context, percent: Int) = prefs(c).edit()
        .putInt(KEY_BOOST, percent.coerceIn(BoostService.MIN_PERCENT, BoostService.MAX_PERCENT))
        .apply()

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()

    /** Equalizer gains in dB, -12..+12. */
    fun bass(c: Context) = prefs(c).getInt(KEY_BASS, 0)
    fun mid(c: Context) = prefs(c).getInt(KEY_MID, 0)
    fun treble(c: Context) = prefs(c).getInt(KEY_TREBLE, 0)
    fun setEq(c: Context, bass: Int, mid: Int, treble: Int) = prefs(c).edit()
        .putInt(KEY_BASS, bass).putInt(KEY_MID, mid).putInt(KEY_TREBLE, treble).apply()

    /** Bass boost strength, 0..100. */
    fun bassBoost(c: Context) = prefs(c).getInt(KEY_BASS_BOOST, 0)
    fun setBassBoost(c: Context, percent: Int) =
        prefs(c).edit().putInt(KEY_BASS_BOOST, percent).apply()

    /** Raises or lowers the boost by [delta] percent and returns the new value. */
    fun adjustBoost(c: Context, delta: Int): Int {
        setBoost(c, boost(c) + delta)
        return boost(c)
    }
}
