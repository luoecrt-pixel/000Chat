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

    /** Until auto is resolved for a request, the badge must not invent a level. */
    fun effortBadge(effort: String, resolvedEffort: String? = null): String =
        if (effort == "auto" && !resolvedEffort.isNullOrBlank())
            "⚡ auto → $resolvedEffort"
        else "⚡ $effort"
}
