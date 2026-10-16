package com.example.openaichat

import org.json.JSONObject

/** A matching request ledger is authoritative for late provider totals and compact diagnostics. */
object RequestStopEventPolicy {
    fun enrich(event: JSONObject, accounting: JSONObject?): JSONObject {
        val row = accounting ?: return event
        val requestId = JsonProtocol.nonBlank(event, "request_id") ?: return event
        if (requestId != JsonProtocol.nonBlank(row, "local_request_id")) return event
        if (event.optString("event_type") == "request_stopped" && row.optString("local_status") != "stopped") return event
        return JSONObject(event.toString()).apply {
            if (row.optString("cost_kind") == "provider_reported" &&
                row.has("cost_usd") && !row.isNull("cost_usd")
            ) {
                put("cost_kind", "provider_reported")
                put("cost_usd", row.opt("cost_usd"))
            } else {
                put("cost_kind", "unknown")
                remove("cost_usd")
            }
            for (tokenKey in listOf("input_tokens", "cached_tokens", "cache_write_tokens", "output_tokens", "reasoning_tokens")) {
                if (row.has(tokenKey) && !row.isNull(tokenKey)) {
                    val value = row.optLong(tokenKey, -1L)
                    if (value >= 0L) put(tokenKey, value)
                }
            }
            val rowTelemetry = row.optJSONObject("telemetry_summary") ?: JsonProtocol.nonBlank(row, "telemetry_summary")
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
            val existing = optJSONObject("telemetry_summary")?.toString() ?: JsonProtocol.nonBlank(this, "telemetry_summary")
            FileBackedIoTelemetry.mergeTelemetryJson(existing, rowTelemetry)?.let { put("telemetry_summary", JSONObject(it)) }
        }
    }
}
