package com.vibe.assistant.core

import android.content.Context
import com.vibe.assistant.agent.GeminiText
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Vibe's long-term memory, kept only on the phone (files/memory.json).
 *
 *   facts   things about you: your name, people, likes, routines — told directly
 *           ("মনে রেখো…") or picked out automatically at the end of a conversation
 *   skills  how a phone task was done successfully, so the next similar task
 *           starts from a known path instead of exploring
 *
 * Facts go into every conversation's prompt; skills go to the screen agent.
 */
object Memory {
    data class Fact(val text: String, val category: String, val time: Long)
    data class Skill(val goal: String, val app: String, val steps: List<String>, val uses: Int, val time: Long)

    private const val MAX_FACTS = 250
    private const val MAX_SKILLS = 120

    private lateinit var file: File
    private val facts = mutableListOf<Fact>()
    private val skills = mutableListOf<Skill>()

    /** For the settings screen. */
    val factsFlow = MutableStateFlow<List<Fact>>(emptyList())
    val skillCount = MutableStateFlow(0)

    fun init(ctx: Context) {
        file = File(ctx.filesDir, "memory.json")
        synchronized(this) {
            facts.clear(); skills.clear()
            try {
                if (file.exists()) {
                    val o = JSONObject(file.readText())
                    o.optJSONArray("facts")?.let { a ->
                        for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                            facts += Fact(it.optString("text"), it.optString("cat", "other"), it.optLong("t"))
                        }
                    }
                    o.optJSONArray("skills")?.let { a ->
                        for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                            val st = it.optJSONArray("steps") ?: JSONArray()
                            skills += Skill(
                                it.optString("goal"), it.optString("app"),
                                (0 until st.length()).map { j -> st.optString(j) },
                                it.optInt("uses", 1), it.optLong("t")
                            )
                        }
                    }
                }
            } catch (_: Exception) {}
            publish()
        }
    }

    private fun publish() {
        factsFlow.value = facts.toList()
        skillCount.value = skills.size
    }

    private fun save() {
        val o = JSONObject()
            .put("facts", JSONArray(facts.map { JSONObject().put("text", it.text).put("cat", it.category).put("t", it.time) }))
            .put("skills", JSONArray(skills.map {
                JSONObject().put("goal", it.goal).put("app", it.app).put("steps", JSONArray(it.steps))
                    .put("uses", it.uses).put("t", it.time)
            }))
        val tmp = File(file.parentFile, "memory.json.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(file)
        publish()
    }

    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s\\p{Punct}।]+"), " ").trim()

    // ── Facts ────────────────────────────────────────────────────────────────

    /** Adds a fact; a near-duplicate replaces the older one. Returns true if new. */
    fun remember(text: String, category: String = "other"): Boolean = synchronized(this) {
        val t = text.trim()
        if (t.length < 3) return false
        if (isSecret(t)) return false
        val n = norm(t)
        val dup = facts.indexOfFirst { norm(it.text) == n || norm(it.text).contains(n) || n.contains(norm(it.text)) }
        val fresh = Fact(t, category.ifBlank { "other" }, System.currentTimeMillis())
        val isNew = dup < 0
        if (dup >= 0) facts.removeAt(dup)
        facts += fresh
        while (facts.size > MAX_FACTS) facts.removeAt(0)
        save()
        isNew
    }

    /** Codes never go into memory: memory is sent to Gemini at every conversation start. */
    fun isSecret(t: String): Boolean =
        Regex("(?i)(\\bpin\\b|পিন|password|পাসওয়ার্ড|otp|ওটিপি|passcode|পাসকোড|\\bcode\\b|কোড)").containsMatchIn(t) &&
            Regex("\\d{4,}|[০-৯]{4,}|(\\d[\\s-]?){4,}").containsMatchIn(t)

    /** Removes facts that mention the given text. Returns how many. */
    fun forget(text: String): Int = synchronized(this) {
        val n = norm(text)
        if (n.isEmpty()) return 0
        val before = facts.size
        facts.removeAll { norm(it.text).contains(n) }
        val removed = before - facts.size
        if (removed > 0) save()
        removed
    }

    fun delete(fact: Fact) = synchronized(this) {
        facts.remove(fact)
        save()
    }

    fun clearAll() = synchronized(this) {
        facts.clear(); skills.clear()
        save()
    }

    /** The block that goes into the system prompt. */
    fun promptBlock(): String = synchronized(this) {
        if (facts.isEmpty()) return ""
        val lines = facts.takeLast(150).groupBy { it.category }.entries.joinToString("\n") { (cat, fs) ->
            "- $cat: " + fs.joinToString("; ") { it.text }
        }
        "What you remember about the user (from earlier conversations — use it naturally, don't recite it):\n$lines"
    }

    // ── Skills (learned phone tasks) ─────────────────────────────────────────

    fun learnSkill(goal: String, app: String, steps: List<String>) = synchronized(this) {
        if (steps.isEmpty()) return
        val g = norm(goal)
        val i = skills.indexOfFirst { norm(it.goal) == g }
        val uses = if (i >= 0) skills[i].uses + 1 else 1
        if (i >= 0) skills.removeAt(i)
        skills += Skill(goal, app, steps.take(20), uses, System.currentTimeMillis())
        while (skills.size > MAX_SKILLS) skills.removeAt(0)
        save()
    }

    /** The most similar past task (shared words), if it is similar enough. */
    fun similarSkill(goal: String): Skill? = synchronized(this) {
        val words = norm(goal).split(" ").filter { it.length > 2 }.toSet()
        if (words.isEmpty()) return null
        skills.map { s ->
            val sw = norm(s.goal).split(" ").filter { it.length > 2 }.toSet()
            s to (words.intersect(sw).size.toFloat() / maxOf(1, words.union(sw).size))
        }.filter { it.second >= 0.34f }.maxByOrNull { it.second }?.first
    }

    // ── Learning from a finished conversation ────────────────────────────────

    /**
     * Reads the conversation and keeps anything worth remembering long-term.
     * Runs in the background after the conversation ends; failures are silent.
     */
    suspend fun learnFromConversation(lines: List<Line>) {
        val talk = lines.filter { it.role != Role.ACTION }
        if (talk.count { it.role == Role.USER } < 2) return
        val transcript = talk.takeLast(40).joinToString("\n") {
            (if (it.role == Role.USER) "User: " else "Vibe: ") + it.text.take(400)
        }
        val known = synchronized(this) { facts.takeLast(80).joinToString("\n") { "- " + it.text } }
        val sys = """
You maintain a personal assistant's long-term memory about its user.
From the conversation, extract only DURABLE facts worth remembering for future conversations:
the user's name, family/friends and who they are, preferences and dislikes, habits and routines,
work/study, important dates, places, recurring requests (e.g. "usually asks for Arijit Singh songs at night").
Do NOT store one-off small talk, the current weather, or anything already known.
Never store passwords, PINs, OTPs, card or bank numbers.
Write each fact as one short sentence in the same language the user used (Bangla is fine).
Return JSON: {"facts":[{"text":"...","category":"name|people|likes|habits|work|dates|places|other"}]}
Return {"facts":[]} if nothing is worth keeping.
""".trim()
        val user = "ALREADY KNOWN:\n${known.ifBlank { "(nothing yet)" }}\n\nCONVERSATION:\n$transcript"
        try {
            val o = GeminiText.json(sys, user)
            val arr = o.optJSONArray("facts") ?: return
            var added = 0
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                if (remember(f.optString("text"), f.optString("category", "other"))) added++
            }
            if (added > 0) VibeBus.event("মেমোরিতে নতুন $added টা কথা জমা হলো")
        } catch (_: Exception) {}
    }
}
