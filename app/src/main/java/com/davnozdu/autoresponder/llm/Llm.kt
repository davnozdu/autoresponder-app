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

    /** Атомарно проверяет дневной лимит. true — можно обращаться к LLM. */
    private fun hasQuota(s: Settings): Boolean {
        if (s.llmDailyCap <= 0) return true  // 0 — без лимита и без счётчика
        synchronized(quotaLock) {
            val today = localDay()
            if (s.llmDay != today) { s.llmDay = today; s.llmCount = 0 }
            return s.llmCount < s.llmDailyCap
        }
    }

    /**
     * Списывает один вызов. Раньше счётчик тратился ДО запроса, поэтому таймауты, 429 и пустые
     * ответы съедали дневной лимит наравне с удачными ответами.
     */
    private fun spendQuota(s: Settings) {
        if (s.llmDailyCap <= 0) return
        synchronized(quotaLock) { s.llmCount = s.llmCount + 1 }
    }

    /**
     * Один повтор на «мягких» отказах: 429, 5xx, обрыв соединения.
     *
     * Ретраев не было вовсе — единственный 429 сразу уводил клиента на офлайн-шаблон, хотя
     * через полсекунды тот же запрос обычно проходит. На 4xx (кроме 429) не повторяем: это
     * ошибка конфигурации, второй попытки не заслуживает.
     */
    private fun withRetry(block: () -> String?): String? {
        try { return block() } catch (e: Exception) {
            val msg = (e.message ?: "").lowercase()
            val retryable = msg.contains("429") || Regex("http 5\\d\\d").containsMatchIn(msg) ||
                e is java.io.IOException
            if (!retryable) throw e
            Thread.sleep(700)
            return block()
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
        if (!hasQuota(s)) return null

        if (mode == "local") return runLocal(context, prompt, system)

        val online = NetworkUtil.isOnline(context)
        val canTryCloud = online && (mode != "auto" ||
            android.os.SystemClock.elapsedRealtime() >= retryCloudAt)
        if (!canTryCloud) return if (mode == "auto") runLocal(context, prompt, system) else null

        // Основной канал.
        if (primary) {
            try {
                val out = withRetry {
                    LlmFactory.create(
                        LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                    ).generate(prompt, maxChars, s.llmThink, system)
                }
                if (!out.isNullOrBlank()) {
                    spendQuota(s)
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
                val out = withRetry {
                    LlmFactory.create(
                        LlmConfig(s.llm2Provider, s.llm2BaseUrl, s.llm2ApiKey, s.llm2Model)
                    ).generate(prompt, maxChars, s.llmThink, system)
                }
                if (!out.isNullOrBlank()) {
                    spendQuota(s)
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
