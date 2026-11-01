package com.shivtej.aj

import android.Manifest
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.BitmapFactory
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.text.format.DateFormat
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Date
import java.util.Locale
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
        findViewById<View>(R.id.settingsDot).setOnClickListener { openSettingsTab() }
        findViewById<View>(R.id.settingsBack).setOnClickListener { showPage(0) }
        settingsUnlocked = savedInstanceState?.getBoolean("unlocked") ?: false
        val startPage = savedInstanceState?.getInt("page") ?: 0
        showPage(if (startPage == 1 && settingsUnlocked) 1 else 0)

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

    override fun onBackPressed() {
        if (pages.getOrNull(1)?.visibility == View.VISIBLE) showPage(0) else super.onBackPressed()
    }

    /** Settings only opens on the correct PIN (asked again after closing and reopening the app). */
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
            .setTitle("Enter PIN to open Settings")
            .setView(wrap)
            .setPositiveButton("Open") { _, _ ->
                if (pin.text.toString().trim() == store.settingsPin) {
                    settingsUnlocked = true
                    showPage(1)
                } else toast("Wrong PIN")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun renderLock() {
        lockBox.removeAllViews()
        lockBox.addView(dim("Settings opens with this PIN. Reopening the app asks for the PIN again."))
        lockBox.addView(btnWide("Change Settings PIN") { changePinDialog() })
    }

    private fun changePinDialog() {
        fun pinField(hintText: String) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = hintText
            setTextColor(color(R.color.text))
            setHintTextColor(color(R.color.text_dim))
        }
        val p1 = pinField("New PIN (at least 4 digits)")
        val p2 = pinField("Re-enter new PIN")
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(p1); addView(p2)
        }
        AlertDialog.Builder(this)
            .setTitle("New Settings PIN")
            .setView(wrap)
            .setPositiveButton("Save") { _, _ ->
                val a = p1.text.toString().trim()
                val b = p2.text.toString().trim()
                when {
                    a.length < 4 -> toast("PIN must be at least 4 digits")
                    a != b -> toast("The two PINs don't match")
                    else -> { store.settingsPin = a; toast("PIN changed ✅") }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onStart() {
        super.onStart()
        AjBus.add(this)
    }

    override fun onStop() {
        AjBus.remove(this)
        stopPlayer()
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
            devicesBox.addView(dim("Cloud login in Settings first, then your phones show here."))
        }
    }

    private fun showPage(index: Int) {
        pages.forEachIndexed { i, p -> p.visibility = if (i == index) View.VISIBLE else View.GONE }
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
            list == null -> devicesBox.addView(dim("Loading..."))
            list.isEmpty() -> devicesBox.addView(dim("No phones found. Open the app on each phone and login with this same account."))
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
            d.lastSeenMinAgo == 0L -> " • online now"
            d.lastSeenMinAgo < 60 -> " • ${d.lastSeenMinAgo} min ago"
            else -> " • ${d.lastSeenMinAgo / 60} hr ago"
        }
        box.addView(TextView(this).apply {
            text = (if (online) "🟢 " else "⚪ ") + d.name + (if (d.isThis) "  (this phone)" else "") + seen
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
                text = "📍 View location ${if (age > 0) "($age min old)" else ""}"
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
                btn("🔔 Ring") { cmd(d, "ring") },
                btn("📍 Locate") { cmd(d, "locate") },
                btn("🔄 Status") { cmd(d, "status") }
            ))
            box.addView(row(
                btn("📷 Back") { cmd(d, "photo_back") },
                btn("🤳 Front") { cmd(d, "photo_front") },
                btn("🎙 Audio") { pickDuration(d, "Audio") { sec -> cmd(d, "record", JSONObject().put("sec", sec)) } },
                btn("🎥 Video") { pickVideoDuration(d) { sec -> cmd(d, "video", JSONObject().put("sec", sec)) } },
                btn("👁 Live") { openLive(d) }
            ))
            box.addView(row(
                btn("💬 Messages") { openMessages(d) },
                btn("📞 Calls") { openCalls(d) },
                btn("📂 Files") { openFiles(d, "") }
            ))
            box.addView(dim("Camera/mic tasks show a notification, sound and a green dot on that phone. After a few seconds open the gallery below."))
        } else {
            box.addView(dim("This phone is linked to your admin. Use another phone or the web portal to control it."))
        }
        box.addView(btnWide("🖼 View photos / recordings") { openMediaGallery(d) })
        return box
    }

    private fun cmd(d: DeviceSync.Device, type: String, args: JSONObject? = null) {
        toast("Sending...")
        Thread {
            val err = DeviceSync.sendCommand(this, d.id, type, args)
            runOnUiThread {
                toast(
                    err ?: when (type) {
                        "ring" -> "${d.name} will ring (when it is online)"
                        "locate" -> "Requested location from ${d.name}, tap ↻ shortly"
                        "photo_back", "photo_front" -> "Requested a photo from ${d.name}. After a few seconds open the gallery."
                        "record" -> "Requested a recording from ${d.name}. After ~20 seconds open the gallery."
                        else -> "Requested fresh info from ${d.name}, tap ↻ shortly"
                    }
                )
            }
        }.start()
    }

    private var player: MediaPlayer? = null

    private fun stopPlayer() {
        try { player?.stop() } catch (_: Exception) {}
        try { player?.release() } catch (_: Exception) {}
        player = null
    }

    /** उस फ़ोन की सारी फ़ोटो (ऊपर) और रिकॉर्डिंग (नीचे) एक gallery में */
    private fun openMediaGallery(d: DeviceSync.Device) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(8))
        }
        box.addView(dim("Loading..."))
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("${d.name} — photos / recordings")
            .setView(scroll)
            .setPositiveButton("Close", null)
            .setOnDismissListener { stopPlayer() }
            .create()
        dialog.show()

        Thread {
            val list = DeviceSync.recentMedia(this, d.id)
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                box.removeAllViews()
                if (list == null) {
                    box.addView(dim("Could not load. Check internet / login and try again."))
                    return@runOnUiThread
                }
                val photos = list.filter { it.kind == "photo" }
                val videos = list.filter { it.kind == "video" }
                val audios = list.filter { it.kind == "audio" }
                if (photos.isEmpty() && videos.isEmpty() && audios.isEmpty()) {
                    box.addView(dim("Nothing yet. Ask this phone for a 📷 photo, 🎥 video or 🎙 recording, wait a few seconds, then reopen this."))
                    return@runOnUiThread
                }

                box.addView(head("📷 Photos (${photos.size})"))
                if (photos.isEmpty()) box.addView(dim("No photos yet."))
                photos.forEach { box.addView(photoBlock(it)) }

                box.addView(head("🎥 Videos (${videos.size})"))
                if (videos.isEmpty()) box.addView(dim("No videos yet."))
                videos.forEach { box.addView(videoRow(it)) }

                box.addView(head("🎙 Recordings (${audios.size})"))
                if (audios.isEmpty()) box.addView(dim("No recordings yet."))
                audios.forEach { box.addView(audioRow(it)) }
            }
        }.start()
    }

    private fun photoBlock(m: DeviceSync.Media): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(12))
        }
        wrap.addView(dim("${if (m.front) "Front camera" else "Back camera"}  •  ${timeLabel(m.timeMillis)}"))
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(240))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.color.bg2)
        }
        wrap.addView(img)
        val note = dim("loading image…")
        wrap.addView(note)
        img.setOnClickListener {
            Thread { val u = MediaUpload.sign(this, m.path); runOnUiThread { if (u != null) openUrl(u) } }.start()
        }
        Thread {
            val url = MediaUpload.sign(this, m.path)
            val bytes = if (url != null) Http.getBytes(url) else null
            val bmp = bytes?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null } }
            runOnUiThread {
                if (bmp != null) { img.setImageBitmap(bmp); note.text = "Tap to open full size" }
                else note.text = "Could not load this image"
            }
        }.start()
        return wrap
    }

    private fun videoRow(m: DeviceSync.Media): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        wrap.addView(line("🎥 Video  •  ${timeLabel(m.timeMillis)}"))
        wrap.addView(btnWide("▶ Play / download") {
            toast("Loading…")
            Thread { val u = MediaUpload.sign(this, m.path); runOnUiThread { if (u != null) openUrl(u) else toast("Could not load") } }.start()
        })
        return wrap
    }

    private fun audioRow(m: DeviceSync.Media): View {
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.bg_card)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }
        wrap.addView(line("🎙 Recording  •  ${timeLabel(m.timeMillis)}"))
        wrap.addView(btnWide("▶ Play") {
            toast("Loading…")
            Thread {
                val url = MediaUpload.sign(this, m.path)
                runOnUiThread {
                    if (url == null) { toast("Could not load"); return@runOnUiThread }
                    stopPlayer()
                    try {
                        player = MediaPlayer().apply {
                            setDataSource(url)
                            setOnPreparedListener { it.start(); toast("Playing…") }
                            setOnCompletionListener { stopPlayer() }
                            prepareAsync()
                        }
                    } catch (e: Exception) { toast("Could not play") }
                }
            }.start()
        })
        return wrap
    }

    private fun timeLabel(ms: Long): String =
        if (ms <= 0) "" else DateFormat.format("dd MMM, hh:mm a", Date(ms)).toString()

    // ---------------- duration picker ----------------

    private fun pickDuration(d: DeviceSync.Device, label: String, cb: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("$label — how long?")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .create()
        intArrayOf(1, 2, 5, 10, 15, 30, 60).forEach { m ->
            box.addView(btnWide("$m min") { cb(m * 60); dialog.dismiss() })
        }
        val inp = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = "custom minutes"
            setTextColor(color(R.color.text)); setHintTextColor(color(R.color.text_dim))
        }
        box.addView(inp)
        box.addView(btnWide("Start") {
            val m = inp.text.toString().trim().toIntOrNull()
            if (m == null || m < 1) toast("Enter minutes") else { cb(m * 60); dialog.dismiss() }
        })
        dialog.show()
    }

    private fun pickVideoDuration(d: DeviceSync.Device, cb: (Int) -> Unit) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), 0) }
        val dialog = AlertDialog.Builder(this)
            .setTitle("Video — how long? (max 3 min)")
            .setView(ScrollView(this).apply { addView(box) })
            .setNegativeButton("Cancel", null)
            .create()
        listOf("15 sec" to 15, "30 sec" to 30, "1 min" to 60, "2 min" to 120, "3 min" to 180).forEach { (label, sec) ->
            box.addView(btnWide(label) { cb(sec); dialog.dismiss() })
        }
        dialog.show()
    }

    // ---------------- Live camera ----------------

    private fun openLive(d: DeviceSync.Device) {
        AlertDialog.Builder(this)
            .setTitle("👁 Live camera — ${d.name}")
            .setMessage("New picture every ~3 sec for 2 minutes. That phone shows a green camera dot.")
            .setPositiveButton("Back camera") { _, _ -> startLive(d, false) }
            .setNeutralButton("Front camera") { _, _ -> startLive(d, true) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun startLive(d: DeviceSync.Device, front: Boolean) {
        cmd(d, "live", JSONObject().put("front", front).put("sec", 120))
        val img = ImageView(this).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(300))
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundResource(R.color.bg2)
        }
        val status = dim("Waiting for first picture…")
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(8), dp(14), 0)
            addView(img); addView(status)
        }
        val stopped = booleanArrayOf(false)
        val dialog = AlertDialog.Builder(this)
            .setTitle("👁 Live — ${if (front) "front" else "back"}")
            .setView(box)
            .setPositiveButton("Stop", null)
            .setOnDismissListener { stopped[0] = true }
            .create()
        dialog.show()

        val uid = store.userId
        val path = "$uid/live/frame_${d.id}.jpg"
        val count = intArrayOf(0)
        fun tick() {
            if (stopped[0] || !dialog.isShowing || count[0] > 46) return
            count[0]++
            Thread {
                val url = MediaUpload.sign(this, path)
                val bytes = if (url != null) Http.getBytes(url) else null
                val bmp = bytes?.let { try { BitmapFactory.decodeByteArray(it, 0, it.size) } catch (e: Exception) { null } }
                runOnUiThread {
                    if (stopped[0] || !dialog.isShowing) return@runOnUiThread
                    if (bmp != null) { img.setImageBitmap(bmp); status.text = "🔴 Live • ${timeLabel(System.currentTimeMillis())}" }
                    img.postDelayed({ tick() }, 3000)
                }
            }.start()
        }
        img.postDelayed({ tick() }, 2500)
    }

    // ---------------- Messages / Calls ----------------

    private fun dataDialog(title: String, build: (LinearLayout) -> Unit, fetch: () -> JSONObject?) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        box.addView(dim("Loading… (phone must be online)"))
        val dialog = AlertDialog.Builder(this).setTitle(title)
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Close", null).create()
        dialog.show()
        Thread {
            val res = fetch()
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                box.removeAllViews()
                when {
                    res == null -> box.addView(dim("No answer. Is that phone online?"))
                    !res.optBoolean("ok") -> box.addView(dim(res.optString("error", "Could not read")))
                    else -> build(box)
                }
            }
        }.start()
    }

    private fun openMessages(d: DeviceSync.Device) {
        var result: JSONObject? = null
        dataDialog("💬 Messages — ${d.name}", { box ->
            val arr = result?.optJSONArray("list") ?: org.json.JSONArray()
            box.addView(head("💬 Messages (${arr.length()})"))
            if (arr.length() == 0) box.addView(dim("No messages."))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                box.addView(line("${o.optString("from")}  •  ${timeLabel(o.optLong("time"))}"))
                box.addView(dim(o.optString("body")))
            }
        }, { DeviceSync.sendCommandWait(this, d.id, "sms", null).also { result = it } })
    }

    private fun openCalls(d: DeviceSync.Device) {
        var result: JSONObject? = null
        val ic = mapOf("in" to "📥", "out" to "📤", "missed" to "❌", "rejected" to "⛔", "other" to "•")
        dataDialog("📞 Calls — ${d.name}", { box ->
            val arr = result?.optJSONArray("list") ?: org.json.JSONArray()
            box.addView(head("📞 Call history (${arr.length()})"))
            if (arr.length() == 0) box.addView(dim("No calls."))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val dur = if (o.optInt("dur") > 0) " • ${o.optInt("dur")}s" else ""
                box.addView(line("${ic[o.optString("type")] ?: "•"} ${o.optString("name")}  •  ${timeLabel(o.optLong("time"))}"))
                box.addView(dim("${o.optString("type")}$dur"))
            }
        }, { DeviceSync.sendCommandWait(this, d.id, "calls", null).also { result = it } })
    }

    // ---------------- Files ----------------

    private fun openFiles(d: DeviceSync.Device, path: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(8), dp(18), dp(8)) }
        val scroll = ScrollView(this).apply { addView(box) }
        val dialog = AlertDialog.Builder(this).setTitle("📂 Files — ${d.name}")
            .setView(scroll).setPositiveButton("Close", null).create()
        dialog.show()

        if (path.isEmpty()) {
            box.addView(head("📂 Files"))
            val base = "/storage/emulated/0"
            val roots = listOf(
                "⬇️ Downloads" to "$base/Download",
                "🖼️ Gallery (DCIM)" to "$base/DCIM",
                "📸 Pictures" to "$base/Pictures",
                "🎬 Movies" to "$base/Movies",
                "💬 WhatsApp" to "$base/Android/media/com.whatsapp/WhatsApp/Media",
                "🗂️ All storage" to base
            )
            roots.forEach { (name, p) -> box.addView(btnWide(name) { dialog.dismiss(); openFiles(d, p) }) }
            return
        }

        box.addView(dim("Opening folder… (phone must be online)"))
        Thread {
            val res = DeviceSync.sendCommandWait(this, d.id, "list_dir", JSONObject().put("path", path))
            runOnUiThread {
                if (!dialog.isShowing) return@runOnUiThread
                box.removeAllViews()
                if (res == null) { box.addView(dim("No answer. Is that phone online?")); box.addView(btnWide("← Back") { dialog.dismiss(); openFiles(d, "") }); return@runOnUiThread }
                if (!res.optBoolean("ok")) { box.addView(dim(res.optString("error", "Could not open"))); box.addView(btnWide("← Back") { dialog.dismiss(); openFiles(d, "") }); return@runOnUiThread }
                box.addView(dim("🗂️ ${res.optString("path")}"))
                val parent = res.optString("parent")
                box.addView(btnWide("← Back") { dialog.dismiss(); openFiles(d, parent) })
                val dirs = res.optJSONArray("dirs") ?: org.json.JSONArray()
                for (i in 0 until dirs.length()) {
                    val o = dirs.getJSONObject(i)
                    box.addView(btnWide("📁 ${o.optString("name")}") { dialog.dismiss(); openFiles(d, o.optString("path")) })
                }
                val files = res.optJSONArray("files") ?: org.json.JSONArray()
                for (i in 0 until files.length()) {
                    val o = files.getJSONObject(i)
                    box.addView(line("${o.optString("name")}"))
                    box.addView(dim("${fmtSize(o.optLong("size"))} • ${timeLabel(o.optLong("mtime"))}"))
                    box.addView(btnWide("👁 Open / download") { pullAndOpen(d, o.optString("path"), o.optString("name")) })
                }
                if (dirs.length() == 0 && files.length() == 0) box.addView(dim("Empty folder."))
            }
        }.start()
    }

    private fun pullAndOpen(d: DeviceSync.Device, filePath: String, name: String) {
        toast("Fetching $name…")
        Thread {
            val res = DeviceSync.sendCommandWait(this, d.id, "pull_file", JSONObject().put("path", filePath), 60)
            val url = if (res != null && res.optBoolean("ok")) MediaUpload.sign(this, res.optString("path")) else null
            runOnUiThread {
                when {
                    res == null -> toast("No answer. Phone offline?")
                    !res.optBoolean("ok") -> toast(res.optString("error", "Could not fetch"))
                    url == null -> toast("Could not create link")
                    else -> openUrl(url)
                }
            }
        }.start()
    }

    private fun fmtSize(b: Long): String = when {
        b < 1024 -> "$b B"
        b < 1048576 -> "${b / 1024} KB"
        b < 1073741824 -> String.format(Locale.US, "%.1f MB", b / 1048576.0)
        else -> String.format(Locale.US, "%.2f GB", b / 1073741824.0)
    }

    // ---------------- Cloud login ----------------

    private var showCloudSetup = false

    private fun renderCloud() {
        cloudBox.removeAllViews()
        cloudBox.addView(dim("Login all your phones with one account. Then they all show under My Phone and can be controlled."))

        val configured = CloudSync.isConfigured(store)
        if (!configured || showCloudSetup) {
            cloudBox.addView(head("Cloud setup (one time)"))
            val url = input("Supabase Project URL (https://xxxx.supabase.co)", store.cloudUrl, false)
            val key = input("Supabase anon public key", store.cloudAnonKey, true)
            cloudBox.addView(url)
            cloudBox.addView(key)
            cloudBox.addView(btnWide("Save setup") {
                val u = url.text.toString().trim()
                if (!u.startsWith("https://")) toast("URL must start with https://") else {
                    store.cloudUrl = u
                    store.cloudAnonKey = key.text.toString().trim()
                    showCloudSetup = false
                    toast("Setup saved ✅")
                    renderCloud()
                }
            })
            if (!configured) return
        }

        if (!store.loggedIn) {
            val email = input("Email", store.userEmail, false).apply {
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
            }
            val pass = input("Password (at least 6 characters)", "", true)
            cloudBox.addView(email)
            cloudBox.addView(pass)
            val status = dim("")
            cloudBox.addView(row(
                btn("Login") {
                    status.text = "Logging in..."
                    Thread {
                        val err = CloudSync.signIn(this, email.text.toString(), pass.text.toString())
                        runOnUiThread {
                            if (err == null) {
                                toast("Logged in ✅"); renderCloud(); refresh()
                            } else status.text = err
                        }
                    }.start()
                },
                btn("New account") {
                    status.text = "Creating account..."
                    Thread {
                        val err = CloudSync.signUp(this, email.text.toString(), pass.text.toString())
                        runOnUiThread {
                            if (err == null) {
                                toast("Account created ✅"); renderCloud(); refresh()
                            } else status.text = err
                        }
                    }.start()
                }
            ))
            cloudBox.addView(status)
            if (!showCloudSetup) {
                cloudBox.addView(btnWide("Change cloud setup (URL / key)") { showCloudSetup = true; renderCloud() })
            }
        } else {
            cloudBox.addView(head("✅ Login: ${store.userEmail}"))
            val nameBox = input("This phone's name (e.g. My Phone, Shop Phone)", store.deviceName, false)
            cloudBox.addView(nameBox)
            cloudBox.addView(row(
                btn("Save name") {
                    store.deviceName = nameBox.text.toString().trim()
                    toast("Name saved ✅")
                    Thread { DeviceSync.pushHeartbeat(this) }.start()
                },
                btn("Logout") {
                    AlertDialog.Builder(this)
                        .setMessage("Logout?")
                        .setPositiveButton("Logout") { _, _ ->
                            CloudSync.signOut(this)
                            AjService.send(this, AjService.ACTION_STOP)
                            renderCloud(); refresh()
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
            ))
            if (configured && !showCloudSetup) {
                cloudBox.addView(btnWide("Change cloud setup") { showCloudSetup = true; renderCloud() })
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
            Manifest.permission.READ_SMS,
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
                text = "Enable"; textSize = 12f; setOnClickListener { onFix() }
            })
            permBox.addView(l)
        }

        fun rt(label: String, vararg perms: String) {
            addRow(label, perms.all { PhoneInfo.has(this, it) }) { requestRuntime(arrayOf(*perms)) }
        }

        rt("Location (where the phone is)", Manifest.permission.ACCESS_FINE_LOCATION)
        rt("Camera (remote photo)", Manifest.permission.CAMERA)
        rt("Microphone (remote recording)", Manifest.permission.RECORD_AUDIO)
        rt("Call log (history)", Manifest.permission.READ_CALL_LOG)
        rt("Messages (read SMS)", Manifest.permission.READ_SMS)
        if (Build.VERSION.SDK_INT >= 33) rt("Show notifications", Manifest.permission.POST_NOTIFICATIONS)

        addRow("All files (today's downloads)", PhoneInfo.canReadAllFiles(this)) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                openSettings(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                    Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                )
            } else requestRuntime(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE))
        }

        val pm = getSystemService(PowerManager::class.java)
        addRow("Battery optimization exempt (stay connected)", pm.isIgnoringBatteryOptimizations(packageName)) {
            openSettings(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")),
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            )
        }

        permBox.addView(dim("If a permission is blocked as restricted: phone Settings → Apps → My Devices → ⋮ → Allow restricted settings."))
    }

    private fun openSettings(vararg intents: Intent) {
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {
            }
        }
        toast("Settings did not open, find My Devices in your phone Settings")
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
            toast("Browser did not open")
        }
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
