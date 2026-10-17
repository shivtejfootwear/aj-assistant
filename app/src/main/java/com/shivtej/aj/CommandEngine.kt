package com.shivtej.aj

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Build
import android.provider.AlarmClock
import android.provider.MediaStore
import android.telephony.SmsManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * AJ क्या जवाब दे और क्या काम करे।
 * speak = AJ क्या बोलेगा, action = बोलने के बाद कौन सा काम, listenAgain = बोलकर फिर सुनना है या नहीं
 */
data class Reply(
    val speak: String,
    val action: (() -> Unit)? = null,
    val listenAgain: Boolean = false,
)

/** Phase 1 का दिमाग: फ़ोन के अंदर ही, बिना internet के समझने वाले commands */
object CommandEngine {

    private data class PendingSms(val name: String, val number: String, val text: String)

    @Volatile
    private var pendingSms: PendingSms? = null

    private val YES = setOf("हां", "हा", "हाँ", "haan", "han", "ha", "yes", "ok", "okay", "ओके", "ठीक", "भेजो", "भेज", "जी", "बिल्कुल")
    private val NO = setOf("नहीं", "नही", "no", "मत", "रहने", "cancel", "कैंसल", "ना")

    fun handle(ctx: Context, raw: String): Reply {
        val store = Store(ctx)
        val t = TextUtil.stripWake(raw)

        // सिर्फ "AJ" बोला, आगे कुछ नहीं
        if (t.isBlank()) return Reply(store.greeting(), listenAgain = true)

        val words = t.split(" ").toSet()

        // 1) SMS भेजने की हाँ/ना
        pendingSms?.let { p ->
            pendingSms = null
            if (words.any { it in NO }) return Reply("ठीक है, मैसेज नहीं भेजा।")
            if (words.any { it in YES }) return sendSms(ctx, p)
            // कुछ और बोला तो उसे नया command मान लेंगे
        }

        // 2) आपकी सिखाई हुई बातें (सबसे पहले, ताकि आप AJ का जवाब खुद तय कर सकें)
        store.teaches()
            .filter { it.trigger.isNotBlank() && t.contains(TextUtil.norm(it.trigger)) }
            .maxByOrNull { it.trigger.length }
            ?.let { return Reply(it.reply) }

        // 3) काम वाले commands
        smsCommand(ctx, t)?.let { return it }
        callCommand(ctx, t)?.let { return it }
        timerCommand(t)?.let { return it.withAction(ctx) }
        alarmCommand(t)?.let { return it.withAction(ctx) }
        torchCommand(ctx, t)?.let { return it }
        openAppCommand(ctx, t)?.let { return it }
        searchCommand(ctx, t)?.let { return it }

        // 4) जानकारी वाले सवाल
        if (hasAny(t, "मैसेज", "मेसेज", "message", "sms", "एसएमएस", "संदेश")) return smsReport(ctx, t)
        if (hasAny(t, "कॉल", "call", "फोन", "मिस्ड", "missed")) callReport(ctx)?.let { return it }
        if (hasAny(t, "डाउनलोड", "download")) return downloadsReport(ctx)
        if (hasAny(t, "स्टोरेज", "storage", "मेमोरी", "memory", "जगह", "स्पेस", "space")) return storageReport()
        if (hasAny(t, "बैटरी", "battery", "चार्ज", "charge")) return batteryReport(ctx)
        if (hasAny(t, "तारीख", "date", "दिनांक", "कौन सा दिन", "क्या दिन")) return Reply(todayDate())
        if (hasAny(t, "टाइम", "time", "समय", "कितने बजे", "क्या बजा", "बजे हैं")) return Reply("अभी ${hindiTime(nowHour(), nowMinute())} हैं।")
        if (hasAny(t, "क्या कर सकते", "help", "मदद", "क्या क्या", "हेल्प")) return Reply(HELP)

        return Reply("माफ़ कीजिए, यह अभी मुझे नहीं आता। आप AJ app में ‘AJ को सिखाओ’ से मुझे यह सिखा सकते हैं।")
    }

