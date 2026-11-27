package com.vibe.assistant.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vibe.assistant.R
import com.vibe.assistant.core.Line
import com.vibe.assistant.core.Role
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.KeyChecker
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import com.vibe.assistant.update.Updater
import com.vibe.assistant.wake.WakeModelDownloader
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            val after = pendingAfterPerm
            pendingAfterPerm = null
            if (Flows.hasMic(this)) after?.invoke()
            else Toast.makeText(this, "মাইকের অনুমতি ছাড়া Vibe শুনতে পারবে না", Toast.LENGTH_LONG).show()
            refreshChecklist()
        }
    private var pendingAfterPerm: (() -> Unit)? = null
    private val phoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            refreshChecklist()
            startBackground()
            // "Hey Vibe" is on from the start, so make sure the phone won't kill it.
            Flows.askBattery(this)
        }

    private var homeShown = false
    private var homeJob: Job? = null
    private lateinit var statusTv: TextView
    private lateinit var hintTv: TextView
    private lateinit var talkBtn: TextView
    private lateinit var wakeChip: TextView
    private lateinit var overlayChip: TextView
    private lateinit var transcriptScroll: ScrollView
    private lateinit var transcriptBox: LinearLayout
    private lateinit var checklist: LinearLayout
    private lateinit var tCard: LinearLayout
    private lateinit var updateCard: LinearLayout
    private lateinit var updateText: TextView
    private lateinit var updateBar: android.widget.ProgressBar
    private lateinit var updateButton: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Prefs.apiKey == null) showSetup() else showHome()
    }

    override fun onResume() {
        super.onResume()
        if (Prefs.apiKey == null) return
        if (!homeShown) showHome()
        startBackground()
        com.vibe.assistant.update.Updater.autoCheck(this, onOpen = true)
        if (::updateCard.isInitialized) renderUpdate()
        // "Hey Vibe" is on by default: fetch its offline model in the background the
        // first time, with no prompt. Progress shows under the orb.
        if (Prefs.wakeEnabled && Flows.hasMic(this) &&
            !com.vibe.assistant.wake.WakeWord.isModelReady(this) && !WakeModelDownloader.busy
        ) {
            val app = applicationContext
            WakeModelDownloader.start(this) { VibeService.send(app, VibeService.A_ENSURE) }
        }
        refreshChips()
        refreshChecklist()
    }

    private fun startBackground() {
        if (Flows.hasMic(this)) VibeService.send(this, VibeService.A_ENSURE)
    }

    private fun withPermissions(after: () -> Unit) {
        if (Flows.hasMic(this) && Flows.hasNotifications(this)) {
            after()
            return
        }
        pendingAfterPerm = after
        permLauncher.launch(Flows.runtimePermissions())
    }

    // ── First run: the API key ───────────────────────────────────────────────

    private fun showSetup() {
        homeShown = false
        homeJob?.cancel()
        val root = FrameLayout(this).apply { background = screenBackground() }
        val scroll = ScrollView(this).apply { isFillViewport = true }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(28), dp(24), dp(28))
        }

        col.addView(OrbView(this).apply { forcedState = VState.WAKE }, lp(dp(190), dp(190)))
        col.addView(
            label("Vibe", 46f, VC.TEXT, VC.bold, Gravity.CENTER).apply { gradientText() },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT)
        )
        col.addView(
            label("তোমার নিজের ভয়েস অ্যাসিস্ট্যান্ট", 16f, VC.SUB, gravity = Gravity.CENTER),
            lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(2), b = dp(26))
        )

        val c = card()
        c.addView(label("শুরু করতে Gemini API key দাও", 19f, VC.TEXT, VC.medium))
        c.addView(
            label(
                "১.  নিচের বাটনে Google AI Studio খোলো\n" +
                    "২.  “Create API key” চাপো\n" +
                    "৩.  key কপি করে এখানে পেস্ট করো",
                14.5f, VC.SUB
            ),
            lp().margins(t = dp(10))
        )
        c.addView(
            secondaryButton("AI Studio খোলো  ↗") { Flows.openUrl(this, "https://aistudio.google.com/apikey") },
            lp().margins(t = dp(16))
        )

        val (field, input) = keyField()
        c.addView(field, lp().margins(t = dp(14)))
        val err = label("", 13.5f, VC.RED).apply { visibility = View.GONE }
        c.addView(err, lp().margins(t = dp(8)))

        lateinit var go: TextView
        val submit = {
            val key = input.text.toString().trim()
            if (key.isEmpty()) {
                err.text = "আগে key পেস্ট করো"
                err.visibility = View.VISIBLE
            } else {
                err.visibility = View.GONE
                go.isEnabled = false
                go.text = "যাচাই হচ্ছে…"
                lifecycleScope.launch {
                    when (val r = KeyChecker.check(key)) {
                        is KeyChecker.Result.Ok -> {
                            Prefs.apiKey = key
                            Prefs.liveModels = r.liveModels
                            if (Prefs.model !in r.liveModels) Prefs.model = r.liveModels.first()
                            showHome()
                            withPermissions {
                                startBackground()
                                if (!Flows.hasPhone(this@MainActivity)) phoneLauncher.launch(Flows.PHONE_PERMISSIONS)
                                else Flows.askBattery(this@MainActivity)
                            }
                        }
                        is KeyChecker.Result.Bad -> {
                            err.text = r.message
                            err.visibility = View.VISIBLE
                            go.isEnabled = true
                            go.text = "চালু করো"
                        }
                    }
                }
            }
        }
        go = primaryButton("চালু করো") { submit() }
        input.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { submit(); true } else false
        }
        c.addView(go, lp().margins(t = dp(14)))
        col.addView(c, lp())

        col.addView(
            label(
                "key শুধু এই ফোনেই এনক্রিপ্ট করে রাখা হয়। পরে সেটিংস থেকে বদলাতে পারবে।",
                12.5f, VC.FAINT, gravity = Gravity.CENTER
            ),
            lp().margins(t = dp(18))
        )

        scroll.addView(col)
        root.addView(scroll)
        setContentView(root)
        edgeToEdge(root)
    }

    // ── Home ─────────────────────────────────────────────────────────────────

    private fun showHome() {
        homeShown = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = screenBackground()
        }

        // Top bar
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(10), dp(12), dp(4))
        }
        bar.addView(
            label("Vibe", 28f, VC.TEXT, VC.bold).apply { gradientText() },
            lp(0).apply { weight = 1f }
        )
        bar.addView(iconButton(R.drawable.ic_settings) {
            startActivity(Intent(this, SettingsActivity::class.java))
        })
        root.addView(bar)

        // Orb
        val orbBox = FrameLayout(this)
        val orb = OrbView(this).apply {
            isClickable = true
            contentDescription = "Vibe-এর সাথে কথা বলো"
        }
        Flows.holdToTalk(orb) { ensureMic() }
        orbBox.addView(orb, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))
        root.addView(orbBox, lp(h = 0).apply { weight = 1f })

        statusTv = label("", 21f, VC.TEXT, VC.medium, Gravity.CENTER)
        root.addView(statusTv, lp().margins(l = dp(24), r = dp(24)))
        hintTv = label("", 13.5f, VC.SUB, gravity = Gravity.CENTER)
        root.addView(hintTv, lp().margins(l = dp(28), t = dp(4), r = dp(28)))

        // Transcript
        tCard = card().apply { setPadding(dp(16), dp(12), dp(16), dp(12)) }
        transcriptScroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        transcriptBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        transcriptScroll.addView(transcriptBox)
        tCard.addView(transcriptScroll, lp(h = dp(132)))
        root.addView(tCard, lp().margins(l = dp(16), t = dp(16), r = dp(16)))

        // Toggles
        val chips = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        wakeChip = chip { onWakeChip() }
        overlayChip = chip { onOverlayChip() }
        chips.addView(wakeChip, lp(0).apply { weight = 1f }.margins(r = dp(6)))
        chips.addView(overlayChip, lp(0).apply { weight = 1f }.margins(l = dp(6)))
        root.addView(chips, lp().margins(l = dp(16), t = dp(12), r = dp(16)))

        // What is still missing
        // Update card: the new version downloads inside the app, then the installer opens.
        updateCard = card().apply { visibility = View.GONE }
        updateText = label("", 14.5f, VC.TEXT, VC.medium)
        updateCard.addView(updateText)
        updateBar = android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = android.content.res.ColorStateList.valueOf(VC.A2)
        }
        updateCard.addView(updateBar, lp().margins(t = dp(8)))
        updateButton = smallButton("") {}
        updateCard.addView(updateButton, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(10)))
        root.addView(updateCard, lp().margins(l = dp(16), t = dp(12), r = dp(16)))

        checklist = card().apply { visibility = View.GONE }
        root.addView(checklist, lp().margins(l = dp(16), t = dp(12), r = dp(16)))

        talkBtn = primaryButton("কথা বলো") {}
        Flows.holdToTalk(talkBtn) { ensureMic() }
        root.addView(talkBtn, lp().margins(l = dp(24), t = dp(14), r = dp(24), b = dp(18)))

        setContentView(root)
        edgeToEdge(root)
        observe()
        refreshChips()
        refreshChecklist()
    }

    // Called once per home screen build (also from onResume on first show); the previous
    // collector is cancelled first, so collectors never pile up.
    @android.annotation.SuppressLint("RepeatOnLifecycleWrongUsage")
    private fun observe() {
        homeJob?.cancel()
        homeJob = lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        VibeBus.state, VibeBus.status,
                        WakeModelDownloader.progress, WakeModelDownloader.error
                    ) { s, t, p, e -> arrayOf<Any?>(s, t, p, e) }
                        .collect { a -> render(a[0] as VState, a[1] as String, a[2] as Int, a[3] as String?) }
                }
                launch { VibeBus.lines.collect { renderLines(it) } }
                launch {
                    combine(
                        com.vibe.assistant.update.Updater.stage,
                        com.vibe.assistant.update.Updater.progress,
                    ) { _, _ -> }.collect { renderUpdate() }
                }
                launch {
                    VibeBus.pttHeld.collect {
                        render(
                            VibeBus.state.value, VibeBus.status.value,
                            WakeModelDownloader.progress.value, WakeModelDownloader.error.value
                        )
                    }
                }
            }
        }
    }

    private fun render(st: VState, status: String, progress: Int, dlError: String?) {
        statusTv.text = when {
            st == VState.ERROR && status.isNotBlank() -> status
            st == VState.CONNECTING && status.isNotBlank() -> status
            else -> VibeBus.label(st)
        }
        statusTv.setTextColor(if (st == VState.ERROR) VC.RED else VC.TEXT)
        hintTv.text = when {
            progress in 0..100 -> "“হেই Vibe” মডেল নামছে… $progress%"
            progress == 101 -> "মডেল খোলা হচ্ছে…"
            dlError != null -> dlError
            st == VState.WAKE -> "ফোন লক থাকলেও শুনবে"
            Prefs.pushToTalk && VibeBus.active(st) -> "বল বা নিচের বাটন চেপে ধরে বলো, ছেড়ে দিলে উত্তর দেবে"
            st == VState.LISTENING || st == VState.SPEAKING -> "Vibe বলার সময় তুমি কথা বললেই সে থেমে শুনবে"
            st == VState.OFF && !Prefs.wakeEnabled -> "“হেই Vibe” চালু করলে হাত না লাগিয়েই ডাকতে পারবে"
            else -> ""
        }
        talkBtn.text = when {
            Prefs.pushToTalk && VibeBus.pttHeld.value -> "ছেড়ে দিলেই Vibe উত্তর দেবে"
            Prefs.pushToTalk -> "চেপে ধরে কথা বলো"
            VibeBus.active(st) -> "থামাও"
            else -> "কথা বলো"
        }
        tCard.visibility = if (Prefs.showTranscript) View.VISIBLE else View.GONE
        refreshChips()
    }

    private fun renderLines(lines: List<Line>) {
        if (lines.isEmpty()) {
            transcriptBox.removeAllViews()
            transcriptBox.addView(
                label("কথোপকথন এখানে দেখা যাবে", 14f, VC.FAINT, gravity = Gravity.CENTER),
                lp().margins(t = dp(44))
            )
            return
        }
        val same = transcriptBox.childCount == lines.size && transcriptBox.getChildAt(0) !is TextView
        if (same) {
            (transcriptBox.getChildAt(lines.size - 1) as LinearLayout).getChildAt(1).let {
                (it as TextView).text = lines.last().text
            }
        } else {
            transcriptBox.removeAllViews()
            for (l in lines) transcriptBox.addView(lineView(l), lp().margins(b = dp(8)))
        }
        transcriptScroll.post { transcriptScroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun renderUpdate() {
        val U = com.vibe.assistant.update.Updater
        val ver = Prefs.updateVersion
        val newer = ver.isNotBlank() && U.isNewer(ver, U.currentVersion(this))
        val st = U.stage.value
        if (!newer || st == Updater.Stage.IDLE) {
            updateCard.visibility = View.GONE
            return
        }
        updateCard.visibility = View.VISIBLE
        when (st) {
            Updater.Stage.DOWNLOADING -> {
                updateText.text = "নতুন ভার্সন $ver নামছে… ${U.progress.value}%"
                updateBar.visibility = View.VISIBLE
                updateBar.progress = U.progress.value
                updateButton.visibility = View.GONE
            }
            Updater.Stage.READY -> {
                updateBar.visibility = View.GONE
                updateButton.visibility = View.VISIBLE
                if (!U.canInstall(this)) {
                    updateText.text = "ভার্সন $ver নামানো শেষ। install করতে একবার অনুমতি দাও।"
                    updateButton.text = "অনুমতি দাও"
                    updateButton.setOnClickListener { U.openInstallPermission(this) }
                } else {
                    updateText.text = "ভার্সন $ver নামানো শেষ (Download ফোল্ডারে)।"
                    updateButton.text = "এখনই install করো"
                    updateButton.setOnClickListener { U.installNow(this) }
                }
            }
            Updater.Stage.FAILED -> {
                updateText.text = "আপডেট নামানো যায়নি।"
                updateBar.visibility = View.GONE
                updateButton.visibility = View.VISIBLE
                updateButton.text = "আবার চেষ্টা করো"
                updateButton.setOnClickListener { U.checkNow(this) }
            }
            else -> updateCard.visibility = View.GONE
        }
    }

    private fun lineView(l: Line): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val user = l.role == Role.USER
        addView(label(if (user) "তুমি" else "Vibe", 11.5f, if (user) VC.A1 else VC.A3, VC.medium))
        addView(label(l.text, 15f, if (user) VC.SUB else VC.TEXT), lp().margins(t = dp(1)))
    }

    /** True if the mic is allowed; otherwise asks for it (this press is not acted on). */
    private fun ensureMic(): Boolean {
        if (Flows.hasMic(this)) return true
        withPermissions { startBackground() }
        return false
    }

    // ── Toggles & checklist ──────────────────────────────────────────────────

    private fun onWakeChip() {
        if (Prefs.wakeEnabled) {
            Flows.disableWake(this)
            refreshChips()
            return
        }
        if (WakeModelDownloader.busy) return
        withPermissions { Flows.enableWake(this) { refreshChips(); refreshChecklist() } }
    }

    private fun onOverlayChip() {
        if (Prefs.overlayEnabled && Flows.canOverlay(this)) Flows.disableOverlay(this)
        else Flows.enableOverlay(this)
        refreshChips()
        refreshChecklist()
    }

    private fun refreshChips() {
        if (!homeShown) return
        val downloading = WakeModelDownloader.busy
        setChip(
            wakeChip, Prefs.wakeEnabled,
            when {
                downloading -> "হেই Vibe  ·  নামছে…"
                Prefs.wakeEnabled -> "হেই Vibe  ·  চালু"
                else -> "হেই Vibe  ·  বন্ধ"
            }
        )
        val overlayOn = Prefs.overlayEnabled && Flows.canOverlay(this)
        setChip(overlayChip, overlayOn, if (overlayOn) "ভাসমান বাটন  ·  চালু" else "ভাসমান বাটন  ·  বন্ধ")
    }

    private fun refreshChecklist() {
        if (!homeShown) return
        data class Item(val title: String, val why: String, val fix: () -> Unit)
        val items = mutableListOf<Item>()
        if (!Flows.hasMic(this)) items += Item("মাইকের অনুমতি", "তোমার কথা শোনার জন্য") { withPermissions { startBackground() } }
        if (!Flows.hasNotifications(this)) items += Item("নোটিফিকেশন", "ব্যাকগ্রাউন্ডে চলার চিহ্ন দেখাতে") { withPermissions { startBackground() } }
        if (!Flows.hasPhone(this)) items += Item("কল, কন্টাক্ট ও SMS", "কল করা, কল ধরা, মেসেজ পাঠানোর জন্য") { phoneLauncher.launch(Flows.PHONE_PERMISSIONS) }
        if (!Flows.hasScreenControl(this)) items += Item("স্ক্রিন কন্ট্রোল (Accessibility)", "সব অ্যাপ চালানো, ট্যাপ-টাইপ, ভলিউম চেপে কথা") { Flows.openAccessibility(this) }
        if (Prefs.overlayEnabled && !Flows.canOverlay(this)) items += Item("অন্য অ্যাপের উপরে দেখানো", "ভাসমান বাটন আর লক স্ক্রিনে জেগে ওঠার জন্য") { Flows.openOverlaySettings(this) }
        if (Prefs.wakeEnabled && !Flows.batteryFree(this)) items += Item("ব্যাটারি সীমা থেকে ছাড়", "যাতে ফোন Vibe-কে বন্ধ না করে") { Flows.openBatterySettings(this) }
        if (Prefs.lockScreenShow && !Flows.canFullScreen(this)) items += Item("লক স্ক্রিনে দেখানো", "লক অবস্থায় Vibe-এর স্ক্রিন জ্বালাতে") { Flows.openFullScreenSettings(this) }

        checklist.removeAllViews()
        checklist.visibility = if (items.isEmpty()) View.GONE else View.VISIBLE
        if (items.isEmpty()) return
        checklist.addView(label("আরও ভালোভাবে চালাতে", 14f, VC.SUB, VC.medium))
        for (it in items) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(it.title, 15f, VC.TEXT, VC.medium))
            text.addView(label(it.why, 12.5f, VC.FAINT))
            row.addView(text, lp(0).apply { weight = 1f })
            row.addView(smallButton("চালু করো") { it.fix() })
            checklist.addView(row, lp().margins(t = dp(10)))
        }
    }

    // ── small builders ───────────────────────────────────────────────────────

    private fun chip(onClick: () -> Unit): TextView =
        label("", 13.5f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
            minHeight = dp(44)
            setPadding(dp(10), 0, dp(10), 0)
            isClickable = true
            setOnClickListener { onClick() }
        }

    private fun setChip(v: TextView, on: Boolean, text: String) {
        v.text = text
        val r = dpf(22f)
        v.background = ripple(
            if (on) rounded(VC.BLUE_SOFT, r, VC.A2, dp(1)) else rounded(VC.CARD, r, VC.STROKE, dp(1)),
            rounded(Color.WHITE, r)
        )
        v.setTextColor(if (on) VC.TEXT else VC.SUB)
    }

    private fun iconButton(res: Int, onClick: () -> Unit): ImageView = ImageView(this).apply {
        setImageResource(res)
        scaleType = ImageView.ScaleType.CENTER
        background = ripple(null, rounded(Color.WHITE, dpf(22f)))
        layoutParams = LinearLayout.LayoutParams(dp(44), dp(44))
        isClickable = true
        setOnClickListener { onClick() }
    }
}

