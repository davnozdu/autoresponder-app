package com.davnozdu.autoresponder.llm

import android.content.Context
import com.davnozdu.autoresponder.data.EventLog
import com.davnozdu.autoresponder.data.Settings
import com.davnozdu.autoresponder.respond.NetworkUtil

/** Routes text requests by the selected mode. Cloud is checked only when a request arrives. */
object Llm {
    private val quotaLock = Any()
    @Volatile private var retryCloudAt = 0L
    private const val CLOUD_RETRY_MS = 120_000L

    /** Номер локальных суток (не UTC): иначе счётчик сбрасывался ночью по UTC, а не в полночь. */
    private fun localDay(): Int {
        val c = java.util.Calendar.getInstance()
        return c.get(java.util.Calendar.YEAR) * 1000 + c.get(java.util.Calendar.DAY_OF_YEAR)
    }

    /** Атомарно проверяет и расходует дневной лимит. true — можно обращаться к LLM. */
    private fun consumeQuota(s: Settings): Boolean {
        if (s.llmDailyCap <= 0) return true  // 0 — без лимита и без счётчика
        synchronized(quotaLock) {
            val today = localDay()
            if (s.llmDay != today) { s.llmDay = today; s.llmCount = 0 }
            if (s.llmCount >= s.llmDailyCap) return false
            s.llmCount = s.llmCount + 1
            return true
        }
    }

    /** Настроен ли хоть один канал (основной или резервный) — для экранов пересказа/чата. */
    fun isConfigured(context: Context): Boolean {
        val s = Settings(context)
        val cloud = s.llmModel.isNotBlank() || (s.llm2Enabled && s.llm2Model.isNotBlank())
        val local = LocalTextModel.isReady(context)
        return s.llmEnabled && when (s.llmMode) {
            "local" -> local
            "auto" -> cloud || local
            else -> cloud
        }
    }

    fun generate(context: Context, prompt: String, maxChars: Int, system: String = ""): String? {
        val s = Settings(context)
        val primary = s.llmModel.isNotBlank()
        val backup = s.llm2Enabled && s.llm2Model.isNotBlank()
        val mode = s.llmMode
        val localReady = mode != "cloud" && LocalTextModel.isReady(context)
        if (!s.llmEnabled || (mode == "local" && !localReady)) return null
        // Нечего спрашивать — не трогаем счётчик вообще.
        if (!primary && !backup && !localReady) return null
        if (!consumeQuota(s)) return null

        if (mode == "local") return runLocal(context, prompt, system)

        val online = NetworkUtil.isOnline(context)
        val canTryCloud = online && (mode != "auto" ||
            android.os.SystemClock.elapsedRealtime() >= retryCloudAt)
        if (!canTryCloud) return if (mode == "auto") runLocal(context, prompt, system) else null

        // Основной канал.
        if (primary) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                ).generate(prompt, maxChars, s.llmThink, system)
                if (!out.isNullOrBlank()) {
                    retryCloudAt = 0L
                    LocalTextModel.releaseIfIdle()
                    return out
                }
                EventLog(context).add("LLM основной [${s.llmProvider}/${s.llmModel}] пуст/таймаут → резервный")
            } catch (e: Exception) {
                EventLog(context).add("LLM основной [${s.llmProvider}/${s.llmModel}] ошибка: ${e.message} → резервный")
            }
        }

        // Резервный канал.
        if (backup) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llm2Provider, s.llm2BaseUrl, s.llm2ApiKey, s.llm2Model)
                ).generate(prompt, maxChars, s.llmThink, system)
                if (!out.isNullOrBlank()) {
                    retryCloudAt = 0L
                    LocalTextModel.releaseIfIdle()
                    return out
                }
                EventLog(context).add("LLM резервный [${s.llm2Provider}/${s.llm2Model}] пуст/таймаут → заглушка")
            } catch (e: Exception) {
                EventLog(context).add("LLM резервный [${s.llm2Provider}/${s.llm2Model}] ошибка: ${e.message} → заглушка")
            }
        }
        if (mode == "auto") {
            retryCloudAt = android.os.SystemClock.elapsedRealtime() + CLOUD_RETRY_MS
            return runLocal(context, prompt, system)
        }
        return null
    }

    private fun runLocal(context: Context, prompt: String, system: String): String? {
        if (!LocalTextModel.isReady(context)) return null
        return try {
            LocalTextModel.generate(context, prompt, system)
        } catch (e: Exception) {
            LocalTextModel.release()
            EventLog(context).add("Локальная LLM ошибка: ${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }
}
