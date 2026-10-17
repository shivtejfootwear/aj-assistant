package com.shivtej.aj

import java.util.Locale

/**
 * बोली गई हिंदी/Hinglish को समझने लायक बनाना।
 * जैसे "राहुल" (हिंदी में बोला) को फ़ोन में सेव "Rahul" से मिलाना।
 */
object TextUtil {

    /** छोटे अक्षर, नुक्ता हटाना, चंद्रबिंदु = बिंदु, फालतू चिह्न हटाना */
    fun norm(s: String): String = s.lowercase(Locale.ROOT)
        .replace("़", "")          // नुक्ता: फ़ोन -> फोन
        .replace('ँ', 'ं')    // ँ -> ं : हाँ -> हां
        .replace(Regex("[,.!?।|\"'“”‘’]"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()

    private val WAKE_PREFIX = Regex("^(hey\\s+|हे\\s+|ओ\\s+)?(aj|a j|ए जे|एजे|ऐजे|ए जय|ऐ जे|ए\\.जे)(\\s+|$)")

    /** शुरू में "AJ" बोला हो तो हटा दो */
    fun stripWake(s: String): String {
        var t = norm(s)
        repeat(2) { t = t.replace(WAKE_PREFIX, "").trim() }
        return t
    }

    // ---------------- देवनागरी -> English अक्षर ----------------

    private val CONS = mapOf(
        'क' to "k", 'ख' to "kh", 'ग' to "g", 'घ' to "gh", 'ङ' to "n",
        'च' to "ch", 'छ' to "chh", 'ज' to "j", 'झ' to "jh", 'ञ' to "n",
        'ट' to "t", 'ठ' to "th", 'ड' to "d", 'ढ' to "dh", 'ण' to "n",
        'त' to "t", 'थ' to "th", 'द' to "d", 'ध' to "dh", 'न' to "n",
        'प' to "p", 'फ' to "ph", 'ब' to "b", 'भ' to "bh", 'म' to "m",
        'य' to "y", 'र' to "r", 'ल' to "l", 'ळ' to "l", 'व' to "v",
        'श' to "sh", 'ष' to "sh", 'स' to "s", 'ह' to "h",
    )
    private val MATRA = mapOf(
        'ा' to "aa", 'ि' to "i", 'ी' to "ee", 'ु' to "u", 'ू' to "oo", 'ृ' to "ri",
        'े' to "e", 'ै' to "ai", 'ो' to "o", 'ौ' to "au", 'ॅ' to "e", 'ॉ' to "o",
    )
    private val VOWEL = mapOf(
        'अ' to "a", 'आ' to "aa", 'इ' to "i", 'ई' to "ee", 'उ' to "u", 'ऊ' to "oo",
        'ऋ' to "ri", 'ए' to "e", 'ऐ' to "ai", 'ओ' to "o", 'औ' to "au", 'ऑ' to "o",
    )

    fun translit(s: String): String {
        val sb = StringBuilder()
        var inherent = false
        for (ch in s) {
            val cons = CONS[ch]
            if (cons != null) {
                sb.append(cons).append('a')
                inherent = true
                continue
            }
            val matra = MATRA[ch]
            if (matra != null) {
                if (inherent) sb.setLength(sb.length - 1)
                sb.append(matra)
                inherent = false
                continue
            }
            if (ch == '्') { // हलंत
                if (inherent) sb.setLength(sb.length - 1)
                inherent = false
                continue
            }
            val vowel = VOWEL[ch]
            if (vowel != null) {
                sb.append(vowel)
                inherent = false
                continue
            }
            when (ch) {
                'ं', 'ँ' -> sb.append('n')
                'ः' -> sb.append('h')
                else -> sb.append(ch)
            }
            inherent = false
        }
        return sb.toString()
    }

    /**
     * नाम का "ढाँचा": सिर्फ मुख्य व्यंजन। "राहुल" और "Rahul" दोनों -> "rl"।
     */
    fun skeleton(s: String): String {
        var t = translit(norm(s)).lowercase(Locale.ROOT)
        t = t.replace("ch", "\u0001")
            .replace("ck", "k")
            .replace(Regex("c(?=[eiy])"), "s")   // Facebook, Lucy -> s
            .replace('c', 'k')
            .replace("\u0001", "c")
            .replace('w', 'v')
            .replace('f', 'p')
            .replace('z', 'j')
            .replace('q', 'k')
            .replace('x', 'k')
        val sb = StringBuilder()
        for (c in t) {
            if (c in "aeiouyh") continue
            if (!c.isLetterOrDigit()) continue
            if (sb.isNotEmpty() && sb[sb.length - 1] == c) continue
            sb.append(c)
        }
        return sb.toString()
    }

    /** बोले गए नाम से list में सबसे मिलता-जुलता नाम; न मिले तो -1 */
    fun bestMatch(spoken: String, candidates: List<String>): Int {
        val spokenNorm = norm(spoken)
        val spokenSk = skeleton(spoken)
        if (spokenNorm.isEmpty()) return -1
        var bestIndex = -1
        var bestScore = 0
        candidates.forEachIndexed { index, cand ->
            val candNorm = norm(cand)
            val words = candNorm.split(" ").filter { it.isNotBlank() }
            var score = 0
            if (candNorm == spokenNorm) score = 120
            else if (words.any { it == spokenNorm }) score = 110
            else if (candNorm.contains(spokenNorm) && spokenNorm.length >= 3) score = 100
            else if (spokenSk.isNotEmpty()) {
                val candSk = skeleton(cand)
                val wordSks = words.map { skeleton(it) }
                score = when {
                    candSk == spokenSk -> 95
                    wordSks.any { it == spokenSk } -> 90
                    spokenSk.length >= 2 && wordSks.any { it.startsWith(spokenSk) } -> 70
                    spokenSk.length >= 3 && candSk.contains(spokenSk) -> 50
                    else -> 0
                }
            }
            if (score > bestScore) {
                bestScore = score
                bestIndex = index
            }
        }
        return bestIndex
    }

    // ---------------- गिनती ----------------

    private val NUMBER_WORDS = listOf(
        "पैंतालीस" to "45", "पैंतालिस" to "45", "पचास" to "50", "चालीस" to "40", "तीस" to "30",
        "पच्चीस" to "25", "बीस" to "20", "पंद्रह" to "15", "पन्द्रह" to "15", "बारह" to "12",
        "ग्यारह" to "11", "दस" to "10", "नौ" to "9", "आठ" to "8", "सात" to "7",
        "छह" to "6", "छः" to "6", "छे" to "6", "पांच" to "5", "चार" to "4", "तीन" to "3",
        "दो" to "2", "एक" to "1",
        "one" to "1", "two" to "2", "three" to "3", "four" to "4", "five" to "5",
        "six" to "6", "seven" to "7", "eight" to "8", "nine" to "9", "ten" to "10",
    )

    /** "पांच मिनट" -> "5 मिनट" */
    fun numbersToDigits(s: String): String {
        var t = " $s "
        for ((word, digit) in NUMBER_WORDS) {
            t = t.replace(" $word ", " $digit ")
        }
        return t.trim()
    }
}
