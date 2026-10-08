package com.example.openaichat

import org.json.JSONObject

/**
 * Request completion is an observed outcome even when a provider omits token usage.
 * Effort metrics retain the existing rule: they are emitted only for a usage payload.
 */
data class OpenRouterUsageSnapshot(
    val inputTokens: Long,
    val cachedTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: Double,
    val reportedCostUsd: Double? = null
) {
    companion object {
        fun from(usage: JSONObject?): OpenRouterUsageSnapshot {
            val promptDetails = usage?.optJSONObject("prompt_tokens_details")
            val completionDetails = usage?.optJSONObject("completion_tokens_details")
            val reportedCost = usage?.takeIf { it.has("cost") && !it.isNull("cost") }
                ?.opt("cost")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            return OpenRouterUsageSnapshot(
                inputTokens = usage?.optLong("prompt_tokens", 0L)?.coerceAtLeast(0L) ?: 0L,
                cachedTokens = promptDetails?.optLong("cached_tokens", 0L)?.coerceAtLeast(0L) ?: 0L,
                cacheWriteTokens = promptDetails?.optLong("cache_write_tokens", 0L)?.coerceAtLeast(0L) ?: 0L,
                outputTokens = usage?.optLong("completion_tokens", 0L)?.coerceAtLeast(0L) ?: 0L,
                reasoningTokens = completionDetails?.optLong("reasoning_tokens", 0L)?.coerceAtLeast(0L) ?: 0L,
                costUsd = reportedCost ?: 0.0,
                reportedCostUsd = reportedCost
            )
        }
    }
}

object OpenRouterCompletionTelemetry {
    fun record(
        usage: JSONObject?,
        recordMetric: (OpenRouterUsageSnapshot) -> Unit,
        recordEvent: (OpenRouterUsageSnapshot) -> Unit
    ) {
        val snapshot = OpenRouterUsageSnapshot.from(usage)
        if (usage != null) recordMetric(snapshot)
        recordEvent(snapshot)
    }
}
