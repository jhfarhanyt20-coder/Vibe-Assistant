package com.vibe.assistant

import com.vibe.assistant.agent.GeminiText
import com.vibe.assistant.agent.OpenAiCompat
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test

/** The OpenAI-compatible screen-agent backend, checked without network. */
class OpenAiCompatTest {

    @Test fun baseUrlIsNormalized() {
        assertEquals("https://apihub.agnes-ai.com/v1", OpenAiCompat.normalizeBase("https://apihub.agnes-ai.com/v1/"))
        assertEquals("https://apihub.agnes-ai.com/v1", OpenAiCompat.normalizeBase(" https://apihub.agnes-ai.com/v1/chat/completions "))
        assertEquals("https://openrouter.ai/api/v1", OpenAiCompat.normalizeBase("openrouter.ai/api/v1"))
    }

    @Test fun geminiTurnsBecomeOpenAiMessages() {
        val turns = listOf(
            GeminiText.userTurn("tap send", "QUJD"),
            GeminiText.modelTurn("{\"action\":\"tap\"}"),
            GeminiText.userTurn("next"),
        )
        val m = OpenAiCompat.toMessages("SYS", turns)
        assertEquals(4, m.length())
        assertEquals("system", m.getJSONObject(0).getString("role"))
        val first = m.getJSONObject(1).getJSONArray("content")
        assertEquals("text", first.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,QUJD",
            first.getJSONObject(1).getJSONObject("image_url").getString("url"))
        assertEquals("assistant", m.getJSONObject(2).getString("role"))
        assertEquals("{\"action\":\"tap\"}", m.getJSONObject(2).getString("content"))
        assertEquals("next", m.getJSONObject(3).getString("content"))
    }

    @Test fun replyAndJsonAreExtracted() {
        val body = """{"choices":[{"message":{"role":"assistant","content":"<think>hmm</think>```json\n{\"action\":\"done\"}\n```"}}]}"""
        val text = OpenAiCompat.replyText(body)
        assertEquals("done", GeminiText.parse(text).first.getString("action"))
        val parts = """{"choices":[{"message":{"content":[{"type":"text","text":"{\"a\":1}"}]}}]}"""
        assertEquals("{\"a\":1}", OpenAiCompat.replyText(parts))
    }

    @Test fun modelListIsParsed() {
        val ids = OpenAiCompat.parseModels("""{"object":"list","data":[{"id":"gpt-4o"},{"id":"gemini-2.5-flash"},{"id":"gpt-4o"}]}""")
        assertEquals(listOf("gemini-2.5-flash", "gpt-4o"), ids)
        assertEquals(listOf("a", "b"), OpenAiCompat.parseModels(JSONArray(listOf("b", "a")).toString()))
    }
}
