package com.shivtej.aj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** "जब मैं यह बोलूँ → AJ यह बोले" (updated = आखिरी बदलाव का समय, cloud merge के लिए) */
data class Teach(val id: Long, val trigger: String, val reply: String, val updated: Long = 0)

/** बातचीत की एक लाइन */
data class HistoryItem(val time: Long, val who: String, val text: String)

/**
 * AJ की याददाश्त।
 * फ़ोन में रहती है, और login हो तो हर बदलाव के बाद cloud (Supabase) में भी चली जाती है।
 */
class Store(context: Context) {

    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("aj_memory", Context.MODE_PRIVATE)

    // =================== फ़ोन की अपनी settings (cloud में नहीं जातीं) ===================

    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", false)
        set(value) = prefs.edit().putBoolean("wake_enabled", value).apply()

    // Cloud login (सिर्फ इसी फ़ोन पर)
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
    var lastSync: Long
        get() = prefs.getLong("cloud_last_sync", 0)
        set(value) = prefs.edit().putLong("cloud_last_sync", value).apply()
    var lastBackupDay: String
        get() = prefs.getString("cloud_backup_day", "") ?: ""
        set(value) = prefs.edit().putString("cloud_backup_day", value).apply()

    val loggedIn: Boolean get() = refreshToken.isNotBlank() && userId.isNotBlank()

    fun clearSession() {
        prefs.edit()
            .remove("cloud_access").remove("cloud_refresh").remove("cloud_expires")
            .remove("cloud_uid").remove("cloud_email").remove("cloud_last_sync")
            .apply()
    }

    // Gemini के model का cache (फ़ोन पर)
    fun getCache(key: String): String = prefs.getString("cache_$key", "") ?: ""
    fun setCache(key: String, value: String) = prefs.edit().putString("cache_$key", value).apply()

    // =================== साथ में cloud जाने वाली settings ===================

    var geminiKey: String
        get() = prefs.getString("s_gemini_key", "") ?: ""
        set(value) = setSynced { putString("s_gemini_key", value.trim()) }
    var groqKey: String
        get() = prefs.getString("s_groq_key", "") ?: ""
        set(value) = setSynced { putString("s_groq_key", value.trim()) }
    var voiceName: String
        get() = prefs.getString("s_voice", "") ?: ""
        set(value) = setSynced { putString("s_voice", value) }
    var pitch: Float
        get() = prefs.getFloat("s_pitch", 1.0f)
        set(value) = setSynced { putFloat("s_pitch", value) }
    var rate: Float
        get() = prefs.getFloat("s_rate", 0.95f)
        set(value) = setSynced { putFloat("s_rate", value) }
    var geminiVoiceOn: Boolean
        get() = prefs.getBoolean("s_gvoice_on", false)
        set(value) = setSynced { putBoolean("s_gvoice_on", value) }
    var geminiVoiceName: String
        get() = prefs.getString("s_gvoice", "Sulafat") ?: "Sulafat"
        set(value) = setSynced { putString("s_gvoice", value) }

    private fun setSynced(block: android.content.SharedPreferences.Editor.() -> Unit) {
        val e = prefs.edit()
        e.block()
        e.putLong("s_updated", System.currentTimeMillis())
        e.apply()
        changed()
    }

    // =================== सिखाई हुई बातें ===================

    fun teaches(): List<Teach> = synchronized(LOCK) {
        if (!prefs.contains("teach")) writeTeaches(defaultTeaches())
        parseTeaches(JSONArray(prefs.getString("teach", "[]")))
    }

    fun upsertTeach(teach: Teach) {
        synchronized(LOCK) {
            val list = teaches().toMutableList()
            val stamped = teach.copy(updated = System.currentTimeMillis())
            val index = list.indexOfFirst { it.id == teach.id }
            if (index >= 0) list[index] = stamped else list.add(stamped)
            writeTeaches(list)
        }
        changed()
    }

    fun deleteTeach(id: Long) {
        synchronized(LOCK) {
            writeTeaches(teaches().filter { it.id != id })
            val dead = deletedTeachIds().toMutableSet()
            dead.add(id)
            prefs.edit().putString("teach_deleted", JSONArray(dead.toList()).toString()).apply()
        }
        changed()
    }

    private fun deletedTeachIds(): Set<Long> {
        val arr = JSONArray(prefs.getString("teach_deleted", "[]"))
        return (0 until arr.length()).map { arr.getLong(it) }.toSet()
    }

    /** जब आप सिर्फ "AJ" बोलकर चुप रहें, तब AJ यह बोलेगा */
    fun greeting(): String {
        val t = teaches().firstOrNull { TextUtil.norm(it.trigger) == TextUtil.norm("क्या कर रहे हो") }
        return t?.reply ?: DEFAULT_GREETING
    }

    private fun writeTeaches(list: List<Teach>) {
        prefs.edit().putString("teach", teachesToJson(list).toString()).apply()
    }

