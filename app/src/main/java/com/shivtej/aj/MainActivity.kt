package com.shivtej.aj

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import org.json.JSONObject

/**
 * AJ — आपके अपने फ़ोनों का खुला admin।
 * इस फ़ोन का हाल, आपके सभी फ़ोन (locate/ring/photo/record/media), permissions, cloud login।
 * कोई voice/assistant नहीं। हर दूर का काम सामने वाले फ़ोन पर दिखता है (notification/आवाज़/हरा निशान)।
 */
class MainActivity : AppCompatActivity(), AjBus.Listener {

    private lateinit var store: Store
    private lateinit var statusChipText: TextView
    private lateinit var statusDot: View
    private lateinit var permBox: LinearLayout
    private lateinit var cloudBox: LinearLayout
    private lateinit var devicesBox: LinearLayout
    private lateinit var lockBox: LinearLayout
    private lateinit var pages: List<View>
    private lateinit var tabs: List<TextView>

    private var devices: List<DeviceSync.Device>? = null
    private var settingsUnlocked = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        store = Store(this)

        statusChipText = findViewById(R.id.statusChipText)
        statusDot = findViewById(R.id.statusDot)
        permBox = findViewById(R.id.permBox)
        cloudBox = findViewById(R.id.cloudBox)
        devicesBox = findViewById(R.id.devicesBox)
        lockBox = findViewById(R.id.lockBox)

        pages = listOf<View>(findViewById(R.id.pageDevices), findViewById(R.id.pageSettings))
        tabs = listOf<TextView>(findViewById(R.id.navDevices), findViewById(R.id.navSettings))
        tabs.forEachIndexed { i, tab ->
            tab.setOnClickListener { if (i == 1) openSettingsTab() else showPage(i) }
        }
        settingsUnlocked = savedInstanceState?.getBoolean("unlocked") ?: false
        val startPage = savedInstanceState?.getInt("page") ?: 0
        showPage(if (startPage == 1 && !settingsUnlocked) 0 else startPage)

        findViewById<View>(R.id.refreshButton).setOnClickListener { loadDevices() }

        if (savedInstanceState == null && !PhoneInfo.has(this, Manifest.permission.ACCESS_FINE_LOCATION)) {
            requestRuntime(basicPermissions())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("page", pages.indexOfFirst { it.visibility == View.VISIBLE }.coerceAtLeast(0))
        outState.putBoolean("unlocked", settingsUnlocked)
    }

