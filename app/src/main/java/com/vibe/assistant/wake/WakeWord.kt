package com.vibe.assistant.wake

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.Http
import com.vibe.assistant.data.Prefs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.SpeakerModel
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

data class ModelSpec(val name: String, val url: String, val sizeMb: Int)

/** The two offline Vosk models: words ("hey vibe") and voices (who is speaking). */
object VoskModels {
    val WAKE = ModelSpec(
        "vosk-model-small-en-us-0.15",
        "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip", 40
    )
    val SPEAKER = ModelSpec(
        "vosk-model-spk-0.4",
        "https://alphacephei.com/vosk/models/vosk-model-spk-0.4.zip", 13
    )

    /** Only the wake phrases, or "something else". */
    const val GRAMMAR = "[\"hey vibe\", \"hi vibe\", \"okay vibe\", \"hey vibes\", \"[unk]\"]"

    fun dir(ctx: Context, spec: ModelSpec) = File(ctx.filesDir, "vosk/${spec.name}")

    private fun unpacked(ctx: Context, spec: ModelSpec) = File(dir(ctx, spec), ".ready").exists()

    /** The model ships inside the APK (assets/vosk/<name>). */
    fun bundled(ctx: Context, spec: ModelSpec): Boolean = try {
        ctx.assets.list("vosk/${spec.name}")?.isNotEmpty() == true
    } catch (_: Exception) {
        false
    }

    /** Usable without any download: already unpacked, or inside the APK. */
    fun ready(ctx: Context, spec: ModelSpec) = unpacked(ctx, spec) || bundled(ctx, spec)

    private val lock = Any()
    @Volatile private var model: Model? = null
    @Volatile private var speaker: SpeakerModel? = null

    /**
     * Vosk reads models from real files, so the copy inside the APK is unpacked
     * into app storage once (a few seconds, first run only — or after an update
     * that ships a different model).
     */
    fun ensure(ctx: Context, spec: ModelSpec) = synchronized(lock) {
        if (unpacked(ctx, spec) || !bundled(ctx, spec)) return@synchronized
        val target = dir(ctx, spec)
        target.deleteRecursively()
        fun copy(asset: String, out: File) {
            val children = ctx.assets.list(asset).orEmpty()
            if (children.isEmpty()) {
                out.parentFile?.mkdirs()
                ctx.assets.open(asset).use { i -> out.outputStream().use { i.copyTo(it) } }
            } else {
                out.mkdirs()
                for (c in children) copy("$asset/$c", File(out, c))
            }
        }
        copy("vosk/${spec.name}", target)
        File(target, ".ready").writeText("ok")
    }

    fun model(ctx: Context): Model = synchronized(lock) {
        ensure(ctx, WAKE)
        model ?: Model(dir(ctx, WAKE).absolutePath).also { model = it }
    }

    fun speaker(ctx: Context): SpeakerModel? = synchronized(lock) {
        if (!ready(ctx, SPEAKER)) return null
        ensure(ctx, SPEAKER)
        speaker ?: SpeakerModel(dir(ctx, SPEAKER).absolutePath).also { speaker = it }
    }

    /** Unpack both bundled models in the background at app start, so first use is instant. */
    fun warmUp(ctx: Context) {
        val app = ctx.applicationContext
        Thread {
            try {
                ensure(app, WAKE)
                ensure(app, SPEAKER)
            } catch (_: Exception) {}
        }.start()
    }
}

/**
 * "Hey Vibe", fully offline. Vosk runs the small English model restricted to a
 * tiny grammar, so it can only hear one of the wake phrases or "something else".
 *
 * With Voice Lock on, the speaker model is attached too: the phrase only counts
 * when the voice saying it matches the enrolled owner.
 */
class WakeWord(private val ctx: Context) {
    private val main = Handler(Looper.getMainLooper())
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null
    @Volatile private var wanted = false
    private var lastFire = 0L
    private var locked = false

    val isListening get() = service != null

    fun start(onWake: () -> Unit, onError: (String) -> Unit) {
        if (wanted) return
        wanted = true
        loader.execute {
            val model: Model
            val spk: SpeakerModel?
            try {
                model = VoskModels.model(ctx)
                spk = null // voice identification removed: "Hey Vibe" answers anyone
            } catch (e: Throwable) {
                Log.e(TAG, "model load failed", e)
                wanted = false
                main.post { onError("“হেই Vibe” মডেল লোড হয়নি। সেটিংস থেকে আবার ডাউনলোড করো।") }
                return@execute
            }
            main.post {
                if (!wanted || service != null) return@post
                try {
                    val rec = Recognizer(model, 16000f, VoskModels.GRAMMAR)
                    locked = spk != null
                    if (spk != null) rec.setSpeakerModel(spk)
                    val s = SpeechService(rec, 16000f)
                    recognizer = rec
                    service = s
                    s.startListening(object : RecognitionListener {
                        override fun onPartialResult(hypothesis: String?) {
                            // Without Voice Lock, the partial result is enough — fastest wake.
                            if (!locked) check(hypothesis, "partial", onWake)
                        }
                        override fun onResult(hypothesis: String?) = check(hypothesis, "text", onWake)
                        override fun onFinalResult(hypothesis: String?) {}
                        override fun onError(exception: Exception?) {
                            Log.e(TAG, "listen error", exception)
                            stop()
                            onError("মাইক পাওয়া যাচ্ছে না — অন্য কোনো অ্যাপ হয়তো মাইক ব্যবহার করছে।")
                        }
                        override fun onTimeout() {}
                    })
                } catch (e: Exception) {
                    Log.e(TAG, "start failed", e)
                    stop()
                    onError("“হেই Vibe” চালু করা গেল না: ${e.message}")
                }
            }
        }
    }

