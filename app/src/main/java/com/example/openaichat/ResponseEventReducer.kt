package com.example.openaichat

import org.json.JSONObject

enum class ResponseTerminalState(val wireStatus: String) {
    COMPLETED("completed"), FAILED("failed"), INCOMPLETE("incomplete"), CANCELLED("cancelled")
}

data class ResponseEventState(
    val responseId: String? = null,
    val text: String = "",
    val reasoningSummary: String = "",
    val terminalState: ResponseTerminalState? = null,
    val terminalResponse: JSONObject? = null,
    val completionError: String? = null,
    val usage: JSONObject? = null
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
        return when (JsonProtocol.string(event, "type")) {
            "response.created" -> {
                val responseId = event.optJSONObject("response")?.let { JsonProtocol.nonBlank(it, "id") }
                val state = if (responseId == null || responseId == current.responseId) current
                    else current.copy(responseId = responseId)
                ResponseEventDecision(state, responseIdChanged = state.responseId != current.responseId)
            }
            "response.output_text.delta", "response.refusal.delta" -> {
                val delta = JsonProtocol.string(event, "delta")
                if (delta.isEmpty()) ResponseEventDecision(current)
                else ResponseEventDecision(current.copy(text = current.text + delta), textChanged = true)
            }
            "response.refusal.done" -> {
                val full = JsonProtocol.string(event, "refusal")
                if (full.isBlank() || full == current.text) ResponseEventDecision(current)
                else ResponseEventDecision(current.copy(text = full), textChanged = true)
            }
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
