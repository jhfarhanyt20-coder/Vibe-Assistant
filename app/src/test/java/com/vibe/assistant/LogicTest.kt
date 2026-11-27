package com.vibe.assistant

import com.vibe.assistant.core.Role
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.tools.PhoneActions
import com.vibe.assistant.tools.ToolSpecs
import com.vibe.assistant.update.Updater
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Everything that can be checked without a phone. Run: gradlew testDebugUnitTest */
class LogicTest {

    @Test fun versionsCompareAsNumbers() {
        assertTrue(Updater.isNewer("0.10.0", "0.9.9"))
        assertTrue(Updater.isNewer("v0.6.1", "0.6.0"))
        assertTrue(Updater.isNewer("1.0", "0.9.12"))
        assertFalse(Updater.isNewer("0.6.0", "0.6.0"))
        assertFalse(Updater.isNewer("0.5.9", "0.6.0"))
    }

    @Test fun banglaDigitsBecomeANumber() {
        assertEquals("01712345678", PhoneActions.cleanNumber("০১৭১২-৩৪৫৬৭৮"))
        assertEquals("+8801712345678", PhoneActions.cleanNumber("+880 1712 345678"))
    }

    @Test fun numbersAreToldApartFromNames() {
        assertTrue(PhoneActions.looksLikeNumber("01712345678"))
        assertTrue(PhoneActions.looksLikeNumber("০১৭১২৩৪৫৬৭৮"))
        assertTrue(PhoneActions.looksLikeNumber("+880 1712-345678"))
        assertFalse(PhoneActions.looksLikeNumber("রহিম"))
        assertFalse(PhoneActions.looksLikeNumber("Rahim 2"))
    }

    @Test fun contactNamesMatchAcrossBanglaAndEnglishSpellings() {
        val F = com.vibe.assistant.tools.Fuzzy
        assertEquals(F.skeleton("Rahim"), F.skeleton("রহিম"))
        assertEquals(F.skeleton("Rahim"), F.skeleton("Rohim"))
        assertEquals(F.skeleton("Mahmud"), F.skeleton("Mahmood"))
        assertTrue(F.score("রহিম", "Rahim") != null)
        assertTrue(F.score("Rohim", "Abdur Rahim") != null)
        assertTrue(F.score("Karim", "Rahim") == null)       // different person
        assertTrue(F.score("আম্মু", "Ammu") != null)
        assertTrue(F.score("Sakib", "Shakib Al Hasan") != null)
        assertTrue(F.score("Rahim", "Rahim")!! < F.score("Rahim", "Rahima")!!) // exact beats near
    }

