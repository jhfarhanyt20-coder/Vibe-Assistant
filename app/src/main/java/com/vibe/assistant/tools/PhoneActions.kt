package com.vibe.assistant.tools

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.SmsManager

/** Calls, incoming-call handling, SMS and contact lookup. */
object PhoneActions {
    data class Contact(val name: String, val number: String)

    private fun has(ctx: Context, p: String) =
        ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun looksLikeNumber(s: String) = s.count { it.isDigit() } >= 5 &&
        s.all { it.isDigit() || it in "+-() " || it in '০'..'৯' }

    /** Bangla digits → ASCII, keep + and digits. */
    fun cleanNumber(s: String): String = buildString {
        for (ch in s) when {
            ch in '০'..'৯' -> append('0' + (ch - '০'))
            ch.isDigit() || ch == '+' -> append(ch)
        }
    }

    fun search(ctx: Context, query: String): List<Contact> {
        if (!has(ctx, Manifest.permission.READ_CONTACTS)) throw SecurityException("no_contacts_permission")
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        val out = LinkedHashMap<String, Contact>()
        val proj = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER,
        )
        // 1) the system's own fuzzy name filter
        val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
        ctx.contentResolver.query(uri, proj, null, null, null)?.use { c ->
            while (c.moveToNext() && out.size < 12) {
                val name = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out.putIfAbsent(cleanNumber(num).takeLast(10), Contact(name, num))
            }
        }
        // 2) every contact, matched across scripts and spellings: "রহিম" finds
        //    "Rahim", "Rohim" finds "Rahim Uddin" (see Fuzzy). Best matches first.
        if (out.isEmpty()) {
            val scored = mutableListOf<Pair<Int, Contact>>()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val s = Fuzzy.score(q, name) ?: continue
                    scored += s to Contact(name, num)
                }
            }
            val best = scored.minOfOrNull { it.first }
            if (best != null) {
                // Only the closest tier: a near-miss shouldn't compete with a real match.
                for ((s, c) in scored.sortedBy { it.first }) {
                    if (s > best + 1 || out.size >= 8) break
                    out.putIfAbsent(cleanNumber(c.number).takeLast(10), c)
                }
            }
        }
        val list = out.values.toList()
        // An exact name match wins outright.
        val exact = list.filter { it.name.equals(q, ignoreCase = true) }
        return if (exact.isNotEmpty()) exact else list
    }

    fun nameForNumber(ctx: Context, number: String?): String? {
        if (number.isNullOrBlank() || !has(ctx, Manifest.permission.READ_CONTACTS)) return null
        val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
        return try {
            ctx.contentResolver.query(uri, arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        } catch (_: Exception) {
            null
        }
    }

    /** The SIMs that can make calls, in slot order, with their names (e.g. "Grameenphone"). */
    @SuppressLint("MissingPermission")
    fun sims(ctx: Context): List<Pair<android.telecom.PhoneAccountHandle, String>> {
        if (!has(ctx, Manifest.permission.READ_PHONE_STATE)) return emptyList()
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            tm.callCapablePhoneAccounts.map { h ->
                h to (tm.getPhoneAccount(h)?.label?.toString() ?: "SIM")
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Places a call. With two SIMs Android normally pops up "which SIM?" and the
     * call waits there; choosing the SIM up front (the user's [sim]: 1 or 2, else the
     * one set in Vibe's settings) means no pop-up at all. 0 = let the phone decide.
     */
    @SuppressLint("MissingPermission")
    fun placeCall(ctx: Context, number: String, sim: Int = 0): String {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) return "error: কল করার অনুমতি দেওয়া নেই (Vibe অ্যাপ → সেটিংস → অনুমতি)"
        val tm = ctx.getSystemService(TelecomManager::class.java)
        val extras = Bundle()
        val want = if (sim > 0) sim else com.vibe.assistant.data.Prefs.callSim
        val all = sims(ctx)
        if (want > 0 && all.size >= want) {
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, all[want - 1].first)
        }
        return try {
            tm.placeCall(Uri.fromParts("tel", cleanNumber(number), null), extras)
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun answer(ctx: Context, speaker: Boolean): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "error: কল ধরার অনুমতি দেওয়া নেই"
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            tm.acceptRingingCall()
            if (speaker) {
                val am = ctx.getSystemService(AudioManager::class.java)
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (Build.VERSION.SDK_INT >= 31) {
                            am.availableCommunicationDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                                ?.let { am.setCommunicationDevice(it) }
                        } else {
                            am.isSpeakerphoneOn = true
                        }
                    } catch (_: Exception) {}
                }, 1200)
            }
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun hangUp(ctx: Context): String {
        if (!has(ctx, Manifest.permission.ANSWER_PHONE_CALLS)) return "error: কল কাটার অনুমতি দেওয়া নেই"
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            if (tm.endCall()) "ok" else "error: কোনো কল চলছে না"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }

    /**
     * Sends an SMS and waits for the phone network's own "sent" report for every
     * part (up to 25 s). "ok" means the network accepted it — not just that we asked.
     * Works with the phone locked.
     */
    suspend fun sendSms(ctx: Context, number: String, text: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) return "error: SMS পাঠানোর অনুমতি দেওয়া নেই"
        val sms = try {
            if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
        } catch (e: Exception) {
            return "error: ${e.message}"
        }
        val parts = sms.divideMessage(text)
        val action = "com.vibe.assistant.SMS_SENT." + System.nanoTime()
        val results = java.util.concurrent.LinkedBlockingQueue<Int>()
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                results.offer(resultCode)
            }
        }
        androidx.core.content.ContextCompat.registerReceiver(
            ctx, receiver, android.content.IntentFilter(action), androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
        )
        try {
            val sent = ArrayList<android.app.PendingIntent>()
            for (k in parts.indices) {
                sent += android.app.PendingIntent.getBroadcast(
                    ctx, (System.nanoTime() and 0x7fffffff).toInt() + k,
                    android.content.Intent(action).setPackage(ctx.packageName),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_ONE_SHOT
                )
            }
            try {
                sms.sendMultipartTextMessage(cleanNumber(number), null, parts, sent, null)
            } catch (e: Exception) {
                return "error: ${e.message}"
            }
            return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var okParts = 0
                val deadline = System.currentTimeMillis() + 25_000
                while (okParts < parts.size) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) return@withContext "unconfirmed: the network has not confirmed it yet"
                    val code = results.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS)
                        ?: return@withContext "unconfirmed: the network has not confirmed it yet"
                    if (code != android.app.Activity.RESULT_OK) {
                        return@withContext "error: " + when (code) {
                            SmsManager.RESULT_ERROR_NO_SERVICE -> "no mobile network"
                            SmsManager.RESULT_ERROR_RADIO_OFF -> "airplane mode / radio off"
                            SmsManager.RESULT_ERROR_NULL_PDU, SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "the network refused it (balance or SIM problem?)"
                            else -> "sending failed (code $code)"
                        }
                    }
                    okParts++
                }
                "ok"
            }
        } finally {
            try { ctx.unregisterReceiver(receiver) } catch (_: Exception) {}
        }
    }
}
