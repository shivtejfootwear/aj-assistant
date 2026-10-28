package com.shivtej.aj

import android.content.Context
import org.json.JSONObject

/**
 * Supabase login (email + password)। यही account से दोनों फ़ोन आपस में जुड़ते हैं।
 */
object CloudSync {

    @Volatile
    var lastMessage: String = ""
        private set

    fun isConfigured(store: Store) = store.cloudUrl.startsWith("https://") && store.cloudAnonKey.isNotBlank()

    fun signIn(ctx: Context, email: String, password: String): String? {
        val store = Store(ctx)
        if (!isConfigured(store)) return "Enter the Supabase URL and anon key first"
        val res = try {
            Http.request(
                "POST", "${store.cloudUrl}/auth/v1/token?grant_type=password", baseHeaders(store),
                JSONObject().put("email", email.trim()).put("password", password).toString()
            )
        } catch (e: Exception) {
            return "No internet: ${e.message ?: ""}"
        }
        if (!res.ok) return "Login failed: ${errorText(res)}"
        saveSession(store, JSONObject(res.body))
        return null
    }

    fun signUp(ctx: Context, email: String, password: String): String? {
        val store = Store(ctx)
        if (!isConfigured(store)) return "Enter the Supabase URL and anon key first"
        if (password.length < 6) return "Password must be at least 6 characters"
        val res = try {
            Http.request(
                "POST", "${store.cloudUrl}/auth/v1/signup", baseHeaders(store),
                JSONObject().put("email", email.trim()).put("password", password).toString()
            )
        } catch (e: Exception) {
            return "No internet: ${e.message ?: ""}"
        }
        if (!res.ok) return "Account not created: ${errorText(res)}"
        val json = JSONObject(res.body)
        return if (json.has("access_token")) {
            saveSession(store, json)
            null
        } else {
            "Account created. Tap the link in your email, then Login here."
        }
    }

    fun signOut(ctx: Context) {
        Store(ctx).clearSession()
    }

    private fun saveSession(store: Store, json: JSONObject) {
        store.accessToken = json.optString("access_token")
        store.refreshToken = json.optString("refresh_token")
        store.tokenExpiresAt = System.currentTimeMillis() + json.optLong("expires_in", 3600) * 1000
        json.optJSONObject("user")?.let {
            store.userId = it.optString("id")
            store.userEmail = it.optString("email")
        }
    }

    private fun ensureSession(store: Store): Boolean {
        if (!store.loggedIn) return false
        if (store.accessToken.isNotBlank() && System.currentTimeMillis() < store.tokenExpiresAt - 60_000) return true
        val res = Http.request(
            "POST", "${store.cloudUrl}/auth/v1/token?grant_type=refresh_token", baseHeaders(store),
            JSONObject().put("refresh_token", store.refreshToken).toString()
        )
        if (!res.ok) {
            lastMessage = "Session expired, please login again"
            if (res.code == 400 || res.code == 401) store.clearSession()
            return false
        }
        saveSession(store, JSONObject(res.body))
        return true
    }

    /** session पक्का करके auth headers दो, न हो तो null */
    fun authHeadersEnsured(ctx: Context): Map<String, String>? {
        val store = Store(ctx)
        if (!isConfigured(store) || !ensureSession(store)) return null
        return mapOf("apikey" to store.cloudAnonKey, "Authorization" to "Bearer ${store.accessToken}")
    }

    private fun baseHeaders(store: Store) = mapOf(
        "apikey" to store.cloudAnonKey,
        "Authorization" to "Bearer ${store.cloudAnonKey}",
    )

    private fun errorText(res: Http.Res): String = try {
        val j = JSONObject(res.body)
        j.optString("msg").ifBlank { j.optString("error_description") }
            .ifBlank { j.optString("message") }
            .ifBlank { j.optString("error") }
            .ifBlank { "code ${res.code}" }
    } catch (e: Exception) {
        "code ${res.code}"
    }
}
