package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Assert.assertNull
import org.junit.Assert.fail

class ResponseEventReducerTest {
    @Test fun differentCreatedOrTerminalResponseCannotReplaceOwnedPaidPartial() {
        val owned = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_owned"),
            JSONObject().put("type", "response.output_text.delta").put("delta", "paid partial")).state
        for (type in listOf("response.created", "response.completed", "response.failed", "response.in_progress")) {
            try {
                ResponseEventReducer.reduce(owned, JSONObject().put("type", type)
                    .put("response", JSONObject().put("id", "resp_other").put("output", org.json.JSONArray())))
                fail("A different response must not advance the original state")
            } catch (_: ResponsesResponseIdentityException) { }
            assertEquals("resp_owned", owned.responseId)
            assertEquals("paid partial", owned.text)
            assertNull(owned.terminalState)
        }
    }

    @Test fun topLevelResponseIdentityMustMatchBothOwnerAndNestedIdentity() {
        for (owner in listOf<String?>(null, "resp_owned")) {
            try {
                ResponseEventReducer.reduce(ResponseEventState(responseId = owner), JSONObject()
                    .put("type", "response.created").put("response_id", "resp_other")
                    .put("response", JSONObject().put("id", "resp_owned")))
                fail("Contradictory response identities must fail before mutation")
            } catch (_: ResponsesResponseIdentityException) { }
        }
    }

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
    fun preservesWhitespaceOnlyTextAndReasoningDeltas() {
        val decision = ResponseEventReducer.reduce(
            ResponseEventState(text = "a"),
            JSONObject().put("type", "response.output_text.delta").put("delta", " ")
        )

        assertTrue(decision.textChanged)
        assertEquals("a ", decision.state.text)

        val reasoningDecision = ResponseEventReducer.reduce(
            ResponseEventState(),
            JSONObject().put("type", "response.reasoning_summary_text.delta").put("delta", " ")
        )
        assertTrue(reasoningDecision.reasoningChanged)
        assertEquals(" ", reasoningDecision.state.reasoningSummary)
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
    fun semanticSseErrorPreservesPartialButMarksFailure() {
        val current = ResponseEventState(responseId = "resp_x", text = "partial")
        val result = ResponseEventReducer.reduce(
            current, JSONObject().put("type", "error")
                .put("error", JSONObject().put("message", "provider fault"))
        )
        assertEquals(ResponseTerminalState.FAILED, result.state.terminalState)
        assertEquals("partial", result.state.text)
        assertEquals("provider fault", result.state.completionError)
    }

    @Test fun streamingErrorRetainsNestedPaidOutputAndErrorFieldsWithoutMutatingReceivedResponse() {
        val error = JSONObject().put("message", JSONObject.NULL).put("code", "server_error")
            .put("details", JSONObject().put("retryable", false))
        val terminal = JSONObject().put("id", "resp_owned").put("status", "in_progress")
            .put("output", org.json.JSONArray().put(JSONObject().put("type", "message")
                .put("content", org.json.JSONArray().put(JSONObject().put("type", "refusal")
                    .put("refusal", "canonical refusal")))))
        for (type in listOf("error", "response.error")) {
            val reduced = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_owned"),
                JSONObject().put("type", type).put("response", terminal).put("error", error)
                    .put("message", "top-level provider diagnostic")).state
            assertEquals(ResponseTerminalState.FAILED, reduced.terminalState)
            assertEquals("canonical refusal", reduced.text)
            assertEquals("failed", reduced.terminalResponse!!.getString("status"))
            assertEquals("resp_owned", reduced.terminalResponse!!.getString("id"))
            val retainedError = reduced.terminalResponse!!.getJSONObject("error")
            assertEquals("server_error", retainedError.getString("code"))
            assertEquals(false, retainedError.getJSONObject("details").getBoolean("retryable"))
            assertEquals("top-level provider diagnostic", reduced.completionError)
            assertEquals("in_progress", terminal.getString("status"))
            assertTrue(error.isNull("message"))
            try {
                ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_other"),
                    JSONObject().put("type", type).put("response", terminal).put("error", error))
                fail("A failed response must not claim a different owner's paid output")
            } catch (_: ResponsesResponseIdentityException) { }
        }
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
    @Test
    fun refusalDeltasAndTerminalDoNotLoseOrDuplicateTheRefusal() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state,
            JSONObject().put("type", "response.refusal.delta").put("delta", "I cannot")).state
        state = ResponseEventReducer.reduce(state,
            JSONObject().put("type", "response.refusal.delta").put("delta", " comply")).state
        assertEquals("I cannot comply", state.text)
        state = ResponseEventReducer.reduce(state,
            JSONObject().put("type", "response.refusal.done").put("refusal", "I cannot comply")).state
        assertEquals("I cannot comply", state.text)
        val completed = JSONObject().put("id", "response_refused").put("status", "completed")
            .put("usage", JSONObject().put("output_tokens", 17))
            .put("output", org.json.JSONArray().put(
                JSONObject().put("type", "message").put("content", org.json.JSONArray().put(
                    JSONObject().put("type", "refusal").put("refusal", "I cannot comply")
                ))
            ))
        val final = ResponseEventReducer.reduce(state,
            JSONObject().put("type","response.completed").put("response",completed)).state
        assertEquals("I cannot comply", final.text)
        assertEquals(ResponseTerminalState.COMPLETED, final.terminalState)
        assertEquals(17L, final.usage?.optLong("output_tokens"))
        assertEquals("I cannot comply", OpenAiSseStreamReader.terminalOutputText(completed))
    }

    @Test
    fun refusalTerminalWithoutDeltasBecomesReadableText() {
        val response = JSONObject().put("id","response_2").put("output",org.json.JSONArray().put(
            JSONObject().put("type","message").put("content",org.json.JSONArray().put(
                JSONObject().put("type","refusal").put("refusal","Sorry, not available")
            ))
        ))
        val state = ResponseEventReducer.reduce(ResponseEventState(),
            JSONObject().put("type","response.completed").put("response",response))
        assertTrue(state.textChanged)
        assertEquals("Sorry, not available", state.state.text)
    }

    @Test
    fun terminalStateCannotBeResurrectedByLateSseEvents() {
        val completedResponse = JSONObject().put("id", "resp_done")
            .put("status", "completed").put("output", org.json.JSONArray().put(
                JSONObject().put("type", "message").put("content", org.json.JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "canonical")
                ))
            ))
        val terminal = ResponseEventReducer.reduce(
            ResponseEventState(responseId = "resp_done", text = "partial"),
            JSONObject().put("type", "response.completed").put("response", completedResponse)
        ).state
        assertEquals("canonical", terminal.text)
        val late = listOf(
            JSONObject().put("type", "response.output_text.delta").put("delta", " stale"),
            JSONObject().put("type", "response.created").put("response", JSONObject().put("id", "resp_other")),
            JSONObject().put("type", "response.failed").put("response", JSONObject().put("id", "resp_done")),
            JSONObject().put("type", "error").put("message", "late")
        )
        late.forEach { event ->
            val decision = ResponseEventReducer.reduce(terminal, event)
            assertEquals(terminal, decision.state)
            assertEquals(false, decision.textChanged)
            assertEquals(false, decision.responseIdChanged)
        }
    }

    @Test
    fun interleavedOutputAndContentIndicesPreserveProviderOrderAndEveryDelta() {
        var state = ResponseEventState()
        fun delta(out: Int, content: Int, value: String) {
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.output_text.delta")
                .put("output_index", out)
                .put("content_index", content)
                .put("delta", value)).state
        }
        delta(2, 0, "third-1 ")
        delta(0, 1, "first-2 ")
        delta(1, 0, "second-1 ")
        delta(0, 0, "first-1 ")
        delta(2, 0, "third-2 ")
        delta(0, 1, "first-2b ")
        assertEquals("first-1 first-2 first-2b second-1 third-1 third-2 ", state.text)
        assertEquals(4, state.indexedTextParts.size)
        state = ResponseEventReducer.reduce(state, JSONObject()
            .put("type", "response.output_text.done")
            .put("output_index", 1)
            .put("content_index", 0)
            .put("text", "second-complete ")).state
        assertEquals("first-1 first-2 first-2b second-complete third-1 third-2 ", state.text)
        assertEquals(4, state.indexedTextParts.size)
    }

    @Test
    fun indexedRefusalDoneDoesNotEraseOtherOutputsOrDuplicatePart() {
        var state = ResponseEventState()
        val events = listOf(
            JSONObject().put("type", "response.refusal.delta")
                .put("output_index", 1).put("content_index", 0).put("delta", "No"),
            JSONObject().put("type", "response.output_text.delta")
                .put("output_index", 0).put("content_index", 0).put("delta", "Earlier. "),
            JSONObject().put("type", "response.refusal.done")
                .put("output_index", 1).put("content_index", 0).put("refusal", "No thanks.")
        )
        events.forEach { state = ResponseEventReducer.reduce(state, it).state }
        assertEquals("Earlier. No thanks.", state.text)
        val same = ResponseEventReducer.reduce(state, events.last())
        assertEquals(state, same.state)
        assertEquals(false, same.textChanged)
    }

    @Test
    fun authoritativeTerminalReplacesInterleavedPartialInCanonicalOutputOrder() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state, JSONObject()
            .put("type", "response.output_text.delta")
            .put("output_index", 1).put("content_index", 0).put("delta", "B-before")).state
        state = ResponseEventReducer.reduce(state, JSONObject()
            .put("type", "response.output_text.delta")
            .put("output_index", 0).put("content_index", 0).put("delta", "A-before")).state
        assertEquals("A-beforeB-before", state.text)
        val terminal = JSONObject().put("id", "resp_interleaved").put("output",
            org.json.JSONArray()
                .put(JSONObject().put("type", "message").put("content",
                    org.json.JSONArray().put(JSONObject().put("type", "output_text")
                        .put("text", "A-canonical"))))
                .put(JSONObject().put("type", "message").put("content",
                    org.json.JSONArray().put(JSONObject().put("type", "output_text")
                        .put("text", "B-canonical")))))
        val decision = ResponseEventReducer.reduce(state, JSONObject()
            .put("type", "response.completed").put("response", terminal))
        assertEquals("A-canonicalB-canonical", decision.state.text)
        assertEquals(ResponseTerminalState.COMPLETED, decision.state.terminalState)
        val late = ResponseEventReducer.reduce(decision.state, JSONObject()
            .put("type", "response.output_text.delta")
            .put("output_index", 2).put("content_index", 0).put("delta", "late"))
        assertEquals(decision.state, late.state)
    }

}
