package com.example.openaichat

import org.json.JSONObject

/** Serialize only documented per-model mode capabilities, preserving stored preferences. */
object ModelRequestPolicy {
    private val supportedOpenAiModels = setOf("gpt-6-astra", "gpt-6.1-sol", "gpt-6-luna")

    fun supportsReasoningMode(provider: String?, model: String?): Boolean =
        provider == "openai" && model?.trim()?.removePrefix("~")?.let { it in supportedOpenAiModels } == true

    fun supportedMode(provider: String?, model: String?, mode: String?): String? =
        mode?.takeIf { it == "standard" || it == "pro" }
            ?.takeIf { supportsReasoningMode(provider, model) }

    fun openAiReasoning(model: String?, effort: String, mode: String?, summary: String): JSONObject =
        JSONObject().put("effort", effort).apply {
            supportedMode("openai", model, mode)?.let { put("mode", it) }
            if (summary != "null") put("summary", summary)
        }

    /** Mirrors the existing OpenRouter transport mapping; never rewrites the selected pref. */
    fun effortBadge(provider: String?, effort: String): String {
        val normalized = if (provider == "openrouter") when (effort) {
            "auto" -> "medium"
            "xhigh", "max" -> "high"
            else -> effort
        } else effort
        return if (effort == normalized) "⚡ $effort" else "⚡ $effort → $normalized"
    }
}
