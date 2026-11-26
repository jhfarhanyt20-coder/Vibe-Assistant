package com.vibe.assistant.data

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

object Http {
    val rest: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    /** For the Live socket: no read timeout (silence is normal), pings keep NATs open. */
    val ws: OkHttpClient = rest.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    val download: OkHttpClient = rest.newBuilder()
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
}
