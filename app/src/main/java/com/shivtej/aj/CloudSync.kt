package com.shivtej.aj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/**
 * Cloud backup (Supabase, free)।
 * - Login: email + password (Supabase Auth)
 * - aj_memory: AJ की पूरी ताज़ा याददाश्त (हर बदलाव के कुछ सेकंड बाद)
 * - aj_backups: रोज़ की एक अलग copy (cold backup), पुरानी भी बची रहती हैं
 * Reinstall के बाद login करते ही सब वापस आ जाता है।
 */
object CloudSync {

    private val executor = Executors.newSingleThreadScheduledExecutor()
    private var pending: ScheduledFuture<*>? = null

    @Volatile
    var lastMessage: String = ""
        private set

    fun isConfigured(store: Store) = store.cloudUrl.startsWith("https://") && store.cloudAnonKey.isNotBlank()

    // ---------------- login ----------------

    /** login; सफल हो तो null, नहीं तो गलती का संदेश */
    fun signIn(ctx: Context, email: String, password: String): String? {
        val store = Store(ctx)
        if (!isConfigured(store)) return "पहले Supabase URL और anon key डालिए"
        val res = try {
            Http.request(
                "POST", "${store.cloudUrl}/auth/v1/token?grant_type=password", baseHeaders(store),
                JSONObject().put("email", email.trim()).put("password", password).toString()
            )
        } catch (e: Exception) {
            return "Internet नहीं मिला: ${e.message ?: ""}"
        }
        if (!res.ok) return "Login नहीं हुआ: ${errorText(res)}"
        saveSession(store, JSONObject(res.body))
        val syncError = syncNow(ctx)
        return syncError?.let { "Login हो गया, पर sync में दिक्कत: $it" }
    }

    /** नया account। सफल हो तो null */
    fun signUp(ctx: Context, email: String, password: String): String? {
        val store = Store(ctx)
        if (!isConfigured(store)) return "पहले Supabase URL और anon key डालिए"
        if (password.length < 6) return "Password कम से कम 6 अक्षर का रखें"
        val res = try {
            Http.request(
                "POST", "${store.cloudUrl}/auth/v1/signup", baseHeaders(store),
                JSONObject().put("email", email.trim()).put("password", password).toString()
            )
        } catch (e: Exception) {
            return "Internet नहीं मिला: ${e.message ?: ""}"
        }
        if (!res.ok) return "Account नहीं बना: ${errorText(res)}"
        val json = JSONObject(res.body)
        return if (json.has("access_token")) {
            saveSession(store, json)
            syncNow(ctx)
            null
        } else {
            // Supabase में email confirm चालू है
            "Account बन गया। Email में आया link दबाइए, फिर यहाँ Login कीजिए।"
        }
    }

    fun signOut(ctx: Context) {
        val store = Store(ctx)
        // जाने से पहले आखिरी बार cloud में डाल दो
        syncNow(ctx)
        store.clearSession()
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

    /** token पुराना हो तो नया लो; ठीक रहा तो true */
    private fun ensureSession(store: Store): Boolean {
        if (!store.loggedIn) return false
        if (store.accessToken.isNotBlank() && System.currentTimeMillis() < store.tokenExpiresAt - 60_000) return true
        val res = Http.request(
            "POST", "${store.cloudUrl}/auth/v1/token?grant_type=refresh_token", baseHeaders(store),
            JSONObject().put("refresh_token", store.refreshToken).toString()
        )
        if (!res.ok) {
            lastMessage = "Login पुराना हो गया, फिर से login कीजिए"
            if (res.code == 400 || res.code == 401) store.clearSession()
            return false
        }
        saveSession(store, JSONObject(res.body))
        return true
    }

    // ---------------- sync ----------------

    /** हर बदलाव के बाद 8 सेकंड रुककर एक साथ भेजना */
    private val scheduleLock = Any()

    fun scheduleSync(ctx: Context) {
        val app = ctx.applicationContext
        if (!Store(app).loggedIn) return
        synchronized(scheduleLock) {
            pending?.cancel(false)
            pending = executor.schedule(Runnable { syncNow(app) }, 8, TimeUnit.SECONDS)
        }
    }

    fun syncInBackground(ctx: Context, done: ((String?) -> Unit)? = null) {
        val app = ctx.applicationContext
        executor.execute {
            val r = syncNow(app)
            done?.invoke(r)
        }
    }

    /**
     * cloud से लाओ → फ़ोन में मिलाओ → मिला हुआ वापस cloud में रखो → दिन की पहली बार अलग backup copy।
     * सफल हो तो null।
     */
    @Synchronized
    fun syncNow(ctx: Context): String? {
        val store = Store(ctx)
        return try {
            if (!isConfigured(store)) return "Cloud setup नहीं है"
            if (!ensureSession(store)) return lastMessage.ifBlank { "Login नहीं है" }
            val headers = authHeaders(store)

            // 1) cloud से
            val get = Http.request(
                "GET", "${store.cloudUrl}/rest/v1/aj_memory?select=data&user_id=eq.${store.userId}", headers
            )
            if (!get.ok) return failed("Cloud से पढ़ नहीं पाया: ${errorText(get)}")
            val rows = JSONArray(get.body)
            if (rows.length() > 0) {
                rows.getJSONObject(0).optJSONObject("data")?.let { store.mergeFrom(it) }
            }

            // 2) cloud में
            val data = store.exportJson()
            val row = JSONObject()
                .put("user_id", store.userId)
                .put("data", data)
                .put("updated_at", isoNow())
            val put = Http.request(
                "POST", "${store.cloudUrl}/rest/v1/aj_memory?on_conflict=user_id",
                headers + mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
                row.toString()
            )
            if (!put.ok) return failed("Cloud में रख नहीं पाया: ${errorText(put)}")

            // 3) रोज़ की अलग copy
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            if (store.lastBackupDay != today) {
                val b = Http.request(
                    "POST", "${store.cloudUrl}/rest/v1/aj_backups",
                    headers + mapOf("Prefer" to "return=minimal"),
                    JSONObject().put("user_id", store.userId).put("data", data).toString()
                )
                if (b.ok) store.lastBackupDay = today
            }

            store.lastSync = System.currentTimeMillis()
            lastMessage = "✅ Cloud में सेव"
            AjBus.historyChanged()
            null
        } catch (e: Exception) {
            failed("Internet नहीं मिला: ${e.message ?: ""}")
        }
    }

    private fun failed(msg: String): String {
        lastMessage = msg
        return msg
    }

    /** DeviceSync के लिए: session पक्का करके auth headers दो, न हो तो null */
    fun authHeadersEnsured(ctx: Context): Map<String, String>? {
        val store = Store(ctx)
        if (!isConfigured(store) || !ensureSession(store)) return null
        return authHeaders(store)
    }

    // ---------------- helper ----------------

    private fun baseHeaders(store: Store) = mapOf(
        "apikey" to store.cloudAnonKey,
        "Authorization" to "Bearer ${store.cloudAnonKey}",
    )

    private fun authHeaders(store: Store) = mapOf(
        "apikey" to store.cloudAnonKey,
        "Authorization" to "Bearer ${store.accessToken}",
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

    private fun isoNow(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        f.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }
}
