package com.vibe.assistant.data

/** One of Gemini Live's prebuilt voices. */
data class VoiceInfo(val name: String, val female: Boolean, val style: String)

const val DEFAULT_VOICE = "Kore"

/** All 30 prebuilt Live voices, with their character as Google describes it. */
val VOICES: List<VoiceInfo> = listOf(
    VoiceInfo("Kore", true, "দৃঢ়"),
    VoiceInfo("Aoede", true, "হালকা, ঝরঝরে"),
    VoiceInfo("Leda", true, "তরুণ"),
    VoiceInfo("Zephyr", true, "উজ্জ্বল"),
    VoiceInfo("Callirrhoe", true, "সহজ-সরল"),
    VoiceInfo("Autonoe", true, "উজ্জ্বল"),
    VoiceInfo("Despina", true, "মসৃণ"),
    VoiceInfo("Erinome", true, "স্পষ্ট"),
    VoiceInfo("Laomedeia", true, "প্রাণবন্ত"),
    VoiceInfo("Achernar", true, "কোমল"),
    VoiceInfo("Gacrux", true, "পরিণত"),
    VoiceInfo("Pulcherrima", true, "সাবলীল"),
    VoiceInfo("Vindemiatrix", true, "শান্ত"),
    VoiceInfo("Sulafat", true, "উষ্ণ"),
    VoiceInfo("Puck", false, "প্রাণবন্ত"),
    VoiceInfo("Charon", false, "তথ্যপূর্ণ"),
    VoiceInfo("Fenrir", false, "উচ্ছ্বসিত"),
    VoiceInfo("Orus", false, "দৃঢ়"),
    VoiceInfo("Enceladus", false, "নরম, শ্বাসময়"),
    VoiceInfo("Iapetus", false, "স্পষ্ট"),
    VoiceInfo("Umbriel", false, "সহজ-সরল"),
    VoiceInfo("Algieba", false, "মসৃণ"),
    VoiceInfo("Algenib", false, "ভারী"),
    VoiceInfo("Rasalgethi", false, "তথ্যপূর্ণ"),
    VoiceInfo("Alnilam", false, "দৃঢ়"),
    VoiceInfo("Schedar", false, "সমান"),
    VoiceInfo("Achird", false, "বন্ধুসুলভ"),
    VoiceInfo("Zubenelgenubi", false, "সাধারণ"),
    VoiceInfo("Sadachbia", false, "চঞ্চল"),
    VoiceInfo("Sadaltager", false, "জ্ঞানী"),
)

data class LiveModel(val id: String, val label: String, val note: String)

/**
 * Known Live models, best first. Google's model list (Nov 2026): Gemini 3.8 Live is
 * "the default option for most low-latency voice agent experiences and real-time
 * dialogue without reasoning delays"; 3.1 Flash Live Preview is marked legacy.
 */
val LIVE_MODELS: List<LiveModel> = listOf(
    LiveModel("models/gemini-3.8-live", "Gemini 3.8 Live", "সবচেয়ে দ্রুত উত্তর — Google-এর সুপারিশ করা"),
    LiveModel("models/gemini-3.1-flash-live-preview", "Gemini 3.1 Flash Live", "আগের মডেল (Google বলছে legacy)"),
    LiveModel(
        "models/gemini-2.5-flash-native-audio-preview-12-2025",
        "Gemini 2.5 Native Audio", "পুরনো, বিকল্প হিসেবে"
    ),
    LiveModel("models/gemini-3.8-live-extended-thinking", "Gemini 3.8 Live (বেশি চিন্তা)", "বুদ্ধিমান কিন্তু উত্তর দিতে দেরি করে"),
)

/** Models that think before talking — never picked automatically for conversation. */
fun isSlowModel(id: String) = "extended-thinking" in id || "thinking" in id

fun modelLabel(id: String): String =
    LIVE_MODELS.firstOrNull { it.id == id }?.label
        ?: id.removePrefix("models/").replace('-', ' ')

fun modelNote(id: String): String =
    LIVE_MODELS.firstOrNull { it.id == id }?.note ?: "এই key-তে পাওয়া যাচ্ছে"
