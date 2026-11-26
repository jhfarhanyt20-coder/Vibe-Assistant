package com.vibe.assistant

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.vibe.assistant.data.Prefs

class VibeApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Prefs.init(this)
        com.vibe.assistant.core.Memory.init(this)
        // Voice identification was removed (the owner's voice varies too much and
        // Vibe went silent). Clear an older install's enrolled voice ONCE — the
        // self-unlock PIN set after this is the user's and must survive restarts.
        val mig = getSharedPreferences("vibe_migrations", MODE_PRIVATE)
        if (!mig.getBoolean("v071_voice_cleanup", false)) {
            Prefs.voiceLock = false
            Prefs.voiceUnlock = false
            Prefs.ownerVoice = null
            Prefs.unlockPin = null
            mig.edit().putBoolean("v071_voice_cleanup", true).apply()
        }
        // v0.5: back to always-listening conversation once. Push-to-talk left on by
        // accident makes Vibe hear nothing; the user can still turn it on again.
        val sp = getSharedPreferences("vibe_migrations", MODE_PRIVATE)
        if (!sp.getBoolean("v05_talk_reset", false)) {
            Prefs.pushToTalk = false
            Prefs.bargeIn = true
            sp.edit().putBoolean("v05_talk_reset", true).apply()
        }
        com.vibe.assistant.wake.VoskModels.warmUp(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Vibe চলছে", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Vibe ব্যাকগ্রাউন্ডে শুনছে"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "লক স্ক্রিনে Vibe", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "“হেই Vibe” বললে লক স্ক্রিনে Vibe দেখায়"
                setSound(null, null)
                enableVibration(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "Vibe বার্তা", NotificationManager.IMPORTANCE_DEFAULT)
        )
    }

    companion object {
        const val CH_SERVICE = "vibe_service"
        const val CH_WAKE = "vibe_wake"
        const val CH_ALERT = "vibe_alert"
    }
}
