package com.vibe.assistant.core

import kotlinx.coroutines.flow.MutableStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class VState { OFF, WAKE, CONNECTING, LISTENING, SPEAKING, ERROR }

enum class Role { USER, VIBE, ACTION }

data class Line(val role: Role, val text: String)

/**
 * The one place the service publishes what it is doing, and every screen
 * (app, overlay, lock screen, notification) reads from. Writes happen on the
 * main thread; the two audio levels are plain volatiles written ~20x a second.
 */
object VibeBus {
    val state = MutableStateFlow(VState.OFF)
    val status = MutableStateFlow("")
    val lines = MutableStateFlow<List<Line>>(emptyList())

    /** True while push-to-talk is held. */
    val pttHeld = MutableStateFlow(false)

    /** Set to the current time when a tool needs the phone unlocked; LockActivity reacts. */
    val unlockRequestAt = MutableStateFlow(0L)

    /** Connection / voice-lock events, newest last, for the log in Settings. */
    val log = MutableStateFlow<List<String>>(emptyList())

    @Volatile var micLevel = 0f
    @Volatile var outLevel = 0f

    private var turnOpen = false
    private val clock = SimpleDateFormat("HH:mm:ss", Locale.US)

    fun setState(s: VState) {
        state.value = s
    }

    fun event(text: String) {
        val line = clock.format(Date()) + "  " + text
        synchronized(this) { log.value = (log.value + line).takeLast(60) }
    }

    /** Transcripts arrive in small pieces; join pieces of the same turn into one line. */
    fun append(role: Role, chunk: String) {
        if (chunk.isEmpty()) return
        val cur = lines.value
        val last = cur.lastOrNull()
        lines.value = if (turnOpen && last != null && last.role == role) {
            cur.dropLast(1) + last.copy(text = last.text + chunk)
        } else {
            (cur + Line(role, chunk.trimStart())).takeLast(60)
        }
        turnOpen = true
    }

    /** A one-off line such as "📞 calling Rahim". */
    fun action(text: String) {
        lines.value = (lines.value + Line(Role.ACTION, text)).takeLast(60)
        turnOpen = false
    }

    fun closeTurn() {
        turnOpen = false
    }

    fun active(s: VState = state.value) =
        s == VState.CONNECTING || s == VState.LISTENING || s == VState.SPEAKING

    fun label(s: VState): String = when (s) {
        VState.OFF -> "ট্যাপ করে কথা বলো"
        VState.WAKE -> "“হেই Vibe” বলো, বা ট্যাপ করো"
        VState.CONNECTING -> "যুক্ত হচ্ছে…"
        VState.LISTENING -> if (pttHeld.value) "বলো, শুনছি…" else "শুনছি…"
        VState.SPEAKING -> "বলছি…"
        VState.ERROR -> "সমস্যা হয়েছে"
    }
}
