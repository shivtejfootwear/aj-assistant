package com.shivtej.aj

import android.content.Intent
import android.net.Uri
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Dashboard के तीन नए हिस्से: 🔊 आवाज़, 🤖 AI दिमाग, ☁️ Cloud backup।
 * (पुराने हिस्से MainActivity में जैसे थे वैसे ही हैं)
 */
class SettingsCards(private val act: AppCompatActivity, private val box: LinearLayout) {

    private val store = Store(act)
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var showCloudSetup = false

    private val sample = "नमस्ते, मैं AJ हूँ। मैं आपकी कैसे सहायता कर सकता हूँ?"

    init {
        tts = TextToSpeech(act) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.setLanguage(Locale("hi", "IN"))
                act.runOnUiThread { render() }
            }
        }
    }

    fun destroy() {
        tts?.shutdown()
        tts = null
        PcmPlayer.stop()
    }

    fun render() {
        box.removeAllViews()
        box.addView(voiceCard())
        box.addView(brainCard())
        box.addView(cloudCard())
        box.addView(devicesCard())
    }

    // =============================== 📱 मेरे फ़ोन (multi-device) ===============================

    private var devices: List<DeviceSync.Device>? = null

    private fun devicesCard(): View {
        val card = card("📱 मेरे फ़ोन (admin)")
        card.addView(
            note(
                "अपने सभी फ़ोन एक ही account से login करें। फिर यहाँ हर फ़ोन का हाल दिखेगा। " +
                    "किसी फ़ोन को ढूँढने के लिए ‘बजाओ’ दबाएँ (वह फ़ोन ज़ोर से बजेगा), या ‘location’ से उसकी जगह मँगाएँ। " +
                    "हर काम सामने वाले फ़ोन पर भी दिखता है, कुछ छुपा नहीं।"
            )
        )

        if (!store.loggedIn) {
            card.addView(text("इसके लिए ऊपर Cloud backup में login कीजिए।", R.color.text_dim))
            return card
        }

        // इस फ़ोन का नाम
        val nameBox = EditText(act).apply {
            hint = "इस फ़ोन का नाम (जैसे: मेरा फ़ोन, दुकान वाला फ़ोन)"
            setText(store.deviceName)
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
        }
        card.addView(nameBox)
        card.addView(button("इस फ़ोन का नाम सेव करो") {
            store.deviceName = nameBox.text.toString().trim()
            toast("नाम सेव ✅")
            Thread { DeviceSync.pushHeartbeat(act) }.start()
        })

        card.addView(button("↻ सारे फ़ोन ताज़ा करो") { loadDevices(card) })

        val list = devices
        if (list == null) {
            card.addView(text("‘ताज़ा करो’ दबाकर अपने फ़ोन देखें।", R.color.text_dim))
        } else if (list.isEmpty()) {
            card.addView(text("अभी कोई फ़ोन नहीं मिला। हर फ़ोन पर AJ खोलकर इसी account से login कीजिए।", R.color.text_dim))
        } else {
            list.forEach { card.addView(deviceRow(it, card)) }
        }
        return card
    }

    private fun loadDevices(card: LinearLayout) {
        toast("फ़ोन ढूँढ रहा हूँ...")
        Thread {
            val d = DeviceSync.fetchDevices(act)
            act.runOnUiThread {
                devices = d ?: emptyList()
                render()
            }
        }.start()
    }

    private fun deviceRow(d: DeviceSync.Device, card: LinearLayout): View {
        val box = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_tile)
            setPadding(dp(14), dp(12), dp(14), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(10) }
        }
        val online = d.lastSeenMinAgo in 0..3
        val seen = when {
            d.lastSeenMinAgo < 0 -> ""
            d.lastSeenMinAgo == 0L -> " • अभी online"
            d.lastSeenMinAgo < 60 -> " • ${d.lastSeenMinAgo} मिनट पहले"
            else -> " • ${d.lastSeenMinAgo / 60} घंटे पहले"
        }
        box.addView(text((if (online) "🟢 " else "⚪ ") + d.name + (if (d.isThis) " (यही फ़ोन)" else "") + seen, R.color.cyan))

        val s = d.status
        val parts = ArrayList<String>()
        if (s.has("battery")) parts.add("🔋 ${s.optInt("battery")}%" + if (s.optBoolean("charging")) " ⚡" else "")
        if (s.has("free_gb")) parts.add("💾 ${s.optString("free_gb")}/${s.optString("total_gb")} GB")
        if (s.has("sms")) parts.add("✉ ${s.optInt("sms")}")
        if (s.has("missed")) parts.add("📞 ${s.optInt("missed")} missed")
        if (parts.isNotEmpty()) box.addView(text(parts.joinToString("   "), R.color.text))
        if (s.has("missed_names")) {
            val names = (0 until s.optJSONArray("missed_names")!!.length()).joinToString(", ") {
                s.optJSONArray("missed_names")!!.optString(it)
            }
            if (names.isNotBlank()) box.addView(text("Missed: $names", R.color.text_dim))
        }
        if (s.has("lat")) {
            val age = s.optLong("loc_age_min")
            box.addView(text("📍 location ${if (age > 0) "($age मिनट पुरानी)" else ""}", R.color.text_dim).apply {
                setOnClickListener {
                    openUrl("https://www.google.com/maps/search/?api=1&query=${s.optDouble("lat")},${s.optDouble("lon")}")
                }
            })
        }

        if (!d.isThis) {
            box.addView(row(
                button("🔔 बजाओ") { cmd(d, "ring") },
                button("📍 location") { cmd(d, "locate") },
                button("↻ status") { cmd(d, "status") }
            ))
            box.addView(row(
                button("📷 पीछे फ़ोटो") { cmd(d, "photo_back") },
                button("🤳 आगे फ़ोटो") { cmd(d, "photo_front") },
                button("🎙 रिकॉर्ड 15s") { cmd(d, "record", JSONObject().put("sec", 15)) }
            ))
            box.addView(button("📥 नया फ़ोटो/रिकॉर्डिंग खोलो") { openMedia(d) })
            box.addView(
                note("फ़ोटो/रिकॉर्डिंग माँगने पर उस फ़ोन पर notification, आवाज़ और हरा निशान दिखता है। थोड़ी देर बाद यह बटन दबाकर देखें।")
            )
        }
        return box
    }

    private fun cmd(d: DeviceSync.Device, type: String, args: JSONObject? = null) {
        toast("भेज रहा हूँ...")
        Thread {
            val err = DeviceSync.sendCommand(act, d.id, type, args)
            act.runOnUiThread {
                if (err != null) toast(err)
                else toast(
                    when (type) {
                        "ring" -> "${d.name} बजने लगेगा (जब वह फ़ोन online हो)"
                        "locate" -> "${d.name} से location माँगी, थोड़ी देर में ‘ताज़ा’ दबाएँ"
                        "photo_back", "photo_front" -> "${d.name} से फ़ोटो माँगी। कुछ सेकंड बाद ‘नया फ़ोटो खोलो’ दबाएँ"
                        "record" -> "${d.name} से रिकॉर्डिंग माँगी। ~20 सेकंड बाद ‘नया रिकॉर्डिंग खोलो’ दबाएँ"
                        else -> "${d.name} से ताज़ा जानकारी माँगी, थोड़ी देर में ‘ताज़ा’ दबाएँ"
                    }
                )
            }
        }.start()
    }

    private fun openMedia(d: DeviceSync.Device) {
        toast("media ढूँढ रहा हूँ...")
        Thread {
            val url = DeviceSync.latestMedia(act, d.id)
            act.runOnUiThread {
                if (url == null) toast("अभी कोई media नहीं मिला। थोड़ा और रुककर फिर दबाएँ।")
                else openUrl(url)
            }
        }.start()
    }

    // =============================== 🔊 आवाज़ ===============================

    private fun voiceCard(): View {
        val card = card("🔊 AJ की आवाज़")

        // Gemini की असली आवाज़
        val gSwitch = SwitchCompat(act).apply {
            text = "Gemini की इंसान जैसी आवाज़ (internet, जवाब 3-5 सेकंड धीमा)"
            setTextColor(color(R.color.text))
            textSize = 15f
            isChecked = store.geminiVoiceOn
            setOnCheckedChangeListener { _, checked ->
                if (checked && store.geminiKey.isBlank()) {
                    toast("पहले नीचे ‘AI दिमाग’ में Gemini key डालिए")
                    isChecked = false
                    return@setOnCheckedChangeListener
                }
                store.geminiVoiceOn = checked
                render()
            }
        }
        card.addView(gSwitch)

        if (store.geminiVoiceOn) {
            val label = GeminiAi.VOICES.firstOrNull { it.first == store.geminiVoiceName }?.second ?: store.geminiVoiceName
            card.addView(button("Gemini आवाज़ चुनो: $label") { pickGeminiVoice() })
            card.addView(note("Internet कम हो या free limit खत्म हो, तो AJ अपने-आप फ़ोन वाली आवाज़ में बोलेगा।"))
        }

        // फ़ोन की आवाज़
        val phoneLabel = if (store.voiceName.isBlank()) "अपने-आप (default)" else friendlyVoiceName(store.voiceName)
        card.addView(button("फ़ोन की आवाज़ चुनो: $phoneLabel") { pickPhoneVoice() })

        card.addView(slider("बोलने की रफ़्तार", store.rate, 0.6f, 1.4f) { store.rate = it })
        card.addView(slider("आवाज़ की ऊँचाई (pitch)", store.pitch, 0.6f, 1.4f) { store.pitch = it })

        card.addView(button("🔈 सुनकर देखो") { preview(null) })
        card.addView(
            note(
                "फ़ोन में और अच्छी हिंदी आवाज़ें लाने के लिए: फ़ोन Settings → Text-to-speech → Google का ⚙ → " +
                    "Install voice data → हिंदी (भारत) → सारी आवाज़ें download करें।"
            )
        )
        return card
    }

    private fun hindiVoices(): List<Voice> {
        val all = try {
            tts?.voices?.toList() ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return all
            .filter { v -> v.locale.language == "hi" || (v.locale.language == "en" && v.locale.country == "IN") }
            .filter { v -> v.features?.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED) != true }
            .sortedWith(compareBy<Voice>({ it.locale.language != "hi" }, { -it.quality }, { it.name }))
    }

    private fun friendlyVoiceName(name: String): String {
        val list = hindiVoices()
        val i = list.indexOfFirst { it.name == name }
        if (i < 0) return name
        val v = list[i]
        val lang = if (v.locale.language == "hi") "हिंदी" else "English (India)"
        val net = if (v.isNetworkConnectionRequired) ", internet" else ""
        return "$lang आवाज़ ${i + 1}$net"
    }

    private fun pickPhoneVoice() {
        if (!ttsReady) {
            toast("आवाज़ें लोड हो रही हैं, एक सेकंड बाद दबाइए")
            return
        }
        val voices = hindiVoices()
        if (voices.isEmpty()) {
            toast("फ़ोन में हिंदी आवाज़ नहीं मिली। नीचे लिखे तरीके से हिंदी आवाज़ download करें।")
            return
        }
        val names = arrayOf("अपने-आप (default)") + voices.map { friendlyVoiceName(it.name) }
        val current = voices.indexOfFirst { it.name == store.voiceName } + 1
        AlertDialog.Builder(act)
            .setTitle("फ़ोन की आवाज़ (चुनते ही सुनाएगा)")
            .setSingleChoiceItems(names, current) { _, which ->
                val chosen = if (which == 0) "" else voices[which - 1].name
                speakWithPhone(sample, chosen)
                store.voiceName = chosen
            }
            .setPositiveButton("ठीक है") { _, _ -> render() }
            .show()
    }

    private fun pickGeminiVoice() {
        val names = GeminiAi.VOICES.map { it.second }.toTypedArray()
        val current = GeminiAi.VOICES.indexOfFirst { it.first == store.geminiVoiceName }
        AlertDialog.Builder(act)
            .setTitle("Gemini आवाज़ (चुनते ही सुनाएगा)")
            .setSingleChoiceItems(names, current) { _, which ->
                val voice = GeminiAi.VOICES[which].first
                store.geminiVoiceName = voice
                preview(voice)
            }
            .setPositiveButton("ठीक है") { _, _ -> render() }
            .show()
    }

    /** मौजूदा settings से एक वाक्य सुनाना */
    private fun preview(geminiVoice: String?) {
        if (store.geminiVoiceOn || geminiVoice != null) {
            toast("Gemini से आवाज़ ला रहा हूँ...")
            Thread {
                val audio = try {
                    GeminiAi.synthesize(act, sample, geminiVoice)
                } catch (e: Exception) {
                    null
                }
                act.runOnUiThread {
                    if (audio != null) PcmPlayer.play(audio.first, audio.second) {}
                    else {
                        toast("Gemini आवाज़ नहीं आई (internet या limit), फ़ोन वाली सुनाता हूँ")
                        speakWithPhone(sample, store.voiceName)
                    }
                }
            }.start()
        } else {
            speakWithPhone(sample, store.voiceName)
        }
    }

    private fun speakWithPhone(text: String, voiceName: String) {
        val engine = tts ?: return
        try {
            engine.setLanguage(Locale("hi", "IN"))
            if (voiceName.isNotBlank()) engine.voices?.firstOrNull { it.name == voiceName }?.let { engine.voice = it }
            engine.setPitch(store.pitch)
            engine.setSpeechRate(store.rate)
            engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, "preview")
        } catch (_: Exception) {
        }
    }

    // =============================== 🤖 AI दिमाग ===============================

    private fun brainCard(): View {
        val card = card("🤖 AI दिमाग (internet से जवाब)")
        card.addView(
            note(
                "जो सवाल फ़ोन के commands में नहीं आते, AJ उन्हें internet से पता करके बताता है। " +
                    "पहले Groq (सबसे तेज़, internet search के साथ), उसकी limit लगे तो Gemini। दोनों free, कोई card नहीं।"
            )
        )

        // ---- Groq ----
        card.addView(text("⚡ Groq (तेज़, पहले यही)", R.color.cyan))
        card.addView(keyStatus(store.groqKey))
        val groqBox = keyInput(store.groqKey.isNotBlank(), "Groq API key (gsk_…) यहाँ paste करें")
        card.addView(groqBox)
        card.addView(row(
            button("सेव करो") {
                val k = groqBox.text.toString().trim()
                if (k.length < 20) toast("Key पूरी paste कीजिए") else {
                    store.groqKey = k
                    toast("Groq key सेव हो गई ✅")
                    render()
                }
            },
            button("Key बनाओ") { openUrl("https://console.groq.com/keys") }
        ))

        // ---- Gemini ----
        card.addView(text("✦ Gemini (backup + असली आवाज़)", R.color.violet))
        card.addView(keyStatus(store.geminiKey))
        val gemBox = keyInput(store.geminiKey.isNotBlank(), "Gemini API key यहाँ paste करें")
        card.addView(gemBox)
        card.addView(row(
            button("सेव करो") {
                val k = gemBox.text.toString().trim()
                if (k.length < 20) toast("Key पूरी paste कीजिए") else {
                    store.geminiKey = k
                    toast("Gemini key सेव हो गई ✅")
                    render()
                }
            },
            button("Key बनाओ") { openUrl("https://aistudio.google.com/apikey") }
        ))

        card.addView(button("🧪 AI दिमाग Test करो") { testBrain() })
        card.addView(
            note(
                "बिना billing के ये पैसे नहीं काट सकते; free limit भर जाए तो बस थोड़ी देर रुकते हैं। " +
                    "आपके SMS, कॉल और OTP AI को नहीं भेजे जाते, सिर्फ आपके पूछे सवाल। Free tier में कंपनियाँ सवाल अपने सुधार के लिए देख सकती हैं, इसलिए बहुत निजी बातें न पूछें।"
            )
        )
        return card
    }

    private fun keyStatus(key: String): TextView =
        if (key.isNotBlank()) text("✅ Key लगी है (…${key.takeLast(4)})", R.color.ok)
        else text("❌ Key नहीं लगी", R.color.text_dim)

    private fun keyInput(has: Boolean, hintText: String): EditText = EditText(act).apply {
        hint = if (has) "नई key डालनी हो तो यहाँ paste करें" else hintText
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.text_dim))
    }

    private fun testBrain() {
        if (store.geminiKey.isBlank() && store.groqKey.isBlank()) {
            toast("पहले Groq या Gemini key सेव कीजिए")
            return
        }
        toast("AI से पूछ रहा हूँ...")
        Thread {
            val started = System.currentTimeMillis()
            val answer = Brain.ask(act, "नमस्ते AJ, एक लाइन में बताओ तुम कौन हो और आज भारत की कोई एक बड़ी खबर क्या है?")
            val secs = (System.currentTimeMillis() - started) / 100 / 10.0
            act.runOnUiThread {
                AlertDialog.Builder(act)
                    .setTitle("AI का जवाब (${secs} सेकंड)")
                    .setMessage(answer)
                    .setPositiveButton("ठीक है", null)
                    .show()
            }
        }.start()
    }

    // =============================== ☁️ Cloud ===============================

    private fun cloudCard(): View {
        val card = card("☁️ Cloud backup (हमेशा सुरक्षित)")
        card.addView(
            note(
                "Login करने पर AJ की सारी सीखी बातें, बातचीत और settings आपके अपने Supabase cloud में रहेंगी। " +
                    "फ़ोन बदले या app हटे, तो login करते ही सब वापस। रोज़ की एक अलग backup copy भी बनती है।"
            )
        )

        val configured = CloudSync.isConfigured(store)
        if (!configured || showCloudSetup) {
            card.addView(text("Cloud setup (एक बार):", R.color.cyan))
            val url = input("Supabase Project URL (https://xxxx.supabase.co)", store.cloudUrl, false)
            val key = input("Supabase anon / publishable key", store.cloudAnonKey, true)
            card.addView(url)
            card.addView(key)
            card.addView(button("Setup सेव करो") {
                val u = url.text.toString().trim()
                if (!u.startsWith("https://")) {
                    toast("URL https:// से शुरू होना चाहिए")
                } else {
                    store.cloudUrl = u
                    store.cloudAnonKey = key.text.toString().trim()
                    showCloudSetup = false
                    toast("Setup सेव ✅")
                    render()
                }
            })
            if (!configured) return card
        }

        if (!store.loggedIn) {
            val email = input("Email", store.userEmail, false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val pass = input("Password (कम से कम 6 अक्षर)", "", true)
            card.addView(email)
            card.addView(pass)
            val status = text("", R.color.text_dim)
            card.addView(row(
                button("Login") {
                    status.text = "Login हो रहा है..."
                    Thread {
                        val err = CloudSync.signIn(act, email.text.toString(), pass.text.toString())
                        act.runOnUiThread {
                            if (err == null) {
                                toast("Login हो गया, data वापस आ गया ✅")
                                render()
                                (act as? MainActivity)?.refreshAll()
                            } else {
                                status.text = err
                            }
                        }
                    }.start()
                },
                button("नया account") {
                    status.text = "Account बन रहा है..."
                    Thread {
                        val err = CloudSync.signUp(act, email.text.toString(), pass.text.toString())
                        act.runOnUiThread {
                            if (err == null) {
                                toast("Account बन गया ✅")
                                render()
                            } else {
                                status.text = err
                            }
                        }
                    }.start()
                }
            ))
            card.addView(status)
        } else {
            val last = if (store.lastSync > 0)
                SimpleDateFormat("d MMM, h:mm a", Locale("hi", "IN")).format(Date(store.lastSync))
            else "अभी तक नहीं"
            card.addView(text("✅ Login: ${store.userEmail}", R.color.text))
            card.addView(text("आखिरी backup: $last", R.color.text_dim))
            if (CloudSync.lastMessage.isNotBlank() && !CloudSync.lastMessage.startsWith("✅")) {
                card.addView(text("⚠ ${CloudSync.lastMessage}", R.color.bad))
            }
            card.addView(row(
                button("अभी backup करो") {
                    toast("Backup हो रहा है...")
                    CloudSync.syncInBackground(act) { err ->
                        act.runOnUiThread {
                            toast(err ?: "Backup हो गया ✅")
                            render()
                            (act as? MainActivity)?.refreshAll()
                        }
                    }
                },
                button("Logout") {
                    AlertDialog.Builder(act)
                        .setMessage("Logout करें? Data cloud में सुरक्षित रहेगा, फ़ोन में भी रहेगा।")
                        .setPositiveButton("Logout") { _, _ ->
                            Thread {
                                CloudSync.signOut(act)
                                act.runOnUiThread { render() }
                            }.start()
                        }
                        .setNegativeButton("रहने दो", null)
                        .show()
                }
            ))
        }
        if (configured && !showCloudSetup) {
            card.addView(button("Cloud setup बदलो") {
                showCloudSetup = true
                render()
            })
        }
        return card
    }

    // =============================== छोटे helper ===============================

    private fun card(title: String): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_card)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) }
        addView(TextView(act).apply {
            text = title
            textSize = 18f
            setTextColor(color(R.color.cyan))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })
    }

    private fun text(s: String, colorRes: Int): TextView = TextView(act).apply {
        text = s
        textSize = 15f
        setTextColor(color(colorRes))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun note(s: String): TextView = TextView(act).apply {
        text = s
        textSize = 13f
        setTextColor(color(R.color.text_dim))
        setPadding(0, dp(6), 0, dp(2))
    }

    private fun button(label: String, onClick: () -> Unit): Button = MaterialButton(act).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v ->
            v.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                topMargin = dp(6)
                if (i > 0) marginStart = dp(8)
            }
            addView(v)
        }
    }

    private fun input(hintText: String, value: String, secret: Boolean): EditText = EditText(act).apply {
        hint = hintText
        setText(value)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.text_dim))
    }

    private fun slider(label: String, value: Float, min: Float, max: Float, onSet: (Float) -> Unit): View {
        val wrap = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL }
        val tv = text("$label: ${String.format(Locale.US, "%.2f", value)}", R.color.text)
        val bar = SeekBar(act).apply {
            this.max = 100
            progress = (((value - min) / (max - min)) * 100).toInt().coerceIn(0, 100)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    val v = min + (max - min) * p / 100f
                    tv.text = "$label: ${String.format(Locale.US, "%.2f", v)}"
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {
                    val v = min + (max - min) * (s?.progress ?: 50) / 100f
                    onSet(v)
                }
            })
        }
        wrap.addView(tv)
        wrap.addView(bar)
        return wrap
    }

    private fun openUrl(url: String) {
        try {
            act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Browser नहीं खुला")
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(act, res)
    private fun dp(v: Int): Int = (v * act.resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(act, msg, Toast.LENGTH_LONG).show()
}
