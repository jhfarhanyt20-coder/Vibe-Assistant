package com.vibe.assistant.agent

import com.vibe.assistant.data.Http
import com.vibe.assistant.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * One-shot (non-Live) Gemini calls that must return JSON — the screen agent's brain.
 * Walks a short ladder of text models, like Mark-LVII does, and remembers the one
 * that answered so the next call goes straight to it.
 */
object GeminiText {
    // Fastest first: Mark-LVII measured the lite models answering in ~0.6 s
    // where the full flash models took 2-6 s. Tapping a button does not need more.
    private val LADDER = listOf(
        "gemini-3.5-flash-lite",
        "gemini-flash-lite-latest",
        "gemini-flash-latest",
        "gemini-3.5-flash",
        "gemini-2.5-flash-lite",
        "gemini-2.5-flash",
    )
    private val client: OkHttpClient = Http.rest.newBuilder().readTimeout(60, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    class Failure(msg: String) : Exception(msg)

    /** A user turn: text plus an optional screenshot. */
    fun userTurn(text: String, jpegB64: String? = null): JSONObject {
        val parts = JSONArray().put(JSONObject().put("text", text))
        if (jpegB64 != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegB64)))
        }
        return JSONObject().put("role", "user").put("parts", parts)
    }

    fun modelTurn(text: String): JSONObject =
        JSONObject().put("role", "model").put("parts", JSONArray().put(JSONObject().put("text", text)))

    suspend fun json(system: String, user: String, jpegB64: String? = null): JSONObject =
        chat(system, listOf(userTurn(user, jpegB64))).first

    /**
     * A multi-turn exchange (Lucy keeps a whole task in one chat): [turns]
     * alternate user / model. Returns the parsed JSON reply and its raw text,
     * which the caller appends as the next model turn.
     */
    suspend fun chat(system: String, turns: List<JSONObject>): Pair<JSONObject, String> {
        // The user's own OpenAI-compatible model does the screen work when it is set up;
        // Gemini text models stay as the fallback if it fails and a Gemini key exists.
        if (Prefs.customAgentReady) {
            try {
                val raw = OpenAiCompat.chat(Prefs.agentBaseUrl, Prefs.agentKey!!, Prefs.agentModelName, system, turns)
                return parse(raw)
            } catch (e: Failure) {
                if (Prefs.apiKey == null) throw e
                com.vibe.assistant.core.VibeBus.event("কাজের AI ব্যর্থ (${e.message}) — Gemini দিয়ে চেষ্টা")
            }
        }
        return geminiChat(system, turns)
    }

    /** Pulls the JSON object out of a model reply (code fences, prose around it). */
    fun parse(out: String): Pair<JSONObject, String> {
        val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = cleaned.indexOf('{')
        val end = cleaned.lastIndexOf('}')
        val obj = try {
            JSONObject(if (start >= 0 && end > start) cleaned.substring(start, end + 1) else cleaned)
        } catch (e: Exception) {
            throw Failure("মডেলের উত্তর বোঝা গেল না")
        }
        return obj to cleaned
    }

    private suspend fun geminiChat(system: String, turns: List<JSONObject>): Pair<JSONObject, String> = withContext(Dispatchers.IO) {
        val key = Prefs.apiKey ?: throw Failure("API key নেই")
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray(turns))
            .put("generationConfig", JSONObject()
                .put("responseMimeType", "application/json")
                .put("temperature", 0.2))
            .toString()

        var last = "কোনো মডেল উত্তর দেয়নি"
        for (m in (listOfNotNull(Prefs.agentModel) + LADDER).distinct()) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toRequestBody(JSON))
                .build()
            val (code, text) = try {
                client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
            } catch (e: Exception) {
                throw Failure("ইন্টারনেট সমস্যা: ${e.message}")
            }
            if (code == 404 || code == 429 || code >= 500) {
                last = "$m: $code"
                if (Prefs.agentModel == m) Prefs.agentModel = null
                continue
            }
            if (code !in 200..299) throw Failure("Gemini ভুল জানাল ($code)")
            val out = JSONObject(text).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts")
                ?.let { p -> (0 until p.length()).joinToString("") { p.optJSONObject(it)?.optString("text").orEmpty() } }
                ?: throw Failure("খালি উত্তর")
            Prefs.agentModel = m
            return@withContext parse(out)
        }
        throw Failure(last)
    }
}
