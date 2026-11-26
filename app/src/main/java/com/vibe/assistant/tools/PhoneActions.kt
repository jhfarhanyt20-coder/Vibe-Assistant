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
        // 2) plain "contains" over every name, for nicknames in the middle of a name
        if (out.isEmpty()) {
            val needle = q.lowercase()
            ctx.contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext() && out.size < 12) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    if (name.lowercase().contains(needle)) out.putIfAbsent(cleanNumber(num).takeLast(10), Contact(name, num))
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

    @SuppressLint("MissingPermission")
    fun placeCall(ctx: Context, number: String): String {
        if (!has(ctx, Manifest.permission.CALL_PHONE)) return "error: কল করার অনুমতি দেওয়া নেই (Vibe অ্যাপ → সেটিংস → অনুমতি)"
        val tm = ctx.getSystemService(TelecomManager::class.java)
        return try {
            tm.placeCall(Uri.fromParts("tel", cleanNumber(number), null), Bundle())
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

    fun sendSms(ctx: Context, number: String, text: String): String {
        if (!has(ctx, Manifest.permission.SEND_SMS)) return "error: SMS পাঠানোর অনুমতি দেওয়া নেই"
        return try {
            val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(text)
            sms.sendMultipartTextMessage(cleanNumber(number), null, parts, null, null)
            "ok"
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}
