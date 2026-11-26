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

    suspend fun json(system: String, user: String, jpegB64: String? = null): JSONObject = withContext(Dispatchers.IO) {
        val key = Prefs.apiKey ?: throw Failure("API key নেই")
        val parts = JSONArray().put(JSONObject().put("text", user))
        if (jpegB64 != null) {
            parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", jpegB64)))
        }
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
            .put("contents", JSONArray().put(JSONObject().put("role", "user").put("parts", parts)))
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
            val cleaned = out.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
            return@withContext JSONObject(cleaned)
        }
        throw Failure(last)
    }
}
