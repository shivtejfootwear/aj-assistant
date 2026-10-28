package com.shivtej.aj

import android.content.Context

/**
 * इस फ़ोन की settings और cloud login।
 * (यह app अब सिर्फ आपके अपने फ़ोनों का खुला admin है — कोई voice/assistant नहीं।)
 */
class Store(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aj_link", Context.MODE_PRIVATE)

    /** इस फ़ोन की पक्की पहचान (एक बार बनती है) */
    val deviceId: String
        get() {
            var id = prefs.getString("device_id", null)
            if (id.isNullOrBlank()) {
                id = "dev_" + java.util.UUID.randomUUID().toString().take(12)
                prefs.edit().putString("device_id", id).apply()
            }
            return id
        }

    /** इस फ़ोन का नाम जो admin में दिखेगा */
    var deviceName: String
        get() = prefs.getString("device_name", "") ?: ""
        set(value) = prefs.edit().putString("device_name", value.trim()).apply()

    /** Settings tab खोलने का PIN (शुरुआती 2580, user बदल सकता है) */
    var settingsPin: String
        get() = prefs.getString("settings_pin", "2580")?.takeIf { it.isNotBlank() } ?: "2580"
        set(value) = prefs.edit().putString("settings_pin", value.trim()).apply()

    // ---------------- Cloud (Supabase) ----------------

    var cloudUrl: String
        get() = prefs.getString("cloud_url", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_URL
        set(value) = prefs.edit().putString("cloud_url", value.trim().trimEnd('/')).apply()
    var cloudAnonKey: String
        get() = prefs.getString("cloud_key", null)?.takeIf { it.isNotBlank() } ?: BuildConfig.SUPABASE_ANON_KEY
        set(value) = prefs.edit().putString("cloud_key", value.trim()).apply()
    var accessToken: String
        get() = prefs.getString("cloud_access", "") ?: ""
        set(value) = prefs.edit().putString("cloud_access", value).apply()
    var refreshToken: String
        get() = prefs.getString("cloud_refresh", "") ?: ""
        set(value) = prefs.edit().putString("cloud_refresh", value).apply()
    var tokenExpiresAt: Long
        get() = prefs.getLong("cloud_expires", 0)
        set(value) = prefs.edit().putLong("cloud_expires", value).apply()
    var userId: String
        get() = prefs.getString("cloud_uid", "") ?: ""
        set(value) = prefs.edit().putString("cloud_uid", value).apply()
    var userEmail: String
        get() = prefs.getString("cloud_email", "") ?: ""
        set(value) = prefs.edit().putString("cloud_email", value).apply()

    val loggedIn: Boolean get() = refreshToken.isNotBlank() && userId.isNotBlank()

    fun clearSession() {
        prefs.edit()
            .remove("cloud_access").remove("cloud_refresh").remove("cloud_expires")
            .remove("cloud_uid").remove("cloud_email")
            .apply()
    }
}
