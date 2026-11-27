package com.vibe.assistant.ui

import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vibe.assistant.R
import com.vibe.assistant.agent.GeminiText
import com.vibe.assistant.agent.OpenAiCompat
import com.vibe.assistant.core.Memory
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.KeyChecker
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.data.VOICES
import com.vibe.assistant.data.modelLabel
import com.vibe.assistant.data.modelNote
import com.vibe.assistant.service.VibeService
import com.vibe.assistant.update.Updater
import com.vibe.assistant.wake.WakeModelDownloader
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var keyText: TextView
    private lateinit var voiceList: LinearLayout
    private lateinit var modelList: LinearLayout
    private lateinit var permList: LinearLayout
    private lateinit var modeList: LinearLayout
    private lateinit var memoryList: LinearLayout
    private lateinit var memoryTitle: TextView
    private lateinit var logText: TextView
    private lateinit var updateText: TextView
    private var voiceFilter: Boolean? = null // null = all, true = female, false = male
    private val filterChips = mutableListOf<Pair<Boolean?, TextView>>()
    private val switches = mutableListOf<Pair<Switch, () -> Boolean>>()
    private var showAllMemory = false
    private lateinit var agentKeyText: TextView
    private lateinit var agentModelField: AutoCompleteTextView
    private lateinit var agentStatus: TextView
    private lateinit var home: LinearLayout
    private lateinit var pageHolder: LinearLayout
    private lateinit var titleView: TextView
    private lateinit var scroll: ScrollView
    private var openPage: LinearLayout? = null

    private val phoneLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { renderPermissions() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = screenBackground()
        }

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), dp(8), dp(16), dp(8))
        }
        bar.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_back)
            scaleType = ImageView.ScaleType.CENTER
            background = ripple(null, rounded(Color.WHITE, dpf(22f)))
            isClickable = true
            setOnClickListener { goBack() }
        }, LinearLayout.LayoutParams(dp(44), dp(44)))
        titleView = label("সেটিংস", 22f, VC.TEXT, VC.bold)
        bar.addView(titleView, lp(0).apply { weight = 1f }.margins(l = dp(6)))
        root.addView(bar)

        scroll = ScrollView(this)
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(28))
        }
        home = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        pageHolder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        outer.addView(home)
        outer.addView(pageHolder)
        scroll.addView(outer)
        root.addView(scroll, lp(h = 0).apply { weight = 1f })

        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goBack()
        })

        // Categories: the home list shows only these; each opens its own short page.
        home.addView(label("কী বদলাতে চাও?", 13.5f, VC.FAINT), lp().margins(t = dp(6), b = dp(12)))
        val pTalk = newPage("🗣", "কথা ও কণ্ঠ", "কথা বলার ধরন, কণ্ঠ, কাছের কণ্ঠ, কল SIM")
        val pBehave = newPage("⚙️", "আচরণ", "হেই Vibe, ভাসমান বাটন, লক স্ক্রিন, Google Search")
        val pAi = newPage("🤖", "AI ও মডেল", "Gemini key, Live মডেল, কাজের AI")
        val pId = newPage("👤", "পরিচয় ও মেমোরি", "তোমার নাম, কে বানিয়েছে, যা মনে রেখেছে")
        val pSafe = newPage("🔒", "আনলক ও অনুমতি", "নিজে আনলক (PIN), দরকারি অনুমতি")
        val pMore = newPage("⬆️", "আপডেট ও লগ", "নতুন ভার্সন, সংযোগের লগ")
        var col = pAi

        // ── API key ──
        col.addView(section("Gemini API key"))
        col.addView(card().apply {
            keyText = label("", 16f, VC.TEXT, Typeface.MONOSPACE)
            addView(keyText)
            addView(label("শুধু এই ফোনে এনক্রিপ্ট করে রাখা আছে", 12.5f, VC.FAINT), lp().margins(t = dp(2)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(secondaryButton("key বদলাও") { changeKey() }, lp(0).apply { weight = 1f }.margins(r = dp(6)))
            row.addView(secondaryButton("AI Studio  ↗") {
                Flows.openUrl(this@SettingsActivity, "https://aistudio.google.com/apikey")
            }, lp(0).apply { weight = 1f }.margins(l = dp(6)))
            addView(row, lp().margins(t = dp(14)))
        })

        col = pId
        // ── Identity ──
        col.addView(section("পরিচয়"))
        col.addView(card().apply {
            addView(label("তোমার নাম", 14f, VC.SUB, VC.medium))
            addView(textField(Prefs.userName, "যেমন: ফারহান") { Prefs.userName = it }, lp().margins(t = dp(6)))
            addView(label("Vibe কে বানিয়েছে", 14f, VC.SUB, VC.medium), lp().margins(t = dp(14)))
            addView(textField(Prefs.creatorName, "তোমার নাম (এটাই বলবে)") { Prefs.creatorName = it }, lp().margins(t = dp(6)))
            addView(label("“তোমাকে কে বানিয়েছে” জিজ্ঞেস করলে Vibe এই নাম বলবে — Google নয়। পরের কথোপকথন থেকে কাজ করবে।",
                12.5f, VC.FAINT), lp().margins(t = dp(8)))
        })

        col = pTalk
        // ── How you talk ──
        col.addView(section("কথা বলার ধরন"))
        modeList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modeList)
        col.addView(card().apply {
            addView(switchRow(
                "ভলিউম-ডাউন চেপে ধরে কথা",
                "Push-to-talk-এ ভলিউম-ডাউন চেপে ধরে বলো, ছেড়ে দিলে উত্তর দেবে — লক স্ক্রিনেও। ছোট চাপে ভলিউম কমবে আগের মতোই। (স্ক্রিন কন্ট্রোল চালু থাকতে হবে)",
                { Prefs.pttVolumeKey }
            ) { Prefs.pttVolumeKey = it })
            addView(divider())
            addView(switchRow(
                "শুধু কাছের কণ্ঠ",
                "ফোনের কাছে যে জোরে কথা বলছে শুধু তার কথাই Gemini-তে যাবে। দূরের কথা, টিভি, রাস্তার আওয়াজ যাবে না — শব্দওয়ালা জায়গায় বোঝা আর দ্রুত উত্তর দুটোই ভালো হয়।",
                { Prefs.nearVoice }
            ) { Prefs.nearVoice = it })
            addView(label("কতটা কড়া", 14f, VC.SUB, VC.medium), lp().margins(t = dp(8)))
            addView(choiceRow(listOf(0 to "সাধারণ", 1 to "কড়া (খুব শব্দে)"), { if (Prefs.nearVoiceStrong) 1 else 0 }) {
                Prefs.nearVoiceStrong = it == 1
            }, lp().margins(t = dp(6)))
            addView(divider())
            addView(label("কল কোন SIM দিয়ে যাবে", 15.5f, VC.TEXT, VC.medium))
            addView(label("দুই SIM থাকলে আগে থেকে ঠিক করে দিলে “কোন SIM?” pop-up আর আসবে না। মুখে “SIM 2 দিয়ে কল দাও” বললেও হবে।",
                12.5f, VC.FAINT), lp().margins(t = dp(2)))
            addView(choiceRow(listOf(0 to "ফোন যা বলে", 1 to "SIM 1", 2 to "SIM 2"), { Prefs.callSim }) {
                Prefs.callSim = it
            }, lp().margins(t = dp(8)))
            addView(divider())
            addView(switchRow(
                "পাঠানোর আগে জিজ্ঞেস করবে",
                "চালু করলে SMS / WhatsApp / voice message পাঠানোর আগে Vibe পড়ে শোনাবে আর “পাঠাবো?” জিজ্ঞেস করবে। বন্ধ থাকলে বললেই সরাসরি পাঠিয়ে দেবে।",
                { Prefs.askBeforeSend }
            ) { Prefs.askBeforeSend = it })
            addView(divider())
            addView(switchRow(
                "কথার মাঝে থামানো যাবে",
                "Vibe বলার সময় তুমি কথা বললে সে থেমে শুনবে। স্পিকারে বারবার নিজে থেকেই থেমে গেলে এটা বন্ধ করো।",
                { Prefs.bargeIn }
            ) { Prefs.bargeIn = it })
        }, lp().margins(t = dp(8)))

        // ── Voice ──
        col.addView(section("কণ্ঠ"))
        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((value, text) in listOf<Pair<Boolean?, String>>(null to "সব", true to "মেয়ে", false to "ছেলে")) {
            val chip = label(text, 14f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
                minHeight = dp(38)
                setPadding(dp(18), 0, dp(18), 0)
                isClickable = true
                setOnClickListener {
                    voiceFilter = value
                    renderVoices()
                }
            }
            filterChips += value to chip
            filters.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(r = dp(8)))
        }
        col.addView(filters, lp().margins(b = dp(10)))
        voiceList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(voiceList)
        col.addView(label("নতুন কণ্ঠ পরের কথোপকথন থেকে শোনা যাবে।", 12.5f, VC.FAINT), lp().margins(t = dp(4)))

        col = pAi
        // ── Model ──
        col.addView(section("Live মডেল"))
        modelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(modelList)
        col.addView(smallButton("এই key-তে কোন মডেল আছে, আবার দেখো") { refreshModels() },
            lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(4)))

        col = pBehave
        // ── Behaviour ──
        col.addView(section("আচরণ"))
        col.addView(card().apply {
            addView(switchRow(
                "শুধু কথা (ভার্সন ১-এর মতো)",
                "চালু করলে Vibe ঠিক ভার্সন ১-এর মতো শুধু কথা বলবে — কল, মেসেজ, স্ক্রিন চালানো বন্ধ থাকবে। কথা বোঝায় পার্থক্য হয় কিনা দেখতে কাজে লাগে।",
                { Prefs.talkOnly }
            ) { on ->
                Prefs.talkOnly = on
                VibeService.send(this@SettingsActivity, VibeService.A_STOP_TALK)
            })
            addView(divider())
            addView(switchRow(
                "কথাগুলো লেখা আকারে দেখাও",
                "বন্ধ থাকলে তোমার বা Vibe-এর কথা স্ক্রিনে লেখা আসবে না — শুধু বলটা নড়বে",
                { Prefs.showTranscript }
            ) { Prefs.showTranscript = it })
            addView(divider())
            addView(switchRow(
                "“হেই Vibe”",
                "হাত না লাগিয়ে, লক স্ক্রিনেও ডাকতে পারবে",
                { Prefs.wakeEnabled }
            ) { on ->
                if (on) Flows.enableWake(this@SettingsActivity) { syncSwitches() }
                else Flows.disableWake(this@SettingsActivity)
            })
            addView(divider())
            addView(switchRow(
                "কল এলে জানাবে",
                "কে কল করছে বলবে; “ধরো” বা “কেটে দাও” বললেই হবে",
                { Prefs.callAnnounce }
            ) { Prefs.callAnnounce = it })
            addView(divider())
            addView(switchRow(
                "Google Search",
                "আবহাওয়া, খবর, দাম — এখনকার তথ্য খুঁজে বলবে",
                { Prefs.googleSearch }
            ) { Prefs.googleSearch = it })
            addView(divider())
            addView(switchRow(
                "ভাসমান বাটন",
                "সব অ্যাপের উপরে Vibe-এর বাটন। টেনে ✕-এ ফেললে সরে যায়, Vibe তবুও চলে।",
                { Prefs.overlayEnabled && Flows.canOverlay(this@SettingsActivity) }
            ) { on ->
                if (on) Flows.enableOverlay(this@SettingsActivity) else Flows.disableOverlay(this@SettingsActivity)
            })
            addView(divider())
            addView(switchRow(
                "লক স্ক্রিনে দেখাও",
                "লক অবস্থায় “হেই Vibe” বললে স্ক্রিন জ্বলে Vibe দেখাবে",
                { Prefs.lockScreenShow }
            ) { Prefs.lockScreenShow = it })
            addView(divider())
            addView(label("চুপ থাকলে কতক্ষণ পরে সংযোগ বন্ধ হবে", 15.5f, VC.TEXT, VC.medium), lp().margins(t = dp(10)))
            addView(choiceRow(
                listOf(0 to "কখনো না", 60 to "১ মি.", 120 to "২ মি.", 300 to "৫ মি."),
                { Prefs.sleepSeconds }
            ) { Prefs.sleepSeconds = it }, lp().margins(t = dp(8), b = dp(4)))
            addView(label("“কখনো না” মানে সংযোগ সবসময় থাকবে (ইন্টারনেট আর ব্যাটারি একটু বেশি লাগবে)।", 12.5f, VC.FAINT),
                lp().margins(t = dp(4)))
        })

        col = pSafe
        // ── Self-unlock ──
        col.addView(section("নিজে আনলক"))
        col.addView(card().apply {
            addView(switchRow(
                "Vibe নিজে ফোন আনলক করবে",
                "“আনলক করো” বললে, বা কোনো কাজে আনলক লাগলে, Vibe সেভ করা PIN নিজেই টাইপ করবে।",
                { Prefs.voiceUnlock && Prefs.unlockPin != null }
            ) { on ->
                if (on) askPin() else Prefs.voiceUnlock = false
            })
            addView(label(
                "⚠️ কণ্ঠ মিলিয়ে দেখা হয় না — এটা চালু থাকলে পাশের যে কেউ “হেই Vibe, আনলক করো” বলে ফোন খুলতে পারবে। " +
                    "PIN শুধু এই ফোনে এনক্রিপ্ট করে থাকে, Gemini-র মেমোরিতে যায় না। স্ক্রিন কন্ট্রোল (Accessibility) চালু থাকতে হবে।",
                12.5f, VC.FAINT), lp().margins(t = dp(6)))
            addView(smallButton("PIN দাও / বদলাও") { askPin() }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(10)))
        })

        col = pId
        // ── Memory ──
        col.addView(section("মেমোরি — যা Vibe মনে রেখেছে"))
        col.addView(card().apply {
            memoryTitle = label("", 14f, VC.SUB)
            addView(memoryTitle)
            memoryList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            addView(memoryList, lp().margins(t = dp(8)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(smallButton("সব দেখাও") {
                showAllMemory = !showAllMemory
                renderMemory(Memory.factsFlow.value)
            }, lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(r = dp(8)))
            row.addView(smallButton("সব মুছে ফেলো") {
                Flows.dialog(this@SettingsActivity)
                    .setTitle("সব মেমোরি মুছবে?")
                    .setMessage("Vibe তোমার সম্পর্কে যা মনে রেখেছে আর যত কাজ শিখেছে, সব মুছে যাবে। ফেরত আনা যাবে না।")
                    .setPositiveButton("মুছে ফেলো") { _, _ -> Memory.clearAll() }
                    .setNegativeButton("না", null)
                    .show()
            })
            addView(row, lp().margins(t = dp(10)))
        })

        col = pAi
        // ── Screen-task AI ──
        col.addView(section("কাজের AI (স্ক্রিন দেখে কাজ)"))
        col.addView(card().apply {
            addView(label("কথা বলবে Gemini Live; স্ক্রিনশট দেখে দেখে ট্যাপ-টাইপের কাজ করবে এই AI। " +
                "যেকোনো OpenAI-compatible প্রোভাইডার, যার মডেল ছবি বোঝে। খালি রাখলে Gemini-ই করবে।", 12.5f, VC.FAINT))
            addView(switchRow("এই AI দিয়ে কাজ করাও", "বন্ধ করলে আবার Gemini দিয়ে কাজ হবে",
                { Prefs.agentCustomOn }) { Prefs.agentCustomOn = it; renderAgent() }, lp().margins(t = dp(10)))
            addView(label("Base URL", 14f, VC.SUB, VC.medium), lp().margins(t = dp(14)))
            addView(textField(Prefs.agentBaseUrl, "https://apihub.agnes-ai.com/v1") {
                Prefs.agentBaseUrl = it
                renderAgent()
            }, lp().margins(t = dp(6)))
            addView(label("API key", 14f, VC.SUB, VC.medium), lp().margins(t = dp(14)))
            agentKeyText = label("", 15f, VC.TEXT, Typeface.MONOSPACE)
            addView(agentKeyText, lp().margins(t = dp(4)))
            addView(smallButton("key দাও / বদলাও") { changeAgentKey() },
                lp(ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(6)))
            addView(label("মডেল", 14f, VC.SUB, VC.medium), lp().margins(t = dp(14)))
            agentModelField = AutoCompleteTextView(context).apply {
                setText(Prefs.agentModelName)
                hint = "মডেলের নাম লেখো, বা নিচে 'মডেল আনো'"
                setHintTextColor(VC.FAINT)
                setTextColor(VC.TEXT)
                textSize = 16f
                isSingleLine = true
                threshold = 1
                background = rounded(0xFF0E0E1C.toInt(), dpf(14f), VC.STROKE, dp(1))
                setDropDownBackgroundDrawable(rounded(VC.CARD, dpf(14f), VC.STROKE, dp(1)))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        Prefs.agentModelName = s?.toString().orEmpty()
                        renderAgent()
                    }
                })
            }
            addView(agentModelField, lp().margins(t = dp(6)))
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(secondaryButton("মডেল আনো") { fetchAgentModels() }, lp(0).apply { weight = 1f }.margins(r = dp(6)))
            row.addView(secondaryButton("পরীক্ষা করো") { testAgent() }, lp(0).apply { weight = 1f }.margins(l = dp(6)))
            addView(row, lp().margins(t = dp(12)))
            agentStatus = label("", 13f, VC.SUB)
            addView(agentStatus, lp().margins(t = dp(10)))
        })
        renderAgent()

        col = pSafe
        // ── Permissions ──
        col.addView(section("অনুমতি"))
        permList = card()
        col.addView(permList)

        col = pMore
        // ── Log ──
        col.addView(section("সংযোগের লগ"))
        col.addView(card().apply {
            addView(label("সংযোগ কেন কাটল, কে কথা বলল — সব এখানে দেখা যাবে", 12.5f, VC.FAINT))
            logText = label("", 11.5f, VC.SUB, Typeface.MONOSPACE)
            addView(logText, lp().margins(t = dp(8)))
        })

        col = pMore
        // ── Update ──
        col.addView(section("আপডেট"))
        col.addView(card().apply {
            addView(label("এখনকার ভার্সন: " + Updater.currentVersion(this@SettingsActivity), 15.5f, VC.TEXT, VC.medium))
            updateText = label("", 13.5f, VC.SUB)
            addView(updateText, lp().margins(t = dp(4)))
            addView(secondaryButton("আপডেট আছে কিনা দেখো") { Updater.checkNow(this@SettingsActivity) },
                lp().margins(t = dp(12)))
            addView(divider())
            addView(switchRow(
                "নিজে থেকে আপডেট",
                "নতুন ভার্সন এলে নিজেই Download ফোল্ডারে নামাবে, তারপর install-এর স্ক্রিন খুলবে",
                { Prefs.autoUpdate }
            ) { Prefs.autoUpdate = it })
        })

        // ── Footer (on the home list) ──
        col = home
        col.addView(secondaryButton("Vibe পুরো বন্ধ করো") {
            VibeService.send(this, VibeService.A_QUIT)
            Toast.makeText(this, "Vibe বন্ধ হলো। অ্যাপ খুললে আবার চালু হবে।", Toast.LENGTH_LONG).show()
        }, lp().margins(t = dp(22)))
        col.addView(
            label("Vibe " + Updater.currentVersion(this), 12f, VC.FAINT, gravity = Gravity.CENTER),
            lp().margins(t = dp(16))
        )

        setContentView(root)
        edgeToEdge(root)
        renderVoices()
        renderModels()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { Memory.factsFlow.collect { renderMemory(it) } }
                launch {
                    Updater.status.collect { s ->
                        updateText.text = s.ifBlank { if (Prefs.autoUpdate) "নিজে থেকে নতুন ভার্সন দেখে" else "" }
                    }
                }
                launch {
                    VibeBus.log.collect { l ->
                        logText.text = if (l.isEmpty()) "এখনো কিছু নেই" else l.takeLast(25).reversed().joinToString("\n")
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        renderKey()
        renderModes()
        syncSwitches()
        renderPermissions()
    }

    // ── Category pages ───────────────────────────────────────────────────────

    private fun newPage(icon: String, title: String, desc: String): LinearLayout {
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        pageHolder.addView(page)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(14), dp(14), dp(14))
            val r = dpf(20f)
            background = ripple(rounded(VC.CARD, r, VC.STROKE, dp(1)), rounded(Color.WHITE, r))
            isClickable = true
            setOnClickListener { showPage(page, title) }
        }
        row.addView(label(icon, 22f, VC.TEXT, gravity = Gravity.CENTER), LinearLayout.LayoutParams(dp(40), dp(40)))
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 16.5f, VC.TEXT, VC.medium))
        text.addView(label(desc, 12.5f, VC.FAINT), lp().margins(t = dp(2)))
        row.addView(text, lp(0).apply { weight = 1f }.margins(l = dp(10), r = dp(8)))
        row.addView(label("›", 24f, VC.SUB))
        home.addView(row, lp().margins(b = dp(10)))
        return page
    }

    private fun showPage(page: LinearLayout, title: String) {
        home.visibility = View.GONE
        for (i in 0 until pageHolder.childCount) pageHolder.getChildAt(i).visibility = View.GONE
        page.visibility = View.VISIBLE
        openPage = page
        titleView.text = title
        scroll.scrollTo(0, 0)
    }

    private fun goBack() {
        if (openPage == null) return finish()
        openPage?.visibility = View.GONE
        openPage = null
        home.visibility = View.VISIBLE
        titleView.text = "সেটিংস"
        scroll.scrollTo(0, 0)
    }

    // ── Key ──────────────────────────────────────────────────────────────────

    private fun renderKey() {
        val k = Prefs.apiKey.orEmpty()
        keyText.text = if (k.length > 8) k.take(4) + "••••••••••" + k.takeLast(4) else "দেওয়া নেই"
    }

    private fun changeKey() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val (field, input) = keyField()
        box.addView(field)
        val err = label("", 13f, VC.RED).apply { visibility = View.GONE }
        box.addView(err, lp().margins(t = dp(8)))
        val dlg = Flows.dialog(this)
            .setTitle("নতুন Gemini API key")
            .setView(box)
            .setPositiveButton("সেভ করো", null)
            .setNegativeButton("বাতিল", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.setOnShowListener {
            val save = dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
            save.setOnClickListener {
                val key = input.text.toString().trim()
                if (key.isEmpty()) return@setOnClickListener
                save.isEnabled = false
                save.text = "যাচাই হচ্ছে…"
                lifecycleScope.launch {
                    when (val r = KeyChecker.check(key)) {
                        is KeyChecker.Result.Ok -> {
                            Prefs.apiKey = key
                            Prefs.liveModels = r.liveModels
                            if (Prefs.model !in r.liveModels) Prefs.model = r.liveModels.first()
                            if (VibeBus.state.value == VState.ERROR) {
                                VibeBus.status.value = ""
                                VibeService.send(this@SettingsActivity, VibeService.A_ENSURE)
                            }
                            renderKey()
                            renderModels()
                            dlg.dismiss()
                            Toast.makeText(this@SettingsActivity, "নতুন key সেভ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                        is KeyChecker.Result.Bad -> {
                            err.text = r.message
                            err.visibility = View.VISIBLE
                            save.isEnabled = true
                            save.text = "সেভ করো"
                        }
                    }
                }
            }
        }
        dlg.show()
    }

    // ── Screen-task AI ───────────────────────────────────────────────────────

    private fun renderAgent() {
        if (!::agentStatus.isInitialized) return
        val k = Prefs.agentKey.orEmpty()
        agentKeyText.text = when {
            k.isEmpty() -> "দেওয়া নেই"
            k.length > 8 -> k.take(4) + "••••••••" + k.takeLast(4)
            else -> "••••"
        }
        agentStatus.setTextColor(VC.SUB)
        agentStatus.text = when {
            !Prefs.agentCustomOn -> "বন্ধ — স্ক্রিনের কাজ Gemini করবে"
            Prefs.customAgentReady -> "চালু: ${Prefs.agentModelName}"
            else -> "Base URL, key আর মডেল — তিনটাই দিলে চালু হবে"
        }
    }

    private fun changeAgentKey() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        val (field, input) = keyField()
        input.hint = "প্রোভাইডারের API key"
        box.addView(field)
        val dlg = Flows.dialog(this)
            .setTitle("কাজের AI-র key")
            .setView(box)
            .setPositiveButton("সেভ করো") { _, _ ->
                Prefs.agentKey = input.text.toString()
                renderAgent()
            }
            .setNeutralButton("মুছে দাও") { _, _ ->
                Prefs.agentKey = null
                renderAgent()
            }
            .setNegativeButton("বাতিল", null)
            .create()
        dlg.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dlg.show()
    }

    private fun agentError(msg: String) {
        agentStatus.setTextColor(VC.RED)
        agentStatus.text = msg
    }

    private fun fetchAgentModels() {
        val base = Prefs.agentBaseUrl
        val key = Prefs.agentKey
        if (base.isBlank() || key.isNullOrBlank()) return agentError("আগে Base URL আর key দাও")
        agentStatus.setTextColor(VC.SUB)
        agentStatus.text = "মডেল আনছি…"
        lifecycleScope.launch {
            try {
                val models = OpenAiCompat.listModels(base, key)
                if (models.isEmpty()) {
                    agentError("প্রোভাইডার কোনো মডেল জানায়নি — পুরো নাম লিখে দাও")
                    return@launch
                }
                val adapter = object : ArrayAdapter<String>(this@SettingsActivity,
                    android.R.layout.simple_dropdown_item_1line, models) {
                    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                        super.getView(position, convertView, parent).apply {
                            (this as? TextView)?.setTextColor(VC.TEXT)
                        }
                }
                // ArrayAdapter's filter matches the start of each word: type "g" → gemini-…, gpt-…
                agentModelField.setAdapter(adapter)
                agentStatus.setTextColor(VC.GREEN)
                agentStatus.text = "${models.size}টা মডেল পাওয়া গেছে — নাম লিখতে শুরু করো বা তালিকা থেকে বেছে নাও"
                agentModelField.requestFocus()
                agentModelField.showDropDown()
            } catch (e: Exception) {
                agentError(e.message ?: "মডেল আনা গেল না")
            }
        }
    }

    /** One small request with a picture: proves the key, the model, and that it sees images. */
    private fun testAgent() {
        val base = Prefs.agentBaseUrl
        val key = Prefs.agentKey
        val model = Prefs.agentModelName
        if (base.isBlank() || key.isNullOrBlank() || model.isBlank()) return agentError("Base URL, key আর মডেল দাও")
        agentStatus.setTextColor(VC.SUB)
        agentStatus.text = "পরীক্ষা চলছে…"
        lifecycleScope.launch {
            val t0 = System.currentTimeMillis()
            try {
                val turn = GeminiText.userTurn(
                    "What colour is this image? Reply only JSON: {\"colour\": \"...\"}", testRedJpeg())
                val raw = OpenAiCompat.chat(base, key, model, "You answer with JSON only.", listOf(turn))
                val ms = System.currentTimeMillis() - t0
                val sees = "red" in raw.lowercase()
                agentStatus.setTextColor(if (sees) VC.GREEN else VC.SUB)
                agentStatus.text = if (sees) "কাজ করছে ✓ ছবিও বোঝে — উত্তর $ms ms-এ"
                else "উত্তর এসেছে ($ms ms), কিন্তু ছবি বুঝল কিনা নিশ্চিত না: ${raw.take(80)}"
            } catch (e: Exception) {
                agentError(e.message ?: "কাজ করেনি")
            }
        }
    }

    /** A tiny solid-red JPEG for the "does this model see images?" test. */
    private fun testRedJpeg(): String {
        val bmp = android.graphics.Bitmap.createBitmap(32, 32, android.graphics.Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.RED)
        val out = java.io.ByteArrayOutputStream()
        bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, out)
        return android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
    }

    // ── Talk mode & Voice Lock ───────────────────────────────────────────────

    private fun renderModes() {
        modeList.removeAllViews()
        val modes = listOf(
            false to ("সবসময় শুনবে" to "স্বাভাবিক কথোপকথন — বললেই শুনবে, থামলেই উত্তর দেবে"),
            true to ("চেপে ধরে কথা (Push-to-talk)" to "বল / ভাসমান বাটন / ভলিউম-ডাউন চেপে ধরে বলো; অন্য কারো কথা যাবেই না"),
        )
        for ((ptt, texts) in modes) {
            val selected = Prefs.pushToTalk == ptt
            modeList.addView(optionCard(texts.first, texts.second, selected) {
                Prefs.pushToTalk = ptt
                renderModes()
                if (ptt && !Flows.hasScreenControl(this)) {
                    Toast.makeText(this, "ভলিউম-ডাউন চেপে কথা বলতে স্ক্রিন কন্ট্রোল (Accessibility) চালু করো", Toast.LENGTH_LONG).show()
                }
                VibeService.send(this, VibeService.A_STOP_TALK)
            }, lp().margins(b = dp(8)))
        }
    }

    /** Typed, never spoken: the PIN for self-unlock. */
    private fun askPin() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), 0)
        }
        box.addView(label("ফোনের লক স্ক্রিনের PIN (শুধু সংখ্যা)", 13.5f, VC.SUB))
        val input = EditText(this).apply {
            hint = "••••"
            setHintTextColor(VC.FAINT)
            setTextColor(VC.TEXT)
            textSize = 20f
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            background = rounded(0xFF0E0E1C.toInt(), dpf(14f), VC.STROKE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        box.addView(input, lp().margins(t = dp(10)))
        Flows.dialog(this)
            .setTitle("নিজে আনলক")
            .setView(box)
            .setPositiveButton("সেভ করে চালু করো") { _, _ ->
                val pin = input.text.toString().filter { it.isDigit() }
                if (pin.length < 4) {
                    Toast.makeText(this, "PIN কমপক্ষে ৪ সংখ্যার হতে হবে", Toast.LENGTH_LONG).show()
                } else {
                    Prefs.unlockPin = pin
                    Prefs.voiceUnlock = true
                    Toast.makeText(this, "PIN সেভ হয়েছে — Vibe এখন নিজে আনলক করবে", Toast.LENGTH_SHORT).show()
                    if (!Flows.hasScreenControl(this)) Flows.openAccessibility(this)
                }
                syncSwitches()
            }
            .setNeutralButton("PIN মুছে দাও") { _, _ ->
                Prefs.unlockPin = null
                Prefs.voiceUnlock = false
                syncSwitches()
            }
            .setNegativeButton("বাতিল") { _, _ -> syncSwitches() }
            .show()
    }

    // ── Voices ───────────────────────────────────────────────────────────────

    private fun renderVoices() {
        filterChips.forEach { (v, chip) -> paintChip(chip, v == voiceFilter) }
        voiceList.removeAllViews()
        val current = Prefs.voice
        for (v in VOICES.filter { voiceFilter == null || it.female == voiceFilter }) {
            val selected = v.name == current
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                val r = dpf(18f)
                background = ripple(
                    if (selected) rounded(VC.CARD_HI, r, VC.A2, dp(2)) else rounded(VC.CARD, r, VC.STROKE, dp(1)),
                    rounded(Color.WHITE, r)
                )
                isClickable = true
                setOnClickListener {
                    Prefs.voice = v.name
                    renderVoices()
                }
            }
            val avatar = FrameLayout(this).apply {
                background = rounded(if (v.female) VC.PINK_SOFT else VC.BLUE_SOFT, dpf(20f))
                addView(
                    label(v.name.take(1), 16f, if (v.female) VC.A3 else VC.A1, VC.bold, Gravity.CENTER),
                    FrameLayout.LayoutParams(dp(40), dp(40))
                )
            }
            row.addView(avatar, LinearLayout.LayoutParams(dp(40), dp(40)))
            val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            text.addView(label(v.name, 16f, VC.TEXT, VC.medium))
            text.addView(label((if (v.female) "মেয়ে" else "ছেলে") + "  ·  " + v.style, 13f, VC.SUB))
            row.addView(text, lp(0).apply { weight = 1f }.margins(l = dp(12)))
            if (selected) row.addView(label("✓", 18f, VC.A2, VC.bold))
            voiceList.addView(row, lp().margins(b = dp(8)))
        }
    }

    // ── Models ───────────────────────────────────────────────────────────────

    private fun renderModels() {
        modelList.removeAllViews()
        val current = Prefs.model
        for (id in Prefs.liveModels) {
            modelList.addView(optionCard(modelLabel(id), modelNote(id), id == current) {
                Prefs.model = id
                renderModels()
            }, lp().margins(b = dp(8)))
        }
    }

    private fun refreshModels() {
        val key = Prefs.apiKey ?: return
        Toast.makeText(this, "দেখছি…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            when (val r = KeyChecker.check(key)) {
                is KeyChecker.Result.Ok -> {
                    Prefs.liveModels = r.liveModels
                    if (Prefs.model !in r.liveModels) Prefs.model = r.liveModels.first()
                    renderModels()
                }
                is KeyChecker.Result.Bad ->
                    Toast.makeText(this@SettingsActivity, r.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    // ── Memory ───────────────────────────────────────────────────────────────

    private fun renderMemory(facts: List<Memory.Fact>) {
        memoryTitle.text = if (facts.isEmpty()) "এখনো কিছু মনে রাখেনি। কথা বললে নিজে থেকেই শিখবে, বা বলো “মনে রেখো…”"
        else "${facts.size}টা কথা মনে আছে · ${Memory.skillCount.value}টা কাজ শিখেছে"
        memoryList.removeAllViews()
        val shown = if (showAllMemory) facts.reversed() else facts.takeLast(8).reversed()
        for (f in shown) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(4), 0, dp(4))
            }
            row.addView(label("• " + f.text, 14f, VC.TEXT), lp(0).apply { weight = 1f })
            row.addView(label("✕", 15f, VC.FAINT, VC.medium, Gravity.CENTER).apply {
                setPadding(dp(12), dp(4), dp(4), dp(4))
                isClickable = true
                setOnClickListener { Memory.delete(f) }
            })
            memoryList.addView(row)
        }
    }

    // ── Permissions ──────────────────────────────────────────────────────────

    private fun renderPermissions() {
        permList.removeAllViews()
        val rows = listOf(
            Triple("মাইক", Flows.hasMic(this)) { Flows.openAppSettings(this) },
            Triple("কল, কন্টাক্ট ও SMS", Flows.hasPhone(this)) { phoneLauncher.launch(Flows.PHONE_PERMISSIONS) },
            Triple("স্ক্রিন কন্ট্রোল (Accessibility)", Flows.hasScreenControl(this)) { Flows.openAccessibility(this) },
            Triple("অন্য অ্যাপের উপরে দেখানো", Flows.canOverlay(this)) { Flows.openOverlaySettings(this) },
            Triple("ব্যাটারি সীমা থেকে ছাড়", Flows.batteryFree(this)) { Flows.openBatterySettings(this) },
            Triple("লক স্ক্রিনে পুরো স্ক্রিন দেখানো", Flows.canFullScreen(this)) { Flows.openFullScreenSettings(this) },
            Triple("নোটিফিকেশন", Flows.hasNotifications(this)) { Flows.openAppNotificationSettings(this) },
        )
        rows.forEachIndexed { i, (title, ok, fix) ->
            if (i > 0) permList.addView(divider())
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(0, dp(6), 0, dp(6))
            }
            row.addView(label(title, 15f, VC.TEXT), lp(0).apply { weight = 1f })
            if (ok) row.addView(label("চালু ✓", 13.5f, VC.GREEN, VC.medium))
            else row.addView(smallButton("চালু করো") { fix() })
            permList.addView(row)
        }
        WakeModelDownloader.error.value?.let {
            permList.addView(label(it, 12.5f, VC.RED), lp().margins(t = dp(8)))
        }
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private fun section(text: String): TextView =
        label(text, 14f, VC.SUB, VC.medium).apply {
            setPadding(dp(6), dp(22), 0, dp(10))
        }

    private fun divider(): View = View(this).apply {
        setBackgroundColor(VC.STROKE)
        layoutParams = lp(h = dp(1)).margins(t = dp(10), b = dp(10))
    }

    private fun paintChip(v: TextView, on: Boolean) {
        val r = dpf(19f)
        v.background = ripple(
            if (on) accentGradient(r) else rounded(VC.CARD, r, VC.STROKE, dp(1)),
            rounded(Color.WHITE, r)
        )
        v.setTextColor(if (on) Color.WHITE else VC.SUB)
    }

    private fun optionCard(title: String, desc: String, selected: Boolean, onClick: () -> Unit): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            val r = dpf(18f)
            background = ripple(
                if (selected) rounded(VC.CARD_HI, r, VC.A2, dp(2)) else rounded(VC.CARD, r, VC.STROKE, dp(1)),
                rounded(Color.WHITE, r)
            )
            isClickable = true
            setOnClickListener { onClick() }
            addView(label(title + if (selected) "   ✓" else "", 16f, VC.TEXT, VC.medium))
            addView(label(desc, 13f, VC.SUB))
        }

    private fun choiceRow(options: List<Pair<Int, String>>, get: () -> Int, set: (Int) -> Unit): LinearLayout {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val chips = mutableListOf<Pair<Int, TextView>>()
        fun paint() = chips.forEach { (v, c) -> paintChip(c, get() == v) }
        for ((value, text) in options) {
            val c = label(text, 13.5f, VC.TEXT, VC.medium, Gravity.CENTER).apply {
                minHeight = dp(36)
                isClickable = true
                setOnClickListener {
                    set(value)
                    paint()
                }
            }
            chips += value to c
            row.addView(c, lp(0).apply { weight = 1f }.margins(r = dp(6)))
        }
        paint()
        return row
    }

    private fun textField(value: String, hint: String, save: (String) -> Unit): EditText =
        EditText(this).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(VC.FAINT)
            setTextColor(VC.TEXT)
            textSize = 16f
            isSingleLine = true
            background = rounded(0xFF0E0E1C.toInt(), dpf(14f), VC.STROKE, dp(1))
            setPadding(dp(14), dp(12), dp(14), dp(12))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = save(s?.toString().orEmpty())
            })
        }

    @Suppress("DEPRECATION")
    private fun switchRow(
        title: String, desc: String, get: () -> Boolean, set: (Boolean) -> Unit,
    ): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val text = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        text.addView(label(title, 15.5f, VC.TEXT, VC.medium))
        text.addView(label(desc, 12.5f, VC.FAINT), lp().margins(t = dp(2)))
        row.addView(text, lp(0).apply { weight = 1f }.margins(r = dp(12)))
        val sw = Switch(this).apply {
            val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
            thumbTintList = ColorStateList(states, intArrayOf(Color.WHITE, 0xFFB8B8CC.toInt()))
            trackTintList = ColorStateList(states, intArrayOf(VC.A2, 0x44FFFFFF))
            isChecked = get()
            setOnCheckedChangeListener { b, on ->
                if (!b.isPressed) return@setOnCheckedChangeListener
                set(on)
                b.post {
                    syncSwitches()
                            }
            }
        }
        switches += sw to get
        row.addView(sw)
        return row
    }

    private fun syncSwitches() {
        switches.forEach { (sw, get) -> if (sw.isChecked != get()) sw.isChecked = get() }
    }
}
