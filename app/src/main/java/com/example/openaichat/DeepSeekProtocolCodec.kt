package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Exact wire source is immutable. Presentation and execution evidence are separate fields. */
object DeepSeekProtocolCodec {
    const val FIELD = "deepseek_protocol"
    private const val BINDING = "_deepseek_binding_sha256"
    private const val INPUT_SHA = "_deepseek_input_sha256"
    private const val VALID_PREFIX = "_deepseek_prefix_valid"
    private const val FALLBACK_TEXT = "_deepseek_fallback_text"
    private const val DOWNGRADED = "_deepseek_downgraded"

    fun binding(model: String, tools: JSONArray?, apiKey: String, responseFormat: JSONObject? = null): String {
        require(model.isNotBlank() && apiKey.isNotBlank())
        return StreamingJsonRequestBody.sha256(JSONObject().put("provider", "deepseek")
            .put("model", model.trim().removePrefix("~")).put("tools", tools ?: JSONArray())
            .put("credential_hash", StreamingJsonRequestBody.sha256(apiKey)).apply {
                responseFormat?.let { put("response_format", it) }
            })
    }

    fun fingerprint(messages: JSONArray): String = StreamingJsonRequestBody.sha256(
        JsonProtocol.arrayCopy(messages).apply {
            for (i in 0 until length()) optJSONObject(i)?.apply {
                remove(BINDING); remove(INPUT_SHA); remove(VALID_PREFIX); remove(FALLBACK_TEXT); remove(DOWNGRADED)
            }
        })

    fun markDowngradedFallback(message: JSONObject): JSONObject = message.put(DOWNGRADED, true)

    private fun strictObject(raw: String) = raw.trimStart().startsWith("{") && StrictJsonValue.isObjectOrArray(raw)

    fun hasConfirmedToolChain(tail: JSONArray): Boolean {
        if (tail.length() == 0) return false
        val allIds = mutableSetOf<String>()
        var i = 0
        while (i < tail.length()) {
            val assistant = tail.optJSONObject(i) ?: return false
            if (JsonProtocol.text(assistant, "role") != "assistant") return false
            if (JsonProtocol.text(assistant, "content") == null) return false
            if (assistant.has("tool_calls")) {
                val calls = assistant.optJSONArray("tool_calls") ?: return false
                if (calls.length() == 0) return false
                val expected = linkedSetOf<String>()
                for (c in 0 until calls.length()) {
                    val call = calls.optJSONObject(c) ?: return false
                    if (JsonProtocol.text(call, "type") != "function") return false
                    val id = JsonProtocol.nonBlank(call, "id") ?: return false
                    val fn = call.optJSONObject("function") ?: return false
                    if (JsonProtocol.nonBlank(fn, "name") == null ||
                        JsonProtocol.text(fn, "arguments")?.let(::strictObject) != true) return false
                    if (!expected.add(id) || !allIds.add(id)) return false
                }
                val confirmed = mutableSetOf<String>()
                var j = i + 1
                while (j < tail.length()) {
                    val tool = tail.optJSONObject(j) ?: return false
                    if (JsonProtocol.text(tool, "role") != "tool") break
                    val id = JsonProtocol.nonBlank(tool, "tool_call_id") ?: return false
                    if (id !in expected || !confirmed.add(id) || JsonProtocol.text(tool, "content") == null) return false
                    j++
                }
                if (confirmed != expected || j == tail.length()) return false
                i = j
            } else i++
        }
        val last = tail.optJSONObject(tail.length() - 1) ?: return false
        return JsonProtocol.text(last, "role") == "assistant" && !last.has("tool_calls")
    }

    fun hasCompleteReasoning(protocol: JSONObject?): Boolean {
        if (protocol == null || protocol.optInt("version") != 1 || !protocol.optBoolean("replayable", false)) return false
        val tail = protocol.optJSONArray("tail") ?: return false
        if (!hasConfirmedToolChain(tail)) return false
        if (!protocol.optBoolean("thinking_enabled", true)) return true
        return (0 until tail.length()).all { index ->
            val message = tail.getJSONObject(index)
            message.optString("role") != "assistant" || JsonProtocol.text(message, "reasoning_content") != null
        }
    }

