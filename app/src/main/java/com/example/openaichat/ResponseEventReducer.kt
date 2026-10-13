package com.example.openaichat

import org.json.JSONObject

enum class ResponseTerminalState(val wireStatus: String) {
    COMPLETED("completed"), FAILED("failed"), INCOMPLETE("incomplete"), CANCELLED("cancelled")
}

data class ResponseContentIndex(val outputIndex: Int, val contentIndex: Int) : Comparable<ResponseContentIndex> {
    override fun compareTo(other: ResponseContentIndex): Int =
        compareValuesBy(this, other, ResponseContentIndex::outputIndex, ResponseContentIndex::contentIndex)
}

data class ResponseEventState(
    val responseId: String? = null,
    val text: String = "",
    val reasoningSummary: String = "",
    val terminalState: ResponseTerminalState? = null,
    val terminalResponse: JSONObject? = null,
    val completionError: String? = null,
    val usage: JSONObject? = null,
    // Responses output_text/refusal deltas can interleave across output items.
    // Do not append in arrival order: sort by provider output/content indices.
    val indexedTextParts: Map<ResponseContentIndex, String> = emptyMap(),
    val unindexedText: String = "",
    /** v23 had only flattened text; indexed replay must fail closed. */
    val legacyFlatCheckpoint: Boolean = false
)

data class ResponseEventDecision(
    val state: ResponseEventState,
    val textChanged: Boolean = false,
    val reasoningChanged: Boolean = false,
    val responseIdChanged: Boolean = false
) {
    val terminal: Boolean get() = state.terminalState != null
}

/** Reject non-string SSE protocol fields, especially Android JSONObject.NULL. */
object ResponseEventReducer {
    fun reduce(current: ResponseEventState, event: JSONObject): ResponseEventDecision {
        // A terminal response is immutable. Late SSE callbacks must not append
        // content, change the response ID or replace the canonical terminal.
        if (current.terminalState != null) return ResponseEventDecision(current)
        return when (JsonProtocol.string(event, "type")) {
            "response.created" -> {
                val responseId = event.optJSONObject("response")?.let { JsonProtocol.nonBlank(it, "id") }
                val state = if (responseId == null || responseId == current.responseId) current
                    else current.copy(responseId = responseId)
                ResponseEventDecision(state, responseIdChanged = state.responseId != current.responseId)
            }
            "response.output_text.delta", "response.refusal.delta" ->
                changeText(current, event, JsonProtocol.string(event, "delta"), replacePart = false)
            "response.output_text.done" ->
                changeText(current, event, JsonProtocol.string(event, "text"), replacePart = true)
            "response.refusal.done" ->
                changeText(current, event, JsonProtocol.string(event, "refusal"), replacePart = true)
            "response.reasoning_summary_text.delta" -> {
                val delta = JsonProtocol.string(event, "delta")
                if (delta.isEmpty()) ResponseEventDecision(current)
                else ResponseEventDecision(current.copy(reasoningSummary = current.reasoningSummary + delta), reasoningChanged = true)
            }
            "response.completed" -> terminal(current, event, ResponseTerminalState.COMPLETED)
            "response.failed" -> terminal(current, event, ResponseTerminalState.FAILED,
                event.optJSONObject("response")?.let { response ->
                    response.optJSONObject("error")?.let { JsonProtocol.nonBlank(it, "message") }
                        ?: JsonProtocol.nonBlank(response, "error")
                })
            "response.incomplete" -> terminal(current, event, ResponseTerminalState.INCOMPLETE,
                event.optJSONObject("response")?.optJSONObject("incomplete_details")?.let {
                    JsonProtocol.nonBlank(it, "reason")
                })
            "response.cancelled" -> terminal(current, event, ResponseTerminalState.CANCELLED)
            "error", "response.error" -> ResponseEventDecision(current.copy(
                terminalState = ResponseTerminalState.FAILED,
                completionError = event.optJSONObject("error")?.let { JsonProtocol.nonBlank(it, "message") }
                    ?: JsonProtocol.nonBlank(event, "message") ?: "Responses streaming error"
            ))
            else -> ResponseEventDecision(current)
        }
    }

    private fun changeText(
        current: ResponseEventState, event: JSONObject,
        value: String, replacePart: Boolean
    ): ResponseEventDecision {
        // Missing/empty .done payload is not proof that previously streamed
        // text vanished. Only a full terminal response may replace it with an
        // authoritative empty output. Never erase another indexed item.
        if (value.isEmpty()) return ResponseEventDecision(current)
        val outputIndex = event.optInt("output_index", -1)
        val contentIndex = event.optInt("content_index", -1)
        val updated = if (outputIndex >= 0 && contentIndex >= 0) {
            if (current.legacyFlatCheckpoint) {
                throw java.io.IOException(
                    "Indexed Responses continuation requires an owned checkpoint; " +
                    "legacy flattened partial cannot be safely reordered"
                )
            }
            val key = ResponseContentIndex(outputIndex, contentIndex)
            val oldParts = current.indexedTextParts
            val old = oldParts[key].orEmpty()
            val newValue = if (replacePart) value else old + value
            if (newValue == old) return ResponseEventDecision(current)
            val parts = oldParts + (key to newValue)
            val loose = if (oldParts.isEmpty() && current.unindexedText.isEmpty())
                current.text else current.unindexedText
            current.copy(indexedTextParts = parts, unindexedText = loose,
                text = parts.toSortedMap().values.joinToString("") + loose)
        } else {
            // Old provider/fixture frames do not carry indices: preserve them
            // verbatim in arrival order, without claiming indexed ordering.
            val loose = if (current.indexedTextParts.isEmpty() &&
                current.unindexedText.isEmpty()) current.text else current.unindexedText
            val next = if (replacePart) value else loose + value
            if (next == loose) return ResponseEventDecision(current)
            current.copy(unindexedText = next, text =
                current.indexedTextParts.toSortedMap().values.joinToString("") + next)
        }
        return ResponseEventDecision(updated, textChanged = updated.text != current.text)
    }

    private fun terminal(current: ResponseEventState, event: JSONObject,
                         terminalState: ResponseTerminalState, completionError: String? = null): ResponseEventDecision {
        val response = event.optJSONObject("response")
        val responseId = response?.let { JsonProtocol.nonBlank(it, "id") }
        val canonical = OpenAiSseStreamReader.terminalOutputText(response)
        val state = current.copy(
            responseId = responseId ?: current.responseId,
            text = canonical.ifBlank { current.text },
            terminalState = terminalState,
            terminalResponse = response,
            completionError = completionError,
            usage = response?.optJSONObject("usage") ?: current.usage
        )
        return ResponseEventDecision(state,
            textChanged = state.text != current.text,
            responseIdChanged = state.responseId != current.responseId)
    }
}