    /** Settings tab सिर्फ सही PIN पर खुलेगा (app बंद-खोलने पर फिर से माँगेगा)। */
    private fun openSettingsTab() {
        if (settingsUnlocked) { showPage(1); return }
        val pin = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "PIN"
        }
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(pin)
        }
        AlertDialog.Builder(this)
            .setTitle("Settings खोलने के लिए PIN डालें")
            .setView(wrap)
            .setPositiveButton("खोलो") { _, _ ->
                if (pin.text.toString().trim() == store.settingsPin) {
                    settingsUnlocked = true
                    showPage(1)
                } else toast("गलत PIN")
            }
            .setNegativeButton("रहने दो", null)
            .show()
    }

    private fun renderLock() {
        lockBox.removeAllViews()
        lockBox.addView(dim("Settings इसी PIN से खुलती है। app बंद करके दोबारा खोलने पर PIN फिर माँगा जाएगा।"))
        lockBox.addView(btnWide("Settings PIN बदलो") { changePinDialog() })
    }

    private fun changePinDialog() {
        fun pinField(hintText: String) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = hintText
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
        }
        val p1 = pinField("नया PIN (कम से कम 4 अंक)")
        val p2 = pinField("दोबारा वही PIN")
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(p1); addView(p2)
        }
        AlertDialog.Builder(this)
            .setTitle("नया Settings PIN")
            .setView(wrap)
            .setPositiveButton("सेव करो") { _, _ ->
                val a = p1.text.toString().trim()
                val b = p2.text.toString().trim()
                when {
                    a.length < 4 -> toast("PIN कम से कम 4 अंक का रखें")
                    a != b -> toast("दोनों PIN एक जैसे नहीं हैं")
                    else -> { store.settingsPin = a; toast("PIN बदल गया ✅") }
                }
            }
            .setNegativeButton("रहने दो", null)
            .show()
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
        refresh()
    }

    private fun refresh() {
        renderPermissions()
        renderCloud()
        renderLock()
        onStatus(AjBus.status)
        if (store.loggedIn) {
            AjService.start(this)
            Thread { DeviceSync.pushHeartbeat(this) }.start()
            loadDevices()
        } else {
            devicesBox.removeAllViews()
            devicesBox.addView(dim("पहले Settings में Cloud login कीजिए, फिर यहाँ आपके फ़ोन दिखेंगे।"))
        }
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
        tabs.forEachIndexed { i, t ->
            val active = i == index
            t.setBackgroundResource(if (active) R.drawable.bg_nav_active else 0)
            t.setTextColor(color(if (active) R.color.text else R.color.text_dim))
        }
    }

    override fun onStatus(status: String) {
        val (label, colorRes) = when {
            status.startsWith("📸") || status.startsWith("🎙") -> "ACTIVE" to R.color.violet
            status.startsWith("🔔") -> "RINGING" to R.color.mint
            status.startsWith("⚠") -> "ALERT" to R.color.bad
            else -> (if (store.loggedIn) "LINKED" else "OFFLINE") to R.color.mint
        }
        statusChipText.text = label
        statusChipText.setTextColor(color(colorRes))
        statusDot.backgroundTintList = ColorStateList.valueOf(color(colorRes))
    }

    // ---------------- मेरे फ़ोन ----------------

    private fun loadDevices() {
        if (!store.loggedIn) return
        Thread {
            val d = DeviceSync.fetchDevices(this)
            runOnUiThread {
                devices = d
                renderDevices()
            }
        }.start()
    }

    private fun renderDevices() {
        devicesBox.removeAllViews()
        val list = devices
        when {
            list == null -> devicesBox.addView(dim("लोड हो रहा है..."))
            list.isEmpty() -> devicesBox.addView(dim("कोई फ़ोन नहीं मिला। हर फ़ोन पर AJ खोलकर इसी account से login कीजिए।"))
            else -> list.forEach { devicesBox.addView(deviceCard(it)) }
        }
    }

    private fun deviceCard(d: DeviceSync.Device): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(16), dp(14), dp(16), dp(14))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
        }
        val online = d.lastSeenMinAgo in 0..3
        val seen = when {
            d.lastSeenMinAgo < 0 -> ""
            d.lastSeenMinAgo == 0L -> " • अभी online"
            d.lastSeenMinAgo < 60 -> " • ${d.lastSeenMinAgo} मिनट पहले"
            else -> " • ${d.lastSeenMinAgo / 60} घंटे पहले"
        }
        box.addView(TextView(this).apply {
            text = (if (online) "🟢 " else "⚪ ") + d.name + (if (d.isThis) "  (यही फ़ोन)" else "") + seen
            textSize = 17f
            setTextColor(color(R.color.cyan))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        })

        val s = d.status
        val parts = ArrayList<String>()
        if (s.has("battery")) parts.add("🔋 ${s.optInt("battery")}%" + if (s.optBoolean("charging")) " ⚡" else "")
        if (s.has("free_gb")) parts.add("💾 ${s.optString("free_gb")}/${s.optString("total_gb")} GB")
        if (s.has("sms")) parts.add("✉ ${s.optInt("sms")}")
        if (s.has("missed")) parts.add("📞 ${s.optInt("missed")} missed")
        if (parts.isNotEmpty()) box.addView(line(parts.joinToString("    ")))
        if (s.has("lat")) {
            val age = s.optLong("loc_age_min")
            box.addView(TextView(this).apply {
                text = "📍 location देखें ${if (age > 0) "($age मिनट पुरानी)" else ""}"
                textSize = 14f
                setTextColor(color(R.color.mint))
                setPadding(0, dp(6), 0, dp(2))
                setOnClickListener {
                    openUrl("https://www.google.com/maps/search/?api=1&query=${s.optDouble("lat")},${s.optDouble("lon")}")
                }
            })
        }

        if (!d.isThis) {
            box.addView(row(
                btn("🔔 बजाओ") { cmd(d, "ring") },
                btn("📍 location") { cmd(d, "locate") },
                btn("↻ status") { cmd(d, "status") }
            ))
            box.addView(row(
                btn("📷 पीछे") { cmd(d, "photo_back") },
                btn("🤳 आगे") { cmd(d, "photo_front") },
                btn("🎙 15s") { cmd(d, "record", JSONObject().put("sec", 15)) }
            ))
            box.addView(btnWide("📥 नया फ़ोटो/रिकॉर्डिंग खोलो") { openMedia(d) })
            box.addView(dim("फ़ोटो/रिकॉर्डिंग माँगने पर उस फ़ोन पर notification, आवाज़ और हरा निशान दिखता है। कुछ सेकंड बाद यह बटन दबाएँ।"))
        } else {
            box.addView(dim("यह फ़ोन दूसरे फ़ोन के admin से जुड़ा है। दूसरे फ़ोन पर इसे यहीं से चलाया जा सकता है।"))
        }
        return box
    }

    private fun cmd(d: DeviceSync.Device, type: String, args: JSONObject? = null) {
        toast("भेज रहा हूँ...")
        Thread {
            val err = DeviceSync.sendCommand(this, d.id, type, args)
            runOnUiThread {
                toast(
                    err ?: when (type) {
                        "ring" -> "${d.name} बजने लगेगा (जब वह online हो)"
                        "locate" -> "${d.name} से location माँगी, थोड़ी देर में ↻ दबाएँ"
                        "photo_back", "photo_front" -> "${d.name} से फ़ोटो माँगी। कुछ सेकंड बाद ‘नया फ़ोटो खोलो’"
                        "record" -> "${d.name} से रिकॉर्डिंग माँगी। ~20 सेकंड बाद ‘नया रिकॉर्डिंग खोलो’"
                        else -> "${d.name} से ताज़ा जानकारी माँगी, थोड़ी देर में ↻ दबाएँ"
                    }
                )
            }
        }.start()
    }

    private fun openMedia(d: DeviceSync.Device) {
        toast("media ढूँढ रहा हूँ...")
        Thread {
            val url = DeviceSync.latestMedia(this, d.id)
            runOnUiThread {
                if (url == null) toast("अभी कोई media नहीं मिला। थोड़ा और रुककर फिर दबाएँ।")
                else openUrl(url)
            }
        }.start()
    }

    // ---------------- Cloud login ----------------

    private var showCloudSetup = false

    private fun renderCloud() {
        cloudBox.removeAllViews()
        cloudBox.addView(dim("अपने सभी फ़ोन एक ही account से login करें। फिर ‘मेरे फ़ोन’ में सब दिखेंगे और चलाए जा सकेंगे।"))

        val configured = CloudSync.isConfigured(store)
        if (!configured || showCloudSetup) {
            cloudBox.addView(head("Cloud setup (एक बार)"))
            val url = input("Supabase Project URL (https://xxxx.supabase.co)", store.cloudUrl, false)
            val key = input("Supabase anon public key", store.cloudAnonKey, true)
            cloudBox.addView(url)
            cloudBox.addView(key)
            cloudBox.addView(btnWide("Setup सेव करो") {
                val u = url.text.toString().trim()
                if (!u.startsWith("https://")) toast("URL https:// से शुरू होना चाहिए") else {
                    store.cloudUrl = u
                    store.cloudAnonKey = key.text.toString().trim()
                    showCloudSetup = false
                    toast("Setup सेव ✅")
                    renderCloud()
                }
            })
            if (!configured) return
        }

        if (!store.loggedIn) {
            val email = input("Email", store.userEmail, false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val pass = input("Password (कम से कम 6 अक्षर)", "", true)
            cloudBox.addView(email)
            cloudBox.addView(pass)
            val status = dim("")
            cloudBox.addView(row(
                btn("Login") {
                    status.text = "Login हो रहा है..."
                    Thread {
                        val err = CloudSync.signIn(this, email.text.toString(), pass.text.toString())
                        runOnUiThread {
                            if (err == null) {
                                toast("Login हो गया ✅"); renderCloud(); refresh()
                            } else status.text = err
                        }
                    }.start()
                },
                btn("नया account") {
                    status.text = "Account बन रहा है..."
                    Thread {
                        val err = CloudSync.signUp(this, email.text.toString(), pass.text.toString())
                        runOnUiThread {
                            if (err == null) {
                                toast("Account बन गया ✅"); renderCloud(); refresh()
                            } else status.text = err
                        }
                    }.start()
                }
            ))
            cloudBox.addView(status)
        } else {
            cloudBox.addView(head("✅ Login: ${store.userEmail}"))
            val nameBox = input("इस फ़ोन का नाम (जैसे: मेरा फ़ोन, दुकान वाला फ़ोन)", store.deviceName, false)
            cloudBox.addView(nameBox)
            cloudBox.addView(row(
                btn("नाम सेव करो") {
                    store.deviceName = nameBox.text.toString().trim()
                    toast("नाम सेव ✅")
                    Thread { DeviceSync.pushHeartbeat(this) }.start()
                },
                btn("Logout") {
                    AlertDialog.Builder(this)
                        .setMessage("Logout करें?")
                        .setPositiveButton("Logout") { _, _ ->
                            CloudSync.signOut(this)
                            AjService.send(this, AjService.ACTION_STOP)
                            renderCloud(); refresh()
                        }
                        .setNegativeButton("रहने दो", null)
                        .show()
                }
            ))
            if (configured && !showCloudSetup) {
                cloudBox.addView(btnWide("Cloud setup बदलो") { showCloudSetup = true; renderCloud() })
            }
        }
    }

    // ---------------- Permissions ----------------

    private fun basicPermissions(): Array<String> {
        val list = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.READ_CALL_LOG,
        )
        if (Build.VERSION.SDK_INT >= 33) list.add(Manifest.permission.POST_NOTIFICATIONS)
        return list.toTypedArray()
    }

    private fun requestRuntime(perms: Array<String>) = ActivityCompat.requestPermissions(this, perms, 42)

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        renderPermissions()
    }

    private fun renderPermissions() {
        permBox.removeAllViews()

        fun addRow(label: String, granted: Boolean, onFix: () -> Unit) {
            val l = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(5), 0, dp(5))
            }
            l.addView(View(this).apply {
                setBackgroundResource(R.drawable.bg_dot)
                backgroundTintList = ColorStateList.valueOf(color(if (granted) R.color.ok else R.color.bad))
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) }
            })
            l.addView(TextView(this).apply {
                text = label
                textSize = 14f
                setTextColor(color(if (granted) R.color.text else R.color.text_dim))
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (!granted) l.addView(MaterialButton(this).apply {
                text = "चालू करें"; textSize = 12f; setOnClickListener { onFix() }
            })
            permBox.addView(l)
        }

        fun rt(label: String, vararg perms: String) {
            addRow(label, perms.all { PhoneInfo.has(this, it) }) { requestRuntime(arrayOf(*perms)) }
        }

        rt("Location (फ़ोन कहाँ है)", Manifest.permission.ACCESS_FINE_LOCATION)
        rt("Camera (दूर से फ़ोटो)", Manifest.permission.CAMERA)
        rt("Microphone (दूर से रिकॉर्डिंग)", Manifest.permission.RECORD_AUDIO)
        rt("कॉल लिस्ट (आज की कॉल count)", Manifest.permission.READ_CALL_LOG)
        if (Build.VERSION.SDK_INT >= 33) rt("Notification दिखाना", Manifest.permission.POST_NOTIFICATIONS)

        addRow("सारी फाइलें (आज के downloads)", PhoneInfo.canReadAllFiles(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSettings(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            } else requestRuntime(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }

        val pm = getSystemService(PowerManager::class.java)
        addRow("बैटरी बचत से छूट (जुड़ा रहे)", pm.isIgnoringBatteryOptimizations(packageName)) {
            openSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }

        permBox.addView(dim("कोई permission ‘restricted’ बोलकर न मिले: फ़ोन Settings → Apps → AJ → ⋮ → ‘Allow restricted settings’।"))
    }

    private fun openSettings(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
        toast("Settings नहीं खुली, फ़ोन की Settings में AJ ढूँढें")
    }

    // ---------------- helpers ----------------

    private fun head(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(color(R.color.cyan)); setPadding(0, dp(8), 0, dp(2))
    }

    private fun line(s: String) = TextView(this).apply {
        text = s; textSize = 15f; setTextColor(color(R.color.text)); setPadding(0, dp(6), 0, dp(2))
    }

    private fun dim(s: String) = TextView(this).apply {
        text = s; textSize = 13f; setTextColor(color(R.color.text_dim)); setPadding(0, dp(6), 0, dp(2))
    }

    private fun input(hintText: String, value: String, secret: Boolean) = EditText(this).apply {
        hint = hintText
        setText(value)
        inputType = if (secret) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        else InputType.TYPE_CLASS_TEXT
        setTextColor(color(R.color.text))
        setHintTextColor(color(R.color.text_dim))
    }

    private fun btn(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; textSize = 13f; setOnClickListener { onClick() }
    }

    private fun btnWide(label: String, onClick: () -> Unit) = MaterialButton(this).apply {
        text = label; isAllCaps = false; setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(6) }
    }

    private fun row(vararg views: View): LinearLayout = LinearLayout(this).apply {
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

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            toast("Browser नहीं खुला")
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
