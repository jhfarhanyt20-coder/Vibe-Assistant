package com.vibe.assistant

import com.vibe.assistant.core.Prompt
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.tools.ToolSpecs
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.util.Base64
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Talks to the REAL Gemini Live service from the PC, the way the phone does, with
 * spoken Bangla made by Gemini's own speech model. It checks the part that does
 * not need a phone: does Gemini understand, does it answer, does a tool get
 * called, and does it stop when you talk over it.
 *
 * It needs a key and is skipped without one:
 *   $env:VIBE_TEST_KEY = "AIza..."; .\gradlew.bat testDebugUnitTest --tests "*LiveConversationTest*"
 * Results: app/build/live_report.txt
 */
class LiveConversationTest {
    private val key: String? = System.getenv("VIBE_TEST_KEY")?.trim()?.takeIf { it.length > 20 }
        ?: File("test-key.txt").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.length > 20 }
    private val model = System.getenv("VIBE_TEST_MODEL") ?: "models/gemini-3.1-flash-live-preview"
    private val http = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS).build()
    private val report = StringBuilder()

    private fun log(s: String) {
        println(s)
        report.append(s).append('\n')
    }

    // ── Speech for the "user", from Gemini TTS (24 kHz) resampled to 16 kHz ──

    private fun say(text: String): ByteArray {
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "Say naturally in Bangla: $text")))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                    "prebuiltVoiceConfig", JSONObject().put("voiceName", "Puck")))))
        for (m in listOf("gemini-2.5-flash-preview-tts", "gemini-2.5-pro-preview-tts")) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", key!!)
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()
            val resp = OkHttpClient.Builder().readTimeout(90, TimeUnit.SECONDS).build().newCall(req).execute()
            val txt = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                log("   (TTS $m: ${resp.code})")
                continue
            }
            val parts = JSONObject(txt).getJSONArray("candidates").getJSONObject(0)
                .getJSONObject("content").getJSONArray("parts")
            for (i in 0 until parts.length()) {
                val d = parts.getJSONObject(i).optJSONObject("inlineData")?.optString("data")
                if (!d.isNullOrEmpty()) return resample24to16(Base64.getDecoder().decode(d))
            }
        }
        error("TTS failed")
    }

    private fun resample24to16(pcm: ByteArray): ByteArray {
        val n = pcm.size / 2
        val out = ByteArray((n * 2 / 3) * 2)
        for (j in 0 until out.size / 2) {
            val src = j * 1.5
            val i = src.toInt().coerceAtMost(n - 1)
            val k = (i + 1).coerceAtMost(n - 1)
            fun s(x: Int) = ((pcm[2 * x + 1].toInt() shl 8) or (pcm[2 * x].toInt() and 0xFF)).toShort().toInt()
            val v = (s(i) + (s(k) - s(i)) * (src - i)).toInt()
            out[2 * j] = (v and 0xFF).toByte()
            out[2 * j + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    // ── One Live session ─────────────────────────────────────────────────────

    private class Session(val ws: WebSocket, val events: LinkedBlockingQueue<JSONObject>)

    private fun open(prompt: String, tools: JSONArray): Session {
        val events = LinkedBlockingQueue<JSONObject>()
        val setup = JSONObject()
            .put("model", model)
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                    "prebuiltVoiceConfig", JSONObject().put("voiceName", "Kore")))))
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            .put("sessionResumption", JSONObject())
        if (tools.length() > 0) setup.put("tools", JSONArray().put(JSONObject().put("functionDeclarations", tools)))
        val req = Request.Builder()
            .url("wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent")
            .header("x-goog-api-key", key!!).build()
        val ws = http.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(JSONObject().put("setup", setup).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { events.put(JSONObject(text)) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { events.put(JSONObject(bytes.utf8())) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                events.put(JSONObject().put("_closed", "$code $reason"))
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                events.put(JSONObject().put("_closed", "${response?.code} ${t.message} ${response?.body?.string()}"))
            }
        })
        val first = events.poll(20, TimeUnit.SECONDS) ?: error("no answer to setup")
        if (!first.has("setupComplete")) error("setup refused: $first")
        return Session(ws, events)
    }

    private fun stream(s: Session, pcm: ByteArray, realtime: Boolean = true) {
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + 1600, pcm.size)
            val b64 = Base64.getEncoder().encodeToString(pcm.copyOfRange(i, end))
            s.ws.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$b64\"}}}")
            i = end
            if (realtime) Thread.sleep(48)
        }
    }

    private fun silence(ms: Int) = ByteArray(16 * ms * 2)

    data class Turn(val heard: String, val said: String, val firstAudioMs: Long, val tools: List<String>,
                    val interrupted: Boolean, val closed: String?)

    /** Collects one reply until turnComplete (answers tool calls with a dummy "ok"). */
    private fun collect(s: Session, sentAt: Long, maxS: Long = 30, onAudio: ((Long) -> Unit)? = null): Turn {
        val heard = StringBuilder()
        val said = StringBuilder()
        val tools = mutableListOf<String>()
        var first = -1L
        var interrupted = false
        val deadline = System.currentTimeMillis() + maxS * 1000
        while (System.currentTimeMillis() < deadline) {
            val e = s.events.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (e.has("_closed")) return Turn(heard.toString(), said.toString(), first, tools, interrupted, e.getString("_closed"))
            e.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
                val responses = JSONArray()
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    tools += f.getString("name") + f.optJSONObject("args")?.toString().orEmpty()
                    responses.put(JSONObject().put("id", f.optString("id")).put("name", f.getString("name"))
                        .put("response", JSONObject().put("result", "ok: done (test)")))
                }
                s.ws.send(JSONObject().put("toolResponse", JSONObject().put("functionResponses", responses)).toString())
            }
            val sc = e.optJSONObject("serverContent") ?: continue
            sc.optJSONObject("inputTranscription")?.optString("text")?.let { heard.append(it) }
            sc.optJSONObject("outputTranscription")?.optString("text")?.let { said.append(it) }
            val parts = sc.optJSONObject("modelTurn")?.optJSONArray("parts")
            if (parts != null && first < 0) {
                first = System.currentTimeMillis() - sentAt
                onAudio?.invoke(first)
            }
            if (sc.optBoolean("interrupted")) interrupted = true
            if (sc.optBoolean("turnComplete")) break
        }
        return Turn(heard.toString(), said.toString(), first, tools, interrupted, null)
    }

    private fun ask(s: Session, label: String, speech: ByteArray): Turn {
        stream(s, speech)
        stream(s, silence(1500))
        val t = collect(s, System.currentTimeMillis())
        log("  [$label]")
        log("    শুনেছে : ${t.heard.trim()}")
        log("    বলেছে  : ${t.said.trim().take(220)}")
        log("    উত্তর শুরু: ${if (t.firstAudioMs >= 0) "${t.firstAudioMs} ms" else "আসেনি"}" +
            (if (t.tools.isNotEmpty()) "   টুল: ${t.tools}" else "") + (t.closed?.let { "   বন্ধ: $it" } ?: ""))
        return t
    }

    @Test fun liveConversation() {
        assumeTrue("Set VIBE_TEST_KEY to run the live test", key != null)
        Prefs.initForTest(MemPrefs(), MemPrefs())
        log("Gemini Live test — $model")

        log("কথা বানাচ্ছি (TTS)…")
        val q1 = say("আজকে ঢাকায় অনেক গরম পড়েছে। গরমে সুস্থ থাকার দুইটা সহজ উপায় বলো তো।")
        val q2 = say("রহিমকে একটা কল দাও।")
        val q3 = say("আমাকে একটা লম্বা রূপকথার গল্প বলো, অনেক বিস্তারিত করে।")
        val cut = say("থামো থামো! আরেকটা কথা শোনো।")

        for ((name, prompt, tools) in listOf(
            Triple("শুধু কথা (ভার্সন ১)", Prompt.v01(), JSONArray()),
            Triple("পুরো অ্যাপ (টুলসহ)", Prompt.build(locked = false, canControlScreen = true), ToolSpecs.all(true)),
        )) {
            log("\n=== $name ===")
            val s = try {
                open(prompt, tools)
            } catch (e: Exception) {
                log("  সংযোগ হয়নি: ${e.message}")
                continue
            }
            ask(s, "সাধারণ প্রশ্ন", q1)
            if (tools.length() > 0) ask(s, "কল করতে বলা", q2)

            // Barge-in: start a long story, talk over it 1.5 s after it starts.
            stream(s, q3)
            stream(s, silence(1500))
            var cutSent = false
            val t = collect(s, System.currentTimeMillis(), 25) {
                Thread {
                    Thread.sleep(1500)
                    stream(s, cut)
                    stream(s, silence(1500))
                }.start()
                cutSent = true
            }
            log("  [মাঝপথে থামানো]")
            log("    গল্প শুরু: ${if (t.firstAudioMs >= 0) "${t.firstAudioMs} ms" else "আসেনি"}; মাঝে কথা বলা হয়েছে: $cutSent")
            log("    সার্ভার থেমেছে (interrupted): ${if (t.interrupted) "হ্যাঁ ✓" else "না ✗"}")
            if (t.interrupted) {
                val after = collect(s, System.currentTimeMillis(), 20)
                log("    থামার পর শুনেছে: ${after.heard.trim()} / বলেছে: ${after.said.trim().take(160)}")
            }
            s.ws.close(1000, "done")
        }
        File("build").mkdirs()
        File("build/live_report.txt").writeText(report.toString())
    }
}
