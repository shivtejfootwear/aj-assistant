package com.shivtej.aj

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * AJ का dashboard (futuristic HUD look), तीन tabs:
 * होम = robot orb (बोलो), लिखकर पूछना, quick buttons, आज का हाल, बातचीत
 * सिखाओ = सिखाई हुई बातें
 * Settings = wake word, आवाज़, AI दिमाग, cloud backup, permissions
 */
class MainActivity : AppCompatActivity(), AjBus.Listener {

    private lateinit var store: Store
    private lateinit var statusText: TextView
    private lateinit var statusChipText: TextView
    private lateinit var statusDot: View
    private lateinit var orb: AjOrbView
    private lateinit var wakeSwitch: SwitchCompat
    private lateinit var wakeInfo: TextView
    private lateinit var downloadButton: Button
    private lateinit var statsBox: LinearLayout
    private lateinit var teachBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var typeBox: EditText
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    private var ignoreSwitch = false
    private lateinit var settingsCards: SettingsCards

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        statusText = findViewById(R.id.statusText)
        statusChipText = findViewById(R.id.statusChipText)
        statusDot = findViewById(R.id.statusDot)
        orb = findViewById(R.id.micButton)
        wakeSwitch = findViewById(R.id.wakeSwitch)
        wakeInfo = findViewById(R.id.wakeInfo)
        downloadButton = findViewById(R.id.downloadButton)
        statsBox = findViewById(R.id.statsBox)
        teachBox = findViewById(R.id.teachBox)
        historyBox = findViewById(R.id.historyBox)
        permBox = findViewById(R.id.permBox)
        typeBox = findViewById(R.id.typeBox)

        // tabs
        pages = listOf<View>(findViewById(R.id.pageHome), findViewById(R.id.pageTeach), findViewById(R.id.pageSettings))
        tabs = listOf<TextView>(findViewById(R.id.navHome), findViewById(R.id.navTeach), findViewById(R.id.navSettings))
        tabs.forEachIndexed { i, tab -> tab.setOnClickListener { showPage(i) } }
        showPage(savedInstanceState?.getInt("page") ?: 0)

