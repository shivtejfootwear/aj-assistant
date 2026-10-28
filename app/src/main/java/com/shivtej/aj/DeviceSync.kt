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
                    // camera/recording: CaptureService करता है और खुद result लिखता है (notification + आवाज़ के साथ)
                    "photo_back", "photo_front", "record" ->
                        CaptureService.start(ctx, id, type, args.optInt("sec", 15))
                    else -> markDone(ctx, id, JSONObject().put("ok", false))
                }
            }
            if (located) AjBus.setStatus("📍 Location requested by another phone")
            pushHeartbeat(ctx)
        } catch (_: Exception) {
        }
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
