package com.davnozdu.autoresponder.llm

/** Абстракция LLM-провайдера. Все вызовы синхронные — дёргать из фонового потока. */
interface LlmProvider {
    /** Список доступных моделей (для кнопки «запросить все модели»). */
    fun listModels(): List<String>
    /**
     * Короткий ответ модели; null при ошибке.
     * @param think разрешить режим размышления (reasoning). Если true — даём большой бюджет
     *   токенов на «мысли», сам ответ всё равно обрезается под лимит SMS вызывающим кодом.
     */
    fun generate(prompt: String, maxChars: Int, think: Boolean = false): String?
}

data class LlmConfig(
    val provider: String,   // "ollama" | "openai" | "claude"
    val baseUrl: String,
    val apiKey: String,
    val model: String,
)

object LlmFactory {
    fun create(cfg: LlmConfig): LlmProvider = when (cfg.provider) {
        "openai" -> OpenAiProvider(cfg)
        "claude" -> ClaudeProvider(cfg)
        "gemini" -> OpenAiProvider(cfg.copy(
            baseUrl = cfg.baseUrl.ifBlank { "https://generativelanguage.googleapis.com/v1beta/openai" }))
        "deepseek" -> OpenAiProvider(cfg.copy(
            baseUrl = cfg.baseUrl.ifBlank { "https://api.deepseek.com" }))
        else -> OllamaProvider(cfg)
    }

    fun defaultBaseUrl(provider: String): String = when (provider) {
        "ollama" -> "https://ollama.com"
        "openai" -> "https://api.openai.com"
        "claude" -> "https://api.anthropic.com"
        "gemini" -> "https://generativelanguage.googleapis.com/v1beta/openai"
        "deepseek" -> "https://api.deepseek.com"
        else -> ""
    }
}
