package com.vibe.assistant.tools

import java.text.Normalizer

/**
 * Name matching that survives how people actually save and say names: a
 * contact saved as "Rahim" is found when the user says "রহিম" or Gemini writes
 * "Rohim". Both sides are reduced to a consonant "skeleton" (Bangla letters
 * transliterated, vowels and soft 'h' dropped), then compared with a small edit
 * distance. GeminiLive-Assistant's FindContactTool did plain Levenshtein ≤ 2;
 * this adds the script and spelling layer Bangla names need.
 */
object Fuzzy {
    // Keys are strings: some letters (ড়, ঢ়, য়) are two code points in many inputs.
    private val BN: Map<String, String> = mapOf(
        "ক" to "k", "খ" to "kh", "গ" to "g", "ঘ" to "gh", "ঙ" to "ng",
        "চ" to "c", "ছ" to "ch", "জ" to "j", "ঝ" to "jh", "ঞ" to "n",
        "ট" to "t", "ঠ" to "th", "ড" to "d", "ঢ" to "dh", "ণ" to "n",
        "ত" to "t", "থ" to "th", "দ" to "d", "ধ" to "dh", "ন" to "n",
        "প" to "p", "ফ" to "f", "ব" to "b", "ভ" to "bh", "ম" to "m",
        "য" to "j", "র" to "r", "ল" to "l", "শ" to "sh", "ষ" to "sh",
        "স" to "s", "হ" to "h", "ড়" to "r", "ঢ়" to "r", "য়" to "y",
        "ৎ" to "t", "ং" to "ng", "ঃ" to "h", "ঁ" to "",
        "অ" to "o", "আ" to "a", "ই" to "i", "ঈ" to "i", "উ" to "u", "ঊ" to "u",
        "ঋ" to "ri", "এ" to "e", "ঐ" to "oi", "ও" to "o", "ঔ" to "ou",
        "া" to "a", "ি" to "i", "ী" to "i", "ু" to "u", "ূ" to "u", "ৃ" to "ri",
        "ে" to "e", "ৈ" to "oi", "ো" to "o", "ৌ" to "ou", "্" to "",
    )

    /** Latin letters only, lowercase, no accents. Bangla script is transliterated. */
    fun latin(s: String): String {
        val sb = StringBuilder()
        val t = Normalizer.normalize(s, Normalizer.Form.NFC)
        var i = 0
        while (i < t.length) {
            val two = if (i + 1 < t.length) BN[t.substring(i, i + 2)] else null
            if (two != null) {
                sb.append(two); i += 2; continue
            }
            sb.append(BN[t.substring(i, i + 1)] ?: t[i].toString())
            i++
        }
        return Normalizer.normalize(sb.toString(), Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .lowercase()
            .replace(Regex("[^a-z ]"), "")
            .trim()
    }

    /** Consonant skeleton: "Rahim", "Rohim", "রহিম" → "rm". */
    fun skeleton(word: String): String {
        var s = latin(word).replace(" ", "")
        s = s.replace('v', 'b').replace('z', 'j').replace('q', 'k').replace('w', 'o').replace('x', 'k')
        s = s.replace("ph", "f")
        s = s.replace(Regex("[aeiouy]"), "")
        // Soft 'h' after a consonant (kh, sh, bh, rh...) carries no weight in spelling variants.
        s = s.replace(Regex("(?<=[bcdfgjklmnprst])h"), "")
        // Doubled letters: "Mahmood" / "Mahmud".
        s = s.replace(Regex("(.)\\1+"), "$1")
        return s
    }

    fun distance(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }

    /**
     * How well [query] matches [name]: 0 = same, higher = further, null = no match.
     * Each word of the name is tried, as is the whole name, so "Rahim" finds
     * "Rahim Uddin" and "Abdur Rahim".
     */
    fun score(query: String, name: String): Int? {
        val q = latin(query)
        val n = latin(name)
        if (q.isEmpty() || n.isEmpty()) return null
        if (n == q) return 0
        if (n.split(' ').any { it == q }) return 1
        if (q.length >= 3 && n.contains(q)) return 2
        val qs = skeleton(query)
        if (qs.length < 2) return null
        var best: Int? = null
        for (word in n.split(' ').filter { it.isNotEmpty() } + n) {
            val ws = skeleton(word)
            if (ws.isEmpty()) continue
            val d = distance(qs, ws)
            val allowed = if (qs.length >= 4) 1 else 0
            if (d <= allowed) {
                val s = 3 + d + if (latin(word).length != q.length) 1 else 0
                if (best == null || s < best) best = s
            }
        }
        return best
    }
}