    // =====================================================================
    // काम वाले commands
    // =====================================================================

    private val SMS_REGEX = Regex(
        "^(.+?) को (?:एक )?(?:मैसेज|मेसेज|message|sms|एसएमएस) (?:भेजो|भेज दो|करो|कर दो|send करो|लिखो)(?: कि| की)? (.+)$"
    )

    private val SMS_NO_TEXT_REGEX = Regex(
        "^(.+?) को (?:एक )?(?:मैसेज|मेसेज|message|sms|एसएमएस) (?:भेजो|भेज दो|करो|कर दो|send करो|लिखो)$"
    )

    private fun smsCommand(ctx: Context, t: String): Reply? {
        SMS_NO_TEXT_REGEX.find(t)?.let {
            return Reply("क्या लिखना है? पूरा एक साथ बोलिए, जैसे: ${it.groupValues[1]} को मैसेज भेजो कि मैं आ रहा हूँ।")
        }
        val m = SMS_REGEX.find(t) ?: return null
        val who = m.groupValues[1].trim()
        val text = m.groupValues[2].trim()
        if (text.isBlank()) return Reply("क्या मैसेज भेजना है? जैसे बोलिए: राहुल को मैसेज भेजो कि मैं आ रहा हूँ।")
        val contact = resolveContact(ctx, who) ?: return contactMissing(ctx, who)
        pendingSms = PendingSms(contact.first, contact.second, text)
        return Reply("${contact.first} को यह भेजूँ: $text? हाँ या नहीं बोलिए।", listenAgain = true)
    }

