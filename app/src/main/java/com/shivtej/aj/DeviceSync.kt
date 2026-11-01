package com.shivtej.aj

import android.content.Context
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * कई फ़ोन, एक admin (सब फ़ोन आपके अपने)।
 * - हर फ़ोन अपनी जानकारी (battery, storage, आज के count, location) cloud पर रखता है
 * - admin फ़ोन दूसरे फ़ोन को काम भेजता है: ताज़ा status, location, या ring (ज़ोर से बजाना)
 * - हर काम सामने वाले फ़ोन पर AJ के notification/आवाज़ से दिखता है, कुछ छुपा नहीं
 */
object DeviceSync {

    data class Device(
        val id: String,
        val name: String,
        val status: JSONObject,
        val lastSeenMinAgo: Long,
        val isThis: Boolean,
    )

    /** एक captured फ़ाइल (photo/audio) की जानकारी */
    data class Media(
        val kind: String,      // "photo" या "audio"
        val path: String,      // storage path
        val front: Boolean,    // photo front/back
        val timeMillis: Long,  // कब बना
    )

    // ---------------- इस फ़ोन का काम ----------------

    /** इस फ़ोन की ताज़ा जानकारी cloud पर भेजो */
    fun pushHeartbeat(ctx: Context): Boolean {
        val store = Store(ctx)
        if (!store.loggedIn) return false
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return false
        val name = store.deviceName.ifBlank { Build.MODEL ?: "My Phone" }
        val row = JSONObject()
            .put("device_id", store.deviceId)
            .put("user_id", store.userId)
            .put("name", name)
            .put("status", buildStatus(ctx))
            .put("last_seen", isoNow())
        return try {
            val res = Http.request(
                "POST", "${store.cloudUrl}/rest/v1/aj_devices?on_conflict=device_id",
                headers + mapOf("Prefer" to "resolution=merge-duplicates,return=minimal"),
                row.toString()
            )
            res.ok
        } catch (e: Exception) {
            false
        }
    }

    private fun buildStatus(ctx: Context): JSONObject {
        val o = JSONObject()
        try {
            val b = PhoneInfo.battery(ctx)
            o.put("battery", b.percent).put("charging", b.charging)
        } catch (_: Exception) {
        }
        try {
            val s = PhoneInfo.storage()
            o.put("free_gb", PhoneInfo.gb(s.freeGb)).put("total_gb", PhoneInfo.gb(s.totalGb))
        } catch (_: Exception) {
        }
        PhoneInfo.todaySms(ctx)?.let { o.put("sms", it.size) }
        PhoneInfo.todayCalls(ctx)?.let {
            o.put("missed", it.missedCount).put("calls_in", it.incoming).put("calls_out", it.outgoing)
            if (it.missed.isNotEmpty()) o.put("missed_names", JSONArray(it.missed.distinct().take(5)))
        }
        PhoneInfo.lastLocation(ctx)?.let { (lat, lon, age) ->
            o.put("lat", lat).put("lon", lon).put("loc_age_min", age)
        }
        o.put("at", isoNow())
        return o
    }

