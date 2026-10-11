package com.example.openaichat

import org.json.JSONObject

/** Answer-local metadata only; never consult the global model picker. */
object AssistantModelLabel {
    fun text(message: JSONObject): String {
        val provider = message.optString("provider").takeIf { it.isNotBlank() && it != "null" }
        val response = message.optString("response_model").takeIf { it.isNotBlank() && it != "null" }
        val requested = message.optString("requested_model").takeIf { it.isNotBlank() && it != "null" }
            ?: message.optString("model").takeIf { it.isNotBlank() && it != "null" }
        return when {
            response != null -> ModelCatalog.displayName(provider, response)
            requested != null -> ModelCatalog.displayName(provider, requested)
            else -> "Ассистент · модель неизвестна"
        }
    }
}
