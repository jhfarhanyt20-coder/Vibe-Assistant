package com.vibe.assistant.audio

import kotlin.math.max
import kotlin.math.sqrt

/**
 * "Only the closest voice": lets through only sound that is clearly louder than
 * the room, which in practice is the person holding the phone. A voice across the
 * room, a TV, traffic or a fan sit near the background level and never reach
 * Gemini — while the gate is shut it sends SILENCE instead (so Gemini's own
 * turn-taking still sees time pass and answers when you stop).
 *
 *  - The background level ([floor]) is learned continuously from the quiet parts.
 *  - The gate opens when a chunk is [ratio]× louder than the background (and above
 *    an absolute minimum), and stays open [hangMs] after the voice drops, so word
 *    endings and short pauses are not cut.
 *  - When it opens, the last [prerollChunks] chunks are sent too, so the first
 *    syllable isn't clipped.
 *
 * Pure Kotlin, no Android — tested on the PC.
 */
class NearVoiceGate(strong: Boolean = false) {
    private val ratio = if (strong) 4.0f else 2.6f
    private val minRms = if (strong) 900f else 550f
    private val hangMs = 500L
    private val prerollChunks = 5

    /** Learned background loudness (RMS of 16-bit PCM). */
    var floor = 250f
        private set
    private var seen = 0
    /** Until when the gate stays open; Long.MIN_VALUE = never opened yet (shut from the start). */
    private var openUntil = Long.MIN_VALUE
    private val preroll = ArrayDeque<ByteArray>()

    /** Returns what to send for this chunk: the real audio (plus pre-roll) or silence. */
    fun process(chunk: ByteArray, nowMs: Long): List<ByteArray> {
        val r = rms(chunk)
        val threshold = max(minRms, floor * ratio)
        val loud = r > threshold
        if (loud) openUntil = nowMs + hangMs
        val open = openUntil != Long.MIN_VALUE && nowMs <= openUntil
        if (!open) {
            // Learn the room only while nobody near is speaking. Fast at first, then slow.
            val a = if (seen < 20) 0.25f else 0.04f
            floor = (floor * (1 - a) + r * a).coerceIn(60f, 5000f)
            seen++
            preroll.addLast(chunk)
            while (preroll.size > prerollChunks) preroll.removeFirst()
            return listOf(ByteArray(chunk.size))
        }
        val out = ArrayList<ByteArray>(preroll.size + 1)
        out.addAll(preroll)
        preroll.clear()
        out.add(chunk)
        return out
    }

    companion object {
        fun rms(b: ByteArray, n: Int = b.size): Float {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < n) {
                val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
                sum += (s * s).toDouble()
                count++
                i += 2
            }
            return if (count == 0) 0f else sqrt(sum / count).toFloat()
        }
    }
}
