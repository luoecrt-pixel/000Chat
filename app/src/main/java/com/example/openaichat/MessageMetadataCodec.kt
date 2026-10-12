package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/**
 * Optional per-message fields needed for a faithful history reopen.
 * Generated temporary UI state is deliberately excluded.
 */
object MessageMetadataCodec {
    val textKeys = listOf(
        "provider", "model", "requested_model", "response_model",
        "reasoning_summary", "error", "finish_reason", "protocol_context_state",
        "openai_instructions_sha256"
    )
    val arrayKeys = listOf("tool_trace", "openai_protocol_output")
    val objectKeys = listOf("usage", "openrouter_protocol")
    val allKeys: List<String> = textKeys + arrayKeys + objectKeys

    fun serialized(message: JSONObject, key: String): String? = when (key) {
        in textKeys -> JsonProtocol.nonBlank(message, key)
        in arrayKeys -> message.optJSONArray(key)?.toString()
        in objectKeys -> message.optJSONObject(key)?.toString()
        else -> null
    }

    fun decodeInto(message: JSONObject, key: String, raw: String?) {
        if (raw.isNullOrBlank()) return
        try {
            when (key) {
                in textKeys -> message.put(key, raw)
                in arrayKeys -> message.put(key, JSONArray(raw))
                in objectKeys -> message.put(key, JSONObject(raw))
            }
        } catch (_: Exception) {
            // Corrupted optional metadata must not prevent opening the whole chat.
        }
    }
}
