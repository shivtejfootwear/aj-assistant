package com.shivtej.aj

import android.content.Context
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * AJ का internet वाला दिमाग: Google Gemini (free tier) + Google Search।
 * - सिर्फ वही सवाल यहाँ आते हैं जो फ़ोन वाले commands में नहीं आते
 * - आपके SMS, कॉल लिस्ट, OTP यहाँ नहीं भेजे जाते
 * - कौन सा model free है, यह AJ खुद Google से पूछकर चुनता है (model के नाम बदलते रहते हैं)
 */
object GeminiAi {

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta"

    /** सिर्फ Gemini वाली बातचीत (ताकि आगे के सवाल पिछले से जुड़े रहें) */
    private val chat = ArrayList<Pair<String, String>>() // role, text

    val VOICES = listOf(
        "Sulafat" to "Sulafat (गर्म, soft)",
        "Achernar" to "Achernar (soft)",
        "Leda" to "Leda (जवान, हल्की)",
        "Aoede" to "Aoede (हवादार)",
        "Kore" to "Kore (मज़बूत)",
        "Zephyr" to "Zephyr (चमकदार)",
        "Vindemiatrix" to "Vindemiatrix (नरम)",
        "Charon" to "Charon (पुरुष, जानकार)",
        "Puck" to "Puck (पुरुष, जोशीला)",
        "Achird" to "Achird (पुरुष, दोस्ताना)",
        "Iapetus" to "Iapetus (पुरुष, साफ़)",
    )

    // ======================= सवाल-जवाब =======================

    /** हमेशा एक बोलने लायक जवाब लौटाता है (गलती हो तो उसका संदेश) */
    fun ask(ctx: Context, question: String): String {
        val store = Store(ctx)
        val key = store.geminiKey
        if (key.isBlank()) return "यह सवाल internet से पूछना होगा। AJ app में ‘AI दिमाग’ वाले हिस्से में free Gemini key डालिए।"

        val models = orderedModels(store, "chat")
        var lastError = "जवाब नहीं मिला"
        for (model in models.take(4)) {
            for (withSearch in listOf(true, false)) {
                val res = try {
                    Http.request(
                        "POST", "$BASE/models/$model:generateContent",
                        mapOf("x-goog-api-key" to key),
                        chatBody(question, withSearch).toString(),
                        readTimeoutMs = 45000
                    )
                } catch (e: Exception) {
                    return "Internet नहीं मिल रहा, इसलिए यह जवाब नहीं दे पाया।"
                }
                when {
                    res.ok -> {
                        val text = clean(extractText(res.body))
                        if (text.isNotBlank()) {
                            store.setCache("chat_ok", model)
                            remember(question, text)
                            return text
                        }
                        lastError = "जवाब खाली आया"
                        break
                    }
                    res.code == 401 || res.code == 403 && res.body.contains("API_KEY", ignoreCase = true) ->
                        return "Gemini key सही नहीं लग रही। AJ app में key फिर से डालिए।"
                    res.code == 400 && withSearch -> continue // search के बिना कोशिश
                    res.code == 429 -> { lastError = "आज की free limit पूरी हो गई, कल फिर से चलेगा।"; break }
                    else -> { lastError = "Gemini से गड़बड़ (code ${res.code})"; break }
                }
            }
        }
        if (store.getCache("chat_ok").isNotBlank()) store.setCache("chat_ok", "")
        return lastError
    }

    fun resetChat() = synchronized(chat) { chat.clear() }

    private fun remember(q: String, a: String) = synchronized(chat) {
        chat.add("user" to q)
        chat.add("model" to a)
        while (chat.size > 12) chat.removeAt(0)
    }

