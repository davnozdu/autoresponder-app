package com.davnozdu.autoresponder.llm

/** Абстракция LLM-провайдера. Все вызовы синхронные — дёргать из фонового потока. */
interface LlmProvider {
    /** Список доступных моделей (для кнопки «запросить все модели»). */
    fun listModels(): List<String>
    /** Короткий ответ модели; null при ошибке. */
    fun generate(prompt: String, maxChars: Int): String?
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
        else -> OllamaProvider(cfg)
    }
}
