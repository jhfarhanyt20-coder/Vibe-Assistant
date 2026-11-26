package com.vibe.assistant.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.vibe.assistant.core.VState
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import com.vibe.assistant.wake.VoiceId
import com.vibe.assistant.wake.VoskModels
import com.vibe.assistant.wake.WakeModelDownloader
import kotlinx.coroutines.launch
import org.json.JSONObject
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Records the owner's voice for Voice Lock: five short phrases become one voice
 * fingerprint, kept only on this phone. Afterwards it stays in a test mode that
 * shows how well any voice matches, so you can try it with someone else.
 */
class VoiceEnrollActivity : ComponentActivity() {

    private val phrases = listOf(
        "“হেই Vibe”",
        "আবার: “হেই Vibe”",
        "আরেকবার: “হেই Vibe”",
        "“আজকে আবহাওয়া কেমন থাকবে বলো তো”",
        "নিজের নাম আর আজকের দিনটা কেমন গেল — একটা বাক্যে বলো",
    )
    private val vectors = mutableListOf<FloatArray>()
    private var testing = false
    private var speech: SpeechService? = null
    private var rec: Recognizer? = null

    private lateinit var orb: OrbView
    private lateinit var step: TextView
    private lateinit var say: TextView
    private lateinit var info: TextView
    private lateinit var redo: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        VibeService.micBorrowed = true
        VibeService.send(this, VibeService.A_STOP_TALK)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = screenBackground()
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        root.addView(label("তোমার কণ্ঠ চেনানো", 24f, VC.TEXT, VC.bold, Gravity.CENTER), lp())
        root.addView(
            label("Voice Lock চালু থাকলে শুধু তোমার কণ্ঠেই Vibe সাড়া দেবে। কণ্ঠের ছাপ শুধু এই ফোনেই থাকে।",
                14f, VC.SUB, gravity = Gravity.CENTER),
            lp().margins(t = dp(6))
        )
        val box = FrameLayout(this)
        orb = OrbView(this).apply { forcedState = VState.WAKE }
        box.addView(orb, FrameLayout.LayoutParams(dp(230), dp(230), Gravity.CENTER))
        root.addView(box, lp(h = 0).apply { weight = 1f })

        step = label("", 14f, VC.A2, VC.medium, Gravity.CENTER)
        root.addView(step, lp())
        say = label("", 22f, VC.TEXT, VC.medium, Gravity.CENTER)
        root.addView(say, lp().margins(t = dp(8)))
        info = label("", 14f, VC.SUB, gravity = Gravity.CENTER)
        root.addView(info, lp().margins(t = dp(10), b = dp(18)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        redo = secondaryButton("আবার শুরু") { restart() }
        row.addView(redo, lp(0).apply { weight = 1f }.margins(r = dp(6)))
        row.addView(primaryButton("শেষ") { finish() }, lp(0).apply { weight = 1f }.margins(l = dp(6)))
        root.addView(row, lp())

        setContentView(root)
        edgeToEdge(root)
        prepare()
    }

    private fun prepare() {
        val need = listOf(VoskModels.WAKE, VoskModels.SPEAKER).filter { !VoskModels.ready(this, it) }
        if (need.isEmpty()) {
            begin()
            return
        }
        step.text = ""
        say.text = "কণ্ঠ চেনার মডেল নামছে…"
        info.text = "একবারই লাগবে (প্রায় ${need.sumOf { it.sizeMb }}MB)"
        lifecycleScope.launch {
            WakeModelDownloader.progress.collect { p ->
                if (p in 0..100) info.text = "নামছে… $p%"
                else if (p == 101) info.text = "খোলা হচ্ছে…"
            }
        }
        lifecycleScope.launch {
            WakeModelDownloader.error.collect { e -> if (e != null) info.text = e }
        }
        WakeModelDownloader.start(this, listOf(VoskModels.WAKE, VoskModels.SPEAKER)) { begin() }
    }

    private fun begin() {
        say.text = "প্রস্তুত হচ্ছে…"
        Thread {
            try {
                val model = VoskModels.model(this)
                val spk = VoskModels.speaker(this) ?: throw IllegalStateException("speaker model missing")
                runOnUiThread {
                    if (isFinishing) return@runOnUiThread
                    val r = Recognizer(model, 16000f, spk)
                    rec = r
                    speech = SpeechService(r, 16000f).also { it.startListening(listener) }
                    orb.forcedState = VState.LISTENING
                    showStep()
                }
            } catch (e: Throwable) {
                runOnUiThread { info.text = "চালু করা গেল না: ${e.message}" }
            }
        }.start()
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {}
        override fun onResult(hypothesis: String?) = handle(hypothesis)
        override fun onFinalResult(hypothesis: String?) {}
        override fun onError(exception: Exception?) {
            info.text = "মাইক সমস্যা: ${exception?.message}"
        }
        override fun onTimeout() {}
    }

    private fun handle(json: String?) {
        val o = try { JSONObject(json ?: return) } catch (_: Exception) { return }
        val vec = VoiceId.parse(o)
        if (vec == null) {
            if (o.optString("text").isNotBlank()) info.text = "আরেকটু লম্বা করে, স্পষ্ট করে বলো"
            return
        }
        if (testing) {
            val owner = Prefs.ownerVoice ?: return
            val score = VoiceId.cosine(vec, owner)
            val ok = score >= VoiceId.threshold()
            info.text = (if (ok) "✓ তোমার কণ্ঠ — " else "✗ অন্য কণ্ঠ — ") + VoiceId.percent(score)
            info.setTextColor(if (ok) VC.GREEN else VC.RED)
            return
        }
        vectors += vec
        if (vectors.size < phrases.size) {
            info.setTextColor(VC.GREEN)
            info.text = "✓ পেয়েছি"
            showStep()
            return
        }
        Prefs.ownerVoice = VoiceId.average(vectors)
        Prefs.voiceLock = true
        testing = true
        step.text = "হয়ে গেছে"
        say.text = "এখন কিছু বলে টেস্ট করো"
        info.setTextColor(VC.SUB)
        info.text = "অন্য কাউকে দিয়েও বলিয়ে দেখো — তার বেলায় “অন্য কণ্ঠ” আসা উচিত"
        redo.text = "আবার রেকর্ড করো"
    }

    private fun showStep() {
        step.text = "${vectors.size + 1} / ${phrases.size}"
        say.text = "বলো: " + phrases[vectors.size]
    }

    private fun restart() {
        vectors.clear()
        testing = false
        info.text = ""
        info.setTextColor(VC.SUB)
        redo.text = "আবার শুরু"
        if (speech != null) showStep() else prepare()
    }

    override fun onDestroy() {
        speech?.let {
            try { it.stop(); it.shutdown() } catch (_: Exception) {}
        }
        rec?.let { try { it.close() } catch (_: Exception) {} }
        VibeService.micBorrowed = false
        VibeService.send(this, VibeService.A_ENSURE)
        super.onDestroy()
    }

    @Suppress("unused")
    private fun hide(v: View) { v.visibility = View.GONE }
}