    private fun sendSms(ctx: Context, p: PendingSms): Reply {
        if (!PhoneInfo.has(ctx, Manifest.permission.SEND_SMS)) {
            return Reply("मैसेज भेजने की permission नहीं है। AJ app में Permissions से SMS चालू कीजिए।")
        }
        return try {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ctx.getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }
            val parts = sms.divideMessage(p.text)
            sms.sendMultipartTextMessage(p.number, null, parts, null, null)
            Reply("${p.name} को मैसेज भेज दिया।")
        } catch (e: Exception) {
            Reply("मैसेज नहीं जा पाया: ${e.message ?: "कोई गड़बड़"}")
        }
    }

    private val CALL_REGEX = Regex(
        "^(.+?) (?:को|पर|पे) (?:कॉल|call|फोन) ?(?:करो|कर दो|लगाओ|लगा दो|मिलाओ|मिला दो|करना|कीजिए)?$"
    )
    private val CALL_EN_REGEX = Regex("^(?:call|कॉल) (.+)$")

    private fun callCommand(ctx: Context, t: String): Reply? {
        val who = CALL_REGEX.find(t)?.groupValues?.get(1)?.trim()
            ?: CALL_EN_REGEX.find(t)?.groupValues?.get(1)?.trim()
            ?: return null
        val contact = resolveContact(ctx, who) ?: return contactMissing(ctx, who)
        if (!PhoneInfo.has(ctx, Manifest.permission.CALL_PHONE)) {
            return Reply("कॉल लगाने की permission नहीं है। AJ app में Permissions से ‘कॉल लगाना’ चालू कीजिए।")
        }
        val number = contact.second
        return Reply("${contact.first} को कॉल लगा रहा हूँ।", action = {
            val i = Intent(Intent.ACTION_CALL, Uri.parse("tel:" + Uri.encode(number)))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        })
    }

    /** नाम बोला हो तो contact, नंबर बोला हो तो वही नंबर */
    private fun resolveContact(ctx: Context, who: String): Pair<String, String>? {
        val digits = who.filter { it.isDigit() || it == '+' }
        if (digits.count { it.isDigit() } >= 3 && digits.length >= who.replace(" ", "").length - 1) {
            return digits to digits
        }
        return PhoneInfo.findContact(ctx, who)
    }

    private fun contactMissing(ctx: Context, who: String): Reply =
        if (!PhoneInfo.has(ctx, Manifest.permission.READ_CONTACTS))
            Reply("Contacts देखने की permission नहीं है। AJ app में Permissions से Contacts चालू कीजिए।")
        else
            Reply("$who नाम का contact नहीं मिला।")

    // ---- timer ----

    /** timer/alarm का intent; action बाद में जुड़ता है */
    private class Pending(val speak: String, val intent: Intent?)

    private fun Pending.withAction(ctx: Context): Reply {
        val i = intent ?: return Reply(speak)
        return Reply(speak, action = {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        })
    }

    private fun timerCommand(t: String): Pending? {
        if (!hasAny(t, "टाइमर", "timer")) return null
        val s = TextUtil.numbersToDigits(t)
        var total = 0
        var found = false
        Regex("(\\d+) ?(घंटे|घंटा|घंटों|घण्टा|घण्टे|hour|hours|hr)").find(s)?.let {
            total += it.groupValues[1].toInt() * 3600; found = true
        }
        Regex("(\\d+) ?(मिनट|मिनिट|minute|minutes|min)").find(s)?.let {
            total += it.groupValues[1].toInt() * 60; found = true
        }
        Regex("(\\d+) ?(सेकंड|सेकेंड|second|seconds|sec)").find(s)?.let {
            total += it.groupValues[1].toInt(); found = true
        }
        if (!found && s.contains("आधा घंटा")) { total = 1800; found = true }
        if (!found || total <= 0) {
            return Pending("कितने समय का टाइमर लगाऊँ? जैसे बोलिए: 5 मिनट का टाइमर लगाओ।", null)
        }
        total = total.coerceIn(1, 86400)
        val intent = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, total)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "AJ टाइमर")
        return Pending("${durationText(total)} का टाइमर लगा दिया।", intent)
    }

    private fun durationText(sec: Int): String {
        val h = sec / 3600
        val m = (sec % 3600) / 60
        val s = sec % 60
        val parts = ArrayList<String>()
        if (h > 0) parts.add("$h घंटे")
        if (m > 0) parts.add("$m मिनट")
        if (s > 0) parts.add("$s सेकंड")
        return parts.joinToString(" ")
    }

    // ---- alarm ----

    private fun alarmCommand(t: String): Pending? {
        if (!hasAny(t, "अलार्म", "alarm", "उठाना", "जगाना", "उठा देना", "जगा देना")) return null
        val s = TextUtil.numbersToDigits(t)
        var h: Int
        var m = 0
        val colon = Regex("(\\d{1,2})[:.](\\d{1,2})").find(s)
        val bajkar = Regex("(\\d{1,2}) ?बजकर ?(\\d{1,2})").find(s)
        val baje = Regex("(\\d{1,2}) ?(बजे|बजा|baje|am|pm|o clock|बज)").find(s)
        val modified = Regex("(साढे|सवा|पौने) ?(\\d{1,2})").find(s)
        when {
            colon != null -> { h = colon.groupValues[1].toInt(); m = colon.groupValues[2].toInt() }
            bajkar != null -> { h = bajkar.groupValues[1].toInt(); m = bajkar.groupValues[2].toInt() }
            modified != null -> {
                h = modified.groupValues[2].toInt()
                when (modified.groupValues[1]) {
                    "साढे" -> m = 30
                    "सवा" -> m = 15
                    "पौने" -> { m = 45; h -= 1; if (h <= 0) h = 12 }
                }
            }
            baje != null -> h = baje.groupValues[1].toInt()
            else -> {
                val any = Regex("(\\d{1,2})").find(s)
                    ?: return Pending("कितने बजे का अलार्म लगाऊँ? जैसे बोलिए: सुबह 7 बजे का अलार्म लगाओ।", null)
                h = any.groupValues[1].toInt()
            }
        }
        if (h > 23 || m > 59) return Pending("यह समय समझ नहीं आया, फिर से बोलिए।", null)

        // सुबह / दोपहर / शाम / रात
        when {
            hasAny(s, "शाम", "pm") -> if (h < 12) h += 12
            hasAny(s, "रात") -> { if (h in 6..11) h += 12 else if (h == 12) h = 0 }
            hasAny(s, "दोपहर") -> if (h in 1..5) h += 12
            hasAny(s, "सुबह", "am") -> if (h == 12) h = 0
        }
        val intent = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
            .putExtra(AlarmClock.EXTRA_MESSAGE, "AJ अलार्म")
        return Pending("${hindiTime(h, m)} का अलार्म लगा दिया।", intent)
    }

    // ---- टॉर्च ----

    private fun torchCommand(ctx: Context, t: String): Reply? {
        if (!hasAny(t, "टॉर्च", "टार्च", "torch", "flashlight", "फ्लैश", "flash")) return null
        val off = hasAny(t, "बंद", "off", "ऑफ")
        return try {
            val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return Reply("इस फ़ोन में टॉर्च नहीं मिली।")
            cm.setTorchMode(id, !off)
            Reply(if (off) "टॉर्च बंद कर दी।" else "टॉर्च चालू कर दी।")
        } catch (e: Exception) {
            Reply("टॉर्च नहीं चल पाई: ${e.message ?: "कोई गड़बड़"}")
        }
    }

    // ---- app खोलना ----

    private val OPEN_REGEX = Regex("^(.+?) (?:खोलो|खोल दो|ओपन करो|ओपन|open करो|open|चालू करो|शुरू करो)$")
    private val OPEN_EN_REGEX = Regex("^(?:open|ओपन) (.+)$")

    private fun openAppCommand(ctx: Context, t: String): Reply? {
        val name = OPEN_REGEX.find(t)?.groupValues?.get(1)?.trim()
            ?: OPEN_EN_REGEX.find(t)?.groupValues?.get(1)?.trim()
            ?: return null
        val clean = name.removeSuffix(" app").removeSuffix(" ऐप").removeSuffix(" एप").trim()

        if (hasAny(clean, "कैमरा", "camera")) {
            return Reply("कैमरा खोल रहा हूँ।", action = {
                val i = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(i)
            })
        }

        val pm = ctx.packageManager
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(launcher, 0)
        val labels = apps.map { it.loadLabel(pm).toString() }
        val index = TextUtil.bestMatch(clean, labels)
        if (index < 0) return Reply("$clean नाम का app नहीं मिला।")
        val pkg = apps[index].activityInfo.packageName
        val label = labels[index]
        return Reply("$label खोल रहा हूँ।", action = {
            pm.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
        })
    }

    // ---- Google search ----

    private fun searchCommand(ctx: Context, t: String): Reply? {
        if (!hasAny(t, "सर्च", "search", "ढूंढो", "खोजो", "गूगल", "google")) return null
        var q = t.replace(Regex("^(गूगल|google) ?(पे|पर|में|पर जाकर|पे जाकर)? ?"), "")
        q = q.replace(Regex(" ?(को )?(सर्च|search|ढूंढो|खोजो) ?(करो|कर दो|करना|कीजिए|कर)?$"), "").trim()
        if (q.isBlank()) return Reply("क्या सर्च करना है? जैसे बोलिए: गूगल पे आज का मौसम सर्च करो।")
        val query = q
        return Reply("गूगल पे $query सर्च कर रहा हूँ।", action = {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query)))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        })
    }

    // =====================================================================
    // जानकारी वाले जवाब
    // =====================================================================

    private fun smsReport(ctx: Context, t: String): Reply {
        val list = PhoneInfo.todaySms(ctx)
            ?: return Reply("मैसेज पढ़ने की permission नहीं है। AJ app में Permissions से SMS चालू कीजिए।")
        if (list.isEmpty()) return Reply("आज अभी तक कोई SMS नहीं आया।")
        val latest = list.first()
        val sb = StringBuilder("आज ${list.size} मैसेज आए। सबसे नया ${latest.from} का है: ${latest.body.take(120)}।")
        val readMore = hasAny(t, "पढो", "पढ़ो", "सुनाओ", "सारे", "सब")
        if (readMore) {
            list.drop(1).take(3).forEach { sb.append(" फिर ${it.from}: ${it.body.take(80)}।") }
        } else if (list.size > 1) {
            val others = list.drop(1).map { it.from }.distinct().take(4)
            sb.append(" बाकी मैसेज ${others.joinToString(", ")} के हैं।")
        }
        return Reply(sb.toString())
    }

    private fun callReport(ctx: Context): Reply? {
        val info = PhoneInfo.todayCalls(ctx)
            ?: return Reply("कॉल की लिस्ट देखने की permission नहीं है। AJ app में Permissions से ‘कॉल लिस्ट’ चालू कीजिए।")
        val sb = StringBuilder()
        if (info.missedCount == 0) sb.append("आज कोई missed call नहीं है।")
        else sb.append("आज ${info.missedCount} missed call हैं: ${info.missed.distinct().take(5).joinToString(", ")}।")
        sb.append(" ${info.incoming} कॉल आईं और आपने ${info.outgoing} कॉल कीं।")
        return Reply(sb.toString())
    }

    private fun downloadsReport(ctx: Context): Reply {
        val files = PhoneInfo.todayDownloads(ctx)
            ?: return Reply("फाइलें देखने की permission नहीं है। AJ app में Permissions से ‘सारी फाइलें’ चालू कीजिए।")
        if (files.isEmpty()) return Reply("आज Download folder में कोई नई फाइल नहीं आई।")
        val names = files.take(4).joinToString(", ") { it.nameWithoutExtension.take(40) }
        return Reply("आज ${files.size} फाइलें डाउनलोड हुईं: $names।")
    }

    private fun storageReport(): Reply {
        val s = PhoneInfo.storage()
        val used = s.totalGb - s.freeGb
        return Reply("फ़ोन की कुल स्टोरेज ${PhoneInfo.gb(s.totalGb)} GB है। उसमें ${PhoneInfo.gb(s.freeGb)} GB खाली है और ${PhoneInfo.gb(used)} GB भरी हुई है।")
    }

    private fun batteryReport(ctx: Context): Reply {
        val b = PhoneInfo.battery(ctx)
        val extra = if (b.charging) " और चार्ज हो रही है" else ""
        return Reply("बैटरी ${b.percent} प्रतिशत है$extra।")
    }

    // =====================================================================
    // छोटे helper
    // =====================================================================

    private fun hasAny(t: String, vararg keys: String) = keys.any { t.contains(it) }

    private fun nowHour() = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    private fun nowMinute() = Calendar.getInstance().get(Calendar.MINUTE)

    fun hindiTime(h: Int, m: Int): String {
        val period = when (h) {
            in 4..11 -> "सुबह"
            in 12..15 -> "दोपहर"
            in 16..19 -> "शाम"
            else -> "रात"
        }
        val h12 = if (h % 12 == 0) 12 else h % 12
        return if (m == 0) "$period $h12 बजे" else "$period $h12 बजकर $m मिनट"
    }

    private fun todayDate(): String {
        val f = SimpleDateFormat("EEEE, d MMMM yyyy", Locale("hi", "IN"))
        return "आज ${f.format(Date())} है।"
    }

    private const val HELP = "मैं ये काम कर सकता हूँ: आज के मैसेज और कॉल बताना, किसी को कॉल लगाना या मैसेज भेजना, " +
        "स्टोरेज, बैटरी और आज के डाउनलोड बताना, टाइमर और अलार्म लगाना, टॉर्च जलाना, गूगल पे सर्च करना, कोई भी app खोलना, " +
        "और जो आप मुझे सिखाएँ वो जवाब देना।"
}
