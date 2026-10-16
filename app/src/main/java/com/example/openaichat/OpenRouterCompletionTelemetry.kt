package com.example.openaichat

import org.json.JSONArray
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
    val costUsd: Double? = null,
    val reportedCostUsd: Double? = costUsd,
    val costDetails: JSONObject? = null
) {
    val costKind: String get() = if (reportedCostUsd != null) "provider_reported" else "unknown"
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
                costUsd = reportedCost,
                reportedCostUsd = reportedCost,
                costDetails = extractCostDetails(usage)
            )
        }

        /** This metadata describes components of usage.cost; it never increases that total. */
        fun extractCostDetails(usage: JSONObject?): JSONObject? {
            if (usage == null) return null
            val out = JSONObject()
            fun amounts(source: JSONObject, target: JSONObject, keys: List<String>) {
                keys.forEach { key ->
                    source.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                        ?.let { target.put(key, it) }
                }
            }
            usage.optJSONObject("cost_details")?.let { details ->
                val clean = JSONObject()
                amounts(details, clean, listOf("upstream_inference_cost", "upstream_inference_prompt_cost",
                    "upstream_inference_completions_cost", "server_tool_cost"))
                if (clean.length() > 0) out.put("cost_details", clean)
            }
            amounts(usage, out, listOf("upstream_inference_cost", "server_tool_cost"))
            JsonProtocol.nonBlank(usage, "service_tier")?.let { out.put("service_tier", it) }
            usage.optJSONObject("server_tool_use")?.let { toolUse ->
                val clean = JSONObject()
                toolUse.keys().forEach { key ->
                    toolUse.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { clean.put(key, it) }
                }
                if (clean.length() > 0) out.put("server_tool_use", clean)
            }
            usage.optJSONArray("iterations")?.let { iterations ->
                val clean = JSONArray()
                for (i in 0 until iterations.length()) {
                    val item = iterations.optJSONObject(i) ?: continue
                    val entry = JSONObject()
                    for (key in listOf("type", "model", "service_tier")) {
                        JsonProtocol.nonBlank(item, key)?.let { entry.put(key, it) }
                    }
                    for (key in listOf("prompt_tokens", "completion_tokens", "input_tokens", "output_tokens", "total_tokens")) {
                        item.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { entry.put(key, it) }
                    }
                    amounts(item, entry, listOf("cost", "upstream_inference_cost", "server_tool_cost"))
                    if (entry.length() > 0) clean.put(entry)
                }
                if (clean.length() > 0) out.put("iterations", clean)
            }
            return out.takeIf { it.length() > 0 }
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