    private fun chatBody(question: String, withSearch: Boolean): JSONObject {
        val now = SimpleDateFormat("EEEE, d MMMM yyyy, h:mm a", Locale("hi", "IN")).format(Date())
        val system = """
            तुम AJ हो, user का अपना personal voice assistant, जो उसके Android फ़ोन में रहता है।
            अभी का समय: $now (भारत)।
            नियम:
            - जवाब उसी भाषा में दो जिसमें सवाल पूछा गया (ज़्यादातर हिंदी या Hinglish), देवनागरी में।
            - जवाब छोटा रखो, 1 से 3 वाक्य, जैसे कोई दोस्त बोलकर बताता है। लंबी बात तभी जब user माँगे।
            - markdown, *, #, bullet, emoji, link या सूची मत लिखो, क्योंकि जवाब आवाज़ में सुनाया जाएगा।
            - ताज़ा जानकारी (मौसम, खबर, भाव, स्कोर, कोई व्यक्ति या जगह) के लिए Google Search से पता करके सही जवाब दो।
            - "तुम कहाँ हो" जैसे सवाल पर बताओ कि तुम उनके फ़ोन में हो और हमेशा साथ हो।
            - पता न हो तो साफ़ बोलो, बात मत बनाओ।
        """.trimIndent()

        val contents = JSONArray()
        val turns = synchronized(chat) { chat.toList() } + ("user" to question)
        turns.forEach { (role, text) ->
            contents.put(JSONObject().put("role", role).put("parts", JSONArray().put(JSONObject().put("text", text))))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.6))
        if (withSearch) body.put("tools", JSONArray().put(JSONObject().put("google_search", JSONObject())))
        return body
    }

    private fun extractText(body: String): String {
        val parts = JSONObject(body).optJSONArray("candidates")?.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.optJSONObject(i) ?: continue
            if (p.optBoolean("thought", false)) continue
            sb.append(p.optString("text"))
        }
        return sb.toString()
    }

    /** आवाज़ में सुनाने लायक साफ़ text */
    private fun clean(s: String): String = s
        .replace(Regex("\\[[0-9, ]+]"), "")           // [1] जैसे निशान
        .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1")  // markdown link
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("[*#_`>|]"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(700)

    // ======================= असली आवाज़ (TTS) =======================

    /** text → (PCM आवाज़, sample rate)। न बने तो null (तब फ़ोन वाली आवाज़ चलेगी) */
    fun synthesize(ctx: Context, text: String, voiceOverride: String? = null): Pair<ByteArray, Int>? {
        val store = Store(ctx)
        val key = store.geminiKey
        if (key.isBlank()) return null
        val voice = voiceOverride ?: store.geminiVoiceName
        val body = JSONObject()
            .put(
                "contents", JSONArray().put(
                    JSONObject().put(
                        "parts", JSONArray().put(
                            JSONObject().put("text", "Say this in a soft, warm, calm and friendly voice: $text")
                        )
                    )
                )
            )
            .put(
                "generationConfig", JSONObject()
                    .put("responseModalities", JSONArray().put("AUDIO"))
                    .put(
                        "speechConfig", JSONObject().put(
                            "voiceConfig", JSONObject().put(
                                "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                            )
                        )
                    )
            )
        for (model in orderedModels(store, "tts").take(3)) {
            val res = try {
                Http.request(
                    "POST", "$BASE/models/$model:generateContent",
                    mapOf("x-goog-api-key" to key), body.toString(), readTimeoutMs = 45000
                )
            } catch (e: Exception) {
                return null
            }
            if (!res.ok) continue
            val parts = JSONObject(res.body).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
            for (i in 0 until parts.length()) {
                val inline = parts.optJSONObject(i)?.optJSONObject("inlineData") ?: continue
                val data = inline.optString("data")
                if (data.isBlank()) continue
                val mime = inline.optString("mimeType")
                val rate = Regex("rate=(\\d+)").find(mime)?.groupValues?.get(1)?.toIntOrNull() ?: 24000
                var pcm = Base64.decode(data, Base64.DEFAULT)
                if (pcm.size % 2 != 0) pcm = pcm.copyOf(pcm.size - 1)
                store.setCache("tts_ok", model)
                return pcm to rate
            }
        }
        return null
    }

    // ======================= model चुनना =======================

    /** पिछली बार जो चला वो सबसे पहले, फिर Google की list से सबसे नया free-जैसा model */
    private fun orderedModels(store: Store, kind: String): List<String> {
        val out = LinkedHashSet<String>()
        store.getCache("${kind}_ok").takeIf { it.isNotBlank() }?.let { out.add(it) }
        out.addAll(listModels(store, kind))
        if (kind == "chat") out.addAll(listOf("gemini-flash-latest", "gemini-flash-lite-latest"))
        else out.addAll(listOf("gemini-3.1-flash-tts-preview", "gemini-2.5-flash-preview-tts"))
        return out.toList()
    }

    private fun listModels(store: Store, kind: String): List<String> {
        val cacheKey = "models_$kind"
        val cacheDay = "models_day"
        val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        if (store.getCache(cacheDay) == today && store.getCache(cacheKey).isNotBlank()) {
            return store.getCache(cacheKey).split(",").filter { it.isNotBlank() }
        }
        return try {
            val res = Http.request("GET", "$BASE/models?pageSize=200", mapOf("x-goog-api-key" to store.geminiKey))
            if (!res.ok) return emptyList()
            val arr = JSONObject(res.body).optJSONArray("models") ?: return emptyList()
            val chat = ArrayList<String>()
            val tts = ArrayList<String>()
            for (i in 0 until arr.length()) {
                val m = arr.getJSONObject(i)
                val name = m.optString("name").removePrefix("models/")
                val methods = m.optJSONArray("supportedGenerationMethods")?.toString() ?: ""
                if (!methods.contains("generateContent") || !name.contains("flash")) continue
                when {
                    name.contains("tts") -> tts.add(name)
                    listOf("image", "live", "audio", "embedding", "robotics", "computer", "exp").none { name.contains(it) } ->
                        chat.add(name)
                }
            }
            val sortedChat = chat.sortedWith(compareBy<String>({ it.contains("lite") }, { -version(it) }, { it.contains("preview") }))
            val sortedTts = tts.sortedWith(compareBy<String>({ -version(it) }, { it.contains("pro") }))
            store.setCache("models_chat", sortedChat.joinToString(","))
            store.setCache("models_tts", sortedTts.joinToString(","))
            store.setCache(cacheDay, today)
            if (kind == "chat") sortedChat else sortedTts
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun version(name: String): Double =
        Regex("gemini-(\\d+(?:\\.\\d+)?)").find(name)?.groupValues?.get(1)?.toDoubleOrNull() ?: 0.0
}
