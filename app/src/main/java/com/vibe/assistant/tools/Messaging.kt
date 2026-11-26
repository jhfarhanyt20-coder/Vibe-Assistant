package com.vibe.assistant.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.telephony.TelephonyManager
import android.view.accessibility.AccessibilityNodeInfo
import com.vibe.assistant.access.VibeAccessibility
import kotlinx.coroutines.delay

/**
 * WhatsApp without wandering around the screen: open the chat straight from
 * WhatsApp's own link with the text already typed, then press its Send button.
 * That is one jump and one tap, instead of a dozen steps of searching and typing.
 */
object Messaging {
    val WHATSAPP = listOf("com.whatsapp", "com.whatsapp.w4b")

    private val CALLING_CODES = mapOf(
        "bd" to "880", "in" to "91", "pk" to "92", "np" to "977", "lk" to "94", "us" to "1", "ca" to "1",
        "gb" to "44", "ae" to "971", "sa" to "966", "qa" to "974", "kw" to "965", "om" to "968",
        "bh" to "973", "my" to "60", "sg" to "65", "it" to "39", "de" to "49", "fr" to "33", "au" to "61",
    )

    /** Digits with country code, as WhatsApp wants: 01712345678 → 8801712345678. */
    fun international(ctx: Context, number: String): String {
        var d = PhoneActions.cleanNumber(number)
        if (d.startsWith("+")) return d.drop(1)
        if (d.startsWith("00")) return d.drop(2)
        val tm = ctx.getSystemService(TelephonyManager::class.java)
        val iso = (tm?.simCountryIso?.takeIf { it.isNotBlank() } ?: tm?.networkCountryIso ?: "bd").lowercase()
        val cc = CALLING_CODES[iso] ?: "880"
        if (d.startsWith(cc) && d.length > 10) return d
        if (d.startsWith("0")) d = d.drop(1)
        return cc + d
    }

    fun installedWhatsApp(ctx: Context): String? =
        WHATSAPP.firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    /** Opens the chat with the message typed in. */
    fun openChat(ctx: Context, number: String, text: String): String? {
        val pkg = installedWhatsApp(ctx) ?: return null
        val url = "https://api.whatsapp.com/send?phone=${international(ctx, number)}&text=${Uri.encode(text)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url)).setPackage(pkg)
        return if (DeviceActions.launch(ctx, i)) pkg else null
    }

    /** Waits for WhatsApp's send button (chat or media preview) and presses it. */
    suspend fun pressSend(pkg: String, timeoutMs: Long = 8000): Boolean {
        val acc = VibeAccessibility.instance ?: return false
        val ids = listOf("$pkg:id/send", "$pkg:id/send_btn", "$pkg:id/send_button")
        val labels = listOf("send", "পাঠান", "পাঠাও", "भेजें")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < timeoutMs) {
            delay(400)
            val root = acc.rootInActiveWindow ?: continue
            if (root.packageName?.toString() != pkg) continue
            var target: AccessibilityNodeInfo? = null
            for (id in ids) {
                target = root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull { it.isVisibleToUser }
                if (target != null) break
            }
            if (target == null) {
                // The send arrow (described "Send") OR a confirm button whose TEXT is "SEND"/"পাঠান"
                // — WhatsApp asks "Send to Rahim?" after sharing a file.
                target = find(root) { n ->
                    val d = (n.contentDescription ?: "").toString().trim().lowercase()
                    val t = (n.text ?: "").toString().trim().lowercase()
                    n.isVisibleToUser && n.isEnabled && (labels.any { d == it } || labels.any { t == it })
                }
            }
            if (target != null && acc.tap(target)) return true
        }
        return false
    }

    private fun find(n: AccessibilityNodeInfo, pred: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (pred(n)) return n
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            find(c, pred)?.let { return it }
        }
        return null
    }
}