    private fun check(json: String?, field: String, onWake: () -> Unit) {
        if (json == null || !wanted) return
        val o = try {
            JSONObject(json)
        } catch (_: Exception) {
            return
        }
        val text = o.optString(field)
        if (!text.contains("vibe")) return
        if (locked) {
            val owner = Prefs.ownerVoice
            val vec = VoiceId.parse(o)
            val score = if (owner != null && vec != null) VoiceId.cosine(vec, owner) else -1f
            if (score < VoiceId.threshold()) {
                VibeBus.event("“হেই Vibe” শোনা গেছে, কিন্তু কণ্ঠ মেলেনি (${VoiceId.percent(score)}) — উপেক্ষা")
                return
            }
            VibeBus.event("তোমার কণ্ঠ চেনা গেছে (${VoiceId.percent(score)})")
            VibeBus.ownerVerifiedAt = SystemClock.elapsedRealtime()
        }
        val now = SystemClock.elapsedRealtime()
        if (now - lastFire < 2000) return
        lastFire = now
        Log.i(TAG, "wake: $text")
        stop()
        onWake()
    }

    fun stop() {
        wanted = false
        service?.let {
            try {
                it.stop()
                it.shutdown()
            } catch (_: Exception) {}
        }
        service = null
        recognizer?.let { try { it.close() } catch (_: Exception) {} }
        recognizer = null
    }

    companion object {
        private const val TAG = "VibeWake"
        val MODEL_SIZE_MB = VoskModels.WAKE.sizeMb
        private val loader = Executors.newSingleThreadExecutor()

        fun isModelReady(ctx: Context) = VoskModels.ready(ctx, VoskModels.WAKE)
    }
}

/** Downloads and unpacks Vosk models once. Survives leaving the screen. */
object WakeModelDownloader {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null

    /** -1 idle, 0..100 downloading, 101 unpacking. */
    val progress = MutableStateFlow(-1)
    val error = MutableStateFlow<String?>(null)

    val busy get() = job?.isActive == true

    fun start(ctx: Context, specs: List<ModelSpec> = listOf(VoskModels.WAKE), onDone: () -> Unit) {
        if (busy) return
        val app = ctx.applicationContext
        val todo = specs.filter { !VoskModels.ready(app, it) }
        if (todo.isEmpty()) {
            onDone()
            return
        }
        error.value = null
        progress.value = 0
        job = scope.launch {
            try {
                for (s in todo) download(app, s)
                progress.value = -1
                withContext(Dispatchers.Main) { onDone() }
            } catch (e: Exception) {
                progress.value = -1
                error.value = "ডাউনলোড হয়নি: ${e.message ?: "ইন্টারনেট দেখো"}"
            }
        }
    }

    private fun download(ctx: Context, spec: ModelSpec) {
        val root = File(ctx.filesDir, "vosk").apply { mkdirs() }
        val zip = File(ctx.cacheDir, "${spec.name}.zip")
        val req = Request.Builder().url(spec.url).build()
        Http.download.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            val body = r.body ?: throw IOException("খালি উত্তর")
            val total = body.contentLength().takeIf { it > 0 } ?: (spec.sizeMb * 1_000_000L)
            body.byteStream().use { input ->
                zip.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        read += n
                        progress.value = ((read * 100) / total).toInt().coerceIn(0, 100)
                    }
                }
            }
        }
        progress.value = 101
        val canonRoot = root.canonicalPath + File.separator
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var e = zis.nextEntry
            while (e != null) {
                val f = File(root, e.name)
                if (!f.canonicalPath.startsWith(canonRoot)) throw IOException("ভুল ফাইল")
                if (e.isDirectory) {
                    f.mkdirs()
                } else {
                    f.parentFile?.mkdirs()
                    f.outputStream().use { zis.copyTo(it) }
                }
                e = zis.nextEntry
            }
        }
        zip.delete()
        val dir = VoskModels.dir(ctx, spec)
        if (!dir.exists()) throw IOException("মডেল ফোল্ডার পাওয়া যায়নি")
        File(dir, ".ready").writeText("ok")
    }
}
