package com.mark.infiniterecorder.data

import android.content.Context

class SettingsRepository(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var bitrate: Int
        get() = prefs.getInt(KEY_BITRATE, DEFAULT_BITRATE)
        set(value) = prefs.edit().putInt(KEY_BITRATE, value).apply()

    var segmentMinutes: Int
        get() = prefs.getInt(KEY_SEGMENT_MINUTES, DEFAULT_SEGMENT_MINUTES)
        set(value) = prefs.edit().putInt(KEY_SEGMENT_MINUTES, value).apply()

    var silenceSuppression: Boolean
        get() = prefs.getBoolean(KEY_SILENCE, true)
        set(value) = prefs.edit().putBoolean(KEY_SILENCE, value).apply()

    var sensitivity: String
        get() = prefs.getString(KEY_SENSITIVITY, DEFAULT_SENSITIVITY) ?: DEFAULT_SENSITIVITY
        set(value) = prefs.edit().putString(KEY_SENSITIVITY, value).apply()

    companion object {
        private const val KEY_BITRATE = "bitrate"
        private const val KEY_SEGMENT_MINUTES = "segment_minutes"
        private const val KEY_SILENCE = "silence_suppression"
        private const val KEY_SENSITIVITY = "sensitivity"
        const val DEFAULT_BITRATE = 64_000
        const val DEFAULT_SEGMENT_MINUTES = 60
        const val DEFAULT_SENSITIVITY = "Medium"
        const val MAX_STORAGE_BYTES = 5_000_000_000L
    }
}
