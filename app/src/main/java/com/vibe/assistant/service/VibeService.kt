package com.vibe.assistant.service

import android.Manifest
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.vibe.assistant.R
import com.vibe.assistant.VibeApp
import com.vibe.assistant.access.VibeAccessibility
import com.vibe.assistant.audio.AudioRoute
import com.vibe.assistant.audio.Mic
import com.vibe.assistant.audio.Speaker
import com.vibe.assistant.core.Prompt
import com.vibe.assistant.core.Role
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.live.GeminiLiveClient
import com.vibe.assistant.overlay.OverlayController
import com.vibe.assistant.tools.DeviceActions
import com.vibe.assistant.tools.PhoneActions
import com.vibe.assistant.tools.ToolRunner
import com.vibe.assistant.tools.ToolSpecs
import com.vibe.assistant.ui.LockActivity
import com.vibe.assistant.ui.MainActivity
import com.vibe.assistant.wake.VoiceGate
import com.vibe.assistant.wake.VoiceId
import com.vibe.assistant.wake.WakeWord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Vibe's always-on part. A foreground service (with the permanent notification
 * Android requires) that owns the microphone and runs one of two things:
 *
 *   sleeping  -> the offline "Hey Vibe" listener
 *   talking   -> a Gemini Live session: mic -> Gemini -> speaker, with interruption,
 *                and tools Gemini can call to work the phone
 *
 * Turn-taking has three modes:
 *   automatic     the server hears when you start and stop talking
 *   push-to-talk  only while you hold the bubble / orb / volume-down
 *   voice lock    every utterance is checked on the phone; only the owner's reaches Gemini
 */
class VibeService : Service(), GeminiLiveClient.Listener, ToolRunner.Host {

