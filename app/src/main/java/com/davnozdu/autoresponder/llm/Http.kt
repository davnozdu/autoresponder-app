package com.davnozdu.autoresponder.llm

import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

internal object Http {
    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()
    const val JSON = "application/json"
}
