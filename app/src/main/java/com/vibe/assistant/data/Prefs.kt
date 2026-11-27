package com.vibe.assistant.data

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * All settings. The Gemini key is the one secret: it is encrypted with an AES key
 * that lives in the Android Keystore (never leaves secure hardware where present),
 * so the stored preference is useless if copied off the phone.
 */
object Prefs {
    private lateinit var sp: SharedPreferences
    private lateinit var secure: SharedPreferences

    @Volatile private var cachedKey: String? = null
    @Volatile private var keyLoaded = false

    fun init(ctx: Context) {
        sp = ctx.getSharedPreferences("vibe_settings", Context.MODE_PRIVATE)
        secure = ctx.getSharedPreferences("vibe_secure", Context.MODE_PRIVATE)
    }

    /** PC tests only: plain in-memory settings, so the real prompt can be built without a phone. */
    fun initForTest(settings: SharedPreferences, secrets: SharedPreferences) {
        sp = settings
        secure = secrets
    }

    var apiKey: String?
        get() {
            if (!keyLoaded) {
                cachedKey = SecureBox.decrypt(secure.getString("gemini_key", null))
                keyLoaded = true
            }
            return cachedKey
        }
        set(value) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            secure.edit().apply {
                if (v == null) remove("gemini_key") else putString("gemini_key", SecureBox.encrypt(v))
            }.apply()
            cachedKey = v
            keyLoaded = true
        }

    var voice: String
        get() {
            val v = sp.getString("voice", DEFAULT_VOICE) ?: DEFAULT_VOICE
            return if (VOICES.any { it.name == v }) v else DEFAULT_VOICE
        }
        set(v) = sp.edit().putString("voice", v).apply()

    var model: String
        get() = sp.getString("model", null) ?: LIVE_MODELS[0].id
        set(v) = sp.edit().putString("model", v).apply()

    /** Live models this key can actually use, as reported by the API (ordered). */
    var liveModels: List<String>
        get() = sp.getString("live_models", null)
            ?.split("|")?.filter { it.isNotBlank() }?.takeIf { it.isNotEmpty() }
            ?: LIVE_MODELS.map { it.id }
        set(v) = sp.edit().putString("live_models", v.joinToString("|")).apply()

    var wakeEnabled: Boolean
        get() = sp.getBoolean("wake_enabled", true) // on by default: the model ships in the APK
        set(v) = sp.edit().putBoolean("wake_enabled", v).apply()

    var overlayEnabled: Boolean
        get() = sp.getBoolean("overlay_enabled", true)
        set(v) = sp.edit().putBoolean("overlay_enabled", v).apply()

    /** Whether you can cut in while Vibe is talking (needs good echo cancelling). */
    var bargeIn: Boolean
        get() = sp.getBoolean("barge_in", true)
        set(v) = sp.edit().putBoolean("barge_in", v).apply()

    var lockScreenShow: Boolean
        get() = sp.getBoolean("lock_show", true)
        set(v) = sp.edit().putBoolean("lock_show", v).apply()

    /** Seconds of silence after which the conversation closes and Vibe goes back to sleep. */
    var sleepSeconds: Int
        get() = sp.getInt("sleep_seconds", 0) // 0 = never sleep: always connected
        set(v) = sp.edit().putInt("sleep_seconds", v).apply()

    var overlayX: Int
        get() = sp.getInt("overlay_x", -1)
        set(v) = sp.edit().putInt("overlay_x", v).apply()

    var overlayY: Int
        get() = sp.getInt("overlay_y", -1)
        set(v) = sp.edit().putInt("overlay_y", v).apply()

    /** "auto" = Vibe listens all the time; "ptt" = only while you hold the button / volume key. */
    var pushToTalk: Boolean
        get() = sp.getBoolean("ptt", false)
        set(v) = sp.edit().putBoolean("ptt", v).apply()

    /** Hold volume-down to talk (needs the accessibility service). */
    var pttVolumeKey: Boolean
        get() = sp.getBoolean("ptt_volume", true)
        set(v) = sp.edit().putBoolean("ptt_volume", v).apply()

    /** Voice Lock: only the enrolled owner's voice can wake Vibe. */
    var voiceLock: Boolean
        get() = sp.getBoolean("voice_lock", false)
        set(v) = sp.edit().putBoolean("voice_lock", v).apply()

    /** Voice Lock also during the conversation: other people's speech never reaches Gemini. */
    var voiceStrict: Boolean
        get() = sp.getBoolean("voice_strict", true)
        set(v) = sp.edit().putBoolean("voice_strict", v).apply()

    /** 0 = lenient, 1 = normal, 2 = strict. */
    var voiceLevel: Int
        get() = sp.getInt("voice_level", 1)
        set(v) = sp.edit().putInt("voice_level", v).apply()

    var ownerVoice: FloatArray?
        get() = sp.getString("owner_voice", null)?.split(",")?.mapNotNull { it.toFloatOrNull() }
            ?.takeIf { it.size >= 32 }?.toFloatArray()
        set(v) = sp.edit().apply {
            if (v == null) remove("owner_voice") else putString("owner_voice", v.joinToString(","))
        }.apply()

    /** Announce incoming calls and let you answer / reject by voice. */
    var callAnnounce: Boolean
        get() = sp.getBoolean("call_announce", true)
        set(v) = sp.edit().putBoolean("call_announce", v).apply()

    /** Let Gemini search Google for current information. Off by default: v0.1 had none. */
    var googleSearch: Boolean
        get() = sp.getBoolean("google_search2", false)
        set(v) = sp.edit().putBoolean("google_search2", v).apply()

    /** The phone's lock-screen PIN, encrypted like the API key. Only used by voice unlock. */
    var unlockPin: String?
        get() = SecureBox.decrypt(secure.getString("unlock_pin", null))
        set(value) {
            val v = value?.filter { it.isDigit() }?.takeIf { it.length >= 4 }
            secure.edit().apply {
                if (v == null) remove("unlock_pin") else putString("unlock_pin", SecureBox.encrypt(v))
            }.apply()
        }

    /** Unlock by voice: only after Voice Lock has just recognised the owner. */
    var voiceUnlock: Boolean
        get() = sp.getBoolean("voice_unlock", false)
        set(v) = sp.edit().putBoolean("voice_unlock", v).apply()

    /** Check GitHub for a new version and download it automatically. */
    var autoUpdate: Boolean
        get() = sp.getBoolean("auto_update", true)
        set(v) = sp.edit().putBoolean("auto_update", v).apply()

    var lastUpdateCheck: Long
        get() = sp.getLong("last_update_check", 0L)
        set(v) = sp.edit().putLong("last_update_check", v).apply()

    var updateDownloadId: Long
        get() = sp.getLong("update_dl_id", -1L)
        set(v) = sp.edit().putLong("update_dl_id", v).apply()

    var updateVersion: String
        get() = sp.getString("update_version", "") ?: ""
        set(v) = sp.edit().putString("update_version", v).apply()

    /**
     * Talk only, exactly as v0.1 connected: no tools, no search, the v0.1 prompt.
     * For when the conversation matters more than phone control (and to compare).
     */
    var talkOnly: Boolean
        get() = sp.getBoolean("talk_only", false)
        set(v) = sp.edit().putBoolean("talk_only", v).apply()

    /** Show what you and Vibe say as text on screen (off = only the orb moves). */
    var showTranscript: Boolean
        get() = sp.getBoolean("show_transcript", false)
        set(v) = sp.edit().putBoolean("show_transcript", v).apply()

    /**
     * Ask "পাঠাবো?" before sending SMS / WhatsApp / voice messages. Off by default:
     * the user wants a direction carried out start to finish without questions.
     */
    var askBeforeSend: Boolean
        get() = sp.getBoolean("ask_before_send", false)
        set(v) = sp.edit().putBoolean("ask_before_send", v).apply()

    /** Only the close, loud voice reaches Gemini (far voices / noise become silence). */
    var nearVoice: Boolean
        get() = sp.getBoolean("near_voice", true)
        set(v) = sp.edit().putBoolean("near_voice", v).apply()

    /** Stricter near-voice filter for very noisy places. */
    var nearVoiceStrong: Boolean
        get() = sp.getBoolean("near_voice_strong", false)
        set(v) = sp.edit().putBoolean("near_voice_strong", v).apply()

    /** Which SIM calls go out on: 1, 2, or 0 = whatever the phone asks / its default. */
    var callSim: Int
        get() = sp.getInt("call_sim", 0)
        set(v) = sp.edit().putInt("call_sim", v).apply()

    /** Who built Vibe — what it answers to "who made you?". */
    var creatorName: String
        get() = sp.getString("creator_name", "") ?: ""
        set(v) = sp.edit().putString("creator_name", v.trim()).apply()

    /** What Vibe calls the user. */
    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) = sp.edit().putString("user_name", v.trim()).apply()

    /** The text model that last worked for the screen-control agent. */
    var agentModel: String?
        get() = sp.getString("agent_model", null)
        set(v) = sp.edit().putString("agent_model", v).apply()
}

private object SecureBox {
    private const val ALIAS = "vibe_api_key_v1"
    private const val TRANSFORM = "AES/GCM/NoPadding"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    fun encrypt(plain: String): String {
        val c = Cipher.getInstance(TRANSFORM)
        c.init(Cipher.ENCRYPT_MODE, key())
        val ct = c.doFinal(plain.toByteArray(Charsets.UTF_8))
        return b64(c.iv) + ":" + b64(ct)
    }

    fun decrypt(stored: String?): String? {
        if (stored.isNullOrEmpty()) return null
        return try {
            val parts = stored.split(":", limit = 2)
            val c = Cipher.getInstance(TRANSFORM)
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, unb64(parts[0])))
            String(c.doFinal(unb64(parts[1])), Charsets.UTF_8)
        } catch (e: Exception) {
            null
        }
    }

    private fun b64(b: ByteArray) = Base64.encodeToString(b, Base64.NO_WRAP)
    private fun unb64(s: String) = Base64.decode(s, Base64.NO_WRAP)
}
