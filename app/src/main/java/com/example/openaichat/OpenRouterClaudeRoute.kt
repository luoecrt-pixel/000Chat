package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Captured at Send, never read again from preferences during an answer. */
// Limits: https://platform.claude.com/docs/en/build-with-claude/vision and
// https://platform.claude.com/docs/en/api/overview#request-size-limits (2026-10-10).
enum class OpenRouterClaudeRoute(val preference: String, val label: String, val imageBase64Limit: Long, val requestByteLimit: Long) {
    AUTO("auto", "Автоматический", 5_000_000L, 20_000_000L),
    ANTHROPIC("anthropic", "Прямой Anthropic", 10_000_000L, 32_000_000L);

    companion object {
        fun fromPreference(value: String?): OpenRouterClaudeRoute =
            entries.firstOrNull { it.preference == value } ?: AUTO
        fun isClaude(model: String): Boolean = model.removePrefix("~").startsWith("anthropic/")
    }

    /** Merge routing and format requirements in one object; other model families stay unrestricted. */
    fun provider(model: String, requireParameters: Boolean): JSONObject? {
        val result = JSONObject()
        if (requireParameters) result.put("require_parameters", true)
        if (isClaude(model) && this == ANTHROPIC) {
            // https://openrouter.ai/docs/guides/routing/provider-selection (2026-10-10)
            result.put("order", JSONArray().put("anthropic")).put("allow_fallbacks", false)
        }
        return result.takeIf { it.length() > 0 }
    }
}
