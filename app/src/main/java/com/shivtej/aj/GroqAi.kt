package com.shivtej.aj

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * तेज़ दिमाग: Groq (free, card नहीं)।
 * groq/compound अपने अंदर internet search करता है और बहुत तेज़ जवाब देता है।
 * इसकी limit लगे तो दूसरा model, और फिर Gemini (Brain में)।
 */
object GroqAi {

    private const val URL = "https://api.groq.com/openai/v1/chat/completions"
    private val MODELS = listOf("groq/compound", "groq/compound-mini", "llama-3.3-70b-versatile")

    private val chat = ArrayList<Pair<String, String>>() // role, text

    fun ask(ctx: Context, question: String): GeminiAi.Res {
        val store = Store(ctx)
        val key = store.groqKey
        if (key.isBlank()) return GeminiAi.Res(false, "Groq key नहीं लगी है।")

        val ordered = LinkedHashSet<String>()
        store.getCache("groq_ok").takeIf { it.isNotBlank() }?.let { ordered.add(it) }
        ordered.addAll(MODELS)

        var lastError = "Groq से जवाब नहीं मिला।"
        for (model in ordered) {
            val res = try {
                Http.request(
                    "POST", URL,
                    mapOf("Authorization" to "Bearer $key"),
                    body(model, question).toString(),
                    readTimeoutMs = 25000
                )
            } catch (e: Exception) {
                return GeminiAi.Res(false, "Internet नहीं मिल रहा, इसलिए यह जवाब नहीं दे पाया।")
            }
            if (res.ok) {
                val text = try {
                    JSONObject(res.body).getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").optString("content")
                } catch (e: Exception) {
                    ""
                }
                val clean = TextUtil.cleanForSpeech(text)
                if (clean.isNotBlank()) {
                    store.setCache("groq_ok", model)
                    remember(question, clean)
                    return GeminiAi.Res(true, clean)
                }
                continue
            }
            if (res.code == 401) return GeminiAi.Res(false, "Groq key सही नहीं लग रही। AJ app में key फिर से डालिए।")
            lastError = if (res.code == 429) "Groq की free limit अभी भरी है।" else "Groq से गड़बड़ (code ${res.code})।"
            if (store.getCache("groq_ok") == model) store.setCache("groq_ok", "")
        }
        return GeminiAi.Res(false, lastError)
    }

    private fun remember(q: String, a: String) = synchronized(chat) {
        chat.add("user" to q)
        chat.add("assistant" to a)
        while (chat.size > 12) chat.removeAt(0)
    }

    private fun body(model: String, question: String): JSONObject {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale("hi", "IN")).format(Date())
        val system = """
            तुम AJ हो, user का अपना personal voice assistant, जो उसके Android फ़ोन में रहता है।
            अभी का समय: $now (भारत)।
            नियम:
            - जवाब उसी भाषा में दो जिसमें सवाल पूछा गया (ज़्यादातर हिंदी या Hinglish), देवनागरी में।
            - जवाब छोटा रखो, 1 से 3 वाक्य, जैसे कोई दोस्त बोलकर बताता है। लंबी बात तभी जब user माँगे।
            - markdown, *, #, bullet, emoji, link या सूची मत लिखो, क्योंकि जवाब आवाज़ में सुनाया जाएगा।
            - ताज़ा जानकारी (मौसम, खबर, भाव, स्कोर, कोई व्यक्ति या जगह) के लिए internet search से पता करके सही जवाब दो।
            - "तुम कहाँ हो" जैसे सवाल पर बताओ कि तुम उनके फ़ोन में हो और हमेशा साथ हो।
            - पता न हो तो साफ़ बोलो, बात मत बनाओ।
        """.trimIndent()
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", system))
        synchronized(chat) { chat.toList() }.forEach { (role, text) ->
            messages.put(JSONObject().put("role", role).put("content", text))
        }
        messages.put(JSONObject().put("role", "user").put("content", question))
        return JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("temperature", 0.5)
    }
}
