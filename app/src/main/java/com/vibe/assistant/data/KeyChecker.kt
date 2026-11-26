package com.vibe.assistant.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

/**
 * Checks a Gemini key before it is saved, and finds which Live models it can use.
 * The key goes in a header, never in the URL.
 */
object KeyChecker {
    sealed class Result {
        data class Ok(val liveModels: List<String>) : Result()
        data class Bad(val message: String) : Result()
    }

    private const val BASE = "https://generativelanguage.googleapis.com/v1beta/models"
    private val NOT_ASSISTANTS = listOf("transcribe", "translate", "robotic", "tts")

    suspend fun check(key: String): Result = withContext(Dispatchers.IO) {
        val k = key.trim()
        if (k.length < 20) return@withContext Result.Bad("এটা ঠিক API key মনে হচ্ছে না। পুরোটা কপি করে দাও।")
        val found = mutableListOf<String>()
        var pageToken: String? = null
        try {
            for (page in 0 until 5) {
                val url = BASE + "?pageSize=1000" + (pageToken?.let { "&pageToken=$it" } ?: "")
                val req = Request.Builder().url(url).header("x-goog-api-key", k).build()
                Http.rest.newCall(req).execute().use { r ->
                    val body = r.body?.string().orEmpty()
                    when {
                        r.code == 429 -> return@withContext Result.Ok(orderModels(found))
                        r.code == 400 || r.code == 401 || r.code == 403 ->
                            return@withContext Result.Bad(
                                if ("API_KEY_INVALID" in body || "not valid" in body)
                                    "এই API key কাজ করছে না। AI Studio থেকে আবার কপি করো।"
                                else "Google এই key গ্রহণ করছে না (${r.code})।"
                            )
                        !r.isSuccessful -> return@withContext Result.Bad("Google সার্ভার সমস্যা (${r.code})। একটু পরে চেষ্টা করো।")
                    }
                    val json = JSONObject(body)
                    val models = json.optJSONArray("models")
                    if (models != null) for (i in 0 until models.length()) {
                        val m = models.optJSONObject(i) ?: continue
                        val name = m.optString("name")
                        val methods = m.optJSONArray("supportedGenerationMethods")?.toString().orEmpty()
                        val lower = name.lowercase()
                        if ("bidiGenerateContent" in methods &&
                            ("live" in lower || "native-audio" in lower) &&
                            NOT_ASSISTANTS.none { it in lower }
                        ) found += name
                    }
                    pageToken = json.optString("nextPageToken").takeIf { it.isNotEmpty() }
                }
                if (pageToken == null) break
            }
        } catch (e: IOException) {
            return@withContext Result.Bad("ইন্টারনেট সংযোগ পাওয়া যাচ্ছে না। নেট চালু করে আবার চেষ্টা করো।")
        } catch (e: Exception) {
            return@withContext Result.Bad("যাচাই করা গেল না: ${e.message}")
        }
        Result.Ok(orderModels(found))
    }

    /** Known good models first (in our order), then anything new the key offers. */
    private fun orderModels(found: List<String>): List<String> {
        if (found.isEmpty()) return LIVE_MODELS.map { it.id }
        val known = LIVE_MODELS.map { it.id }.filter { it in found }
        return known + found.filter { it !in known }.sorted()
    }
}
