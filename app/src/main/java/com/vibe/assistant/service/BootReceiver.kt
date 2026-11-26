package com.vibe.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.vibe.assistant.data.Prefs

/**
 * After a restart. Android 14+ does not let an app open the microphone from the
 * background on boot, so there Vibe only posts a "tap to start" notification;
 * one tap and it is running again. Older Android starts it straight away.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (Prefs.apiKey == null) return
        if (!Prefs.wakeEnabled && !Prefs.overlayEnabled) return
        if (Build.VERSION.SDK_INT >= 34) {
            VibeService.notifyActivate(context, "ফোন রিস্টার্ট হয়েছে — Vibe আবার চালু করতে ট্যাপ করো")
        } else {
            VibeService.send(context, VibeService.A_ENSURE)
        }
    }
}