    /** admin ने भेजे काम करो (status/locate/ring) */
    fun runPendingCommands(ctx: Context) {
        val store = Store(ctx)
        if (!store.loggedIn) return
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return
        try {
            val get = Http.request(
                "GET",
                "${store.cloudUrl}/rest/v1/aj_commands?select=id,type,args&state=eq.new&target=eq.${store.deviceId}&order=id.asc",
                headers
            )
            if (!get.ok) return
            val arr = JSONArray(get.body)
            if (arr.length() == 0) return
            var located = false
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val id = c.getLong("id")
                val type = c.optString("type")
                val args = c.optJSONObject("args") ?: JSONObject()
                when (type) {
                    "ring" -> { ring(ctx); markDone(ctx, id, JSONObject().put("ok", true)) }
                    "locate" -> { located = true; markDone(ctx, id, JSONObject().put("ok", true)) }
                    "status" -> markDone(ctx, id, JSONObject().put("ok", true))
                    "sms" -> markDone(ctx, id, smsResult(ctx))
                    "calls" -> markDone(ctx, id, callsResult(ctx))
                    "list_dir" -> markDone(ctx, id, PhoneInfo.listDir(ctx, args.optString("path")))
                    "pull_file" -> markDone(ctx, id, pullFileResult(ctx, args.optString("path")))
                    // camera/recording: CaptureService करता है और खुद result लिखता है (notification + आवाज़ के साथ)
                    "photo_back", "photo_front", "record", "video", "live" ->
                        CaptureService.start(ctx, id, type, args.optInt("sec", 15), args.optBoolean("front", false))
                    else -> markDone(ctx, id, JSONObject().put("ok", false))
                }
            }
            if (located) AjBus.setStatus("📍 Location requested by another phone")
            pushHeartbeat(ctx)
        } catch (_: Exception) {
        }
    }

    private fun smsResult(ctx: Context): JSONObject {
        val list = PhoneInfo.recentSms(ctx) ?: return JSONObject().put("ok", false).put("error", "SMS permission off")
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("from", it.from).put("body", it.body).put("time", it.time)) }
        return JSONObject().put("ok", true).put("list", arr)
    }

    private fun callsResult(ctx: Context): JSONObject {
        val list = PhoneInfo.recentCalls(ctx) ?: return JSONObject().put("ok", false).put("error", "Call log permission off")
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("name", it.name).put("number", it.number).put("type", it.type).put("time", it.time).put("dur", it.durationSec))
        }
        return JSONObject().put("ok", true).put("list", arr)
    }

    private fun mimeOf(name: String): String {
        val n = name.lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".gif") -> "image/gif"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".mp4") -> "video/mp4"
            n.endsWith(".3gp") -> "video/3gpp"
            n.endsWith(".mkv") -> "video/x-matroska"
            n.endsWith(".mp3") -> "audio/mpeg"
            n.endsWith(".m4a") || n.endsWith(".aac") -> "audio/mp4"
            n.endsWith(".opus") || n.endsWith(".ogg") -> "audio/ogg"
            n.endsWith(".wav") -> "audio/wav"
            n.endsWith(".pdf") -> "application/pdf"
            else -> "application/octet-stream"
        }
    }

    private fun pullFileResult(ctx: Context, path: String): JSONObject {
        if (path.isBlank()) return JSONObject().put("ok", false).put("error", "No file")
        val pair = PhoneInfo.readFileForPull(path) ?: return JSONObject().put("ok", false).put("error", "File too big or unreadable (max 60 MB)")
        val (name, bytes) = pair
        val uid = Store(ctx).userId.ifBlank { "unknown" }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date())
        val storePath = "$uid/pull/${stamp}_$name"
        val up = MediaUpload.upload(ctx, storePath, bytes, mimeOf(name))
            ?: return JSONObject().put("ok", false).put("error", "Upload failed")
        return JSONObject().put("ok", true).put("kind", "file").put("name", name).put("path", up).put("mime", mimeOf(name))
    }

    /** फ़ोन ढूँढने के लिए ज़ोर से बजाना + हिलाना (सामने वाले को साफ़ पता चले) */
    private fun ring(ctx: Context) {
        AjBus.setStatus("🔔 This phone is being located by another phone")
        try {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            val uri = RingtoneManager.getActualDefaultRingtoneUri(ctx, RingtoneManager.TYPE_ALARM)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
            val rt = RingtoneManager.getRingtone(ctx, uri)
            rt?.play()
            android.os.Handler(ctx.mainLooper).postDelayed({
                try {
                    rt?.stop()
                } catch (_: Exception) {
                }
            }, 25000)
        } catch (_: Exception) {
        }
        try {
            val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
            val pattern = longArrayOf(0, 600, 400, 600, 400, 600)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, -1))
            } else {
                @Suppress("DEPRECATION") v.vibrate(pattern, -1)
            }
        } catch (_: Exception) {
        }
    }

    // ---------------- admin फ़ोन का काम ----------------

    /** account के सारे फ़ोन लाओ */
    fun fetchDevices(ctx: Context): List<Device>? {
        val store = Store(ctx)
        if (!store.loggedIn) return null
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        return try {
            val res = Http.request(
                "GET",
                "${store.cloudUrl}/rest/v1/aj_devices?select=device_id,name,status,last_seen&order=last_seen.desc",
                headers
            )
            if (!res.ok) return null
            val arr = JSONArray(res.body)
            val out = ArrayList<Device>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val id = o.optString("device_id")
                val seen = parseIso(o.optString("last_seen"))
                val ageMin = if (seen > 0) (System.currentTimeMillis() - seen) / 60000 else -1
                out.add(
                    Device(
                        id = id,
                        name = o.optString("name").ifBlank { "Phone" },
                        status = o.optJSONObject("status") ?: JSONObject(),
                        lastSeenMinAgo = ageMin,
                        isThis = id == store.deviceId
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    /** command पूरा होने का निशान (CaptureService भी इसे इस्तेमाल करता है) */
    fun markDone(ctx: Context, id: Long, result: JSONObject) {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return
        try {
            Http.request(
                "PATCH", "${store.cloudUrl}/rest/v1/aj_commands?id=eq.$id",
                headers + mapOf("Prefer" to "return=minimal"),
                JSONObject().put("state", "done").put("result", result).toString()
            )
        } catch (_: Exception) {
        }
    }

    /** दूसरे फ़ोन को काम भेजो */
    fun sendCommand(ctx: Context, targetDeviceId: String, type: String, args: JSONObject? = null): String? {
        val store = Store(ctx)
        if (!store.loggedIn) return "Please Cloud login first"
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return "Not logged in"
        return try {
            val row = JSONObject()
                .put("user_id", store.userId)
                .put("target", targetDeviceId)
                .put("type", type)
            if (args != null) row.put("args", args)
            val res = Http.request(
                "POST", "${store.cloudUrl}/rest/v1/aj_commands",
                headers + mapOf("Prefer" to "return=minimal"),
                row.toString()
            )
            if (res.ok) null else "Couldn't send (code ${res.code})"
        } catch (e: Exception) {
            "No internet"
        }
    }

    /** उस फ़ोन का सबसे नया फ़ोटो/रिकॉर्डिंग link (signed) या null */
    fun latestMedia(ctx: Context, targetDeviceId: String): String? {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        return try {
            val res = Http.request(
                "GET",
                "${store.cloudUrl}/rest/v1/aj_commands?select=result&target=eq.$targetDeviceId&state=eq.done&type=in.(photo_back,photo_front,record)&order=id.desc&limit=1",
                headers
            )
            if (!res.ok) return null
            val arr = JSONArray(res.body)
            if (arr.length() == 0) return null
            val path = arr.getJSONObject(0).optJSONObject("result")?.optString("path").orEmpty()
            if (path.isBlank()) null else MediaUpload.sign(ctx, path)
        } catch (e: Exception) {
            null
        }
    }

    /** काम भेजो और उसका result आने तक रुको (timeout तक) */
    fun sendCommandWait(ctx: Context, target: String, type: String, args: JSONObject?, timeoutSec: Int = 45): JSONObject? {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        val id = try {
            val row = JSONObject().put("user_id", store.userId).put("target", target).put("type", type)
            if (args != null) row.put("args", args)
            val res = Http.request(
                "POST", "${store.cloudUrl}/rest/v1/aj_commands",
                headers + mapOf("Prefer" to "return=representation"), row.toString()
            )
            if (!res.ok) return null
            JSONArray(res.body).optJSONObject(0)?.optLong("id") ?: return null
        } catch (e: Exception) {
            return null
        }
        val deadline = System.currentTimeMillis() + timeoutSec * 1000L
        while (System.currentTimeMillis() < deadline) {
            try { Thread.sleep(2000) } catch (_: Exception) {}
            try {
                val res = Http.request("GET", "${store.cloudUrl}/rest/v1/aj_commands?select=state,result&id=eq.$id", headers)
                if (res.ok) {
                    val o = JSONArray(res.body).optJSONObject(0)
                    if (o != null && o.optString("state") == "done") return o.optJSONObject("result") ?: JSONObject()
                }
            } catch (_: Exception) {}
        }
        return null
    }

    /** उस फ़ोन की हाल की सारी फ़ोटो/रिकॉर्डिंग (नई पहले) */
    fun recentMedia(ctx: Context, targetDeviceId: String, limit: Int = 30): List<Media>? {
        val store = Store(ctx)
        val headers = CloudSync.authHeadersEnsured(ctx) ?: return null
        return try {
            val res = Http.request(
                "GET",
                "${store.cloudUrl}/rest/v1/aj_commands?select=type,result,created_at&target=eq.$targetDeviceId&state=eq.done&type=in.(photo_back,photo_front,record,video)&order=id.desc&limit=$limit",
                headers
            )
            if (!res.ok) return null
            val arr = JSONArray(res.body)
            val out = ArrayList<Media>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val r = o.optJSONObject("result") ?: continue
                if (!r.optBoolean("ok")) continue
                val path = r.optString("path")
                if (path.isBlank()) continue
                val type = o.optString("type")
                out.add(
                    Media(
                        kind = r.optString("kind").ifBlank { if (type.startsWith("photo")) "photo" else if (type == "video") "video" else "audio" },
                        path = path,
                        front = type == "photo_front",
                        timeMillis = parseIso(o.optString("created_at"))
                    )
                )
            }
            out
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- helper ----------------

    private fun isoNow(): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }.format(Date())

    private fun parseIso(s: String): Long = try {
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }.parse(s.take(19))?.time ?: 0
    } catch (e: Exception) {
        0
    }
}
