package com.vibe.assistant.tools

import android.content.Context
import android.content.Intent
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Base64
import androidx.core.content.FileProvider
import com.vibe.assistant.data.Http
import com.vibe.assistant.data.Prefs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * A voice message in Vibe's own voice: Gemini's speech model reads the text with
 * the same prebuilt voice Vibe talks in, the audio is packed into an .m4a, and the
 * file is handed to WhatsApp (straight into the right chat) or any other app.
 */
object VoiceNote {
    private val client = Http.rest.newBuilder().readTimeout(90, TimeUnit.SECONDS).build()
    private val JSON = "application/json".toMediaType()

    private val TTS_MODELS = listOf(
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts",
    )
    @Volatile private var discovered: List<String>? = null

    class Failure(msg: String) : Exception(msg)

    /** Speech-capable models this key offers, newest first, falling back to the known ones. */
    private fun ttsModels(key: String): List<String> {
        discovered?.let { return it }
        val found = try {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models?pageSize=1000")
                .header("x-goog-api-key", key).build()
            client.newCall(req).execute().use { r ->
                val arr = JSONObject(r.body?.string().orEmpty()).optJSONArray("models") ?: JSONArray()
                (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.optString("name") }
                    .filter { "tts" in it.lowercase() }
                    .map { it.removePrefix("models/") }
                    .sortedDescending()
                    .sortedBy { if ("flash" in it) 0 else 1 }
            }
        } catch (_: Exception) {
            emptyList()
        }
        return (found + TTS_MODELS).distinct().also { discovered = it }
    }

    /** Reads the text aloud with Gemini and returns 24 kHz mono PCM16. */
    suspend fun speak(text: String): ByteArray = withContext(Dispatchers.IO) {
        val key = Prefs.apiKey ?: throw Failure("API key নেই")
        val body = JSONObject()
            .put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(
                JSONObject().put("text", "Say this naturally and warmly, like a friendly voice message:\n$text")
            ))))
            .put("generationConfig", JSONObject()
                .put("responseModalities", JSONArray().put("AUDIO"))
                .put("speechConfig", JSONObject().put("voiceConfig", JSONObject().put(
                    "prebuiltVoiceConfig", JSONObject().put("voiceName", Prefs.voice)
                ))))
            .toString()
        var last = "কোনো কণ্ঠ-মডেল উত্তর দেয়নি"
        for (m in ttsModels(key)) {
            val req = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
                .header("x-goog-api-key", key)
                .post(body.toRequestBody(JSON))
                .build()
            val (code, resp) = try {
                client.newCall(req).execute().use { it.code to it.body?.string().orEmpty() }
            } catch (e: Exception) {
                throw Failure("ইন্টারনেট সমস্যা: ${e.message}")
            }
            if (code == 404 || code == 429 || code >= 500) {
                last = "$m: $code"
                continue
            }
            if (code !in 200..299) throw Failure("Gemini ভুল জানাল ($code)")
            val parts = JSONObject(resp).optJSONArray("candidates")?.optJSONObject(0)
                ?.optJSONObject("content")?.optJSONArray("parts") ?: throw Failure("খালি উত্তর")
            for (i in 0 until parts.length()) {
                val d = parts.optJSONObject(i)?.optJSONObject("inlineData")?.optString("data")
                if (!d.isNullOrEmpty()) return@withContext Base64.decode(d, Base64.DEFAULT)
            }
            throw Failure("কণ্ঠ পাওয়া যায়নি")
        }
        throw Failure(last)
    }

    /** PCM16 mono → AAC in an .m4a container (what chat apps play as audio). */
    fun toM4a(pcm: ByteArray, rate: Int, out: File) {
        val fmt = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, 64_000)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var track = -1
        var started = false
        val info = MediaCodec.BufferInfo()
        var offset = 0
        var inputDone = false
        var pts = 0L
        try {
            while (true) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(10_000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        buf.clear()
                        val n = minOf(buf.capacity(), pcm.size - offset)
                        if (n <= 0) {
                            codec.queueInputBuffer(ii, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buf.put(pcm, offset, n)
                            codec.queueInputBuffer(ii, 0, n, pts, 0)
                            offset += n
                            pts += (n / 2) * 1_000_000L / rate
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(info, 10_000)
                if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                } else if (oi >= 0) {
                    val ob = codec.getOutputBuffer(oi)!!
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0 && started) {
                        ob.position(info.offset)
                        ob.limit(info.offset + info.size)
                        muxer.writeSampleData(track, ob, info)
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            if (started) muxer.stop()
            muxer.release()
        }
    }

    /** Makes the voice-message file and returns it. */
    suspend fun make(ctx: Context, text: String): File = withContext(Dispatchers.Default) {
        val pcm = speak(text)
        val dir = File(ctx.cacheDir, "voice").apply { mkdirs() }
        dir.listFiles()?.forEach { if (System.currentTimeMillis() - it.lastModified() > 86_400_000L) it.delete() }
        val f = File(dir, "Vibe-${System.currentTimeMillis()}.m4a")
        toM4a(pcm, 24_000, f)
        f
    }

    /**
     * Hands the file to the app. For WhatsApp with a number it opens that chat
     * directly; otherwise Android's share sheet lets the user pick.
     */
    fun share(ctx: Context, file: File, pkg: String?, waNumber: String?): Boolean {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        val i = Intent(Intent.ACTION_SEND)
            .setType("audio/mp4")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (pkg != null) {
            i.setPackage(pkg)
            if (waNumber != null && pkg in Messaging.WHATSAPP) i.putExtra("jid", "$waNumber@s.whatsapp.net")
            return DeviceActions.launch(ctx, i)
        }
        return DeviceActions.launch(ctx, Intent.createChooser(i, "Voice message পাঠাও"))
    }
}
