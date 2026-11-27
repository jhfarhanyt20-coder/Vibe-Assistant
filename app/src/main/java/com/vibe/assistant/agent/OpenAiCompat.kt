package com.vibe.assistant.agent

import com.vibe.assistant.data.Http
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
 * Any OpenAI-compatible provider (Agnes, OpenRouter, Groq, …) as the screen agent's
 * brain. Gemini Live keeps the voice; this model reads the screenshots and decides
 * the taps. The user gives a base URL (…/v1), a key and a model that accepts images.
 */
object OpenAiCompat {
    private val client: OkHttpClient = Http.rest.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    /** "https://x.com/v1/", "https://x.com/v1/chat/completions" → "https://x.com/v1". */
    fun normalizeBase(url: String): String {
        var u = url.trim().trimEnd('/')
        for (suffix in listOf("/chat/completions", "/models", "/completions")) {
            if (u.endsWith(suffix)) u = u.removeSuffix(suffix)
        }
        if (u.isNotEmpty() && !u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        return u.trimEnd('/')
    }

    /** Gemini-style turns (role user/model, parts text/inlineData) → OpenAI chat messages. */
    fun toMessages(system: String, turns: List<JSONObject>): JSONArray {
        val out = JSONArray().put(JSONObject().put("role", "system").put("content", system))
        for (t in turns) {
            val role = if (t.optString("role") == "model") "assistant" else "user"
            val parts = t.optJSONArray("parts") ?: JSONArray()
            val texts = StringBuilder()
            val images = mutableListOf<String>()
            for (i in 0 until parts.length()) {
                val p = parts.optJSONObject(i) ?: continue
                p.optString("text").takeIf { it.isNotEmpty() }?.let {
                    if (texts.isNotEmpty()) texts.append('\n')
                    texts.append(it)
                }
                p.optJSONObject("inlineData")?.let { d ->
                    images += "data:${d.optString("mimeType", "image/jpeg")};base64,${d.optString("data")}"
                }
            }
            if (role == "assistant" || images.isEmpty()) {
                out.put(JSONObject().put("role", role).put("content", texts.toString()))
            } else {
                val content = JSONArray()
                if (texts.isNotEmpty()) content.put(JSONObject().put("type", "text").put("text", texts.toString()))
                for (img in images) {
                    content.put(JSONObject().put("type", "image_url").put("image_url", JSONObject().put("url", img)))
                }
                out.put(JSONObject().put("role", role).put("content", content))
            }
        }
        return out
    }

    /** The reply text of a chat completion (content may be a string or a list of parts). */
    fun replyText(body: String): String {
        val msg = JSONObject(body).optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")
            ?: throw GeminiText.Failure("খালি উত্তর")
        val c = msg.opt("content")
        val text = when (c) {
            is String -> c
            is JSONArray -> (0 until c.length()).joinToString("") { c.optJSONObject(it)?.optString("text").orEmpty() }
            else -> ""
        }
        // Reasoning models sometimes put their thinking inline.
        return text.replace(Regex("(?s)<think>.*?</think>"), "").trim()
    }

    suspend fun chat(base: String, key: String, model: String, system: String, turns: List<JSONObject>): String =
        withContext(Dispatchers.IO) {
            val messages = toMessages(system, turns)
            fun body(jsonMode: Boolean) = JSONObject()
                .put("model", model)
                .put("messages", messages)
                .put("temperature", 0.2)
                .apply { if (jsonMode) put("response_format", JSONObject().put("type", "json_object")) }
                .toString()

            var jsonMode = true
            repeat(2) {
                val req = Request.Builder()
                    .url("${normalizeBase(base)}/chat/completions")
                    .header("Authorization", "Bearer $key")
                    .post(body(jsonMode).toRequestBody(JSON))
                    .build()
                val (code, text) = try {
                    client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
                } catch (e: Exception) {
                    throw GeminiText.Failure("ইন্টারনেট সমস্যা: ${e.message}")
                }
                if (code in 200..299) return@withContext replyText(text)
                // Some providers don't know response_format: try once more without it.
                if (code == 400 && jsonMode) {
                    jsonMode = false
                    return@repeat
                }
                throw GeminiText.Failure("$model: $code ${errorMessage(text)}".trim())
            }
            throw GeminiText.Failure("$model: উত্তর দেয়নি")
        }

    /** Model ids the provider offers (GET /models), sorted. */
    suspend fun listModels(base: String, key: String): List<String> = withContext(Dispatchers.IO) {
        val req = Request.Builder()
            .url("${normalizeBase(base)}/models")
            .header("Authorization", "Bearer $key")
            .get()
            .build()
        val (code, text) = try {
            client.newCall(req).execute().use { r -> r.code to r.body?.string().orEmpty() }
        } catch (e: Exception) {
            throw GeminiText.Failure("ইন্টারনেট সমস্যা: ${e.message}")
        }
        if (code !in 200..299) throw GeminiText.Failure("মডেল তালিকা আসেনি ($code) ${errorMessage(text)}".trim())
        parseModels(text)
    }

    fun parseModels(body: String): List<String> {
        val root = body.trim()
        val arr = if (root.startsWith("[")) JSONArray(root)
        else JSONObject(root).let { it.optJSONArray("data") ?: it.optJSONArray("models") ?: JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            when (val v = arr.opt(i)) {
                is JSONObject -> v.optString("id").ifEmpty { v.optString("name") }.ifEmpty { null }
                is String -> v
                else -> null
            }
        }.distinct().sorted()
    }

    private fun errorMessage(body: String): String = try {
        val o = JSONObject(body)
        (o.optJSONObject("error")?.optString("message") ?: o.optString("message")).take(160)
    } catch (e: Exception) {
        body.take(120)
    }
}
