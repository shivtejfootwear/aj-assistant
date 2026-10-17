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

    /** बोले गए नाम से contact ढूँढना: (नाम, नंबर) */
    fun findContact(ctx: Context, spokenName: String): Pair<String, String>? {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val names = ArrayList<String>()
        val numbers = ArrayList<String>()
        val seen = HashSet<String>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                null, null, null
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val number = c.getString(1) ?: continue
                    if (seen.add(name)) {
                        names.add(name)
                        numbers.add(number)
                    }
                }
            }
        } catch (e: Exception) {
            return null
        }
        val index = TextUtil.bestMatch(spokenName, names)
        return if (index >= 0) names[index] to numbers[index] else null
    }
}
