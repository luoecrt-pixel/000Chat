package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** UI text and the signed protocol are distinct representations. */
object OpenRouterProtocolCodec {
    private const val BINDING = "_astra_binding_sha256"
    private const val RESET = "_astra_reset_reasoning"
    private const val VALID_PREFIX = "_astra_prefix_valid"
    fun append(destination: JSONArray, stored: JSONObject): Boolean {
        if (JsonProtocol.string(stored, "protocol_context_state") == "invalidated") return false
        val protocol = stored.optJSONObject("openrouter_protocol") ?: return false
        if (protocol.optInt("version") != 1) return false
        val tail = protocol.optJSONArray("tail") ?: return false
        val exactPrefix = protocol.optString("input_sha256") == fingerprint(destination)
        for (i in 0 until tail.length()) {
            val message = tail.optJSONObject(i) ?: continue
            destination.put(JsonProtocol.objectCopy(message).apply {
                put(BINDING, protocol.optString("binding_sha256"))
                if (!exactPrefix) put(RESET, true)
                if (i == 0 && exactPrefix) put(VALID_PREFIX, true)
            })
        }
        return true
    }
    fun fingerprint(messages: JSONArray): String {
        val clean = JsonProtocol.arrayCopy(messages)
        for (i in 0 until clean.length()) clean.optJSONObject(i)?.apply { remove(BINDING); remove(RESET); remove(VALID_PREFIX) }
        return StreamingJsonRequestBody.sha256(clean)
    }
    /** Changing a signed prefix invalidates all subsequent thinking blocks. */
    fun checkBinding(messages: JSONArray, expected: String): Boolean {
        var reset = false
        var dropped = false
        for (i in 0 until messages.length()) {
            val item = messages.optJSONObject(i) ?: continue
            val binding = JsonProtocol.nonBlank(item, BINDING)
            if (item.optBoolean(VALID_PREFIX, false) && binding == expected) reset = false
            if (item.optBoolean(RESET, false)) reset = true
            // Old account/model blocks do not invalidate newer blocks signed after that switch.
            if (reset || (binding != null && binding != expected)) {
                if (item.has("reasoning_details") || item.has("reasoning")) dropped = true
                item.remove("reasoning_details"); item.remove("reasoning")
            }
            item.remove(BINDING); item.remove(RESET); item.remove(VALID_PREFIX)
        }
        return dropped
    }

    fun binding(model: String, tools: JSONArray?, apiKey: String, responseFormat: JSONObject? = null,
        claudeRoute: OpenRouterClaudeRoute = OpenRouterClaudeRoute.AUTO): String = StreamingJsonRequestBody.sha256(
        JSONObject().put("model", model).put("tools", tools ?: JSONArray())
            .put("credential_hash", StreamingJsonRequestBody.sha256(apiKey)).apply {
                responseFormat?.let { put("response_format", it) }
                // Keep old Auto bindings byte-compatible; a deliberate endpoint switch invalidates replay only.
                if (OpenRouterClaudeRoute.isClaude(model) && claudeRoute != OpenRouterClaudeRoute.AUTO)
                    put("claude_route", claudeRoute.preference)
            })

    fun withVisibleTail(checkpoint: JSONObject, message: JSONObject): JSONObject = JsonProtocol.objectCopy(checkpoint).apply {
        getJSONArray("tail").put(JSONObject().put("role", "assistant").put("content", JsonProtocol.string(message, "text")))
    }
}