    private fun teachesToJson(list: List<Teach>): JSONArray {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("t", it.trigger).put("r", it.reply).put("u", it.updated))
        }
        return arr
    }

    private fun parseTeaches(arr: JSONArray): List<Teach> {
        val out = ArrayList<Teach>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Teach(o.getLong("id"), o.getString("t"), o.getString("r"), o.optLong("u", 0)))
        }
        return out
    }

    private fun defaultTeaches() = listOf(
        Teach(1, "क्या कर रहे हो", DEFAULT_GREETING),
        Teach(2, "कैसे हो", "मैं बढ़िया हूँ। बताइए, आपके लिए क्या करूँ?"),
        Teach(3, "तुम कौन हो", "मैं AJ हूँ, आपका अपना assistant।"),
        Teach(4, "थैंक यू", "आपका स्वागत है।"),
    )

    // =================== बातचीत ===================

    fun history(): List<HistoryItem> = synchronized(LOCK) {
        parseHistory(JSONArray(prefs.getString("history", "[]")))
    }

    fun addHistory(who: String, text: String) {
        synchronized(LOCK) {
            val list = history().toMutableList()
            list.add(HistoryItem(System.currentTimeMillis(), who, text))
            writeHistory(list)
        }
        changed()
    }

    private fun writeHistory(list: List<HistoryItem>) {
        val keep = list.sortedBy { it.time }.takeLast(MAX_HISTORY)
        prefs.edit().putString("history", historyToJson(keep).toString()).apply()
    }

    private fun historyToJson(list: List<HistoryItem>): JSONArray {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("time", it.time).put("who", it.who).put("text", it.text)) }
        return arr
    }

    private fun parseHistory(arr: JSONArray): List<HistoryItem> {
        val out = ArrayList<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(HistoryItem(o.getLong("time"), o.getString("who"), o.getString("text")))
        }
        return out
    }

    // =================== Cloud के लिए: पूरा data एक JSON में ===================

    fun exportJson(): JSONObject = synchronized(LOCK) {
        JSONObject()
            .put("v", 1)
            .put("teach", teachesToJson(teaches()))
            .put("teach_deleted", JSONArray(deletedTeachIds().toList()))
            .put("history", historyToJson(history()))
            .put(
                "settings", JSONObject()
                    .put("updated", prefs.getLong("s_updated", 0))
                    .put("gemini_key", geminiKey)
                    .put("groq_key", groqKey)
                    .put("voice", voiceName)
                    .put("pitch", pitch.toDouble())
                    .put("rate", rate.toDouble())
                    .put("gvoice_on", geminiVoiceOn)
                    .put("gvoice", geminiVoiceName)
            )
    }

    /**
     * Cloud वाला data फ़ोन वाले में मिलाना। कुछ भी डिलीट नहीं होता:
     * सिखाई बातें id से मिलती हैं (जो नई बदली वो जीते), बातचीत जुड़ जाती है, settings में जो नई हो वो।
     */
    fun mergeFrom(cloud: JSONObject) {
        synchronized(LOCK) {
            // हटाई गई बातें
            val dead = deletedTeachIds().toMutableSet()
            cloud.optJSONArray("teach_deleted")?.let { arr -> for (i in 0 until arr.length()) dead.add(arr.getLong(i)) }
            prefs.edit().putString("teach_deleted", JSONArray(dead.toList()).toString()).apply()

            // सिखाई बातें
            val map = LinkedHashMap<Long, Teach>()
            teaches().forEach { map[it.id] = it }
            cloud.optJSONArray("teach")?.let { arr ->
                parseTeaches(arr).forEach { c ->
                    val local = map[c.id]
                    if (local == null || c.updated > local.updated) map[c.id] = c
                }
            }
            writeTeaches(map.values.filter { it.id !in dead })

            // बातचीत
            val all = LinkedHashMap<String, HistoryItem>()
            history().forEach { all["${it.time}|${it.who}|${it.text}"] = it }
            cloud.optJSONArray("history")?.let { arr ->
                parseHistory(arr).forEach { all["${it.time}|${it.who}|${it.text}"] = it }
            }
            writeHistory(all.values.toList())

            // settings
            val s = cloud.optJSONObject("settings")
            if (s != null && s.optLong("updated", 0) > prefs.getLong("s_updated", 0)) {
                prefs.edit()
                    .putString("s_gemini_key", s.optString("gemini_key", ""))
                    .putString("s_groq_key", s.optString("groq_key", ""))
                    .putString("s_voice", s.optString("voice", ""))
                    .putFloat("s_pitch", s.optDouble("pitch", 1.0).toFloat())
                    .putFloat("s_rate", s.optDouble("rate", 0.95).toFloat())
                    .putBoolean("s_gvoice_on", s.optBoolean("gvoice_on", false))
                    .putString("s_gvoice", s.optString("gvoice", "Sulafat"))
                    .putLong("s_updated", s.optLong("updated", 0))
                    .apply()
            }
        }
    }

    /** कुछ भी बदला → थोड़ी देर बाद cloud में भेजो */
    private fun changed() {
        CloudSync.scheduleSync(app)
    }

    companion object {
        private val LOCK = Any()
        private const val MAX_HISTORY = 300
        const val DEFAULT_GREETING = "मैं आपकी कैसे सहायता कर सकता हूँ?"
    }
}
