package com.example.openaichat

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

data class ModelOption(
    val id: String,
    val label: String,
    val contextWindow: Int? = null,
    val custom: Boolean = false
)

data class ApiSourceOption(
    val id: String,
    val label: String,
    val models: List<ModelOption>
)

object ModelCatalog {
    private const val PREF_CUSTOM_MODELS = "custom_model_catalog_v1"
    private const val DEFAULT_OPENAI_CONTEXT = 1_050_000

    @Volatile
    private var runtimeCustomModels: List<CustomModelEntry> = emptyList()

    private data class CustomModelEntry(
        val providerId: String,
        val id: String,
        val label: String,
        val contextWindow: Int?
    )

    private val builtInSources: List<ApiSourceOption> = listOf(
        ApiSourceOption(
            id = "openai",
            label = "OpenAI",
            models = listOf(
                ModelOption("gpt-6-astra", "GPT-6 Astra", DEFAULT_OPENAI_CONTEXT),
                ModelOption("gpt-6.1-sol", "GPT-6.1 Sol", DEFAULT_OPENAI_CONTEXT),
                ModelOption("gpt-6-luna", "GPT-6 Luna", DEFAULT_OPENAI_CONTEXT)
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

    val sources: List<ApiSourceOption>
        get() = builtInSources.map { base ->
            val extras = runtimeCustomModels.filter { it.providerId == base.id }
            if (extras.isEmpty()) base
            else {
                val merged = base.models.toMutableList()
                for (custom in extras) {
                    val existingIdx = merged.indexOfFirst { it.id == custom.id }
                    val option = ModelOption(custom.id, custom.label, custom.contextWindow, custom = true)
                    if (existingIdx >= 0) merged[existingIdx] = option else merged.add(option)
                }
                base.copy(models = merged)
            }
        }

    fun syncFromPreferences(prefs: SharedPreferences) {
        val raw = prefs.getString(PREF_CUSTOM_MODELS, null)
        if (raw.isNullOrBlank()) {
            runtimeCustomModels = emptyList()
            return
        }
        val parsed = mutableListOf<CustomModelEntry>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val provider = JsonProtocol.nonBlank(item, "provider")?.trim().orEmpty()
                val id = JsonProtocol.nonBlank(item, "id")?.trim()?.removePrefix("~").orEmpty()
                val label = JsonProtocol.nonBlank(item, "label")?.trim().orEmpty().ifBlank { id }
                val window = item.optInt("context_window", -1).takeIf { it > 0 }
                if (provider in setOf("openai", "openrouter") && id.isNotBlank()) {
                    parsed.add(CustomModelEntry(provider, id, label, window))
                }
            }
        } catch (_: Exception) {
            // Ignore malformed custom catalog JSON and keep safe defaults.
        }
        runtimeCustomModels = parsed
    }

    fun upsertCustomModel(
        prefs: SharedPreferences,
        providerId: String,
        modelId: String,
        label: String,
        contextWindow: Int? = null
    ): Boolean {
        val cleanProvider = providerId.trim().lowercase()
        val cleanId = modelId.trim().removePrefix("~")
        if (cleanProvider !in setOf("openai", "openrouter") || cleanId.isBlank()) return false
        if (cleanId.any { it.isWhitespace() || it.isISOControl() }) return false
        val cleanLabel = label.trim().ifBlank { cleanId }
        val cleanWindow = contextWindow?.takeIf { it >= 4_096 }
        syncFromPreferences(prefs)
        val updated = runtimeCustomModels
            .filterNot { it.providerId == cleanProvider && it.id == cleanId } +
            CustomModelEntry(cleanProvider, cleanId, cleanLabel, cleanWindow)
        if (!saveEntries(prefs, updated)) return false
        runtimeCustomModels = updated
        return true
    }

    fun removeCustomModel(prefs: SharedPreferences, providerId: String, modelId: String): Boolean {
        val cleanProvider = providerId.trim().lowercase()
        val cleanId = modelId.trim().removePrefix("~")
        syncFromPreferences(prefs)
        val updated = runtimeCustomModels.filterNot { it.providerId == cleanProvider && it.id == cleanId }
        if (!saveEntries(prefs, updated)) return false
        runtimeCustomModels = updated
        return true
    }

    fun isBuiltIn(providerId: String, modelId: String): Boolean {
        val normalized = modelId.trim().removePrefix("~")
        return builtInSources.firstOrNull { it.id == providerId }
            ?.models?.any { it.id == normalized } == true
    }

    private fun saveEntries(prefs: SharedPreferences, entries: List<CustomModelEntry>): Boolean {
        val arr = JSONArray()
        for (entry in entries) {
            arr.put(
                JSONObject()
                    .put("provider", entry.providerId)
                    .put("id", entry.id)
                    .put("label", entry.label)
                    .apply { entry.contextWindow?.let { put("context_window", it) } }
            )
        }
        return prefs.edit().putString(PREF_CUSTOM_MODELS, arr.toString()).commit()
    }

    // https://developers.openai.com/api/docs/models (verified 2026-10-10).
    // This is a context-window capability, never a client-side output-token cap.
    fun openAiContextWindow(model: String): Int? {
        val normalized = model.trim().removePrefix("~")
        runtimeCustomModels.firstOrNull { it.providerId == "openai" && it.id == normalized }
            ?.contextWindow?.let { return it }
        return when (normalized) {
            "gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna" -> DEFAULT_OPENAI_CONTEXT
            else -> null
        }
    }

    fun compactionThreshold(model: String): Int? = openAiContextWindow(model)?.let { window ->
        minOf(800_000, window - 16_384).takeIf { it > 0 }
    }

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