    companion object {
        private const val TAG = "VibeService"
        const val A_ENSURE = "com.vibe.assistant.ENSURE"
        const val A_TOGGLE = "com.vibe.assistant.TOGGLE"
        const val A_TALK = "com.vibe.assistant.TALK"
        const val A_STOP_TALK = "com.vibe.assistant.STOP_TALK"
        const val A_QUIT = "com.vibe.assistant.QUIT"
        const val A_PTT_DOWN = "com.vibe.assistant.PTT_DOWN"
        const val A_PTT_UP = "com.vibe.assistant.PTT_UP"
        const val A_STOP_TASK = "com.vibe.assistant.STOP_TASK"

        private const val NOTIF_ID = 1
        private const val LOCK_NOTIF_ID = 2
        private const val ALERT_NOTIF_ID = 3

        @Volatile var isRunning = false
            private set

        /** The running service, for the accessibility service to report touches to. */
        @Volatile var instance: VibeService? = null
            private set

        /** Tools during which Vibe itself is working the screen. */
        private val SCREEN_TOOLS = setOf(
            "do_task", "tap_on", "type_text", "scroll_screen", "whatsapp_message",
            "send_voice_message", "youtube_play",
        )

        /** True while the voice-enrollment screen needs the microphone. */
        @Volatile var micBorrowed = false

        fun send(ctx: Context, action: String) {
            val i = Intent(ctx, VibeService::class.java).setAction(action)
            try {
                ContextCompat.startForegroundService(ctx, i)
            } catch (e: Exception) {
                Log.w(TAG, "could not start service: ${e.message}")
            }
        }

        fun notifyActivate(ctx: Context, text: String) {
            val pi = PendingIntent.getActivity(
                ctx, 11, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, VibeApp.CH_ALERT)
                .setSmallIcon(R.drawable.ic_stat_vibe)
                .setContentTitle("Vibe")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            try {
                ctx.getSystemService(NotificationManager::class.java).notify(ALERT_NOTIF_ID, n)
            } catch (_: SecurityException) {}
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val learnScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var linesAtStart = 0
    private lateinit var overlay: OverlayController
    private lateinit var route: AudioRoute
    private lateinit var wake: WakeWord
    private lateinit var tools: ToolRunner

    private var fgStarted = false
    private var inConversation = false
    @Volatile private var ready = false
    @Volatile private var bargeIn = true
    private var mic: Mic? = null
    @Volatile private var speaker: Speaker? = null
    @Volatile private var client: GeminiLiveClient? = null
    private var currentModel = ""
    private val pending = ArrayDeque<ByteArray>()
    private val pendingUtterances = ArrayDeque<List<ByteArray>>()
    private var pendingText: String? = null
    @Volatile private var resumeHandle: String? = null
    private var retries = 0
    private var readyAt = 0L
    private var keyInUrl = false
    private var useSearch = true
    // The shorter end-of-speech wait cut people off mid-sentence; the server's own
    // turn detection (what v0.1 used) understands the whole sentence. Kept off.
    private var useQuick = false
    private val triedModels = mutableSetOf<String>()
    @Volatile private var lastActivity = 0L
    private var wakeLock: PowerManager.WakeLock? = null
    private val toolJobs = mutableMapOf<String, Job>()
    /** Screen-working tool calls in flight, id → name, so "stop" can answer each one. */
    private val screenCalls = mutableMapOf<String, String>()
    @Volatile private var apps: List<String> = emptyList()

    // Turn-taking
    @Volatile private var manual = false
    @Volatile private var held = false
    /** Phone-side barge-in fired: drop the rest of the reply being spoken. */
    @Volatile private var dropReply = false
    private var loudChunks = 0
    private var pttStartedEarly = false
    private var pttEndedEarly = false
    @Volatile private var gate: VoiceGate? = null
    @Volatile private var wakeTurn = false
    private var wakeTurnStart = 0L
    private var wakeTurnSpeech = false
    private var wakeTurnLoudAt = 0L

    // Phone calls
    @Volatile private var inCall = false
    @Volatile private var ringing = false
    @Volatile private var ringNumber: String? = null
    private var ringAnnounced = false

    // Network
    private var waitingForNet = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null

    // ── Lifecycle ────────────────────────────────────────────────────────────

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        overlay = OverlayController(this)
        route = AudioRoute(this)
        wake = WakeWord(this)
        tools = ToolRunner(this, this)
        com.vibe.assistant.update.Updater.autoCheck(this)
        scope.launch {
            combine(VibeBus.state, VibeBus.status) { s, t -> s to t }.collect { updateNotification() }
        }
        refreshApps()
        registerCalls()
        registerNetwork()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        when (intent?.action) {
            A_TALK -> startConversation()
            A_STOP_TALK -> endConversation()
            A_TOGGLE -> if (inConversation) endConversation() else startConversation()
            A_PTT_DOWN -> pttDown()
            A_PTT_UP -> pttUp()
            A_STOP_TASK -> stopScreenTasks()
            A_QUIT -> {
                quit()
                return START_NOT_STICKY
            }
        }
        applyPrefs()
        return START_STICKY
    }

    override fun onDestroy() {
        endConversation(resumeWake = false)
        wake.stop()
        overlay.hide()
        try { unregisterReceiver(callReceiver) } catch (_: Exception) {}
        netCallback?.let {
            try { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } catch (_: Exception) {}
        }
        scope.cancel()
        main.removeCallbacksAndMessages(null)
        if (!VibeBus.active()) VibeBus.setState(VState.OFF)
        isRunning = false
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun goForeground(): Boolean {
        if (fgStarted) return true
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyActivate(this, "Vibe-কে মাইকের অনুমতি দাও")
            return false
        }
        // A visible bubble first: it counts as being on screen, which helps when
        // Vibe is (re)started from the background.
        if (Prefs.overlayEnabled && Settings.canDrawOverlays(this)) overlay.show()
        return try {
            val type = if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            ServiceCompat.startForeground(this, NOTIF_ID, buildNotification(), type)
            fgStarted = true
            true
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            overlay.hide()
            notifyActivate(this, "Vibe চালু করতে ট্যাপ করো")
            false
        }
    }

    private fun quit() {
        endConversation(resumeWake = false)
        wake.stop()
        overlay.hide()
        VibeBus.setState(VState.OFF)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        fgStarted = false
        stopSelf()
    }

    /** Bubble and wake word follow the settings whenever Vibe is not mid-conversation. */
    private fun applyPrefs() {
        if (Prefs.overlayEnabled && Settings.canDrawOverlays(this)) overlay.show() else overlay.hide()
        if (inConversation) return
        if (micBorrowed || inCall) {
            wake.stop()
            return
        }
        if (Prefs.wakeEnabled && WakeWord.isModelReady(this)) {
            startWake()
        } else {
            wake.stop()
            if (VibeBus.state.value != VState.ERROR) VibeBus.setState(VState.OFF)
        }
    }

    private fun refreshApps() {
        Thread {
            apps = try {
                DeviceActions.installedApps(this).map { it.label }.distinct().sorted()
            } catch (_: Exception) {
                emptyList()
            }
        }.start()
    }

    // ── Wake word ────────────────────────────────────────────────────────────

    private fun startWake() {
        if (wake.isListening) {
            if (VibeBus.state.value == VState.OFF) VibeBus.setState(VState.WAKE)
            return
        }
        wake.start(
            onWake = { onWakeWord() },
            onError = { msg ->
                VibeBus.status.value = msg
                VibeBus.setState(VState.ERROR)
                VibeBus.event(msg)
                // The mic may be busy (a call, another recorder). Try again later.
                main.postDelayed({ if (!inConversation && Prefs.wakeEnabled) startWake() }, 15_000)
            }
        )
        if (VibeBus.state.value != VState.ERROR) VibeBus.setState(VState.WAKE)
    }

    private fun onWakeWord() {
        beep()
        startConversation()
        // In push-to-talk mode, the sentence right after "Hey Vibe" counts as held.
        if (inConversation && Prefs.pushToTalk) {
            wakeTurn = true
            wakeTurnStart = SystemClock.elapsedRealtime()
            wakeTurnSpeech = false
            pttDown()
        }
    }

    private fun beep() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70)
            tg.startTone(ToneGenerator.TONE_PROP_ACK, 150)
            main.postDelayed({ tg.release() }, 400)
        } catch (_: Exception) {}
    }

    // ── Conversation ─────────────────────────────────────────────────────────

    private fun startConversation() {
        if (inConversation) return
        if (inCall) {
            VibeBus.status.value = "কল চলছে — কল শেষ হলে কথা বলো"
            VibeBus.setState(VState.ERROR)
            return
        }
        val key = Prefs.apiKey
        if (key.isNullOrBlank()) {
            VibeBus.status.value = "আগে Gemini API key দাও"
            VibeBus.setState(VState.ERROR)
            return
        }
        wake.stop()
        inConversation = true
        ready = false
        retries = 0
        readyAt = 0L
        keyInUrl = false
        useSearch = Prefs.googleSearch
        useQuick = false
        waitingForNet = false
        triedModels.clear()
        resumeHandle = null
        bargeIn = Prefs.bargeIn
        held = false
        pttStartedEarly = false
        pttEndedEarly = false
        wakeTurn = false
        synchronized(pending) {
            pending.clear()
            pendingUtterances.clear()
        }
        VibeBus.status.value = ""
        VibeBus.setState(VState.CONNECTING)
        linesAtStart = VibeBus.lines.value.size

        // Turn-taking mode for this conversation.
        gate = null
        // Checking every sentence's voice during the conversation was removed: it
        // too often took the owner's own speech for someone else's and Vibe went
        // silent. Voice Lock now guards only the wake word (and voice unlock);
        // Push-to-talk is the way to keep other people out of a conversation.
        val wantGate = false
        manual = Prefs.pushToTalk || wantGate
        if (wantGate) {
            val owner = Prefs.ownerVoice!!
            Thread {
                try {
                    val g = VoiceGate(this, owner, onOwner = { chunks, _ -> onOwnerUtterance(chunks) },
                        onOther = { score ->
                            main.post { VibeBus.event("অন্য কারো কথা উপেক্ষা করা হলো (${VoiceId.percent(score)})") }
                        })
                    if (inConversation) gate = g else g.close()
                } catch (e: Throwable) {
                    Log.e(TAG, "voice gate failed", e)
                    main.post { VibeBus.event("Voice Lock চালু করা গেল না: ${e.message}") }
                }
            }.start()
        }

        acquireWakeLock()
        route.start()
        speaker = Speaker().also { it.start() }
        val m = Mic { onMic(it) }
        if (!m.start()) {
            fail("মাইক চালু করা যাচ্ছে না — অন্য অ্যাপ হয়তো মাইক ব্যবহার করছে।")
            return
        }
        mic = m
        lastActivity = SystemClock.elapsedRealtime()
        // As in v0.1: locked OR screen off → light the screen and show Vibe over the lock screen.
        if ((isLocked() || !isScreenOn()) && !ringing) showLockUi(force = false)
        refreshApps()
        VibeBus.event("কথোপকথন শুরু" + when {
            Prefs.pushToTalk -> " (চেপে ধরে কথা)"
            wantGate -> " (Voice Lock)"
            else -> ""
        })
        connect(Prefs.model)
        main.removeCallbacks(tick)
        main.postDelayed(tick, 250)
    }

    private fun connect(model: String) {
        val key = Prefs.apiKey ?: return fail("আগে Gemini API key দাও")
        currentModel = model
        triedModels += model
        synchronized(pending) {
            ready = false
            if (held) pttStartedEarly = true
        }
        client?.close()
        dropReply = false
        val screen = VibeAccessibility.isEnabled(this)
        val talkOnly = Prefs.talkOnly
        VibeBus.event(
            "সংযোগ: ${model.removePrefix("models/")}, " +
                (if (talkOnly) "শুধু কথা (ভার্সন ১-এর মতো)" else "টুল চালু") +
                (if (!talkOnly && useSearch) ", Search" else "") + (if (manual) ", চেপে ধরে কথা" else "")
        )
        client = GeminiLiveClient(
            apiKey = key,
            model = model,
            voice = Prefs.voice,
            // Talk-only connects exactly as v0.1 did: its prompt, no tools, no search.
            systemPrompt = if (talkOnly) Prompt.v01() else Prompt.build(isLocked(), screen, apps),
            resumeHandle = resumeHandle,
            listener = this,
            keyInUrl = keyInUrl,
            tools = if (talkOnly) org.json.JSONArray() else ToolSpecs.all(screen),
            googleSearch = !talkOnly && useSearch,
            manualTurns = manual,
            quickTurns = false,
        ).also { it.connect() }
    }

    /** Mic thread. */
    private fun onMic(chunk: ByteArray) {
        val now = SystemClock.elapsedRealtime()
        if (VibeBus.micLevel > 0.2f) lastActivity = now

        if (manual) {
            gate?.let {
                it.feed(chunk)
                return
            }
            if (wakeTurn) trackWakeTurn(now)
            if (!held) return
        } else {
            val sp = speaker
            // Without barge-in the mic is muted while Vibe talks, so it can't hear itself.
            if (!bargeIn && sp != null && sp.isSpeaking()) return
            // Phone-side barge-in. The server decides interruptions by itself, and on
            // a loudspeaker it can miss you under Vibe's own voice. So: if you talk
            // clearly over Vibe for ~0.3 s, stop its voice here at once — the server
            // then hears you cleanly and takes your turn.
            if (bargeIn && sp != null && sp.isSpeaking() && !dropReply) {
                loudChunks = if (VibeBus.micLevel > 0.30f) loudChunks + 1 else 0
                if (loudChunks >= 6) {
                    loudChunks = 0
                    dropReply = true
                    sp.flush()
                    main.post {
                        VibeBus.event("তুমি কথা বললে — Vibe থামল")
                        if (inConversation) VibeBus.setState(VState.LISTENING)
                    }
                }
            } else if (sp?.isSpeaking() != true) {
                loudChunks = 0
            }
        }
        val c = client
        synchronized(pending) {
            if (ready && c != null) {
                while (pending.isNotEmpty()) c.sendAudio(pending.removeFirst())
                c.sendAudio(chunk)
            } else {
                pending.addLast(chunk)
                while (pending.size > 160) pending.removeFirst() // keep the last ~8 s
            }
        }
    }

    /** Ends the automatic push-to-talk turn after "Hey Vibe" once you stop talking. */
    private fun trackWakeTurn(now: Long) {
        if (VibeBus.micLevel > 0.1f) {
            wakeTurnSpeech = true
            wakeTurnLoudAt = now
        }
        val silentLong = wakeTurnSpeech && now - wakeTurnLoudAt > 1100
        val tooLong = now - wakeTurnStart > 12_000
        val nothing = !wakeTurnSpeech && now - wakeTurnStart > 6000
        if (silentLong || tooLong || nothing) {
            wakeTurn = false
            main.post { pttUp() }
        }
    }

    /** Mic thread: a verified utterance from the owner (Voice Lock mode). */
    private fun onOwnerUtterance(chunks: List<ByteArray>) {
        if (chunks.isEmpty()) return
        lastActivity = SystemClock.elapsedRealtime()
        VibeBus.ownerVerifiedAt = lastActivity
        val c = client
        synchronized(pending) {
            if (ready && c != null) sendUtterance(c, chunks)
            else {
                pendingUtterances.addLast(chunks)
                while (pendingUtterances.size > 3) pendingUtterances.removeFirst()
            }
        }
    }

    private fun sendUtterance(c: GeminiLiveClient, chunks: List<ByteArray>) {
        speaker?.flush()
        c.activityStart()
        for (ch in chunks) c.sendAudio(ch)
        c.activityEnd()
    }

    private fun pttDown() {
        if (!Prefs.pushToTalk) {
            if (!inConversation) startConversation()
            return
        }
        VibeBus.pttHeld.value = true
        if (!inConversation) startConversation()
        if (!inConversation) return
        speaker?.flush()
        synchronized(pending) {
            if (held) return
            held = true
            if (ready) client?.activityStart() else pttStartedEarly = true
        }
        lastActivity = SystemClock.elapsedRealtime()
        VibeBus.setState(VState.LISTENING)
    }

    private fun pttUp() {
        VibeBus.pttHeld.value = false
        wakeTurn = false
        synchronized(pending) {
            if (!held) return
            held = false
            if (ready) client?.activityEnd() else pttEndedEarly = true
        }
        lastActivity = SystemClock.elapsedRealtime()
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!inConversation) return
            val now = SystemClock.elapsedRealtime()
            val speaking = speaker?.isSpeaking() == true
            if (speaking || toolJobs.isNotEmpty() || held || ringing) lastActivity = now
            if (VibeBus.state.value == VState.SPEAKING && !speaking) VibeBus.setState(VState.LISTENING)
            val sleep = Prefs.sleepSeconds
            if (sleep > 0 && ready && now - lastActivity > sleep * 1000L) {
                VibeBus.action("😴 অনেকক্ষণ চুপ ছিলে, তাই ঘুমিয়ে পড়লাম")
                VibeBus.event("$sleep সেকেন্ড চুপ — কথোপকথন বন্ধ (ঘুমের টাইমার)")
                endConversation()
                return
            }
            main.postDelayed(this, 250)
        }
    }

    private fun endConversation(resumeWake: Boolean = true) {
        if (!inConversation) return
        inConversation = false
        ready = false
        main.removeCallbacks(tick)
        toolJobs.values.forEach { it.cancel() }
        toolJobs.clear()
        client?.close()
        client = null
        mic?.stop()
        mic = null
        gate?.close()
        gate = null
        speaker?.stop()
        speaker = null
        route.stop()
        releaseWakeLock()
        held = false
        wakeTurn = false
        VibeBus.pttHeld.value = false
        synchronized(pending) {
            pending.clear()
            pendingUtterances.clear()
        }
        pendingText = null
        waitingForNet = false
        stopScreenShare()
        VibeBus.controllingScreen = false
        VibeAccessibility.instance?.hideShield()
        screenCalls.clear()
        VibeBus.closeTurn()
        cancelLockNotification()
        VibeBus.setState(VState.OFF)
        VibeBus.event("কথোপকথন শেষ")
        val all = VibeBus.lines.value
        val talk = if (all.size > linesAtStart) all.drop(linesAtStart) else all
        learnScope.launch { com.vibe.assistant.core.Memory.learnFromConversation(talk) }
        if (resumeWake && !inCall && !micBorrowed && Prefs.wakeEnabled && WakeWord.isModelReady(this)) startWake()
    }

    private fun fail(message: String) {
        VibeBus.event("ত্রুটি: $message")
        endConversation()
        VibeBus.status.value = message
        VibeBus.setState(VState.ERROR)
        main.postDelayed({
            if (!inConversation && VibeBus.state.value == VState.ERROR) {
                VibeBus.setState(if (wake.isListening) VState.WAKE else VState.OFF)
            }
        }, 8000)
    }

    // ── Gemini Live callbacks (OkHttp thread) ────────────────────────────────

    override fun onReady(c: GeminiLiveClient) {
        main.post {
            if (c !== client || !inConversation) return@post
            synchronized(pending) {
                ready = true
                when {
                    manual && gate == null -> {
                        if (pttStartedEarly) {
                            c.activityStart()
                            while (pending.isNotEmpty()) c.sendAudio(pending.removeFirst())
                            if (pttEndedEarly) c.activityEnd()
                        } else {
                            pending.clear()
                        }
                        pttStartedEarly = false
                        pttEndedEarly = false
                    }
                    manual -> while (pendingUtterances.isNotEmpty()) sendUtterance(c, pendingUtterances.removeFirst())
                    else -> while (pending.isNotEmpty()) c.sendAudio(pending.removeFirst())
                }
            }
            readyAt = SystemClock.elapsedRealtime()
            lastActivity = readyAt
            VibeBus.status.value = ""
            VibeBus.setState(VState.LISTENING)
            VibeBus.event("যুক্ত হয়েছে — ${c.model.removePrefix("models/")}" + if (resumeHandle != null) " (আগের কথা মনে আছে)" else "")
            pendingText?.let {
                c.sendText(it)
                pendingText = null
            }
        }
    }

    override fun onAudio(c: GeminiLiveClient, pcm: ByteArray) {
        if (c !== client) return
        // You cut in: the rest of this reply is not wanted, even if the server keeps sending it.
        if (dropReply) return
        speaker?.enqueue(pcm)
        lastActivity = SystemClock.elapsedRealtime()
        if (VibeBus.state.value != VState.SPEAKING) {
            main.post { if (inConversation && c === client && !held) VibeBus.setState(VState.SPEAKING) }
        }
    }

    override fun onInterrupted(c: GeminiLiveClient) {
        if (c !== client) return
        dropReply = false
        speaker?.flush()
        main.post {
            if (inConversation && c === client) {
                VibeBus.closeTurn()
                VibeBus.setState(VState.LISTENING)
            }
        }
    }

    override fun onTurnComplete(c: GeminiLiveClient) {
        // The dropped reply has ended; the next reply (to what you said) plays normally.
        if (c === client) dropReply = false
        main.post { if (c === client) VibeBus.closeTurn() }
    }

    override fun onInputText(c: GeminiLiveClient, text: String) {
        lastActivity = SystemClock.elapsedRealtime()
        main.post { if (c === client) VibeBus.append(Role.USER, text) }
    }

    override fun onOutputText(c: GeminiLiveClient, text: String) {
        main.post { if (c === client) VibeBus.append(Role.VIBE, text) }
    }

    override fun onToolCalls(c: GeminiLiveClient, calls: List<GeminiLiveClient.ToolCall>) {
        main.post {
            if (c !== client || !inConversation) return@post
            for (call in calls) {
                Log.i(TAG, "tool ${call.name} ${call.args}")
                VibeBus.event("টুল: ${call.name}")
                val touchesScreen = call.name in SCREEN_TOOLS
                if (touchesScreen) prepareWarning()
                val job = scope.launch {
                    if (touchesScreen) {
                        screenCalls[call.id] = call.name
                        VibeBus.controllingScreen = true
                        VibeAccessibility.instance?.showShield()
                        updateNotification()
                    }
                    val result = try {
                        tools.run(call.name, call.args)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        JSONObject().put("error", e.message ?: e.javaClass.simpleName)
                    } finally {
                        if (touchesScreen) {
                            screenCalls.remove(call.id)
                            if (screenCalls.isEmpty()) {
                                VibeBus.controllingScreen = false
                                VibeAccessibility.instance?.hideShield()
                                updateNotification()
                            }
                        }
                    }
                    toolJobs.remove(call.id)
                    lastActivity = SystemClock.elapsedRealtime()
                    val live = client
                    if (live != null && live.ready) live.sendToolResponse(call.id, call.name, result)
                }
                toolJobs[call.id] = job
            }
        }
    }

    override fun onToolCancel(c: GeminiLiveClient, ids: List<String>) {
        main.post { ids.forEach { toolJobs.remove(it)?.cancel() } }
    }

    override fun onResumeHandle(c: GeminiLiveClient, handle: String) {
        if (c === client) resumeHandle = handle
    }

    /** The server is about to rotate the connection: move to a fresh one, same conversation. */
    override fun onGoAway(c: GeminiLiveClient) {
        main.post {
            if (c === client && inConversation) {
                VibeBus.event("সার্ভার সংযোগ বদলাচ্ছে — নতুন সংযোগে যাচ্ছি")
                connect(c.model)
            }
        }
    }

    override fun onClosed(c: GeminiLiveClient, code: Int, reason: String, wasReady: Boolean) {
        main.post {
            if (c !== client || !inConversation) return@post
            ready = false
            handleClose(c, code, reason, wasReady)
        }
    }

    private fun hasNetwork(): Boolean {
        val cm = getSystemService(ConnectivityManager::class.java)
        return cm.activeNetwork != null
    }

    private fun handleClose(c: GeminiLiveClient, code: Int, reason: String, wasReady: Boolean) {
        val r = reason.lowercase()
        Log.w(TAG, "session closed $code: $reason")
        VibeBus.event("সংযোগ কাটল ($code) ${reason.take(120)}")
        when {
            "api key not valid" in r || "api_key_invalid" in r || "api key expired" in r ->
                fail("API key কাজ করছে না। সেটিংস থেকে নতুন key দাও।")

            // A real quota error only. ("Deadline exceeded" is an ordinary server
            // timeout and must NOT be read as "your quota is used up".)
            code == 429 || "quota" in r || "resource_exhausted" in r || "rate limit" in r ||
                "rate_limit" in r || "too many requests" in r -> {
                val searchQuota = useSearch && ("search" in r || "grounding" in r)
                val next = Prefs.liveModels.firstOrNull { it !in triedModels }
                when {
                    searchQuota -> {
                        useSearch = false
                        VibeBus.event("Google Search-এর লিমিট শেষ — Search ছাড়াই চলছি")
                        connect(c.model)
                    }
                    next != null -> {
                        VibeBus.event("${c.model.removePrefix("models/")}-এর লিমিট শেষ — অন্য মডেলে যাচ্ছি")
                        VibeBus.status.value = "অন্য মডেলে চেষ্টা করছি…"
                        connect(next)
                    }
                    else -> fail("Gemini-র লিমিট শেষ (${reason.take(70)})। কিছুক্ষণ পরে আবার চেষ্টা করো।")
                }
            }

            !wasReady && !keyInUrl && (code == 401 || code == 403) -> {
                keyInUrl = true
                connect(c.model)
            }

            !wasReady && useQuick && ("realtime_input_config" in r || "sensitivity" in r ||
                "activity_detection" in r || "realtimeinputconfig" in r) -> {
                useQuick = false
                connect(c.model)
            }

            !wasReady && useSearch && ("search" in r || "tool" in r) -> {
                useSearch = false
                VibeBus.event("Google Search ছাড়া আবার চেষ্টা")
                connect(c.model)
            }

            !wasReady && ("not found" in r || "not supported" in r || "unsupported" in r) -> {
                val next = Prefs.liveModels.firstOrNull { it !in triedModels }
                if (next != null) {
                    VibeBus.status.value = "অন্য মডেলে চেষ্টা করছি…"
                    connect(next)
                } else {
                    fail("এই key-তে কোনো Live মডেল চলছে না।")
                }
            }

            !hasNetwork() -> {
                waitingForNet = true
                VibeBus.setState(VState.CONNECTING)
                VibeBus.status.value = "ইন্টারনেট নেই — নেট ফিরলেই আবার যুক্ত হবে"
            }

            // A session that was working reconnects for as long as it takes; one that
            // never got going gives up after a few tries so a real error is shown.
            !wasReady && retries >= 6 ->
                fail("যুক্ত হওয়া যাচ্ছে না। (${reason.take(80).ifBlank { code.toString() }})")

            else -> {
                // A session that ran a good while starts the count again; one that keeps
                // dropping right after connecting backs off further each time
                // (1, 2, 4, 8, 16, 30 s) so Vibe never hammers the server.
                if (readyAt > 0 && SystemClock.elapsedRealtime() - readyAt > 60_000) retries = 0
                retries++
                VibeBus.setState(VState.CONNECTING)
                VibeBus.status.value = "আবার যুক্ত হচ্ছে…"
                val wait = minOf(1000L shl minOf(retries - 1, 5), 30_000L)
                main.postDelayed({ if (inConversation && client === c) connect(c.model) }, wait)
            }
        }
    }

    // ── Network ──────────────────────────────────────────────────────────────

    private fun registerNetwork() {
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                main.post {
                    if (inConversation && waitingForNet) {
                        waitingForNet = false
                        VibeBus.event("নেট ফিরে এসেছে — আবার যুক্ত হচ্ছি")
                        VibeBus.status.value = "আবার যুক্ত হচ্ছে…"
                        connect(currentModel)
                    }
                }
            }

            override fun onLost(network: Network) {
                main.post { VibeBus.event("নেট চলে গেছে") }
            }
        }
        try {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(cb)
            netCallback = cb
        } catch (_: Exception) {}
    }

    // ── Phone calls ──────────────────────────────────────────────────────────

    private val callReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val st = intent.getStringExtra(TelephonyManager.EXTRA_STATE) ?: return
            @Suppress("DEPRECATION")
            val num = intent.getStringExtra(TelephonyManager.EXTRA_INCOMING_NUMBER)
            onCallState(st, num)
        }
    }

    private fun registerCalls() {
        try {
            ContextCompat.registerReceiver(
                this, callReceiver, IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {}
    }

    private fun onCallState(state: String, number: String?) {
        when (state) {
            TelephonyManager.EXTRA_STATE_RINGING -> {
                ringing = true
                if (!number.isNullOrBlank()) ringNumber = number
                if (ringAnnounced) return
                // The number often arrives in a second broadcast a moment later.
                if (ringNumber == null) main.postDelayed({ announceCall() }, 800) else announceCall()
            }
            TelephonyManager.EXTRA_STATE_OFFHOOK -> {
                ringing = false
                inCall = true
                VibeBus.event("কল চলছে — Vibe চুপ")
                // A call Vibe placed itself is confirmed by callAndVerify, which then ends the session.
                if (inConversation && !pausedForCall) endConversation(resumeWake = false)
                wake.stop()
            }
            TelephonyManager.EXTRA_STATE_IDLE -> {
                ringing = false
                inCall = false
                ringNumber = null
                ringAnnounced = false
                applyPrefs()
            }
        }
    }

    private fun announceCall() {
        if (ringAnnounced || !ringing) return
        ringAnnounced = true
        if (!Prefs.callAnnounce || Prefs.apiKey == null) return
        val num = ringNumber
        val name = PhoneActions.nameForNumber(this, num)
        val who = name ?: num ?: "অজানা নম্বর"
        VibeBus.action("📞 কল আসছে: $who")
        val text = "[PHONE] Incoming call from $who" + (if (name != null && num != null) " ($num)" else "") +
            ". Tell the user in one short sentence who is calling and ask if they want to answer. " +
            "Then use answer_call or reject_call as they say."
        val c = client
        if (inConversation && ready && c != null) c.sendText(text)
        else {
            pendingText = text
            startConversation()
        }
    }

    // ── ToolRunner.Host ──────────────────────────────────────────────────────

    override fun isLocked(): Boolean = getSystemService(KeyguardManager::class.java).isKeyguardLocked

    private fun isScreenOn(): Boolean = getSystemService(PowerManager::class.java).isInteractive

    /**
     * Voice unlock is allowed only when every lock is in place: the user turned it
     * on, saved a PIN, enrolled their voice, and Voice Lock recognised that voice
     * in the last 45 seconds. The PIN never goes to Gemini — it is typed from the
     * phone's own encrypted storage.
     */
    /**
     * Self-unlock: the user turned it on and saved their PIN (encrypted, on this
     * phone only). Vibe then types it on the lock screen itself. There is no voice
     * check — anyone who can talk to Vibe can unlock — which Settings says plainly.
     */
    private fun canSelfUnlock(): Boolean =
        Prefs.voiceUnlock && Prefs.unlockPin != null && VibeAccessibility.instance != null

    override fun canVoiceUnlock(): Boolean = canSelfUnlock()

    override suspend fun ensureUnlocked(): Boolean {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) return true
        val byVoice = canVoiceUnlock()
        withContext(Dispatchers.Main) {
            showLockUi(force = true)
            VibeBus.unlockRequestAt.value = SystemClock.elapsedRealtime()
        }
        if (byVoice) {
            val pin = Prefs.unlockPin
            val acc = VibeAccessibility.instance
            if (pin != null && acc != null) {
                VibeBus.event("সেভ করা PIN দিয়ে আনলক করছি")
                VibeBus.action("🔓 আনলক করছি")
                // 1. A dark screen shows no PIN pad: light it first.
                if (!isScreenOn()) {
                    @Suppress("DEPRECATION")
                    getSystemService(PowerManager::class.java).newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP, "vibe:unlock"
                    ).acquire(4000)
                }
                delay(900) // the unlock screen (PIN pad) needs a moment to appear
                // 2. Still the clock face, no keypad? Swipe up, as a person would.
                if (acc.pinKeys() == null) {
                    VibeBus.event("PIN pad দেখা যাচ্ছে না — উপরে swipe করছি")
                    acc.swipe("up")
                    delay(900)
                }
                // 3. Type it — once.
                if (acc.typePin(pin)) {
                    VibeBus.event("PIN টাইপ হয়েছে")
                } else {
                    VibeBus.event("PIN-এর বোতাম পড়া যাচ্ছে না — এই ফোনের লক স্ক্রিন Vibe-কে দেখতে দিচ্ছে না। নিজে আনলক করো।")
                }
            }
        }
        repeat(60) {
            delay(500)
            if (!km.isKeyguardLocked) {
                delay(700) // let the unlocked screen settle
                return true
            }
        }
        return false
    }

    override fun endConversationSoon() {
        main.post {
            val start = SystemClock.elapsedRealtime()
            val waiter = object : Runnable {
                override fun run() {
                    if (!inConversation) return
                    val speaking = speaker?.isSpeaking() == true
                    if (speaking && SystemClock.elapsedRealtime() - start < 8000) {
                        main.postDelayed(this, 300)
                    } else {
                        endConversation()
                    }
                }
            }
            main.postDelayed(waiter, 1200)
        }
    }

    override fun ringingNumber(): String? = if (ringing) (ringNumber ?: "unknown") else null

    override fun sendImageToLive(jpegB64: String): Boolean = client?.sendImage(jpegB64) == true

    // ── Live screen sharing (like Gemini Live) ───────────────────────────────

    private var shareJob: Job? = null

    override fun setScreenShare(on: Boolean): String? {
        if (!on) {
            stopScreenShare()
            return null
        }
        if (Build.VERSION.SDK_INT < 30) return "Live screen sharing needs Android 11 or newer."
        if (VibeAccessibility.instance == null) return "Screen control (Accessibility) is off, so Vibe cannot see the screen."
        if (shareJob?.isActive == true) return null
        VibeBus.screenSharing.value = true
        shareJob = scope.launch {
            val started = SystemClock.elapsedRealtime()
            var lastSent = 0L
            var lastSeenEvent = -1L
            var lastBlocked = false
            while (inConversation && VibeBus.screenSharing.value &&
                SystemClock.elapsedRealtime() - started < 10 * 60_000L
            ) {
                val acc = VibeAccessibility.instance ?: break
                val now = SystemClock.elapsedRealtime()
                val changed = acc.lastEventAt != lastSeenEvent
                // A frame when the screen changed (at most every ~1.5 s), and every 5 s anyway.
                if ((changed && now - lastSent > 1400) || now - lastSent > 5000) {
                    val pkg = acc.rootInActiveWindow?.packageName?.toString().orEmpty()
                    val blocked = com.vibe.assistant.agent.PhoneAgent(this@VibeService).isBlocked(pkg)
                    if (blocked) {
                        if (!lastBlocked) client?.sendText("[SCREEN] The user opened a private app ($pkg); screen frames are paused for safety.")
                    } else {
                        lastSeenEvent = acc.lastEventAt
                        acc.shot(1024, 70)?.let { client?.sendImage(it.b64) }
                    }
                    lastBlocked = blocked
                    lastSent = SystemClock.elapsedRealtime()
                }
                delay(400)
            }
            VibeBus.screenSharing.value = false
        }
        return null
    }

    private fun stopScreenShare() {
        shareJob?.cancel()
        shareJob = null
        VibeBus.screenSharing.value = false
    }

    // ── Calls with proof ─────────────────────────────────────────────────────

    @Volatile private var pausedForCall = false

    /** Lets go of the mic, speaker and call-audio mode, but keeps the Live session open. */
    private fun pauseAudio() {
        mic?.stop(); mic = null
        speaker?.stop(); speaker = null
        route.stop()
    }

    private fun resumeAudio() {
        if (!inConversation) return
        route.start()
        speaker = Speaker().also { it.start() }
        mic = Mic { onMic(it) }.also { if (!it.start()) VibeBus.event("মাইক আবার চালু হয়নি") }
    }

    private suspend fun waitForCall(ms: Long): Boolean {
        val start = SystemClock.elapsedRealtime()
        while (!inCall && SystemClock.elapsedRealtime() - start < ms) delay(200)
        return inCall
    }

    /** After a verified call: Vibe's answer goes out, then the session closes for the call. */
    private fun endForCall() {
        main.postDelayed({
            pausedForCall = false
            if (inConversation) endConversation(resumeWake = false)
        }, 700)
    }

    override suspend fun callAndVerify(number: String): String = withContext(Dispatchers.Main) {
        pausedForCall = true
        pauseAudio()
        var r = PhoneActions.placeCall(this@VibeService, number)
        // Some phones refuse to start a call from the lock screen: unlock (if allowed) and try once more.
        if (r == "ok" && !waitForCall(8000) && isLocked() && canSelfUnlock()) {
            VibeBus.event("লক থাকায় কল যায়নি — আনলক করে আবার চেষ্টা")
            if (ensureUnlocked()) {
                r = PhoneActions.placeCall(this@VibeService, number)
                if (r == "ok") waitForCall(8000)
            }
        }
        if (r == "ok" && inCall) {
            VibeBus.event("কল শুরু হয়েছে ✓")
            endForCall()
            "ok"
        } else {
            pausedForCall = false
            resumeAudio()
            if (r != "ok") r
            else "error: the phone did not go into a call within 8 seconds" +
                (if (isLocked()) " (the phone is locked — ask the user to unlock, or turn on self-unlock in Vibe settings)" else "")
        }
    }

    override suspend fun answerAndVerify(speaker: Boolean): String = withContext(Dispatchers.Main) {
        pausedForCall = true
        pauseAudio()
        val r = PhoneActions.answer(this@VibeService, speaker)
        if (r == "ok" && waitForCall(5000)) {
            endForCall()
            "ok"
        } else {
            pausedForCall = false
            resumeAudio()
            if (r != "ok") r else "error: the call did not connect"
        }
    }

    override fun showContactChoices(contacts: List<PhoneActions.Contact>) {
        main.post {
            if (!inConversation) return@post
            overlay.showChoices(
                "কাকে?",
                contacts.map { "${it.name}  ·  ${it.number}" to "${it.name} (${it.number})" }
            ) { picked ->
                VibeBus.action("👆 $picked")
                client?.sendText("[CHOICE] The user tapped \"$picked\" on the screen card. Use exactly this contact and continue the request.")
            }
        }
    }

    /** The user pressed "stop": cancel every screen task and tell Gemini it was stopped. */
    private fun stopScreenTasks() {
        if (screenCalls.isEmpty()) return
        val live = client
        for ((id, name) in screenCalls.toMap()) {
            toolJobs.remove(id)?.cancel()
            if (live != null && live.ready) {
                live.sendToolResponse(id, name, JSONObject().put("error",
                    "The user stopped this task with the stop button. Say briefly that you stopped, and don't retry unless asked."))
            }
        }
        screenCalls.clear()
        VibeBus.controllingScreen = false
        VibeAccessibility.instance?.hideShield()
        VibeBus.status.value = ""
        VibeBus.action("✋ কাজ থামানো হলো")
        VibeBus.event("তুমি কাজ থামিয়েছ")
        updateNotification()
    }

    // ── "I'm using the phone right now" ──────────────────────────────────────

    private val warnPhrase = "এখন আমি ফোনটা ব্যবহার করছি, একটু অপেক্ষা করো।"
    @Volatile private var warnPcm: ByteArray? = null
    @Volatile private var warnVoice = ""
    private var lastWarnAt = 0L

    /** Prepares the warning in Vibe's own voice ahead of time, so it plays instantly. */
    private fun prepareWarning() {
        if (warnPcm != null && warnVoice == Prefs.voice) return
        val voice = Prefs.voice
        val file = java.io.File(filesDir, "warn_$voice.pcm")
        learnScope.launch {
            try {
                val pcm = if (file.exists()) file.readBytes()
                else com.vibe.assistant.tools.VoiceNote.speak(warnPhrase).also { file.writeBytes(it) }
                warnPcm = pcm
                warnVoice = voice
            } catch (_: Exception) {}
        }
    }

    /** The user touched the screen while Vibe was working it. */
    fun onUserTouchedDuringTask() {
        main.post {
            val now = SystemClock.elapsedRealtime()
            if (now - lastWarnAt < 8000) return@post
            lastWarnAt = now
            VibeBus.action("✋ $warnPhrase")
            VibeBus.event("কাজের মাঝে তুমি স্ক্রিনে চাপ দিয়েছ")
            val pcm = warnPcm
            val sp = speaker
            if (inConversation && pcm != null && sp != null) {
                sp.enqueue(pcm)
            } else {
                android.widget.Toast.makeText(this, warnPhrase, android.widget.Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun releaseAudioThen(action: () -> Unit) {
        main.post {
            val start = SystemClock.elapsedRealtime()
            val waiter = object : Runnable {
                override fun run() {
                    val speaking = speaker?.isSpeaking() == true
                    val waited = SystemClock.elapsedRealtime() - start
                    // Give Vibe ~1 s to start its short line, then let it finish (max 5 s).
                    if (inConversation && (waited < 1000 || (speaking && waited < 5000))) {
                        main.postDelayed(this, 200)
                        return
                    }
                    endConversation(resumeWake = false)
                    main.postDelayed({
                        try { action() } catch (e: Exception) { Log.w(TAG, "after-release action failed", e) }
                    }, 300)
                }
            }
            main.postDelayed(waiter, 200)
        }
    }

    // ── Lock screen ──────────────────────────────────────────────────────────

    private fun showLockUi(force: Boolean) {
        if (!force && !Prefs.lockScreenShow) return
        val i = Intent(this, LockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_USER_ACTION)
        // With "display over other apps" Android lets us open the screen directly;
        // the full-screen notification is the route that works without it.
        if (Settings.canDrawOverlays(this) || VibeAccessibility.instance != null) {
            try { DeviceActions.launch(this, i) } catch (_: Exception) {}
        }
        val pi = PendingIntent.getActivity(
            this, 7, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, VibeApp.CH_WAKE)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle("Vibe")
            .setContentText("শুনছি…")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setFullScreenIntent(pi, true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setTimeoutAfter(120_000)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(LOCK_NOTIF_ID, n)
        } catch (_: SecurityException) {}
    }

    private fun cancelLockNotification() {
        getSystemService(NotificationManager::class.java).cancel(LOCK_NOTIF_ID)
    }

    // ── Notification & wake lock ─────────────────────────────────────────────

    private fun servicePi(action: String, code: Int): PendingIntent =
        PendingIntent.getForegroundService(
            this, code, Intent(this, VibeService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

    private fun buildNotification(): Notification {
        val st = VibeBus.state.value
        val text = if (st == VState.ERROR) VibeBus.status.value.ifBlank { VibeBus.label(st) }
        else if (Prefs.pushToTalk && VibeBus.active(st)) "ভলিউম-ডাউন বা বাটন চেপে ধরে কথা বলো"
        else VibeBus.label(st)
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, VibeApp.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_vibe)
            .setContentTitle("Vibe")
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(0, if (VibeBus.active(st)) "থামাও" else "কথা বলো", servicePi(A_TOGGLE, 2))
            .apply {
                // While Vibe is working the screen, one tap stops it (Lucy's stop notification).
                if (VibeBus.controllingScreen) addAction(0, "✋ কাজ থামাও", servicePi(A_STOP_TASK, 4))
                else addAction(0, "বন্ধ করো", servicePi(A_QUIT, 3))
            }
            .build()
    }

    private fun updateNotification() {
        if (!fgStarted) return
        try {
            getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildNotification())
        } catch (_: SecurityException) {}
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vibe:conversation")
            .apply {
                setReferenceCounted(false)
                acquire(6 * 60 * 60 * 1000L)
            }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }
}
