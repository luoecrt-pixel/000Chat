package com.example.openaichat

import org.json.JSONObject

/** Actual last terminal request, independent of the currently selected provider and visible bubbles. */
data class ContextLastRequest(
    val requestId: String,
    val accounting: JSONObject?,
    val terminalAtMillis: Long?,
    val provider: String?,
    val model: String?
)

object ContextLastRequestPolicy {
    private val terminalEvents = setOf("request_completed", "request_failed", "request_incomplete",
        "request_disconnected", "request_stopped")

    fun selectLatestCompletedRequest(accounting: List<JSONObject>, events: List<JSONObject>): ContextLastRequest? {
        val terminal = events.filter { it.optString("event_type") in terminalEvents &&
            JsonProtocol.nonBlank(it, "request_id") != null && it.optLong("created_at", 0) > 0 }
            .maxWithOrNull(compareBy<JSONObject> { it.optLong("created_at") }.thenBy { it.optLong("id") })
        // A terminal accounting row may outlive the limited event window. Do not
        // display an older request's known cache merely because its event survived.
        val eventIds = events.filter { it.optString("event_type") in terminalEvents }.mapNotNull { JsonProtocol.nonBlank(it, "request_id") }.toSet()
        val unobserved = accounting.filter { it.optString("local_status") in setOf("completed", "failed", "incomplete", "stopped", "disconnected") &&
            JsonProtocol.nonBlank(it, "local_request_id") != null &&
            it.optString("local_request_id") !in eventIds && it.optString("http_request_id") !in eventIds }
            .maxByOrNull { it.optLong("created_at", 0) }
        if (unobserved != null && (terminal == null || unobserved.optLong("created_at", 0) > terminal.optLong("created_at"))) {
            return ContextLastRequest(unobserved.getString("local_request_id"), JsonProtocol.objectCopy(unobserved), null,
                JsonProtocol.nonBlank(unobserved, "provider"), JsonProtocol.nonBlank(unobserved, "response_model")
                    ?: JsonProtocol.nonBlank(unobserved, "requested_model"))
        }
        if (terminal == null) return null
        val id = terminal.getString("request_id")
        val row = accounting.lastOrNull { JsonProtocol.nonBlank(it, "local_request_id") == id ||
            JsonProtocol.nonBlank(it, "http_request_id") == id }
        return ContextLastRequest(JsonProtocol.nonBlank(row ?: JSONObject(), "local_request_id") ?: id,
            row?.let(JsonProtocol::objectCopy), terminal.optLong("created_at"),
            row?.let { JsonProtocol.nonBlank(it, "provider") } ?: JsonProtocol.nonBlank(terminal, "provider"),
            row?.let { JsonProtocol.nonBlank(it, "response_model") ?: JsonProtocol.nonBlank(it, "requested_model") }
                ?: JsonProtocol.nonBlank(terminal, "response_model") ?: JsonProtocol.nonBlank(terminal, "requested_model"))
    }
}
