package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ResponseEventReducerTest {
    @Test
    fun accumulatesTextAndReasoningDeltas() {
        var state = ResponseEventState()

        var decision = ResponseEventReducer.reduce(
            state,
            JSONObject().put("type", "response.output_text.delta").put("delta", "Hel")
        )
        state = decision.state
        assertTrue(decision.textChanged)
        assertEquals("Hel", state.text)

        decision = ResponseEventReducer.reduce(
            state,
            JSONObject().put("type", "response.output_text.delta").put("delta", "lo")
        )
        state = decision.state
        assertEquals("Hello", state.text)

        decision = ResponseEventReducer.reduce(
            state,
            JSONObject().put("type", "response.reasoning_summary_text.delta").put("delta", "plan")
        )
        assertTrue(decision.reasoningChanged)
        assertEquals("plan", decision.state.reasoningSummary)
    }

    @Test
    fun capturesResponseIdFromCreatedAndTerminalEvents() {
        var state = ResponseEventState()

        state = ResponseEventReducer.reduce(
            state,
            JSONObject()
                .put("type", "response.created")
                .put("response", JSONObject().put("id", "resp_1"))
        ).state

        assertEquals("resp_1", state.responseId)

        val decision = ResponseEventReducer.reduce(
            state,
            JSONObject()
                .put("type", "response.completed")
                .put("response", JSONObject()
                    .put("id", "resp_1")
                    .put("usage", JSONObject().put("output_tokens", 42)))
        )

        assertEquals(ResponseTerminalState.COMPLETED, decision.state.terminalState)
        assertEquals(42L, decision.state.usage?.optLong("output_tokens"))
        assertTrue(decision.terminal)
    }

    @Test
    fun capturesFailureAndIncompleteReason() {
        val failed = ResponseEventReducer.reduce(
            ResponseEventState(),
            JSONObject()
                .put("type", "response.failed")
                .put("response", JSONObject()
                    .put("id", "resp_failed")
                    .put("error", JSONObject().put("message", "boom")))
        ).state

        assertEquals(ResponseTerminalState.FAILED, failed.terminalState)
        assertEquals("boom", failed.completionError)

        val incomplete = ResponseEventReducer.reduce(
            ResponseEventState(),
            JSONObject()
                .put("type", "response.incomplete")
                .put("response", JSONObject()
                    .put("id", "resp_partial")
                    .put("incomplete_details", JSONObject().put("reason", "max_output_tokens")))
        ).state

        assertEquals(ResponseTerminalState.INCOMPLETE, incomplete.terminalState)
        assertEquals("max_output_tokens", incomplete.completionError)
    }

    @Test
    fun ignoresUnknownEventsAndEmptyDeltas() {
        val initial = ResponseEventState(responseId = "resp_1", text = "x")

        val unknown = ResponseEventReducer.reduce(
            initial,
            JSONObject().put("type", "response.reasoning_summary_text.done")
        )
        assertEquals(initial, unknown.state)

        val empty = ResponseEventReducer.reduce(
            initial,
            JSONObject().put("type", "response.output_text.delta").put("delta", "")
        )
        assertEquals(initial, empty.state)
    }

    @Test
    fun cancellationIsTerminalWithoutInventingText() {
        val initial = ResponseEventState(text = "partial")

        val decision = ResponseEventReducer.reduce(
            initial,
            JSONObject()
                .put("type", "response.cancelled")
                .put("response", JSONObject().put("id", "resp_cancelled"))
        )

        assertEquals(ResponseTerminalState.CANCELLED, decision.state.terminalState)
        assertEquals("partial", decision.state.text)
        assertEquals("resp_cancelled", decision.state.responseId)
    }
}
