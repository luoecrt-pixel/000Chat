package com.example.openaichat

import org.json.JSONObject

/**
 * Records a Worker terminal failure only for the still-owned active request.
 * It checkpoints any partial assistant text before the Worker clears the active
 * cursor; stopped, replaced or deleted requests cannot resurrect chat content.
 */
object BackgroundRequestFailureRecorder {
    fun persist(
        db: ChatDatabase,
        chatId: String,
        localRequestId: String,
        responseId: String,
        status: String,
        error: String
    ): Boolean {
        val active = db.getActiveResponse(chatId) ?: return false
        if (active.optString("request_id") != localRequestId) return false
        val chat = db.getChat(chatId) ?: return false
        val messages = chat.optJSONArray("messages") ?: return false
        val items = (0 until messages.length()).mapNotNull { messages.optJSONObject(it) }
            .map { JSONObject(it.toString()) }.toMutableList()
        val userMessage = items.firstOrNull {
            it.optString("request_id") == localRequestId && it.optString("role") == "user"
        }
        val requestedModel = userMessage?.optString("requested_model").orEmpty()
            .ifBlank { userMessage?.optString("model").orEmpty() }
            .takeIf { it.isNotBlank() }

        val partial = active.optString("text")
        if (partial.isNotBlank() && items.none {
                it.optString("role") == "assistant" && it.optString("request_id") == localRequestId
            }) {
            items += JSONObject()
                .put("role", "assistant")
                .put("text", partial)
                .put("provider", "openai")
                .put("request_id", localRequestId)
                .put("response_id", responseId)
                .put("status", status)
                .put("incomplete", true)
                .put("error", error.take(1200))
                .apply { requestedModel?.let { put("requested_model", it) } }
        }
        val previousResponseId = chat.optString("response_id")
            .takeIf { it.isNotBlank() && it != "null" }
        // Atomic chat/ownership check. No new message is committed after Stop.
        if (!db.finalizeCurrentChatIfActiveRequest(
                chatId, chat.optString("title", "Новый чат"), previousResponseId,
                chat.optString("baseline_effort").takeIf { it.isNotBlank() && it != "null" },
                items, localRequestId
            )) return false

        db.recordRequestAccounting(
            chatId = chatId, localRequestId = localRequestId,
            status = status, responseId = responseId, error = error
        )
        db.recordRequestEvent(
            chatId = chatId,
            eventType = "request_${status}",
            requestId = localRequestId,
            provider = "openai",
            requestedModel = requestedModel,
            responseModel = null,
            modelMatch = ModelCatalog.Match.UNKNOWN,
            status = status,
            error = error
        )
        return true
    }
}
