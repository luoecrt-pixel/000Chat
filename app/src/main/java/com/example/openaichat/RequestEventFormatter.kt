package com.example.openaichat

import org.json.JSONObject
import java.util.Locale

object RequestEventFormatter {
    fun match(event: JSONObject): ModelCatalog.Match = when (event.optString("model_match").lowercase(Locale.US)) {
        "exact" -> ModelCatalog.Match.EXACT
        "mismatch" -> ModelCatalog.Match.MISMATCH
        else -> ModelCatalog.Match.UNKNOWN
    }

    fun summary(event: JSONObject): String {
        val provider = event.optString("provider", "unknown")
        val requested = event.optString("requested_model").takeIf { it.isNotBlank() }
        val response = event.optString("response_model").takeIf { it.isNotBlank() }
        val modelText = when (match(event)) {
            ModelCatalog.Match.EXACT -> ModelCatalog.displayName(provider, requested)
            ModelCatalog.Match.MISMATCH ->
                "requested=${ModelCatalog.displayName(provider, requested)} · response=${ModelCatalog.displayName(provider, response)}"
            ModelCatalog.Match.UNKNOWN ->
                "requested=${ModelCatalog.displayName(provider, requested)} · response=unknown"
        }
        val cost = costText(event)
        val latency = event.optLong("latency_ms", 0L)
        return "● $modelText · ${tokens(event.optLong("input_tokens"))} in · " +
            "${tokens(event.optLong("output_tokens"))} out · " +
            "${tokens(event.optLong("reasoning_tokens"))} reasoning · " +
            "cache ${tokens(event.optLong("cached_tokens"))} · " +
            "$cost · %.1fs".format(Locale.US, latency / 1000.0)
    }

    fun details(event: JSONObject): String = buildString {
        append("timestamp=").append(event.optLong("created_at", 0L)).append('\n')
        append("provider=").append(event.optString("provider", "unknown")).append('\n')
        append("requested_model=").append(event.optString("requested_model", "unknown")).append('\n')
        append("response_model=").append(event.optString("response_model", "unknown")).append('\n')
        append("model_match=").append(event.optString("model_match", "unknown")).append('\n')
        append("status=").append(event.optString("status", "unknown")).append('\n')
        append("input_tokens=").append(event.optLong("input_tokens")).append('\n')
        append("cached_tokens=").append(event.optLong("cached_tokens")).append('\n')
        append("cache_write_tokens=").append(event.optLong("cache_write_tokens")).append('\n')
        append("output_tokens=").append(event.optLong("output_tokens")).append('\n')
        append("reasoning_tokens=").append(event.optLong("reasoning_tokens")).append('\n')
        append("estimated_cost_usd=legacy/unverified").append('\n')
        append("cost=").append(costText(event)).append('\n')
        append("cost_kind=").append(event.optString("cost_kind", "unknown")).append('\n')
        append("latency_ms=").append(event.optLong("latency_ms")).append('\n')
        append("tools=").append(event.optString("tool_summary", "")).append('\n')
        append("request_id=").append(event.optString("request_id", "")).append('\n')
        val error = event.optString("error", "")
        if (error.isNotBlank()) append("error=").append(error).append('\n')
    }

    private fun costText(event: JSONObject): String {
        if (event.optString("cost_kind") != "provider_reported") return "Расход неизвестен"
        val raw = event.optString("cost_usd")
        val amount = raw.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 } ?: return "Расход неизвестен"
        return when {
            amount > 0.0 && amount < 0.0001 -> "Расход < $0.0001"
            else -> "Расход $%.4f".format(Locale.US, amount)
        }
    }

    private fun tokens(value: Long): String = when {
        value >= 1_000_000 -> "%.1fM".format(Locale.US, value / 1_000_000.0)
        value >= 1_000 -> "%.1fK".format(Locale.US, value / 1_000.0)
        else -> value.toString()
    }
}
