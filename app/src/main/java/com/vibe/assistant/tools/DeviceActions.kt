package com.vibe.assistant.tools

import android.app.KeyguardManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.provider.Settings
import android.view.KeyEvent
import com.vibe.assistant.access.VibeAccessibility
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Quick phone abilities that need no screen reading. */
object DeviceActions {

    /** Start an activity from the background: through the accessibility service when it is on. */
    fun launch(ctx: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val via: Context = VibeAccessibility.instance ?: ctx
        return try {
            via.startActivity(intent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private val ALIASES = mapOf(
        "ইউটিউব" to "youtube", "ফেসবুক" to "facebook", "হোয়াটসঅ্যাপ" to "whatsapp", "হোয়াটসঅ্যাপ" to "whatsapp",
        "মেসেঞ্জার" to "messenger", "ক্যামেরা" to "camera", "গ্যালারি" to "gallery", "সেটিংস" to "settings",
        "ক্রোম" to "chrome", "ক্যালকুলেটর" to "calculator", "ঘড়ি" to "clock", "ম্যাপ" to "maps",
        "ইমো" to "imo", "টেলিগ্রাম" to "telegram", "ইনস্টাগ্রাম" to "instagram", "টিকটক" to "tiktok",
        "জিমেইল" to "gmail", "প্লে স্টোর" to "play store", "ফোন" to "phone", "মেসেজ" to "messages",
        "photos" to "gallery", "browser" to "chrome", "dialer" to "phone", "sms" to "messages",
    )

    data class App(val label: String, val pkg: String)

    fun installedApps(ctx: Context): List<App> {
        val pm = ctx.packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(i, 0).map {
            App(it.loadLabel(pm).toString(), it.activityInfo.packageName)
        }.distinctBy { it.pkg }
    }

    fun findApp(ctx: Context, name: String): App? {
        val raw = name.trim().lowercase()
        val q = (ALIASES[raw] ?: raw).replace(" ", "")
        if (q.isEmpty()) return null
        val apps = installedApps(ctx)
        fun norm(s: String) = s.lowercase().replace(" ", "")
        return apps.firstOrNull { norm(it.label) == q }
            ?: apps.firstOrNull { norm(it.label).startsWith(q) }
            ?: apps.firstOrNull { norm(it.label).contains(q) }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q) }
            ?: if (q == "gallery") apps.firstOrNull { norm(it.label).contains("photo") } else null
    }

    fun openApp(ctx: Context, name: String): String {
        val app = findApp(ctx, name) ?: return "error: “$name” নামে কোনো অ্যাপ পাওয়া যায়নি"
        val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg) ?: return "error: অ্যাপটা খোলা যায় না"
        return if (launch(ctx, intent)) "ok: ${app.label} খোলা হয়েছে" else "error: ${app.label} খোলা গেল না"
    }

    fun youtubeSearch(ctx: Context, query: String): Boolean {
        val yt = Intent(Intent.ACTION_SEARCH).setPackage("com.google.android.youtube")
            .putExtra(SearchManager.QUERY, query)
        if (launch(ctx, yt)) return true
        return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(query))))
    }

    fun webSearch(ctx: Context, query: String): Boolean =
        launch(ctx, Intent(Intent.ACTION_WEB_SEARCH).putExtra(SearchManager.QUERY, query)) ||
            launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))))

    fun openUrl(ctx: Context, url: String): Boolean {
        val u = if (url.startsWith("http")) url else "https://$url"
        return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(u)))
    }

    fun media(ctx: Context, action: String): String {
        val code = when (action) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "previous" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
        return "ok"
    }

    fun volume(ctx: Context, level: Int, stream: String): String {
        val am = ctx.getSystemService(AudioManager::class.java)
        val s = when (stream) {
            "ring" -> AudioManager.STREAM_RING
            "alarm" -> AudioManager.STREAM_ALARM
            "call" -> AudioManager.STREAM_VOICE_CALL
            else -> AudioManager.STREAM_MUSIC
        }
        return try {
            val max = am.getStreamMaxVolume(s)
            am.setStreamVolume(s, (level.coerceIn(0, 100) * max + 50) / 100, AudioManager.FLAG_SHOW_UI)
            "ok: ${level.coerceIn(0, 100)}%"
        } catch (e: Exception) {
            "error: ${e.message} (Do Not Disturb চালু থাকলে রিং ভলিউম বদলানো যায় না)"
        }
    }

    fun torch(ctx: Context, on: Boolean): String {
        val cm = ctx.getSystemService(CameraManager::class.java)
        return try {
            val id = cm.cameraIdList.firstOrNull {
                cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "error: এই ফোনে ফ্ল্যাশলাইট নেই"
            cm.setTorchMode(id, on)
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    fun alarm(ctx: Context, hour: Int, minute: Int, label: String): Boolean =
        launch(ctx, Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
            .putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Vibe" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    fun timer(ctx: Context, seconds: Int, label: String): Boolean =
        launch(ctx, Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceIn(1, 86_400))
            .putExtra(AlarmClock.EXTRA_MESSAGE, label.ifBlank { "Vibe" })
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true))

    fun settings(ctx: Context, page: String): Boolean = launch(ctx, Intent(
        when (page) {
            "wifi" -> Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "mobile_data" -> Settings.ACTION_DATA_ROAMING_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "battery" -> Intent.ACTION_POWER_USAGE_SUMMARY
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "apps" -> Settings.ACTION_APPLICATION_SETTINGS
            "hotspot" -> Settings.ACTION_WIRELESS_SETTINGS
            "airplane" -> Settings.ACTION_AIRPLANE_MODE_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
    ))

    fun status(ctx: Context): String {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        val charging = bm.isCharging
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "no internet"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile data"
            else -> "connected"
        }
        val locked = ctx.getSystemService(KeyguardManager::class.java).isKeyguardLocked
        val time = SimpleDateFormat("h:mm a, EEEE d MMMM", Locale.ENGLISH).format(Date())
        return "battery $pct%${if (charging) " (charging)" else ""}; network: $net; " +
            "phone ${if (locked) "locked" else "unlocked"}; time: $time"
    }
}
