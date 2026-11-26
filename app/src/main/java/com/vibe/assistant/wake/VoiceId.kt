package com.vibe.assistant.wake

import android.content.Context
import com.vibe.assistant.audio.pcmLevel
import com.vibe.assistant.data.Prefs
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.sqrt

/**
 * Who is speaking. Vosk's speaker model turns each utterance into a voice
 * fingerprint (an x-vector); the owner's fingerprint is the average of a few
 * enrollment phrases, and any new utterance is compared to it by cosine similarity.
 */
object VoiceId {
    fun parse(o: JSONObject): FloatArray? {
        val arr = o.optJSONArray("spk") ?: return null
        if (arr.length() < 32) return null
        return FloatArray(arr.length()) { arr.optDouble(it).toFloat() }
    }

    fun cosine(a: FloatArray, b: FloatArray): Float {
        if (a.size != b.size) return -1f
        var dot = 0.0
        var na = 0.0
        var nb = 0.0
        for (i in a.indices) {
            dot += a[i] * b[i]
            na += a[i] * a[i]
            nb += b[i] * b[i]
        }
        if (na == 0.0 || nb == 0.0) return -1f
        return (dot / (sqrt(na) * sqrt(nb))).toFloat()
    }

    /** Mean of unit vectors — the enrolled owner fingerprint. */
    fun average(vs: List<FloatArray>): FloatArray {
        val out = FloatArray(vs.first().size)
        for (v in vs) {
            val n = sqrt(v.fold(0.0) { s, x -> s + x * x }).toFloat().takeIf { it > 0f } ?: 1f
            for (i in v.indices) out[i] += v[i] / n
        }
        for (i in out.indices) out[i] /= vs.size
        return out
    }

    fun threshold(): Float = when (Prefs.voiceLevel) {
        0 -> 0.30f
        2 -> 0.55f
        else -> 0.42f
    }

    fun percent(score: Float): String = "${(score.coerceIn(0f, 1f) * 100).toInt()}% মিল"

    /** Ready to gate the conversation: models downloaded and an owner enrolled. */
    fun canGate(ctx: Context) = Prefs.voiceLock && Prefs.ownerVoice != null &&
        VoskModels.ready(ctx, VoskModels.WAKE) && VoskModels.ready(ctx, VoskModels.SPEAKER)
}

/**
 * Voice Lock during the conversation. Every mic chunk goes through Vosk on the
 * mic thread; audio is held until the utterance ends, then — only if the voice
 * is the owner's — handed on as one turn. Other people (and Vibe's own voice
 * leaking from the speaker) are dropped and never reach Gemini.
 *
 * The cost: Gemini hears you about half a second after you stop talking,
 * instead of while you talk.
 */
class VoiceGate(
    ctx: Context,
    private val owner: FloatArray,
    private val onOwner: (List<ByteArray>, Float) -> Unit,
    private val onOther: (Float) -> Unit,
) {
    private val rec: Recognizer = Recognizer(VoskModels.model(ctx), 16000f, VoskModels.GRAMMAR).apply {
        VoskModels.speaker(ctx)?.let { setSpeakerModel(it) }
    }
    private val buf = ArrayList<ByteArray>()

    /** Mic thread only. */
    fun feed(chunk: ByteArray) {
        buf.add(chunk)
        if (buf.size > 400) buf.removeAt(0) // ~20 s cap
        val ended = try {
            rec.acceptWaveForm(chunk, chunk.size)
        } catch (_: Exception) {
            false
        }
        if (ended) {
            val o = try { JSONObject(rec.result) } catch (_: Exception) { null }
            val chunks = trim(ArrayList(buf))
            buf.clear()
            val vec = o?.let { VoiceId.parse(it) } ?: return // silence or too short to judge
            val score = VoiceId.cosine(vec, owner)
            if (score >= VoiceId.threshold()) onOwner(chunks, score) else onOther(score)
        }
    }

    /** Drop the long silence before speech, keeping ~300 ms of lead-in. */
    private fun trim(chunks: List<ByteArray>): List<ByteArray> {
        val first = chunks.indexOfFirst { pcmLevel(it, it.size) > 0.06f }
        if (first < 0) return emptyList()
        return chunks.subList(maxOf(0, first - 6), chunks.size)
    }

    fun close() {
        try { rec.close() } catch (_: Exception) {}
        buf.clear()
    }
}
