package com.vibe.assistant.audio

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.SystemClock
import com.vibe.assistant.core.VibeBus
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.sqrt

fun pcmLevel(b: ByteArray, n: Int): Float {
    var sum = 0.0
    var count = 0
    var i = 0
    while (i + 1 < n) {
        val s = ((b[i + 1].toInt() shl 8) or (b[i].toInt() and 0xFF)).toShort().toInt()
        sum += (s * s).toDouble()
        count++
        i += 2
    }
    if (count == 0) return 0f
    val rms = sqrt(sum / count).toFloat()
    return ((rms - 60f) / 2600f).coerceIn(0f, 1f)
}

/**
 * Microphone at 16 kHz mono PCM16 — what Gemini Live expects.
 * VOICE_COMMUNICATION + the echo canceller is what lets you talk over Vibe
 * without Vibe hearing its own voice from the speaker.
 */
class Mic(private val onChunk: (ByteArray) -> Unit) {
    @Volatile private var running = false
    private var rec: AudioRecord? = null
    private var thread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null
    private var agc: AutomaticGainControl? = null

    @SuppressLint("MissingPermission")
    fun start(): Boolean {
        val rate = 16000
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val r = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                maxOf(min, rate) // half a second of headroom
            )
        } catch (e: Exception) {
            return false
        }
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release()
            return false
        }
        val sid = r.audioSessionId
        if (AcousticEchoCanceler.isAvailable()) aec = AcousticEchoCanceler.create(sid)?.apply { enabled = true }
        if (NoiseSuppressor.isAvailable()) ns = NoiseSuppressor.create(sid)?.apply { enabled = true }
        if (AutomaticGainControl.isAvailable()) agc = AutomaticGainControl.create(sid)?.apply { enabled = true }

        try {
            r.startRecording()
        } catch (e: Exception) {
            r.release()
            return false
        }
        rec = r
        running = true
        thread = Thread({
            val buf = ByteArray(1600) // 50 ms
            while (running) {
                val n = r.read(buf, 0, buf.size)
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    VibeBus.micLevel = pcmLevel(chunk, n)
                    onChunk(chunk)
                } else if (n < 0) {
                    SystemClock.sleep(20)
                }
            }
        }, "vibe-mic").apply { start() }
        return true
    }

    fun stop() {
        running = false
        try { thread?.join(400) } catch (_: InterruptedException) {}
        thread = null
        rec?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        rec = null
        aec?.release(); ns?.release(); agc?.release()
        aec = null; ns = null; agc = null
        VibeBus.micLevel = 0f
    }
}

/**
 * Speaker at 24 kHz mono PCM16 — what Gemini Live sends back.
 * Audio is fed in ~50 ms slices so an interruption stops the voice almost at once.
 */
class Speaker {
    private val q = LinkedBlockingQueue<ByteArray>()
    @Volatile private var running = false
    @Volatile private var generation = 0
    @Volatile private var lastWriteAt = 0L
    private var track: AudioTrack? = null
    private var thread: Thread? = null

    fun start() {
        val rate = 24000
        val min = AudioTrack.getMinBufferSize(rate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val t = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(rate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(maxOf(min * 2, 9600))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        t.play()
        track = t
        running = true
        thread = Thread({
            while (running) {
                val chunk = try {
                    q.poll(100, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                }
                if (chunk == null) {
                    VibeBus.outLevel = 0f
                    continue
                }
                val gen = generation
                VibeBus.outLevel = pcmLevel(chunk, chunk.size)
                if (gen == generation && running) {
                    t.write(chunk, 0, chunk.size)
                    lastWriteAt = SystemClock.elapsedRealtime()
                }
            }
        }, "vibe-speaker").apply { start() }
    }

    fun enqueue(pcm: ByteArray) {
        val slice = 2400 // 50 ms at 24 kHz
        var i = 0
        while (i < pcm.size) {
            val end = minOf(i + slice, pcm.size)
            q.offer(pcm.copyOfRange(i, end))
            i = end
        }
    }

    /** Drop everything queued — used when you interrupt. */
    fun flush() {
        generation++
        q.clear()
        track?.let {
            try {
                it.pause(); it.flush(); it.play()
            } catch (_: Exception) {}
        }
        lastWriteAt = 0L
        VibeBus.outLevel = 0f
    }

    /** True while audio is queued or still coming out of the speaker. */
    fun isSpeaking(): Boolean =
        q.isNotEmpty() || SystemClock.elapsedRealtime() - lastWriteAt < 350

    fun stop() {
        running = false
        q.clear()
        try { thread?.join(400) } catch (_: InterruptedException) {}
        thread = null
        track?.let {
            try { it.stop() } catch (_: Exception) {}
            it.release()
        }
        track = null
        VibeBus.outLevel = 0f
    }
}

/**
 * Puts the phone in "voice call" audio mode while talking (that is where the
 * hardware echo canceller works), routes to a headset if one is connected or
 * to the loudspeaker otherwise, and asks other apps (music) to pause.
 */
class AudioRoute(ctx: Context) {
    private val am = ctx.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private var active = false

    fun start() {
        if (active) return
        active = true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build()
        focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            .setAudioAttributes(attrs)
            .build()
            .also { am.requestAudioFocus(it) }

        am.mode = AudioManager.MODE_IN_COMMUNICATION
        if (Build.VERSION.SDK_INT >= 31) {
            val devs = am.availableCommunicationDevices
            val headset = devs.firstOrNull { it.type in headsetTypes() }
            val speaker = devs.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            (headset ?: speaker)?.let { am.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            am.isSpeakerphoneOn = !hasHeadsetLegacy()
        }
    }

    fun stop() {
        if (!active) return
        active = false
        // A phone call may have taken over the audio (we stop because a call was
        // answered). Its routing and mode belong to the call — never touch them.
        if (am.mode == AudioManager.MODE_IN_COMMUNICATION) {
            if (Build.VERSION.SDK_INT >= 31) {
                am.clearCommunicationDevice()
            } else {
                @Suppress("DEPRECATION")
                am.isSpeakerphoneOn = false
            }
            am.mode = AudioManager.MODE_NORMAL
        }
        focus?.let { am.abandonAudioFocusRequest(it) }
        focus = null
    }

    private fun headsetTypes(): Set<Int> {
        val s = mutableSetOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )
        if (Build.VERSION.SDK_INT >= 31) s += AudioDeviceInfo.TYPE_BLE_HEADSET
        return s
    }

    private fun hasHeadsetLegacy(): Boolean =
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in headsetTypes() }
}
