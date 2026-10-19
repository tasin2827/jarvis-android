package com.tasin.jarvis.data

import android.content.Context

class JarvisSettings(context: Context) {
    private val prefs = context.getSharedPreferences("jarvis_settings", Context.MODE_PRIVATE)

    var apiKey: String
        get() = prefs.getString("api_key", "") ?: ""
        set(value) { prefs.edit().putString("api_key", value.trim()).apply() }

    var model: String
        get() = prefs.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(value) { prefs.edit().putString("model", value.trim().ifEmpty { DEFAULT_MODEL }).apply() }

    var cloudEnabled: Boolean
        get() = prefs.getBoolean("cloud_enabled", true)
        set(value) { prefs.edit().putBoolean("cloud_enabled", value).apply() }

    var voiceReplies: Boolean
        get() = prefs.getBoolean("voice_replies", true)
        set(value) { prefs.edit().putBoolean("voice_replies", value).apply() }

    var language: String
        get() = prefs.getString("language", "bn-BD") ?: "bn-BD"
        set(value) { prefs.edit().putString("language", value).apply() }

    companion object {
        const val DEFAULT_MODEL = "gemini-3.6-flash"
    }
}
