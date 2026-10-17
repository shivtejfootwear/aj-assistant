package com.shivtej.aj

import android.Manifest
import android.content.Intent
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** AJ का dashboard: बोलो बटन, wake word, आज का हाल, सिखाई बातें, बातचीत, permissions */
class MainActivity : AppCompatActivity(), AjBus.Listener {

    private lateinit var store: Store
    private lateinit var statusText: TextView
    private lateinit var wakeSwitch: SwitchCompat
    private lateinit var wakeInfo: TextView
    private lateinit var downloadButton: Button
    private lateinit var statsBox: LinearLayout
    private lateinit var teachBox: LinearLayout
    private lateinit var historyBox: LinearLayout
    private lateinit var permBox: LinearLayout
    private lateinit var typeBox: EditText

    private var ignoreSwitch = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        statusText = findViewById(R.id.statusText)
        wakeSwitch = findViewById(R.id.wakeSwitch)
        wakeInfo = findViewById(R.id.wakeInfo)
        downloadButton = findViewById(R.id.downloadButton)
        statsBox = findViewById(R.id.statsBox)
        teachBox = findViewById(R.id.teachBox)
        historyBox = findViewById(R.id.historyBox)
        permBox = findViewById(R.id.permBox)
        typeBox = findViewById(R.id.typeBox)

        findViewById<View>(R.id.micButton).setOnClickListener {
            if (ensureMic()) AjService.send(this, AjService.ACTION_LISTEN)
        }
        findViewById<Button>(R.id.sendButton).setOnClickListener { sendTyped() }
        typeBox.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) {
                sendTyped(); true
            } else false
        }
        findViewById<Button>(R.id.refreshButton).setOnClickListener { renderStats() }
        findViewById<Button>(R.id.addTeachButton).setOnClickListener { editTeach(null) }
        downloadButton.setOnClickListener { downloadModel() }

        wakeSwitch.setOnCheckedChangeListener { _, checked -> onWakeToggled(checked) }

        // पहली बार खुलने पर ज़रूरी permissions माँगो
        if (savedInstanceState == null && !PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)) {
            requestRuntime(basicPermissions())
        }
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
        renderWake()
        renderStats()
        renderTeaches()
        renderHistory()
        renderPermissions()
        // wake word चालू हो तो AJ को चलता रखो
        if (store.wakeEnabled && WakeWordEngine.isModelReady(this) &&
            PhoneInfo.has(this, Manifest.permission.RECORD_AUDIO)
        ) {
            AjService.send(this, AjService.ACTION_START)
        }
    }

    // ---------------- AjBus ----------------

    override fun onStatus(status: String) {
        statusText.text = status
    }

    override fun onHistoryChanged() {
        renderHistory()
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
                "‘AJ’ बोलकर जगाने के लिए एक बार free model download करना होगा। तब तक ऊपर वाला बोलो बटन या notification वाला ‘बोलो’ इस्तेमाल करें।"
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

    // ---------------- आज का हाल ----------------

    private fun renderStats() {
        statsBox.removeAllViews()
        statsBox.addView(row("लोड हो रहा है...", dim = true))
        Thread {
            val lines = ArrayList<String>()
            try {
                val s = PhoneInfo.storage()
                lines.add("💾 स्टोरेज: ${PhoneInfo.gb(s.freeGb)} GB खाली / कुल ${PhoneInfo.gb(s.totalGb)} GB")
            } catch (_: Exception) {
            }
            try {
                val b = PhoneInfo.battery(this)
                lines.add("🔋 बैटरी: ${b.percent}%" + if (b.charging) " (चार्ज हो रही है)" else "")
            } catch (_: Exception) {
            }
            val sms = PhoneInfo.todaySms(this)
            lines.add(if (sms == null) "✉️ आज के SMS: permission चाहिए" else "✉️ आज के SMS: ${sms.size}")
            val calls = PhoneInfo.todayCalls(this)
            lines.add(
                if (calls == null) "📞 आज की कॉल: permission चाहिए"
                else "📞 आज: ${calls.missedCount} missed, ${calls.incoming} आईं, ${calls.outgoing} कीं"
            )
            val dl = PhoneInfo.todayDownloads(this)
            lines.add(if (dl == null) "⬇️ आज के downloads: permission चाहिए" else "⬇️ आज के downloads: ${dl.size}")
            runOnUiThread {
                statsBox.removeAllViews()
                lines.forEach { statsBox.addView(row(it)) }
            }
        }.start()
    }

    // ---------------- AJ को सिखाओ ----------------

    private fun renderTeaches() {
        teachBox.removeAllViews()
        val list = store.teaches()
        if (list.isEmpty()) teachBox.addView(row("अभी कुछ नहीं सिखाया", dim = true))
        list.forEach { teach ->
            val line = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            val label = TextView(this).apply {
                text = "“${teach.trigger}”  →  ${teach.reply}"
                setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text))
                textSize = 15f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                setOnClickListener { editTeach(teach) }
            }
            val delete = TextView(this).apply {
                text = "🗑"
                textSize = 20f
                setPadding(dp(12), dp(4), dp(4), dp(4))
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
            line.addView(label)
            line.addView(delete)
            teachBox.addView(line)
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

    // ---------------- बातचीत ----------------

    private fun renderHistory() {
        historyBox.removeAllViews()
        val list = store.history().takeLast(20).reversed()
        if (list.isEmpty()) {
            historyBox.addView(row("अभी कोई बातचीत नहीं। ऊपर बोलो दबाकर शुरू करें।", dim = true))
            return
        }
        val fmt = SimpleDateFormat("h:mm a", Locale.US)
        list.forEach { item ->
            val tv = TextView(this).apply {
                text = "${fmt.format(Date(item.time))}  ${item.who}: ${item.text}"
                textSize = 14f
                setTextColor(
                    ContextCompat.getColor(
                        this@MainActivity,
                        if (item.who == "AJ") R.color.cyan else R.color.text
                    )
                )
                setPadding(0, dp(4), 0, dp(4))
            }
            historyBox.addView(tv)
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
            val tv = TextView(this).apply {
                text = (if (granted) "✅ " else "❌ ") + label
                textSize = 15f
                setTextColor(ContextCompat.getColor(this@MainActivity, if (granted) R.color.text else R.color.text_dim))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            line.addView(tv)
            if (!granted) {
                val b = Button(this).apply {
                    text = "चालू करें"
                    setOnClickListener { onFix() }
                }
                line.addView(b)
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
        setTextColor(ContextCompat.getColor(this@MainActivity, if (dim) R.color.text_dim else R.color.text))
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