/** A password-style key box with a show/hide toggle. */
fun android.content.Context.keyField(): Pair<LinearLayout, EditText> {
    val input = EditText(this).apply {
        hint = "AIza…"
        setHintTextColor(VC.FAINT)
        setTextColor(VC.TEXT)
        textSize = 16f
        typeface = Typeface.MONOSPACE
        isSingleLine = true
        imeOptions = EditorInfo.IME_ACTION_DONE
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        background = null
        setPadding(dp(16), dp(14), dp(8), dp(14))
    }
    val row = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = rounded(0xFF0E0E1C.toInt(), dpf(16f), VC.STROKE, dp(1))
    }
    row.addView(input, lp(0).apply { weight = 1f })
    var shown = false
    lateinit var eye: TextView
    eye = label("দেখাও", 13f, VC.SUB, VC.medium, Gravity.CENTER).apply {
        setPadding(dp(14), dp(12), dp(14), dp(12))
        isClickable = true
        setOnClickListener {
            shown = !shown
            input.inputType = InputType.TYPE_CLASS_TEXT or
                if (shown) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD else InputType.TYPE_TEXT_VARIATION_PASSWORD
            input.typeface = Typeface.MONOSPACE
            input.setSelection(input.text.length)
            eye.text = if (shown) "লুকাও" else "দেখাও"
        }
    }
    row.addView(eye)
    return row to input
}
