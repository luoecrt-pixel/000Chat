package com.example.openaichat

import org.json.JSONObject
import java.io.File

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
        error: String,
        receivedFilesDir: File? = null,
        latencyMs: Long = 0L
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

        val partial = JsonProtocol.string(active, "text")
        // A provider may finish signed reasoning/tool output_item.done BEFORE
        // emitting its first visible text delta. Persist the exact received
        // typed items and readable summary even if the assistant text is empty.
        // Otherwise the Worker clears the owned checkpoint and loses paid data.
        val recovered = try {
            ResponseIndexedCheckpoint.restore(active)
        } catch (_: UnrecoverableResponsesCheckpointException) {
            null
        }
        val typed = recovered?.typedOutputItems?.toSortedMap()?.values.orEmpty()
        val summary = recovered?.reasoningSummary.orEmpty()
        val sourceMessage = JSONObject()
        receivedFilesDir?.let {
            OpenAiRawSseArchive.attachToMessage(sourceMessage, it, chatId, localRequestId)
        }
        if ((partial.isNotBlank() || typed.isNotEmpty() || summary.isNotBlank() ||
                sourceMessage.has("annotations")) && items.none {
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
                .apply {
                    userMessage?.optString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, it) }
                    sourceMessage.optJSONArray("annotations")?.let { put("annotations", it) }
                    if (summary.isNotBlank()) put("reasoning_summary", summary)
                    if (typed.isNotEmpty()) put("openai_protocol_output",
                        org.json.JSONArray().apply {
                            typed.forEach { item -> put(JSONObject(item.toString())) }
                        })
                }
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
            latencyMs = latencyMs,
            error = error
        )
        return true
    }
}
