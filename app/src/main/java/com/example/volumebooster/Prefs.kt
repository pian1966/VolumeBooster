package com.example.volumebooster

import android.content.Context

object Prefs {
    private const val FILE = "settings"
    private const val KEY_BOOST = "boost_percent"
    private const val KEY_ENABLED = "enabled"

    private fun prefs(c: Context) = c.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun boost(c: Context) = prefs(c).getInt(KEY_BOOST, 150)
    fun setBoost(c: Context, percent: Int) = prefs(c).edit().putInt(KEY_BOOST, percent).apply()

    fun enabled(c: Context) = prefs(c).getBoolean(KEY_ENABLED, false)
    fun setEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_ENABLED, on).apply()
}
