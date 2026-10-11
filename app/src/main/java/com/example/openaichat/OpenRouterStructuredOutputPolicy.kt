package com.example.openaichat

import org.json.JSONObject

object OpenRouterStructuredOutputPolicy {
    /** Explicit opt-in only. The provider must enforce the selected format; no silent fallback. */
    fun format(kind: String, schema: String): JSONObject? = when (kind) {
        "text" -> null
        "json_object" -> JSONObject().put("type", "json_object")
        "json_schema" -> {
            require(schema.isNotBlank()) { "Укажите JSON Schema для выбранного формата" }
            JSONObject().put("type", "json_schema").put("json_schema", JSONObject()
                .put("name", "astra_response").put("strict", true).put("schema", JSONObject(schema)))
        }
        else -> throw IllegalArgumentException("Неизвестный формат ответа")
    }
}
