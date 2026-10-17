package com.shivtej.aj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** "जब मैं यह बोलूँ → AJ यह बोले" */
data class Teach(val id: Long, val trigger: String, val reply: String)

/** बातचीत की एक लाइन */
data class HistoryItem(val time: Long, val who: String, val text: String)

/** AJ की याददाश्त: फ़ोन के अंदर ही रहती है, कहीं बाहर नहीं जाती। */
class Store(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("aj_memory", Context.MODE_PRIVATE)

    var wakeEnabled: Boolean
        get() = prefs.getBoolean("wake_enabled", false)
        set(value) {
            prefs.edit().putBoolean("wake_enabled", value).apply()
        }

    // ---------------- सिखाई हुई बातें ----------------

    fun teaches(): List<Teach> = synchronized(LOCK) {
        if (!prefs.contains("teach")) writeTeaches(defaultTeaches())
        val arr = JSONArray(prefs.getString("teach", "[]"))
        val out = ArrayList<Teach>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(Teach(o.getLong("id"), o.getString("t"), o.getString("r")))
        }
        out
    }

    fun upsertTeach(teach: Teach) = synchronized(LOCK) {
        val list = teaches().toMutableList()
        val index = list.indexOfFirst { it.id == teach.id }
        if (index >= 0) list[index] = teach else list.add(teach)
        writeTeaches(list)
    }

    fun deleteTeach(id: Long) = synchronized(LOCK) {
        writeTeaches(teaches().filter { it.id != id })
    }

    /** जब आप सिर्फ "AJ" बोलकर चुप रहें, तब AJ यह बोलेगा */
    fun greeting(): String {
        val t = teaches().firstOrNull { TextUtil.norm(it.trigger) == TextUtil.norm("क्या कर रहे हो") }
        return t?.reply ?: DEFAULT_GREETING
    }

    private fun writeTeaches(list: List<Teach>) {
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("id", it.id).put("t", it.trigger).put("r", it.reply)) }
        prefs.edit().putString("teach", arr.toString()).apply()
    }

    private fun defaultTeaches() = listOf(
        Teach(1, "क्या कर रहे हो", DEFAULT_GREETING),
        Teach(2, "कैसे हो", "मैं बढ़िया हूँ। बताइए, आपके लिए क्या करूँ?"),
        Teach(3, "तुम कौन हो", "मैं AJ हूँ, आपका अपना assistant।"),
        Teach(4, "थैंक यू", "आपका स्वागत है।"),
    )

    // ---------------- बातचीत ----------------

    fun history(): List<HistoryItem> = synchronized(LOCK) {
        val arr = JSONArray(prefs.getString("history", "[]"))
        val out = ArrayList<HistoryItem>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            out.add(HistoryItem(o.getLong("time"), o.getString("who"), o.getString("text")))
        }
        out
    }

    fun addHistory(who: String, text: String) = synchronized(LOCK) {
        val list = history().toMutableList()
        list.add(HistoryItem(System.currentTimeMillis(), who, text))
        val keep = list.takeLast(80)
        val arr = JSONArray()
        keep.forEach { arr.put(JSONObject().put("time", it.time).put("who", it.who).put("text", it.text)) }
        prefs.edit().putString("history", arr.toString()).apply()
    }

    companion object {
        private val LOCK = Any()
        const val DEFAULT_GREETING = "मैं आपकी कैसे सहायता कर सकता हूँ?"
    }
}
