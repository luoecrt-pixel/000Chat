package com.example.openaichat

import org.json.JSONObject

/** A stopped event can display newer cost and token usage only from its own stopped ledger row. */
object RequestStopEventPolicy {
    fun enrich(event: JSONObject, accounting: JSONObject?): JSONObject {
        val row = accounting ?: return event
        if (event.optString("event_type") != "request_stopped" ||
            row.optString("local_status") != "stopped" ||
            event.optString("request_id") != row.optString("local_request_id")
        ) return event
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
            for (tokenKey in listOf("input_tokens", "cached_tokens", "output_tokens", "reasoning_tokens")) {
                if (row.has(tokenKey) && !row.isNull(tokenKey)) {
                    val value = row.optLong(tokenKey, -1L)
                    if (value >= 0L) put(tokenKey, value)
                }
            }
        }
    }
}
