package com.example.openaichat

import org.json.JSONArray
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
        val ownedResponseId = JsonProtocol.nonBlank(active, "response_id") ?: responseId
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

        // A provider may finish signed reasoning/tool output_item.done BEFORE
        // emitting its first visible text delta. Persist the exact received
        // typed items and readable summary even if the assistant text is empty.
        // Otherwise the Worker clears the owned checkpoint and loses paid data.
        val recovered = try {
            ResponseIndexedCheckpoint.restore(active)
        } catch (_: UnrecoverableResponsesCheckpointException) {
            null
        }
        val checkpoint = db.getOpenAiToolCheckpoint(chatId, localRequestId)
        val durableTerminal = checkpoint?.takeIf { it.optString("phase") == "finished" }
            ?.optJSONObject("final_response")
        // A received response can be newer than active_indexed_checkpoint: the
        // local tool bridge writes it before validation/execution of its tools.
        // Never merge a parent's output indices into an active child's indices.
        val receivedResponse = checkpoint?.optJSONObject("received_response")
            ?.takeIf { JsonProtocol.nonBlank(it, "id") == ownedResponseId }
        val sourceResponse = durableTerminal ?: recovered?.terminalResponse ?: receivedResponse
        val response = if (durableTerminal != null) JsonProtocol.objectCopy(durableTerminal)
            else OpenAiEffectiveResponse.assemble(sourceResponse,
                recovered ?: ResponseEventState(responseId = ownedResponseId))
        if (JsonProtocol.nonBlank(response, "id") == null) response.put("id", ownedResponseId)
        OpenAiLocalFileToolBridge.attachReceivedPrefix(db, chatId, localRequestId, response)
        val received = response.optJSONArray("output") ?: JSONArray()
        val typed = OpenAiLocalFileToolBridge.completePendingOutputs(received)
        val partial = OpenAiSseStreamReader.terminalOutputText(response)
            .ifBlank { JsonProtocol.string(active, "text") }
        val model = AstraResponseModel.fromResponse(response, partial, localRequestId, status, error.take(1200))
        val summary = model.reasoningSummary?.takeIf(String::isNotBlank)
            ?: recovered?.reasoningSummary.orEmpty()
        val usage = response.optJSONObject("usage")
        val toolTelemetry = OpenAiLocalFileToolBridge.extractTelemetrySummary(db, chatId, localRequestId)
            ?: JSONObject()
        response.optJSONObject(OpenAiUsageLedger.FIELD)?.let {
            toolTelemetry.put(OpenAiUsageLedger.FIELD, JsonProtocol.objectCopy(it))
        }
        val compactTelemetry = FileBackedIoTelemetry.mergeCompactTelemetry(
            toolTelemetry = toolTelemetry,
            costDetails = OpenRouterUsageSnapshot.extractCostDetails(usage)
        )
        val sourceMessage = JSONObject()
        receivedFilesDir?.let {
            OpenAiRawSseArchive.attachToMessage(sourceMessage, it, chatId, localRequestId)
        }
        if ((partial.isNotBlank() || typed.length() > 0 || summary.isNotBlank() ||
                sourceMessage.has("annotations") || usage != null || compactTelemetry != null) && items.none {
                it.optString("role") == "assistant" && it.optString("request_id") == localRequestId
            }) {
            items += model.toChatJson()
                .put("provider", "openai")
                .put("request_id", localRequestId)
                .put("response_id", JsonProtocol.nonBlank(response, "id") ?: ownedResponseId)
                .put("status", status)
                .put("incomplete", true)
                .put("error", error.take(1200))
                .apply { requestedModel?.let { put("requested_model", it) } }
                .apply {
                    userMessage?.optString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD)
                        ?.takeIf { it.isNotBlank() }
                        ?.let { put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, it) }
                    sourceMessage.optJSONArray("annotations")?.let { extra ->
                        val annotations = optJSONArray("annotations") ?: JSONArray().also { put("annotations", it) }
                        for (i in 0 until extra.length()) annotations.put(JsonProtocol.copy(extra.get(i)))
                    }
                    if (summary.isNotBlank()) put("reasoning_summary", summary)
                    if (typed.length() > 0) put("openai_protocol_output", typed)
                    usage?.let { put("usage", JsonProtocol.objectCopy(it)) }
                    response.optJSONObject(OpenAiUsageLedger.FIELD)?.let {
                        put(OpenAiUsageLedger.FIELD, JsonProtocol.objectCopy(it))
                    }
                    compactTelemetry?.let { put("telemetry_summary", it) }
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
            status = status, responseId = ownedResponseId, error = error,
            usage = usage, telemetrySummary = compactTelemetry
        )
        db.recordRequestEvent(
            chatId = chatId,
            eventType = "request_${status}",
            requestId = localRequestId,
            provider = "openai",
            requestedModel = requestedModel,
            responseModel = model.model,
            modelMatch = ModelCatalog.match(requestedModel, model.model),
            status = status,
            inputTokens = usage?.optLong("input_tokens", 0L) ?: 0L,
            cachedTokens = usage?.optJSONObject("input_tokens_details")?.optLong("cached_tokens", 0L) ?: 0L,
            cacheWriteTokens = usage?.optJSONObject("input_tokens_details")?.optLong("cache_write_tokens", 0L) ?: 0L,
            outputTokens = usage?.optLong("output_tokens", 0L) ?: 0L,
            reasoningTokens = usage?.optJSONObject("output_tokens_details")?.optLong("reasoning_tokens", 0L) ?: 0L,
            latencyMs = latencyMs,
            error = error,
            telemetrySummary = compactTelemetry
        )
        return true
    }
}
