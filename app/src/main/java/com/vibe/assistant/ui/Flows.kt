package com.vibe.assistant.ui

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.vibe.assistant.data.Prefs
import com.vibe.assistant.service.VibeService
import com.vibe.assistant.wake.WakeModelDownloader
import com.vibe.assistant.wake.WakeWord

/** Permission checks and the small multi-step flows both screens share. */
object Flows {
    fun hasMic(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    fun hasNotifications(ctx: Context) = Build.VERSION.SDK_INT < 33 ||
        ctx.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun runtimePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)

    val PHONE_PERMISSIONS = arrayOf(
        Manifest.permission.CALL_PHONE,
        Manifest.permission.ANSWER_PHONE_CALLS,
        Manifest.permission.READ_PHONE_STATE,
        Manifest.permission.READ_CALL_LOG,
        Manifest.permission.READ_CONTACTS,
        Manifest.permission.SEND_SMS,
    )

    fun hasPhone(ctx: Context) = PHONE_PERMISSIONS.all {
        ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED
    }

    fun hasScreenControl(ctx: Context) = com.vibe.assistant.access.VibeAccessibility.isEnabled(ctx)

    fun openAccessibility(a: Activity) {
        dialog(a)
            .setTitle("স্ক্রিন কন্ট্রোল চালু করো")
            .setMessage(
                "পরের স্ক্রিনে “Vibe” খুঁজে চালু করো (Installed apps / Downloaded apps-এর ভেতরে থাকতে পারে)।\n\n" +
                    "বোতামটা ধূসর হলে বা “Restricted setting” লেখা এলে:\n" +
                    "১. ফোনের Settings → Apps → Vibe খোলো\n" +
                    "২. উপরে ডানে ⋮ চাপো → “Allow restricted settings”\n" +
                    "৩. আবার এখানে এসে চালু করো"
            )
            .setPositiveButton("চালু করতে যাও") { _, _ ->
                try {
                    a.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {}
            }
            .setNeutralButton("Vibe অ্যাপ সেটিংস") { _, _ -> openAppSettings(a) }
            .setNegativeButton("পরে", null)
            .show()
    }

    /**
     * Makes a view the talk button: a tap toggles the conversation; in
     * push-to-talk mode, press-and-hold talks and releasing sends.
     */
    @SuppressLint("ClickableViewAccessibility")
    fun holdToTalk(v: android.view.View, before: () -> Boolean = { true }) {
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        var pressed = false
        var holding = false
        val startHold = Runnable {
            holding = true
            v.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
            VibeService.send(v.context, VibeService.A_PTT_DOWN)
        }
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    pressed = true
                    holding = false
                    view.animate().scaleX(0.95f).scaleY(0.95f).setDuration(90).start()
                    if (Prefs.pushToTalk && before()) handler.postDelayed(startHold, 180)
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    view.animate().scaleX(1f).scaleY(1f).setDuration(140).start()
                    handler.removeCallbacks(startHold)
                    if (holding) {
                        VibeService.send(view.context, VibeService.A_PTT_UP)
                    } else if (pressed && e.actionMasked == android.view.MotionEvent.ACTION_UP) {
                        view.performClick()
                        if (before()) VibeService.send(view.context, VibeService.A_TOGGLE)
                    }
                    pressed = false
                    holding = false
                }
            }
            true
        }
    }

    fun canOverlay(ctx: Context) = Settings.canDrawOverlays(ctx)

    fun batteryFree(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    fun canFullScreen(ctx: Context) = Build.VERSION.SDK_INT < 34 ||
        ctx.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    fun dialog(a: Activity): AlertDialog.Builder =
        AlertDialog.Builder(a, android.R.style.Theme_DeviceDefault_Dialog_Alert)

    fun openOverlaySettings(a: Activity) {
        a.startActivity(
            Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${a.packageName}"))
        )
    }

    @SuppressLint("BatteryLife")
    fun openBatterySettings(a: Activity) {
        try {
            a.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${a.packageName}"))
            )
        } catch (_: Exception) {
            a.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
        }
    }

    fun openFullScreenSettings(a: Activity) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                a.startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, Uri.parse("package:${a.packageName}"))
                )
                return
            } catch (_: Exception) {}
        }
        openAppNotificationSettings(a)
    }

    fun openAppSettings(a: Activity) {
        a.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.packageName}"))
        )
    }

    fun openAppNotificationSettings(a: Activity) {
        a.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, a.packageName)
        )
    }

    fun askBattery(a: Activity) {
        if (batteryFree(a)) return
        dialog(a)
            .setTitle("Vibe যেন বন্ধ না হয়")
            .setMessage("ফোন ব্যাটারি বাঁচাতে অনেক সময় ব্যাকগ্রাউন্ডের অ্যাপ বন্ধ করে দেয়। “হেই Vibe” সবসময় কাজ করাতে Vibe-কে ব্যাটারি সীমা থেকে ছাড় দাও।")
            .setPositiveButton("ছাড় দাও") { _, _ -> openBatterySettings(a) }
            .setNegativeButton("পরে", null)
            .show()
    }

    /** Turn "Hey Vibe" on, downloading the offline model first if needed. */
    fun enableWake(a: Activity, onChanged: () -> Unit) {
        val app = a.applicationContext
        val turnOn = {
            Prefs.wakeEnabled = true
            VibeService.send(app, VibeService.A_ENSURE)
            onChanged()
        }
        if (WakeWord.isModelReady(a)) {
            turnOn()
            askBattery(a)
            return
        }
        dialog(a)
            .setTitle("“হেই Vibe” চালু করবো?")
            .setMessage(
                "হাত না লাগিয়ে, লক স্ক্রিনেও “হেই Vibe” বলে ডাকতে পারবে।\n\n" +
                    "এর জন্য একবারই প্রায় ${WakeWord.MODEL_SIZE_MB}MB-এর অফলাইন মডেল নামবে। " +
                    "“হেই Vibe” শোনার কাজটা পুরোপুরি ফোনের ভেতরেই হয়, তুমি ডাকার আগে কোনো কথা ইন্টারনেটে যায় না।"
            )
            .setPositiveButton("ডাউনলোড করো") { _, _ ->
                WakeModelDownloader.start(a) { turnOn() }
                onChanged()
                askBattery(a)
            }
            .setNegativeButton("পরে", null)
            .show()
    }

    fun disableWake(ctx: Context) {
        Prefs.wakeEnabled = false
        VibeService.send(ctx, VibeService.A_ENSURE)
    }

    fun enableOverlay(a: Activity) {
        Prefs.overlayEnabled = true
        if (canOverlay(a)) {
            VibeService.send(a, VibeService.A_ENSURE)
            return
        }
        dialog(a)
            .setTitle("ভাসমান বাটন")
            .setMessage("সব অ্যাপের উপরে Vibe-এর বাটন দেখাতে “অন্য অ্যাপের উপরে দেখানো”-র অনুমতি দাও। পরের স্ক্রিনে Vibe খুঁজে চালু করো।")
            .setPositiveButton("অনুমতি দাও") { _, _ -> openOverlaySettings(a) }
            .setNegativeButton("পরে", null)
            .show()
    }

    fun disableOverlay(ctx: Context) {
        Prefs.overlayEnabled = false
        VibeService.send(ctx, VibeService.A_ENSURE)
    }

    fun openUrl(a: Activity, url: String) {
        try {
            a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {}
    }
}
