package com.example.openaichat

import org.json.JSONObject

enum class ResponseTerminalState(val wireStatus: String) {
    COMPLETED("completed"),
    FAILED("failed"),
    INCOMPLETE("incomplete"),
    CANCELLED("cancelled")
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
    val terminal: Boolean
        get() = state.terminalState != null
}

object ResponseEventReducer {
    fun reduce(current: ResponseEventState, event: JSONObject): ResponseEventDecision {
        return when (event.optString("type")) {
            "response.created" -> {
                val response = event.optJSONObject("response")
                val responseId = response?.optString("id")?.takeIf { it.isNotBlank() }
                val state = if (responseId == null || responseId == current.responseId) {
                    current
                } else {
                    current.copy(responseId = responseId)
                }
                ResponseEventDecision(
                    state = state,
                    responseIdChanged = state.responseId != current.responseId
                )
            }

            "response.output_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isEmpty()) {
                    ResponseEventDecision(current)
                } else {
                    ResponseEventDecision(
                        state = current.copy(text = current.text + delta),
                        textChanged = true
                    )
                }
            }

            "response.reasoning_summary_text.delta" -> {
                val delta = event.optString("delta")
                if (delta.isEmpty()) {
                    ResponseEventDecision(current)
                } else {
                    ResponseEventDecision(
                        state = current.copy(reasoningSummary = current.reasoningSummary + delta),
                        reasoningChanged = true
                    )
                }
            }

            "response.completed" -> terminal(
                current = current,
                event = event,
                terminalState = ResponseTerminalState.COMPLETED
            )

            "response.failed" -> terminal(
                current = current,
                event = event,
                terminalState = ResponseTerminalState.FAILED,
                completionError = event.optJSONObject("response")
                    ?.optJSONObject("error")
                    ?.optString("message")
                    ?.takeIf { it.isNotBlank() }
                    ?: event.optJSONObject("response")?.optString("error")?.takeIf { it.isNotBlank() }
            )

            "response.incomplete" -> terminal(
                current = current,
                event = event,
                terminalState = ResponseTerminalState.INCOMPLETE,
                completionError = event.optJSONObject("response")
                    ?.optJSONObject("incomplete_details")
                    ?.optString("reason")
                    ?.takeIf { it.isNotBlank() }
            )

            "response.cancelled" -> terminal(
                current = current,
                event = event,
                terminalState = ResponseTerminalState.CANCELLED
            )

            else -> ResponseEventDecision(current)
        }
    }

    private fun terminal(
        current: ResponseEventState,
        event: JSONObject,
        terminalState: ResponseTerminalState,
        completionError: String? = null
    ): ResponseEventDecision {
        val response = event.optJSONObject("response")
        val responseId = response?.optString("id")?.takeIf { it.isNotBlank() }
        val state = current.copy(
            responseId = responseId ?: current.responseId,
            terminalState = terminalState,
            terminalResponse = response,
            completionError = completionError,
            usage = response?.optJSONObject("usage") ?: current.usage
        )
        return ResponseEventDecision(
            state = state,
            responseIdChanged = state.responseId != current.responseId
        )
    }
}