    /** 50 ms of 16 kHz PCM: a sine of the given loudness (amplitude), or noise. */
    private fun chunk(amp: Int, noise: Boolean = false, seed: Int = 0): ByteArray {
        val r = java.util.Random(seed.toLong())
        val b = ByteArray(1600)
        for (i in 0 until 800) {
            val v = if (noise) (r.nextGaussian() * amp).toInt() else (amp * kotlin.math.sin(i * 0.3)).toInt()
            val s = v.coerceIn(-32768, 32767)
            b[2 * i] = (s and 0xFF).toByte()
            b[2 * i + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return b
    }

    private fun isSilence(b: ByteArray) = b.all { it == 0.toByte() }

    @Test fun nearVoiceGateKeepsTheCloseVoiceAndDropsTheRoom() {
        val g = com.vibe.assistant.audio.NearVoiceGate()
        var t = 0L
        // 2 s of a noisy room with a far-away voice (quiet) — must become silence.
        repeat(40) { i ->
            val out = g.process(chunk(300, noise = true, seed = i), t); t += 50
            assertTrue("room noise leaked at chunk $i", out.all(::isSilence))
        }
        // The person at the phone speaks (loud) — must pass, with the lead-in (pre-roll).
        val first = g.process(chunk(5000), t); t += 50
        assertTrue("near voice did not pass", first.any { !isSilence(it) })
        assertTrue("lead-in missing", first.size > 1)
        repeat(10) { g.process(chunk(5000), t); t += 50 }
        // A short pause inside the sentence stays open (no clipped words)…
        val pause = g.process(chunk(300, noise = true, seed = 99), t + 200)
        assertTrue("short pause was cut", pause.none(::isSilence))
        // …but a long silence closes again.
        val later = g.process(chunk(300, noise = true, seed = 98), t + 2000)
        assertTrue("gate stayed open", later.all(::isSilence))
    }

    @Test fun newestLiveModelIsPreferredAndThinkingModelsLast() {
        val ordered = com.vibe.assistant.data.KeyChecker.orderModels(listOf(
            "models/gemini-3.1-flash-live-preview", "models/gemini-3.8-live-extended-thinking",
            "models/gemini-3.8-live", "models/gemini-2.5-flash-native-audio-preview-12-2025",
        ))
        assertEquals("models/gemini-3.8-live", ordered.first())
        assertEquals("models/gemini-3.8-live-extended-thinking", ordered.last())
    }

    @Test fun pinsNeverGoIntoMemory() {
        val M = com.vibe.assistant.core.Memory
        assertTrue(M.isSecret("আমার ফোনের পিন 4821"))
        assertTrue(M.isSecret("my phone PIN is 1 2 3 4"))
        assertTrue(M.isSecret("লক কোড ৪৮২১"))
        assertFalse(M.isSecret("রহিম আমার ভাই, তার জন্মদিন 12 মার্চ"))
        assertFalse(M.isSecret("I like Arijit Singh songs"))
    }

    @Test fun transcriptPiecesJoinIntoOneLinePerTurn() {
        VibeBus.lines.value = emptyList()
        VibeBus.closeTurn()
        VibeBus.append(Role.USER, "আজ ")
        VibeBus.append(Role.USER, "কেমন আছো")
        VibeBus.append(Role.VIBE, "ভালো আছি")
        VibeBus.closeTurn()
        VibeBus.append(Role.USER, "আবার")
        val l = VibeBus.lines.value
        assertEquals(3, l.size)
        assertEquals("আজ কেমন আছো", l[0].text)
        assertEquals(Role.VIBE, l[1].role)
        assertEquals("আবার", l[2].text)
    }

    /**
     * Gemini rejects the whole session if one function declaration is malformed,
     * and then nothing works at all — so check every one, and save them for the
     * Live test on the PC.
     */
    @Test fun toolDeclarationsAreValid() {
        for (screen in listOf(true, false)) {
            val all = ToolSpecs.all(screen)
            val names = mutableSetOf<String>()
            for (i in 0 until all.length()) {
                val f = all.getJSONObject(i)
                val name = f.getString("name")
                assertTrue("duplicate tool $name", names.add(name))
                assertTrue("bad name $name", Regex("[a-z_]{3,40}").matches(name))
                assertTrue("$name needs a description", f.getString("description").length > 10)
                val p = f.optJSONObject("parameters") ?: continue
                assertEquals("OBJECT", p.getString("type"))
                val props = p.getJSONObject("properties")
                for (k in props.keys()) {
                    val t = props.getJSONObject(k).getString("type")
                    assertTrue("$name.$k has type $t", t in setOf("STRING", "BOOLEAN", "INTEGER", "NUMBER"))
                }
                val req = p.optJSONArray("required")
                if (req != null) for (j in 0 until req.length()) {
                    assertTrue("$name requires missing ${req.getString(j)}", props.has(req.getString(j)))
                }
            }
            if (screen) {
                File("build").mkdirs()
                File("build/tool_specs.json").writeText(all.toString(2))
            }
        }
    }

    @Test fun setupMessageShapeMatchesTheLiveApi() {
        // The exact keys the app sends in its first message (see GeminiLiveClient.setupJson).
        val needed = listOf("model", "generationConfig", "systemInstruction", "inputAudioTranscription",
            "outputAudioTranscription", "contextWindowCompression", "sessionResumption")
        val src = File("src/main/java/com/vibe/assistant/live/GeminiLiveClient.kt").readText()
        for (k in needed) assertTrue("setup is missing $k", src.contains("\"$k\""))
        // The early-cut-off turn tuning must stay off by default (it broke the conversation).
        assertTrue(src.contains("val quickTurns: Boolean = false"))
        JSONObject("{}") // org.json is the real one here
    }
}
