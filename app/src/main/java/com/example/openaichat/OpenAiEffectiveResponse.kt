package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Joins received paid output before any model/UI/artifact extraction occurs. */
object OpenAiEffectiveResponse {
    fun assemble(terminalResponse: JSONObject?, state: ResponseEventState): JSONObject {
        // Copy the envelope without serializing large image/signed payloads.
        // The helper changes neither the canonical response nor received items.
        val effective = JSONObject()
        terminalResponse?.keys()?.forEach { key -> effective.put(key, terminalResponse.opt(key)) }
        if (JsonProtocol.nonBlank(effective, "id") == null) {
            state.responseId?.let { effective.put("id", it) }
        }
        if (JsonProtocol.nonBlank(effective, "status") == null) {
            state.terminalState?.let { effective.put("status", it.wireStatus) }
        }
        if (effective.optJSONObject("usage") == null) state.usage?.let { effective.put("usage", it) }

        val items = sortedMapOf<Int, Any>()
        val canonicalIds = mutableSetOf<String>()
        terminalResponse?.optJSONArray("output")?.let { output ->
            for (index in 0 until output.length()) {
                val item = output.opt(index) ?: JSONObject.NULL
                items[index] = item
                (item as? JSONObject)?.let { JsonProtocol.nonBlank(it, "id") }
                    ?.let(canonicalIds::add)
            }
        }
        state.typedOutputItems.toSortedMap().forEach { (index, item) ->
            val id = JsonProtocol.nonBlank(item, "id")
            // Canonical output wins both its index and its ID, including when
            // the terminal envelope moved that ID to a different array index.
            if (index !in items && (id == null || id !in canonicalIds)) items[index] = item
        }
        if (items.isNotEmpty() || terminalResponse?.has("output") == true) {
            effective.put("output", JSONArray().apply { items.values.forEach { put(it) } })
        }
        return effective
    }
}
