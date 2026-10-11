package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject

/** Merge streaming fragments by explicit identity while retaining block order and opaque signatures. */
class ReasoningDetailAccumulator {
    private val blocks = mutableListOf<JSONObject>()
    private val indexes = mutableMapOf<String, Int>()
    fun accept(details: JSONArray) {
        for (i in 0 until details.length()) {
            val item = details.optJSONObject(i) ?: continue
            val type = JsonProtocol.nonBlank(item, "type").orEmpty()
            val key = when {
                item.has("index") && !item.isNull("index") -> {
                    val prefix = "index:${item.opt("index")}:type:"
                    if (type.isNotBlank()) prefix + type else {
                        val candidates = indexes.keys.filter { it.startsWith(prefix) }
                        if (candidates.size > 1) throw java.io.IOException("Ambiguous reasoning block identity: missing type")
                        candidates.singleOrNull() ?: prefix
                    }
                }
                JsonProtocol.nonBlank(item, "id") != null -> "$type:id:${item.getString("id")}"
                else -> null
            }
            val existing = key?.let { indexes[it] }
            if (existing == null) {
                if (key != null) indexes[key] = blocks.size
                blocks.add(JsonProtocol.objectCopy(item))
            } else {
                val block = blocks[existing]
                item.keys().forEach { field ->
                    val value = item.opt(field)
                    if (value != null && value != JSONObject.NULL) {
                        // OpenRouter documents ordered delta concatenation. Do not guess that a
                        // repeated fragment is a full snapshot: that would corrupt legitimate data.
                        if (field in setOf("text", "summary", "data", "signature") && value is String)
                            block.put(field, JsonProtocol.text(block, field).orEmpty() + value)
                        else block.put(field, JsonProtocol.copy(value))
                    }
                }
            }
        }
    }
    fun array() = JSONArray().apply { blocks.forEach { put(JsonProtocol.objectCopy(it)) } }
}
