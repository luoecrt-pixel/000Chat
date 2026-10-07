package com.example.openaichat

data class ModelOption(
    val id: String,
    val label: String
)

data class ApiSourceOption(
    val id: String,
    val label: String,
    val models: List<ModelOption>
)

object ModelCatalog {
    val sources: List<ApiSourceOption> = listOf(
        ApiSourceOption(
            id = "openai",
            label = "OpenAI",
            models = listOf(
                ModelOption("gpt-6-astra", "GPT-6 Astra"),
                ModelOption("gpt-6.1-sol", "GPT-6.1 Sol"),
                ModelOption("gpt-6-luna", "GPT-6 Luna")
            )
        ),
        ApiSourceOption(
            id = "openrouter",
            label = "OpenRouter / Anthropic",
            models = listOf(
                ModelOption("anthropic/claude-opus-5.5", "Claude Opus 5.5"),
                ModelOption("anthropic/claude-sonnet-5.5", "Claude Sonnet 5.5"),
                ModelOption("anthropic/claude-fable-5.1", "Claude Fable 5.1")
            )
        )
    )

    fun source(id: String?): ApiSourceOption =
        sources.firstOrNull { it.id == id } ?: sources.first()

    fun model(providerId: String?, modelId: String?): ModelOption? {
        val normalized = modelId?.trim()?.removePrefix("~").orEmpty()
        return source(providerId).models.firstOrNull { it.id == normalized }
    }

    fun displayName(providerId: String?, modelId: String?): String {
        val normalized = modelId?.trim()?.removePrefix("~").orEmpty()
        return model(providerId, normalized)?.label
            ?: normalized.ifBlank { "Модель не выбрана" }
    }

    enum class Match {
        EXACT,
        MISMATCH,
        UNKNOWN
    }

    fun match(requestedId: String?, responseId: String?): Match {
        val requested = requestedId?.trim()?.removePrefix("~").orEmpty()
        val response = responseId?.trim()?.removePrefix("~").orEmpty()
        if (requested.isBlank() || response.isBlank()) return Match.UNKNOWN
        return if (requested == response) Match.EXACT else Match.MISMATCH
    }
}
