package com.vibe.assistant.tools

import org.json.JSONArray
import org.json.JSONObject

/** The function declarations Gemini Live gets — the phone's abilities, described for the model. */
object ToolSpecs {
    private fun obj(vararg props: Pair<String, JSONObject>, required: List<String> = emptyList()): JSONObject {
        val p = JSONObject()
        for ((k, v) in props) p.put(k, v)
        return JSONObject().put("type", "OBJECT").put("properties", p).apply {
            if (required.isNotEmpty()) put("required", JSONArray(required))
        }
    }

    private fun str(desc: String) = JSONObject().put("type", "STRING").put("description", desc)
    private fun bool(desc: String) = JSONObject().put("type", "BOOLEAN").put("description", desc)
    private fun int(desc: String) = JSONObject().put("type", "INTEGER").put("description", desc)
    private fun enum(desc: String, vararg values: String) =
        JSONObject().put("type", "STRING").put("description", desc).put("enum", JSONArray(values.toList()))

    private fun fn(name: String, desc: String, params: JSONObject? = null) =
        JSONObject().put("name", name).put("description", desc).apply { params?.let { put("parameters", it) } }

    fun all(screenControl: Boolean): JSONArray {
        val list = mutableListOf(
            fn("find_contact", "Search the phone's contacts by name. Returns matching names and numbers.",
                obj("name" to str("Name as the user said it"), required = listOf("name"))),
            fn("call_contact", "Place a phone call to a contact name or a phone number. Works while locked.",
                obj("to" to str("Contact name or phone number"),
                    "confirmed" to bool("True if the user already confirmed which person/number"),
                    required = listOf("to"))),
            fn("answer_call", "Answer the phone call that is ringing right now.",
                obj("speaker" to bool("Put the call on loudspeaker"))),
            fn("reject_call", "Reject the ringing incoming call. Optionally text the caller a short reply " +
                "(e.g. 'ব্যস্ত আছি, পরে কল দিচ্ছি') — only if the user asked for it.",
                obj("reply_sms" to str("Optional SMS to send to the caller after rejecting"))),
            fn("end_call", "Hang up the current phone call."),
            fn("send_sms", "Send an SMS text message. First call with confirmed=false to get the read-back, " +
                "then with confirmed=true only after the user clearly agreed.",
                obj("to" to str("Contact name or phone number"),
                    "message" to str("The exact message text"),
                    "confirmed" to bool("User has confirmed recipient and text"),
                    required = listOf("to", "message", "confirmed"))),
            fn("whatsapp_message", "Send a WhatsApp text message to a contact — fast and direct. Use this (not do_task) " +
                "for WhatsApp texts. First call with confirmed=false to get the read-back, then confirmed=true after a yes.",
                obj("to" to str("Contact name or phone number"),
                    "message" to str("The exact message text"),
                    "confirmed" to bool("User has confirmed recipient and text"),
                    required = listOf("to", "message", "confirmed"))),
            fn("send_voice_message", "Record the message in YOUR (Vibe's) voice and send it as a voice message, " +
                "on WhatsApp by default. First confirmed=false for the read-back, then confirmed=true after a yes.",
                obj("to" to str("Contact name or phone number"),
                    "message" to str("What the voice message should say, in the user's language"),
                    "app" to enum("Which app", "whatsapp", "telegram", "messenger", "other"),
                    "confirmed" to bool("User has confirmed recipient and text"),
                    required = listOf("to", "message", "confirmed"))),
            fn("open_app", "Open an installed app by its name (English name works best, e.g. WhatsApp, Camera).",
                obj("app" to str("App name"), required = listOf("app"))),
            fn("youtube_play", "Search YouTube and play the first matching video (songs, videos).",
                obj("query" to str("What to play"), required = listOf("query"))),
            fn("web_search", "Open a Google search in the browser so the user can see results.",
                obj("query" to str("Search text"), required = listOf("query"))),
            fn("open_url", "Open a web address in the browser.",
                obj("url" to str("Full URL"), required = listOf("url"))),
            fn("media_control", "Control whatever music/video is playing.",
                obj("action" to enum("What to do", "play", "pause", "toggle", "next", "previous"),
                    required = listOf("action"))),
            fn("set_volume", "Set the phone volume.",
                obj("level" to int("0 to 100 percent"),
                    "stream" to enum("Which volume", "media", "ring", "alarm", "call"),
                    required = listOf("level"))),
            fn("flashlight", "Turn the torch / flashlight on or off.",
                obj("on" to bool("true = on"), required = listOf("on"))),
            fn("set_alarm", "Set an alarm.",
                obj("hour" to int("0-23"), "minute" to int("0-59"), "label" to str("Optional label"),
                    required = listOf("hour", "minute"))),
            fn("set_timer", "Start a countdown timer.",
                obj("seconds" to int("Length in seconds"), "label" to str("Optional label"),
                    required = listOf("seconds"))),
            fn("open_settings", "Open a settings screen.",
                obj("page" to enum("Which page", "main", "wifi", "bluetooth", "mobile_data", "display",
                    "sound", "battery", "location", "apps", "hotspot", "airplane"),
                    required = listOf("page"))),
            fn("phone_action", "System actions: go home, back, recent apps, notifications, quick settings, " +
                "lock the phone, take a screenshot.",
                obj("action" to enum("Action", "home", "back", "recents", "notifications",
                    "quick_settings", "lock", "screenshot"), required = listOf("action"))),
            fn("phone_status", "Battery level, charging, network, time, and whether the phone is locked."),
            fn("unlock_phone", "Bring up the unlock screen and wait while the user unlocks with fingerprint, face or PIN. " +
                "Never ask the user to say their PIN."),
            fn("remember", "Save a durable fact about the user to long-term memory.",
                obj("fact" to str("One short sentence"),
                    "category" to enum("Kind", "name", "people", "likes", "habits", "work", "dates", "places", "other"),
                    required = listOf("fact"))),
            fn("forget", "Remove facts from long-term memory that mention the given text.",
                obj("about" to str("What to forget"), required = listOf("about"))),
            fn("end_conversation", "End the conversation and go back to sleep (after saying bye)."),
        )
        if (screenControl) {
            list += fn("look_at_screen", "SEE the screen: takes a screenshot and shows it to you as an image. " +
                "Use when the user says 'দেখো তো', 'স্ক্রিনে কী আছে', asks about a picture, video, game or anything visual.")
            list += fn("tap_on", "Tap something on the screen, described by its name or look " +
                "(e.g. 'Send', 'the search icon', 'blue Install button', 'first video'). Vibe finds the real control " +
                "and taps its centre; afterwards you get a fresh screenshot. Use for step-by-step control the user directs.",
                obj("target" to str("What to tap, as it appears on screen"),
                    "long_press" to bool("Press and hold instead of a tap"),
                    required = listOf("target")))
            list += fn("type_text", "Type text into a field on the screen (the focused one, or the one named in 'into').",
                obj("text" to str("Exact text to type"),
                    "into" to str("Optional: which field, e.g. 'search box', 'Message'"),
                    "submit" to bool("Press enter/search after typing"),
                    required = listOf("text")))
            list += fn("scroll_screen", "Scroll or swipe the screen.",
                obj("direction" to enum("Which way the content moves into view", "down", "up", "left", "right"),
                    required = listOf("direction")))
            list += fn("read_screen", "Read the text and buttons currently on the screen.")
            list += fn("do_task", "Do a multi-step task inside apps by reading the screen and tapping/typing, " +
                "e.g. 'send \"I am coming\" to Rahim on WhatsApp' or 'turn on dark mode'. " +
                "Give a complete, specific goal. Returns what was done.",
                obj("goal" to str("The full goal, with names and exact text"), required = listOf("goal")))
        }
        return JSONArray(list)
    }
}
