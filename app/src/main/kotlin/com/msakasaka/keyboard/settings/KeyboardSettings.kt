package com.msakasaka.keyboard.settings

import android.content.Context
import androidx.preference.PreferenceManager

class KeyboardSettings(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    /** 1キーの高さ (dp) */
    var keyHeightDp: Int
        get() = prefs.getInt("key_height_dp", 56)
        set(value) = prefs.edit().putInt("key_height_dp", value).apply()

    /** キーボード幅スケール (70〜100) */
    var keyWidthScale: Int
        get() = prefs.getInt("key_width_scale", 100)
        set(value) = prefs.edit().putInt("key_width_scale", value).apply()

    /** Groq API key for AI prediction (free tier: groq.com) */
    var groqApiKey: String
        get() = prefs.getString("groq_api_key", "") ?: ""
        set(value) = prefs.edit().putString("groq_api_key", value).apply()

    fun registerListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterListener(listener: android.content.SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
