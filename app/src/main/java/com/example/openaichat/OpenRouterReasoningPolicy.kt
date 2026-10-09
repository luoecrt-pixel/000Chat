package com.example.openaichat

import java.util.Locale

/** One transport policy for effort; never silently downgrade a user-selected level. */
object OpenRouterReasoningPolicy {
    private val gatewayEfforts = setOf("none", "minimal", "low", "medium", "high", "xhigh", "max")
    private val claudeEfforts = setOf("low", "medium", "high", "xhigh", "max")

    // Five native effort levels for the Claude models in ModelCatalog.
    private val currentClaudeModels = setOf(
        "anthropic/claude-opus-5.5",
        "anthropic/claude-sonnet-5.5",
        "anthropic/claude-fable-5.1"
    )

    private fun modelId(model: String): String = model.trim().removePrefix("~")

    fun requestEffort(model: String, selected: String?): String? {
        val effort = selected?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }
            ?: return null
        require(effort != "auto") {
            "Автоматическое усилие нужно выбрать по запросу до отправки в OpenRouter"
        }
        require(effort in gatewayEfforts) { "Неизвестное усилие мышления: $effort" }
        if (modelId(model) in currentClaudeModels) {
            require(effort in claudeEfforts) {
                "Модель $model не поддерживает усилие $effort"
            }
        }
        return effort
    }

    /** Anthropic uses verbosity as an effort override; sending both would conflict. */
    fun supportsResponseVerbosity(model: String): Boolean =
        !modelId(model).startsWith("anthropic/")
}