    fun append(destination: JSONArray, stored: JSONObject, expectedBinding: String, fallbackVisibleText: String = ""): Boolean {
        if (expectedBinding.isBlank() || JsonProtocol.text(stored, "protocol_context_state") == "invalidated" ||
            JsonProtocol.text(stored, "status") != "completed") return false
        val protocol = stored.optJSONObject(FIELD) ?: return false
        if (!hasCompleteReasoning(protocol)) return false
        val savedBinding = JsonProtocol.nonBlank(protocol, "binding_sha256") ?: return false
        val input = JsonProtocol.nonBlank(protocol, "input_sha256") ?: return false
        if (savedBinding != expectedBinding || fingerprint(destination) != input) return false
        val tail = protocol.getJSONArray("tail")
        for (i in 0 until tail.length()) destination.put(JsonProtocol.objectCopy(tail.getJSONObject(i)).apply {
            put(BINDING, savedBinding)
            if (i == 0) {
                put(VALID_PREFIX, true); put(INPUT_SHA, input)
                put(FALLBACK_TEXT, fallbackVisibleText.ifBlank { JsonProtocol.string(stored, "text") })
            }
        })
        return true
    }

    /** Invalid replay is replaced with visible history; the stored archive is never edited. */
    fun checkBinding(messages: JSONArray, expectedBinding: String): Boolean {
        val cleaned = JSONArray()
        var invalid = false
        var dropped = false
        for (i in 0 until messages.length()) {
            val source = messages.optJSONObject(i) ?: continue
            val item = JsonProtocol.objectCopy(source)
            if (item.optBoolean(DOWNGRADED, false)) { dropped = true; item.remove(DOWNGRADED) }
            val marked = item.has(BINDING) || item.has(VALID_PREFIX) || item.has(INPUT_SHA) || item.has(FALLBACK_TEXT)
            val start = item.optBoolean(VALID_PREFIX, false)
            val binding = JsonProtocol.nonBlank(item, BINDING)
            val input = JsonProtocol.nonBlank(item, INPUT_SHA)
            val fallback = JsonProtocol.string(item, FALLBACK_TEXT)
            item.remove(BINDING); item.remove(VALID_PREFIX); item.remove(INPUT_SHA); item.remove(FALLBACK_TEXT)
            if (start) {
                invalid = binding != expectedBinding || input != StreamingJsonRequestBody.sha256(cleaned)
                if (invalid && fallback.isNotEmpty()) cleaned.put(JSONObject().put("role", "assistant").put("content", fallback))
            } else if (marked) invalid = invalid || binding != expectedBinding
            else invalid = false
            if (marked && invalid) { dropped = true; continue }
            cleaned.put(item)
        }
        while (messages.length() > 0) messages.remove(messages.length() - 1)
        for (i in 0 until cleaned.length()) messages.put(cleaned.get(i))
        return dropped
    }

    fun extractReasoningSummary(protocol: JSONObject?): String {
        if (protocol == null) return ""
        val parts = mutableListOf<String>()
        protocol.optJSONArray("tail")?.let { tail ->
            for (i in 0 until tail.length()) tail.optJSONObject(i)?.let { assistant ->
                if (assistant.optString("role") == "assistant") JsonProtocol.nonBlank(assistant, "reasoning_content")?.let(parts::add)
            }
        }
        // The same interrupted message can be referenced in two archive fields; count it once.
        val pending = protocol.optJSONObject("uncommitted_assistant") ?: protocol.optJSONObject("interrupted_assistant")
        pending?.let { JsonProtocol.nonBlank(it, "reasoning_content")?.let(parts::add) }
        if (pending == null) JsonProtocol.nonBlank(protocol, "partial_reasoning")?.let(parts::add)
        return parts.joinToString("\n\n")
    }

    /** Never rewrite tail or synthesize outcomes for calls whose effects are unknown. */
    fun withVisibleTail(checkpoint: JSONObject, message: JSONObject): JSONObject = JsonProtocol.objectCopy(checkpoint).apply {
        put("replayable", false)
        put("visible_text", JsonProtocol.string(message, "text"))
        val confirmed = linkedSetOf<String>()
        val unknown = JSONArray()
        val tail = optJSONArray("tail") ?: JSONArray()
        for (i in 0 until tail.length()) tail.optJSONObject(i)?.let { item ->
            if (item.optString("role") == "tool") JsonProtocol.nonBlank(item, "tool_call_id")?.let(confirmed::add)
        }
        for (i in 0 until tail.length()) tail.optJSONObject(i)?.optJSONArray("tool_calls")?.let { calls ->
            for (j in 0 until calls.length()) calls.optJSONObject(j)?.let { call ->
                if (JsonProtocol.nonBlank(call, "id") !in confirmed) unknown.put(JsonProtocol.objectCopy(call))
            }
        }
        optJSONObject("uncommitted_assistant")?.let { assistant ->
            put("interrupted_assistant", JsonProtocol.objectCopy(assistant))
            assistant.optJSONArray("tool_calls")?.let { calls ->
                for (i in 0 until calls.length()) unknown.put(JsonProtocol.copy(calls.opt(i)))
            }
        }
        put("confirmed_tool_call_ids", JSONArray(confirmed.toList()))
        if (unknown.length() > 0) put("unconfirmed_tool_calls", unknown)
    }
}
