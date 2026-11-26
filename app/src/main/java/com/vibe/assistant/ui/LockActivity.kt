package com.vibe.assistant.ui

import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.vibe.assistant.core.Role
import com.vibe.assistant.core.VState
import com.vibe.assistant.core.VibeBus
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * What you see when you say "Hey Vibe" with the phone locked: the screen lights
 * up over the lock screen with the orb, without unlocking. When a task needs the
 * phone unlocked, it shows the unlock prompt; once unlocked it gets out of the way.
 */
class LockActivity : ComponentActivity() {
    private lateinit var status: TextView
    private lateinit var caption: TextView
    private var wasActive = false
    private var handledUnlock = 0L

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setShowWhenLocked(true)
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        getSystemService(NotificationManager::class.java).cancel(2)
        ContextCompat.registerReceiver(
            this, unlocked, IntentFilter(Intent.ACTION_USER_PRESENT), ContextCompat.RECEIVER_EXPORTED
        )

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = screenBackground()
        }
        root.addView(
            label("Vibe", 26f, VC.TEXT, VC.bold, Gravity.CENTER).apply { gradientText() },
            lp(android.view.ViewGroup.LayoutParams.WRAP_CONTENT).margins(t = dp(28))
        )
        val orbBox = FrameLayout(this)
        val orb = OrbView(this).apply { isClickable = true }
        Flows.holdToTalk(orb)
        orbBox.addView(orb, FrameLayout.LayoutParams(dp(300), dp(300), Gravity.CENTER))
        root.addView(orbBox, lp(h = 0).apply { weight = 1f })

        status = label("", 22f, VC.TEXT, VC.medium, Gravity.CENTER)
        root.addView(status, lp().margins(l = dp(24), r = dp(24)))
        caption = label("", 16f, VC.SUB, gravity = Gravity.CENTER).apply { maxLines = 4 }
        root.addView(caption, lp().margins(l = dp(28), t = dp(10), r = dp(28)))

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(secondaryButton("আনলক করো") { requestUnlock() }, lp(0).apply { weight = 1f }.margins(r = dp(6)))
        row.addView(primaryButton("শেষ করো") {
            VibeService.send(this, VibeService.A_STOP_TALK)
            finish()
        }, lp(0).apply { weight = 1f }.margins(l = dp(6)))
        root.addView(row, lp().margins(l = dp(20), t = dp(24), r = dp(20), b = dp(28)))

        setContentView(root)
        edgeToEdge(root)

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(VibeBus.state, VibeBus.status, VibeBus.lines) { s, t, l -> Triple(s, t, l) }
                        .collect { (st, text, lines) ->
                            status.text = when {
                                st == VState.ERROR && text.isNotBlank() -> text
                                text.isNotBlank() && VibeBus.active(st) -> text
                                Prefs.pushToTalk && VibeBus.active(st) -> "বল চেপে ধরে কথা বলো"
                                else -> VibeBus.label(st)
                            }
                            status.setTextColor(if (st == VState.ERROR) VC.RED else VC.TEXT)
                            val last = lines.lastOrNull()
                            caption.visibility = if (Prefs.showTranscript) View.VISIBLE else View.GONE
                            caption.text = when {
                                last == null -> ""
                                last.role == Role.USER -> "“${last.text.takeLast(160)}”"
                                else -> last.text.takeLast(200)
                            }
                            if (VibeBus.active(st)) wasActive = true
                            else if (wasActive) {
                                delay(700)
                                if (!VibeBus.active()) finish()
                            }
                        }
                }
                launch {
                    // A tool asked for the phone to be unlocked.
                    VibeBus.unlockRequestAt.collect { at ->
                        if (at > handledUnlock && SystemClock.elapsedRealtime() - at < 30_000) {
                            handledUnlock = at
                            requestUnlock()
                        }
                    }
                }
                launch {
                    // Opened late or by mistake: don't sit on the lock screen forever.
                    delay(4000)
                    if (!wasActive && !VibeBus.active()) finish()
                }
            }
        }
    }

    private fun requestUnlock() {
        val km = getSystemService(KeyguardManager::class.java)
        if (!km.isKeyguardLocked) {
            finish()
            return
        }
        km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() = finish()
        })
    }

    override fun onDestroy() {
        try { unregisterReceiver(unlocked) } catch (_: Exception) {}
        super.onDestroy()
    }
}
