package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.BufferedReader
import java.io.StringReader

/** Android org.json differs from the JVM JSON implementation in optString(null). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAiAndroidNullStreamTest {
    @Test fun nullNumericBooleanAndObjectDeltasCannotLeakIntoAnswerOrReasoning() {
        var state = ResponseEventState(text = "prefix", reasoningSummary = "thought")
        fun reduce(type: String, field: String, value: Any) {
            state = ResponseEventReducer.reduce(state,
                JSONObject().put("type", type).put(field, value)).state
        }
        for (value in listOf(JSONObject.NULL, 14, false, JSONObject().put("x", 2))) {
            reduce("response.output_text.delta", "delta", value)
            reduce("response.refusal.delta", "delta", value)
            reduce("response.reasoning_summary_text.delta", "delta", value)
            reduce("response.refusal.done", "refusal", value)
        }
        assertEquals("prefix", state.text)
        assertEquals("thought", state.reasoningSummary)
        reduce("response.output_text.delta", "delta", "null")
        assertEquals("prefixnull", state.text)
    }

    @Test fun terminalNullIdDoesNotReplaceEarlierResponseIdOrStreamedText() {
        val previous = ResponseEventState(responseId = "resp1", text = "actual")
        val response = JSONObject().put("id", JSONObject.NULL).put("output", org.json.JSONArray().put(
            JSONObject().put("type", "message").put("content", org.json.JSONArray().put(
                JSONObject().put("type", "refusal").put("refusal", JSONObject.NULL)))))
        val completed = ResponseEventReducer.reduce(previous,
            JSONObject().put("type", "response.completed").put("response", response)).state
        assertEquals("resp1", completed.responseId)
        assertEquals("actual", completed.text)
        assertEquals(ResponseTerminalState.COMPLETED, completed.terminalState)
        val failure = ResponseEventReducer.reduce(previous,
            JSONObject().put("type", "error").put("error", JSONObject().put("message", JSONObject.NULL))
                .put("message", "real error")).state
        assertEquals("real error", failure.completionError)
    }

    @Test fun sseTypeMustBeActualStringAndTerminalRefusalNullIsInvisible() {
        val events = "data: {\"type\":\"response.completed\",\"response\":{\"output\":[{\"type\":\"message\",\"content\":[{\"type\":\"refusal\",\"refusal\":null}]}]}}\n\n"
        val result = OpenAiSseStreamReader.read(BufferedReader(StringReader(events))) { true }
        assertEquals(OpenAiStreamEnd.COMPLETED, result.end)
        assertEquals("", OpenAiSseStreamReader.terminalOutputText(JSONObject(events.substringAfter("data: ").trim())))
        try {
            OpenAiSseStreamReader.read(BufferedReader(StringReader("data: {\"type\":null}\n\n"))) { true }
            fail("Null event type cannot be a synthetic event")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("no type"))
        }
    }
}
