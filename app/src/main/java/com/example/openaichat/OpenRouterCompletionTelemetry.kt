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

/** One sanitized sparse snapshot per HTTP round; aggregate each round exactly once. */
internal object OpenRouterUsageAccumulator {
    private val tokenKeys = listOf("prompt_tokens", "completion_tokens", "total_tokens")
    private val detailTokenKeys = mapOf("prompt_tokens_details" to listOf("cached_tokens", "cache_write_tokens"),
        "completion_tokens_details" to listOf("reasoning_tokens"))
    private val nestedCostKeys = listOf("upstream_inference_cost", "upstream_inference_prompt_cost",
        "upstream_inference_completions_cost", "server_tool_cost")
    private val topCostKeys = listOf("upstream_inference_cost", "server_tool_cost")

    private fun nonnegative(source: JSONObject?, key: String): Long? =
        source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }

    private fun sanitized(source: JSONObject?): JSONObject = JSONObject().apply {
        if (source == null) return@apply
        tokenKeys.forEach { key -> nonnegative(source, key)?.let { put(key, it) } }
        detailTokenKeys.forEach { (key, fields) ->
            val details = JSONObject()
            fields.forEach { field -> nonnegative(source.optJSONObject(key), field)?.let { details.put(field, it) } }
            if (details.length() > 0) put(key, details)
        }
        val snapshot = OpenRouterUsageSnapshot.from(source)
        snapshot.reportedCostUsd?.let { put("cost", it) }
        snapshot.costDetails?.let { metadata -> metadata.keys().forEach { key -> put(key, JsonProtocol.copy(metadata.opt(key))) } }
    }

    /** Missing, null and invalid values cannot erase an earlier provider observation. */
    fun mergeSnapshot(previous: JSONObject?, incoming: JSONObject?): JSONObject? {
        fun merge(target: JSONObject, source: JSONObject) {
            source.keys().forEach { key ->
                val value = source.opt(key)
                if (value is JSONObject) {
                    val child = target.optJSONObject(key) ?: JSONObject().also { target.put(key, it) }
                    merge(child, value)
                } else target.put(key, JsonProtocol.copy(value))
            }
        }
        return sanitized(previous).apply { merge(this, sanitized(incoming)) }.takeIf { it.length() > 0 }
    }

    /** Null entries represent attempted rounds without usage; their price remains unknown. */
    fun aggregate(snapshots: List<JSONObject?>): JSONObject? {
        if (snapshots.isEmpty()) return null
        val rounds = snapshots.map(::sanitized)
        val out = JSONObject()
        fun sumLong(values: List<Long>): Long = values.fold(0L) { sum, next ->
            if (Long.MAX_VALUE - sum < next) Long.MAX_VALUE else sum + next
        }
        fun putTokenSum(target: JSONObject, key: String, sources: List<JSONObject?>) {
            val values = sources.mapNotNull { nonnegative(it, key) }
            if (values.isNotEmpty()) target.put(key, sumLong(values))
        }
        tokenKeys.forEach { putTokenSum(out, it, rounds) }
        detailTokenKeys.forEach { (key, fields) ->
            val details = JSONObject()
            fields.forEach { putTokenSum(details, it, rounds.map { round -> round.optJSONObject(key) }) }
            if (details.length() > 0) out.put(key, details)
        }
        fun sumAmounts(target: JSONObject, key: String, sources: List<JSONObject?>) {
            val values = sources.mapNotNull { it?.opt(key)?.toString()?.toDoubleOrNull()?.takeIf { n -> n.isFinite() && n >= 0.0 } }
            if (values.isNotEmpty()) values.sum().takeIf { it.isFinite() }?.let { target.put(key, it) }
        }
        val costs = rounds.mapNotNull { OpenRouterUsageSnapshot.from(it).reportedCostUsd }
        if (costs.size == rounds.size) costs.sum().takeIf { it.isFinite() }?.let { out.put("cost", it) }
        val nestedCosts = JSONObject()
        nestedCostKeys.forEach { key -> sumAmounts(nestedCosts, key, rounds.map { it.optJSONObject("cost_details") }) }
        if (nestedCosts.length() > 0) out.put("cost_details", nestedCosts)
        topCostKeys.forEach { sumAmounts(out, it, rounds) }
        rounds.mapNotNull { JsonProtocol.nonBlank(it, "service_tier") }.lastOrNull()?.let { out.put("service_tier", it) }
        val toolUse = JSONObject()
        val tools = rounds.mapNotNull { it.optJSONObject("server_tool_use") }
        val keys = tools.flatMap { it.keys().asSequence().toList() }.distinct()
        keys.forEach { putTokenSum(toolUse, it, tools) }
        if (toolUse.length() > 0) out.put("server_tool_use", toolUse)
        val iterations = JSONArray()
        rounds.forEach { round -> round.optJSONArray("iterations")?.let { values ->
            for (i in 0 until values.length()) iterations.put(JsonProtocol.copy(values.opt(i)))
        } }
        if (iterations.length() > 0) out.put("iterations", iterations)
        return out.takeIf { it.length() > 0 }
    }
}
