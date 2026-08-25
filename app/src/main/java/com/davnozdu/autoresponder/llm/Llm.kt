package com.davnozdu.autoresponder.llm

import android.content.Context
import com.davnozdu.autoresponder.data.Settings

/** Генерация с фолбэком: основная модель, при неудаче — резервная. */
object Llm {
    fun generate(context: Context, prompt: String, maxChars: Int): String? {
        val s = Settings(context)
        if (s.llmModel.isNotBlank()) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llmProvider, s.llmBaseUrl, s.llmApiKey, s.llmModel)
                ).generate(prompt, maxChars)
                if (!out.isNullOrBlank()) return out
            } catch (_: Exception) {}
        }
        if (s.llm2Enabled && s.llm2Model.isNotBlank()) {
            try {
                val out = LlmFactory.create(
                    LlmConfig(s.llm2Provider, s.llm2BaseUrl, s.llm2ApiKey, s.llm2Model)
                ).generate(prompt, maxChars)
                if (!out.isNullOrBlank()) return out
            } catch (_: Exception) {}
        }
        return null
    }
}
