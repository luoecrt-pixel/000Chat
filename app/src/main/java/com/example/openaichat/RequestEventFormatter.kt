package com.example.openaichat

import org.json.JSONObject
import java.util.Locale

object RequestEventFormatter {
    fun match(event: JSONObject): ModelCatalog.Match = when (JsonProtocol.string(event, "model_match").lowercase(Locale.US)) {
        "exact" -> ModelCatalog.Match.EXACT
        "mismatch" -> ModelCatalog.Match.MISMATCH
        else -> ModelCatalog.Match.UNKNOWN
    }

    fun summary(event: JSONObject): String {
        val provider = JsonProtocol.nonBlank(event, "provider") ?: "unknown"
        val requested = JsonProtocol.nonBlank(event, "requested_model")
        val response = JsonProtocol.nonBlank(event, "response_model")
        val modelText = when (match(event)) {
            ModelCatalog.Match.EXACT -> ModelCatalog.displayName(provider, requested)
            ModelCatalog.Match.MISMATCH ->
                "requested=${ModelCatalog.displayName(provider, requested)} · response=${ModelCatalog.displayName(provider, response)}"
            ModelCatalog.Match.UNKNOWN ->
                "requested=${ModelCatalog.displayName(provider, requested)} · response=unknown"
        }
        val cost = RequestCostFormatter.text(event)
        if (JsonProtocol.string(event, "status") == "stopped") {
            return "● Остановлено · " + ModelCatalog.displayName(provider, requested) + " · " + cost
        }
        val latency = event.optLong("latency_ms", 0L)
        return "● $modelText · ${tokens(event.optLong("input_tokens"))} in · " +
            "${tokens(event.optLong("output_tokens"))} out · " +
            "${tokens(event.optLong("reasoning_tokens"))} reasoning · " +
            "cache ${tokens(event.optLong("cached_tokens"))} · " +
            "$cost · %.1fs".format(Locale.US, latency / 1000.0)
    }

    fun details(event: JSONObject): String = buildString {
        append("timestamp=").append(event.optLong("created_at", 0L)).append('\n')
        append("provider=").append(JsonProtocol.nonBlank(event, "provider") ?: "unknown").append('\n')
        append("requested_model=").append(JsonProtocol.nonBlank(event, "requested_model") ?: "unknown").append('\n')
        append("response_model=").append(JsonProtocol.nonBlank(event, "response_model") ?: "unknown").append('\n')
        append("model_match=").append(JsonProtocol.nonBlank(event, "model_match") ?: "unknown").append('\n')
        append("status=").append(JsonProtocol.nonBlank(event, "status") ?: "unknown").append('\n')
        append("input_tokens=").append(event.optLong("input_tokens")).append('\n')
        append("cached_tokens=").append(event.optLong("cached_tokens")).append('\n')
        append("cache_write_tokens=").append(event.optLong("cache_write_tokens")).append('\n')
        append("output_tokens=").append(event.optLong("output_tokens")).append('\n')
        append("reasoning_tokens=").append(event.optLong("reasoning_tokens")).append('\n')
        append("estimated_cost_usd=legacy/unverified").append('\n')
        append("cost=").append(RequestCostFormatter.text(event)).append('\n')
        append("cost_kind=").append(JsonProtocol.nonBlank(event, "cost_kind") ?: "unknown").append('\n')
        append("latency_ms=").append(event.optLong("latency_ms")).append('\n')
        append("tools=").append(JsonProtocol.nonBlank(event, "tool_summary").orEmpty()).append('\n')
        append("request_id=").append(JsonProtocol.nonBlank(event, "request_id").orEmpty()).append('\n')
        val error = JsonProtocol.nonBlank(event, "error").orEmpty()
        if (error.isNotBlank()) append("error=").append(error).append('\n')
    }

    private fun tokens(value: Long): String = when {
        value >= 1_000_000 -> "%.1fM".format(Locale.US, value / 1_000_000.0)
        value >= 1_000 -> "%.1fK".format(Locale.US, value / 1_000_000.0 * 1000.0)
        else -> value.toString()
    }
}