        orb.setOnClickListener {
            if (ensureMic()) AjService.send(this, AjService.ACTION_LISTEN)
        }
        findViewById<View>(R.id.sendButton).setOnClickListener { sendTyped() }
        typeBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped(); true
            } else false
        }
        findViewById<View>(R.id.refreshButton).setOnClickListener { renderStats() }
        findViewById<View>(R.id.addTeachButton).setOnClickListener { editTeach(null) }
        downloadButton.setOnClickListener { downloadModel() }

        wakeSwitch.setOnCheckedChangeListener { _, checked -> onWakeToggled(checked) }

        buildQuickActions()

        // आवाज़, AI दिमाग, Cloud backup वाले हिस्से
        settingsCards = SettingsCards(this, findViewById(R.id.extraBox))

        // पहली बार खुलने पर ज़रूरी permissions माँगो
        if (savedInstanceState == null && !PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)) {
            requestRuntime(basicPermissions())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pages.indexOfFirst { it.visibility == View.VISIBLE }.coerceAtLeast(0))
    }

    override fun onStart() {
        super.onStart()
        AjBus.add(this)
    }

    override fun onStop() {
        AjBus.remove(this)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        renderGreeting()
        renderWake()
        renderStats()
        renderTeaches()
        renderHistory()
        renderPermissions()
        settingsCards.render()
        // Login हो तो cloud से ताज़ा data लाकर मिलाओ, और इस फ़ोन को admin से जोड़े रखो
        if (Store(this).loggedIn) {
            CloudSync.syncInBackground(this) { runOnUiThread { refreshAll() } }
            AjService.send(this, AjService.ACTION_START)
        }
        // wake word चालू हो तो AJ को चलता रखो
        if (store.wakeEnabled && WakeWordEngine.isModelReady(this) &&
            PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)
        ) {
            AjService.send(this, AjService.ACTION_START)
        }
    }

    override fun onDestroy() {
        settingsCards.destroy()
        super.onDestroy()
    }

    /** cloud से data आने के बाद पूरा dashboard ताज़ा करना */
    fun refreshAll() {
        if (isFinishing || isDestroyed) return
        renderTeaches()
        renderHistory()
        settingsCards.render()
    }

    // ---------------- tabs ----------------

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            val active = i == index
            t.setBackgroundResource(if (active) R.drawable.bg_nav_active else 0)
            t.setTextColor(color(if (active) R.color.text else R.color.text_dim))
        }
    }

    // ---------------- AjBus ----------------

    override fun onStatus(status: String) {
        statusText.text = status
        val (mode, label, colorRes) = when {
            status.startsWith("🎙") -> Triple(AjOrbView.Mode.LISTENING, "LISTENING", R.color.mint)
            status.startsWith("🤔") -> Triple(AjOrbView.Mode.THINKING, "THINKING", R.color.violet)
            status.startsWith("🗣") -> Triple(AjOrbView.Mode.SPEAKING, "SPEAKING", R.color.cyan)
            status.startsWith("⚠") -> Triple(AjOrbView.Mode.IDLE, "ALERT", R.color.bad)
            status.startsWith("✅") -> Triple(AjOrbView.Mode.IDLE, "AJ READY", R.color.mint)
            else -> Triple(AjOrbView.Mode.IDLE, "ONLINE", R.color.mint)
        }
        orb.mode = mode
        statusChipText.text = label
        statusChipText.setTextColor(color(colorRes))
        statusDot.backgroundTintList = ColorStateList.valueOf(color(colorRes))
    }

    override fun onHistoryChanged() {
        renderHistory()
    }

    private fun renderGreeting() {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val g = when (h) {
            in 4..11 -> "सुप्रभात ☀️"
            in 12..16 -> "नमस्ते 👋"
            in 17..20 -> "शुभ संध्या 🌆"
            else -> "शुभ रात्रि 🌙"
        }
        findViewById<TextView>(R.id.greetText).text = g
    }

    // ---------------- बोलो / लिखो ----------------

    private fun ensureMic(): Boolean {
        if (PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)) return true
        toast("पहले माइक की permission दीजिए")
        requestRuntime(arrayOf(Manifest.permission.RECORD_AUDIO))
        return false
    }

    private fun sendTyped() {
        val text = typeBox.text.toString().trim()
        if (text.isEmpty()) return
        if (!ensureMic()) return
        typeBox.setText("")
        AjService.send(this, AjService.ACTION_TEXT, text)
    }

    /** एक tap वाले बटन */
    private fun buildQuickActions() {
        val box = findViewById<LinearLayout>(R.id.quickBox)
        val actions = listOf(
            "✉  आज के मैसेज" to "आज के मैसेज पढ़ो",
            "📞  आज की कॉल" to "आज किसकी कॉल आई",
            "⏱  5 मिनट टाइमर" to "5 मिनट का टाइमर लगाओ",
            "🔦  टॉर्च ON" to "टॉर्च चालू करो",
            "🔦  टॉर्च OFF" to "टॉर्च बंद करो",
            "💾  स्टोरेज" to "स्टोरेज कितना है",
            "🔋  बैटरी" to "बैटरी कितनी है",
            "⬇  आज के downloads" to "आज क्या डाउनलोड हुआ",
            "✨  क्या कर सकते हो" to "तुम क्या कर सकते हो",
        )
        actions.forEach { (label, command) ->
            box.addView(TextView(this).apply {
                text = label
                textSize = 13f
                setTextColor(color(R.color.text))
                setBackgroundResource(R.drawable.bg_chip)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
                setOnClickListener {
                    if (ensureMic()) AjService.send(this@MainActivity, AjService.ACTION_TEXT, command)
                }
            })
        }
    }

    // ---------------- Wake word ----------------

    private fun renderWake() {
        val ready = WakeWordEngine.isModelReady(this)
        ignoreSwitch = true
        wakeSwitch.isChecked = store.wakeEnabled && ready
        ignoreSwitch = false
        downloadButton.visibility = if (ready) View.GONE else View.VISIBLE
        if (!WakeWordEngine.downloading) {
            wakeInfo.text = if (ready)
                "चालू करने पर AJ हर समय ‘AJ’ शब्द का इंतज़ार करेगा। ‘AJ’ बोलें → बीप सुनें → अपनी बात बोलें। यह पहचान फ़ोन के अंदर ही होती है।"
            else
                "‘AJ’ बोलकर जगाने के लिए एक बार free model download करना होगा। तब तक होम पर robot को छूकर या notification वाले ‘बोलो’ से बात करें।"
        }
    }

    private fun onWakeToggled(checked: Boolean) {
        if (ignoreSwitch) return
        if (checked) {
            if (!WakeWordEngine.isModelReady(this)) {
                toast("पहले नीचे वाले बटन से model download करें")
                renderWake()
                return
            }
            if (!ensureMic()) {
                renderWake()
                return
            }
            store.wakeEnabled = true
            AjService.send(this, AjService.ACTION_START)
            toast("अब ‘AJ’ बोलकर बुलाइए")
        } else {
            store.wakeEnabled = false
            if (PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)) {
                AjService.send(this, AjService.ACTION_START)
            }
        }
    }

    private fun downloadModel() {
        downloadButton.isEnabled = false
        wakeInfo.text = "डाउनलोड शुरू हो रहा है..."
        WakeWordEngine.downloadModel(this,
            progress = { msg -> runOnUiThread { wakeInfo.text = msg } },
            done = { ok ->
                runOnUiThread {
                    downloadButton.isEnabled = true
                    renderWake()
                    if (ok) {
                        wakeInfo.text = "✅ Model तैयार है। अब ऊपर वाला switch चालू करें।"
                    }
                }
            })
    }

    // ---------------- आज का हाल (tiles) ----------------

    private data class Tile(val icon: String, val value: String, val label: String, val colorRes: Int)

    private fun renderStats() {
        statsBox.removeAllViews()
        statsBox.addView(row("लोड हो रहा है...", dim = true))
        Thread {
            val tiles = ArrayList<Tile>()
            try {
                val s = PhoneInfo.storage()
                tiles.add(Tile("💾", "${PhoneInfo.gb(s.freeGb)} GB", "खाली / कुल ${PhoneInfo.gb(s.totalGb)} GB", R.color.cyan))
            } catch (_: Exception) {
            }
            try {
                val b = PhoneInfo.battery(this)
                tiles.add(Tile("🔋", "${b.percent}%", if (b.charging) "बैटरी • चार्ज हो रही" else "बैटरी", R.color.mint))
            } catch (_: Exception) {
            }
            val sms = PhoneInfo.todaySms(this)
            tiles.add(Tile("✉", sms?.size?.toString() ?: "—", if (sms == null) "SMS • permission चाहिए" else "आज के SMS", R.color.violet))
            val calls = PhoneInfo.todayCalls(this)
            tiles.add(
                if (calls == null) Tile("📞", "—", "कॉल • permission चाहिए", R.color.bad)
                else Tile("📞", "${calls.missedCount}", "missed • ${calls.incoming} आईं • ${calls.outgoing} कीं", R.color.bad)
            )
            val dl = PhoneInfo.todayDownloads(this)
            tiles.add(Tile("⬇", dl?.size?.toString() ?: "—", if (dl == null) "downloads • permission चाहिए" else "आज के downloads", R.color.cyan))
            tiles.add(
                Tile(
                    "🧠", "${store.teaches().size}",
                    if (store.groqKey.isNotBlank() || store.geminiKey.isNotBlank()) "सीखी बातें • AI ऑन" else "सीखी बातें • AI key नहीं",
                    R.color.violet
                )
            )
            runOnUiThread {
                statsBox.removeAllViews()
                tiles.chunked(2).forEach { pair ->
                    val rowLayout = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply { topMargin = dp(10) }
                    }
                    pair.forEachIndexed { i, tile ->
                        rowLayout.addView(tileView(tile).apply {
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                                if (i == 1) marginStart = dp(10)
                            }
                        })
                    }
                    if (pair.size == 1) rowLayout.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
                    statsBox.addView(rowLayout)
                }
            }
        }.start()
    }

    private fun tileView(t: Tile): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setBackgroundResource(R.drawable.bg_tile)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        addView(TextView(this@MainActivity).apply {
            text = t.icon
            textSize = 18f
        })
        addView(TextView(this@MainActivity).apply {
            text = t.value
            textSize = 24f
            setTextColor(color(t.colorRes))
            typeface = android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)
            setPadding(0, dp(4), 0, 0)
        })
        addView(TextView(this@MainActivity).apply {
            text = t.label
            textSize = 12f
            setTextColor(color(R.color.text_dim))
        })
    }

    // ---------------- AJ को सिखाओ ----------------

    private fun renderTeaches() {
        teachBox.removeAllViews()
        val list = store.teaches()
        if (list.isEmpty()) teachBox.addView(row("अभी कुछ नहीं सिखाया", dim = true))
        list.forEach { teach ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundResource(R.drawable.bg_tile)
                setPadding(dp(14), dp(12), dp(8), dp(12))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(10) }
                setOnClickListener { editTeach(teach) }
            }
            val texts = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            texts.addView(TextView(this).apply {
                text = "🗣  “${teach.trigger}”"
                textSize = 15f
                setTextColor(color(R.color.cyan))
            })
            texts.addView(TextView(this).apply {
                text = "🤖  ${teach.reply}"
                textSize = 14f
                setTextColor(color(R.color.text))
                setPadding(0, dp(4), 0, 0)
            })
            val delete = TextView(this).apply {
                text = "🗑"
                textSize = 20f
                setPadding(dp(12), dp(4), dp(8), dp(4))
                setOnClickListener {
                    AlertDialog.Builder(this@MainActivity)
                        .setMessage("“${teach.trigger}” वाली बात हटा दें?")
                        .setPositiveButton("हटाओ") { _, _ ->
                            store.deleteTeach(teach.id)
                            renderTeaches()
                        }
                        .setNegativeButton("रहने दो", null)
                        .show()
                }
            }
            card.addView(texts)
            card.addView(delete)
            teachBox.addView(card)
        }
    }

    private fun editTeach(existing: Teach?) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val trigger = EditText(this).apply {
            hint = "जब मैं यह बोलूँ (जैसे: क्या कर रहे हो)"
            setText(existing?.trigger.orEmpty())
        }
        val reply = EditText(this).apply {
            hint = "तो AJ यह बोले"
            setText(existing?.reply.orEmpty())
        }
        box.addView(trigger)
        box.addView(reply)
        AlertDialog.Builder(this)
            .setTitle(if (existing == null) "AJ को नई बात सिखाओ" else "बात बदलो")
            .setView(box)
            .setPositiveButton("सेव करो") { _, _ ->
                val t = trigger.text.toString().trim()
                val r = reply.text.toString().trim()
                if (t.isEmpty() || r.isEmpty()) {
                    toast("दोनों खाने भरिए")
                } else {
                    store.upsertTeach(Teach(existing?.id ?: System.currentTimeMillis(), t, r))
                    renderTeaches()
                    toast("AJ ने सीख लिया ✅")
                }
            }
            .setNegativeButton("रद्द", null)
            .show()
    }

    // ---------------- बातचीत (chat bubbles) ----------------

    private fun renderHistory() {
        historyBox.removeAllViews()
        val list = store.history().takeLast(20).reversed()
        if (list.isEmpty()) {
            historyBox.addView(row("अभी कोई बातचीत नहीं। ऊपर robot को छूकर बात शुरू करें।", dim = true))
            return
        }
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        list.forEach { item ->
            val isAj = item.who == "AJ"
            val wrap = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = if (isAj) Gravity.START else Gravity.END
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { topMargin = dp(8) }
            }
            wrap.addView(TextView(this).apply {
                text = item.text
                textSize = 14f
                setTextColor(color(R.color.text))
                setBackgroundResource(if (isAj) R.drawable.bg_bubble_aj else R.drawable.bg_bubble_me)
                setPadding(dp(14), dp(10), dp(14), dp(10))
                maxWidth = (resources.displayMetrics.widthPixels * 0.78f).toInt()
            })
            wrap.addView(TextView(this).apply {
                text = (if (isAj) "AJ • " else "आप • ") + fmt.format(Date(item.time))
                textSize = 10f
                setTextColor(color(R.color.text_dim))
                setPadding(dp(6), dp(2), dp(6), 0)
            })
            historyBox.addView(wrap)
        }
    }

    // ---------------- Permissions ----------------

    private fun basicPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_SMS,
            Manifest.permission.SEND_SMS,
            Manifest.permission.READ_CALL_LOG,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.CALL_PHONE,
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun requestRuntime(perms: Array<String>) {
        ActivityCompat.requestPermissions(this, perms, 42)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissions()
        renderStats()
    }

    private fun renderPermissions() {
        permBox.removeAllViews()

        fun addRow(label: String, granted: Boolean, onFix: () -> Unit) {
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            val dot = View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                backgroundTintList = ColorStateList.valueOf(color(if (granted) R.color.ok else R.color.bad))
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            }
            val tv = TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(color(if (granted) R.color.text else R.color.text_dim))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            line.addView(dot)
            line.addView(tv)
            if (!granted) {
                line.addView(MaterialButton(this).apply {
                    text = "चालू करें"
                    textSize = 12f
                    setOnClickListener { onFix() }
                })
            }
            permBox.addView(line)
        }

        fun runtimeRow(label: String, vararg perms: String) {
            val granted = perms.all { PhoneInfo.has(this, it) }
            addRow(label, granted) { requestRuntime(arrayOf(*perms)) }
        }

        runtimeRow("माइक (आवाज़ सुनने के लिए)", Manifest.permission.RECORD_AUDIO)
        runtimeRow("SMS पढ़ना और भेजना", Manifest.permission.READ_SMS, Manifest.permission.SEND_SMS)
        runtimeRow("कॉल लिस्ट", Manifest.permission.READ_CALL_LOG)
        runtimeRow("Contacts (नाम से कॉल/मैसेज)", Manifest.permission.READ_CONTACTS)
        runtimeRow("कॉल लगाना", Manifest.permission.CALL_PHONE)
        runtimeRow("Location (फ़ोन कहाँ है)", Manifest.permission.ACCESS_FINE_LOCATION)
        runtimeRow("Camera (दूर से फ़ोटो)", Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) runtimeRow("Notification दिखाना", Manifest.permission.POST_NOTIFICATIONS)

        addRow("सारी फाइलें (आज के downloads)", PhoneInfo.canReadAllFiles(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSettings(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            } else {
                requestRuntime(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
            }
        }

        addRow("दूसरे apps के ऊपर (पीछे से app/कॉल खोलने के लिए)", Settings.canDrawOverlays(this)) {
            openSettings(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }

        val pm = getSystemService(PowerManager::class.java)
        addRow("बैटरी बचत से छूट (AJ बंद न हो)", pm.isIgnoringBatteryOptimizations(packageName)) {
            openSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }

        permBox.addView(
            row(
                "अगर कोई permission ‘restricted’ बोलकर न मिले: फ़ोन Settings → Apps → AJ → ऊपर ⋮ → ‘Allow restricted settings’ दबाएँ, फिर यहाँ वापस आएँ।",
                dim = true
            )
        )
    }

    private fun openSettings(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i)
                return
            } catch (_: Exception) {
            }
        }
        toast("Settings नहीं खुली, फ़ोन की Settings में AJ ढूँढें")
    }

    // ---------------- छोटे helper ----------------

    private fun row(text: String, dim: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (dim) 13f else 15f
        setTextColor(color(if (dim) R.color.text_dim else R.color.text))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
