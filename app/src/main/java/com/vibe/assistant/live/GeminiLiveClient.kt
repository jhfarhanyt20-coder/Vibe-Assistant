package com.vibe.assistant.live

import android.util.Base64
import android.util.Log
import com.vibe.assistant.data.Http
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject

/**
 * One Gemini Live session over a raw WebSocket — the same BidiGenerateContent
 * protocol the google-genai SDK (and so Mark-LVII) speaks:
 *
 *   -> setup            model, voice, prompt, tools, transcription, compression, resumption
 *   <- setupComplete
 *   -> realtimeInput    16 kHz mono PCM16 from the mic, ~50 ms at a time
 *                       (+ activityStart / activityEnd in push-to-talk / voice-lock mode)
 *   <- serverContent    24 kHz PCM16 reply audio, transcripts, `interrupted`, `turnComplete`
 *   <- toolCall         Gemini wants the phone to do something
 *   -> toolResponse     what happened
 *   <- sessionResumptionUpdate / goAway
 *
 * Callbacks arrive on OkHttp's thread.
 */
class GeminiLiveClient(
    private val apiKey: String,
    val model: String,
    private val voice: String,
    private val systemPrompt: String,
    private val resumeHandle: String?,
    private val listener: Listener,
    /** Fallback: some networks/front-ends only accept the key as ?key= on the socket URL. */
    val keyInUrl: Boolean = false,
    /** Function declarations (JSON objects) the model may call. */
    private val tools: JSONArray = JSONArray(),
    val googleSearch: Boolean = false,
    /** When true the server does not detect speech; the app marks turns with activityStart/End. */
    val manualTurns: Boolean = false,
    /** Shorter end-of-speech wait. Off: it cut sentences short; the server default is better. */
    val quickTurns: Boolean = false,
) {
    data class ToolCall(val id: String, val name: String, val args: JSONObject)

    interface Listener {
        fun onReady(c: GeminiLiveClient)
        fun onAudio(c: GeminiLiveClient, pcm: ByteArray)
        fun onInterrupted(c: GeminiLiveClient)
        fun onTurnComplete(c: GeminiLiveClient)
        fun onInputText(c: GeminiLiveClient, text: String)
        fun onOutputText(c: GeminiLiveClient, text: String)
        fun onToolCalls(c: GeminiLiveClient, calls: List<ToolCall>)
        fun onToolCancel(c: GeminiLiveClient, ids: List<String>)
        fun onResumeHandle(c: GeminiLiveClient, handle: String)
        fun onGoAway(c: GeminiLiveClient)
        fun onClosed(c: GeminiLiveClient, code: Int, reason: String, wasReady: Boolean)
    }

    @Volatile var ready = false
        private set

    @Volatile private var closedByUs = false
    @Volatile private var finished = false
    private var ws: WebSocket? = null

    fun connect() {
        val req = if (keyInUrl) {
            Request.Builder().url(URL + "?key=" + apiKey).build()
        } else {
            Request.Builder().url(URL).header("x-goog-api-key", apiKey).build()
        }
        ws = Http.ws.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(setupJson())
            }

            override fun onMessage(webSocket: WebSocket, text: String) = handle(text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = handle(bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                finish(code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) =
                finish(code, reason)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val body = try {
                    response?.body?.string()
                } catch (_: Exception) {
                    null
                }
                val code = response?.code ?: -1
                finish(code, listOfNotNull(t.javaClass.simpleName + ": " + t.message, body).joinToString(" "))
            }
        })
    }

    fun sendAudio(pcm: ByteArray) {
        if (!ready) return
        val b64 = Base64.encodeToString(pcm, Base64.NO_WRAP)
        ws?.send("{\"realtimeInput\":{\"audio\":{\"mimeType\":\"audio/pcm;rate=16000\",\"data\":\"$b64\"}}}")
    }

    /** Push-to-talk / voice-lock: "the user started talking". Also interrupts a reply. */
    fun activityStart() {
        if (ready) ws?.send("{\"realtimeInput\":{\"activityStart\":{}}}")
    }

    /** "The user finished talking — answer now." */
    fun activityEnd() {
        if (ready) ws?.send("{\"realtimeInput\":{\"activityEnd\":{}}}")
    }

    /** One picture (a screenshot) into the session, so the model can see the screen. */
    fun sendImage(jpegB64: String): Boolean {
        if (!ready) return false
        return ws?.send("{\"realtimeInput\":{\"video\":{\"mimeType\":\"image/jpeg\",\"data\":\"$jpegB64\"}}}") == true
    }

    /** A text turn from the app itself, e.g. "an incoming call from Rahim". */
    fun sendText(text: String) {
        if (!ready) return
        val msg = JSONObject().put(
            "clientContent", JSONObject()
                .put("turns", JSONArray().put(
                    JSONObject().put("role", "user")
                        .put("parts", JSONArray().put(JSONObject().put("text", text)))
                ))
                .put("turnComplete", true)
        )
        ws?.send(msg.toString())
    }

    fun sendToolResponse(id: String, name: String, result: JSONObject) {
        val msg = JSONObject().put(
            "toolResponse", JSONObject().put(
                "functionResponses", JSONArray().put(
                    JSONObject().put("id", id).put("name", name).put("response", result)
                )
            )
        )
        ws?.send(msg.toString())
    }

    fun close() {
        closedByUs = true
        ready = false
        ws?.close(1000, "bye")
        ws = null
    }

    private fun finish(code: Int, reason: String) {
        if (finished) return
        finished = true
        val wasReady = ready
        ready = false
        Log.i(TAG, "closed code=$code reason=$reason byUs=$closedByUs")
        if (!closedByUs) listener.onClosed(this, code, reason, wasReady)
    }

    private fun setupJson(): String {
        val gen = JSONObject()
            .put("responseModalities", JSONArray().put("AUDIO"))
            .put(
                "speechConfig", JSONObject().put(
                    "voiceConfig", JSONObject().put(
                        "prebuiltVoiceConfig", JSONObject().put("voiceName", voice)
                    )
                )
            )
        val toolList = JSONArray()
        if (tools.length() > 0) toolList.put(JSONObject().put("functionDeclarations", tools))
        if (googleSearch) toolList.put(JSONObject().put("googleSearch", JSONObject()))

        val setup = JSONObject()
            .put("model", model)
            .put("generationConfig", gen)
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", systemPrompt))))
            .put("inputAudioTranscription", JSONObject())
            .put("outputAudioTranscription", JSONObject())
            // Never die from a full context window: the oldest turns slide out.
            .put("contextWindowCompression", JSONObject().put("slidingWindow", JSONObject()))
            // Ask for resumption handles; reuse one so a reconnect keeps the conversation.
            .put("sessionResumption", JSONObject().apply { resumeHandle?.let { put("handle", it) } })
        if (toolList.length() > 0) setup.put("tools", toolList)
        if (manualTurns) {
            setup.put(
                "realtimeInputConfig", JSONObject().put(
                    "automaticActivityDetection", JSONObject().put("disabled", true)
                )
            )
        } else if (quickTurns) {
            // Decide sooner that you have finished speaking, so the answer starts sooner.
            setup.put(
                "realtimeInputConfig", JSONObject().put(
                    "automaticActivityDetection", JSONObject()
                        .put("endOfSpeechSensitivity", "END_SENSITIVITY_HIGH")
                        .put("prefixPaddingMs", 150)
                        .put("silenceDurationMs", 550)
                )
            )
        }
        return JSONObject().put("setup", setup).toString()
    }

    private fun handle(text: String) {
        val o = try {
            JSONObject(text)
        } catch (e: Exception) {
            return
        }

        if (o.has("setupComplete")) {
            ready = true
            listener.onReady(this)
        }

        o.optJSONObject("serverContent")?.let { sc ->
            sc.optJSONObject("modelTurn")?.optJSONArray("parts")?.let { parts ->
                for (i in 0 until parts.length()) {
                    val data = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                    if (!data.isNullOrEmpty()) {
                        listener.onAudio(this, Base64.decode(data, Base64.DEFAULT))
                    }
                }
            }
            sc.optJSONObject("inputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let { listener.onInputText(this, it) }
            sc.optJSONObject("outputTranscription")?.optString("text")
                ?.takeIf { it.isNotEmpty() }?.let { listener.onOutputText(this, it) }
            if (sc.optBoolean("interrupted")) listener.onInterrupted(this)
            if (sc.optBoolean("turnComplete")) listener.onTurnComplete(this)
        }

        o.optJSONObject("toolCall")?.optJSONArray("functionCalls")?.let { arr ->
            val calls = (0 until arr.length()).mapNotNull { i ->
                val f = arr.optJSONObject(i) ?: return@mapNotNull null
                ToolCall(f.optString("id"), f.optString("name"), f.optJSONObject("args") ?: JSONObject())
            }
            if (calls.isNotEmpty()) listener.onToolCalls(this, calls)
        }

        o.optJSONObject("toolCallCancellation")?.optJSONArray("ids")?.let { arr ->
            listener.onToolCancel(this, (0 until arr.length()).map { arr.optString(it) })
        }

        o.optJSONObject("sessionResumptionUpdate")?.let { u ->
            val h = u.optString("newHandle")
            if (u.optBoolean("resumable") && h.isNotEmpty()) listener.onResumeHandle(this, h)
        }

        if (o.has("goAway")) listener.onGoAway(this)
    }

    companion object {
        private const val TAG = "VibeLive"
        private const val URL =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
    }
}
