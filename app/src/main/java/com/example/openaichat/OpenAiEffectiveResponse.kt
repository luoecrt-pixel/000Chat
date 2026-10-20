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
        var nextIndex = (items.keys.lastOrNull() ?: -1) + 1
        val canonicalIndexes = items.keys.toSet()
        val receivedIds = canonicalIds.toMutableSet()
        state.typedOutputItems.toSortedMap().forEach { (index, item) ->
            val id = JsonProtocol.nonBlank(item, "id")
            // Canonical output wins by ID. Its compacted indexes must not discard
            // a distinct paid item received earlier at the same stream index.
            if (id != null) {
                if (receivedIds.add(id)) items[nextIndex++] = item
            } else if (index !in canonicalIndexes) {
                // An item without an ID can only be matched by its original
                // index; retain it when no canonical item claimed that slot.
                items[nextIndex++] = item
            }
        }
        if (items.isNotEmpty() || terminalResponse?.has("output") == true) {
            effective.put("output", JSONArray().apply { items.values.forEach { put(it) } })
        }
        return effective
    }
}
