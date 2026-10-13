package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResponseIndexedCheckpointTest {
    private fun delta(sequence: Int, output: Int, content: Int, value: String) =
        JSONObject().put("type", "response.output_text.delta")
            .put("sequence_number", sequence)
            .put("output_index", output).put("content_index", content)
            .put("delta", value)

    @Test fun sameIndexedPartAppendsAfterResumeAndSecondDisconnect() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state, delta(10, 0, 0, "Hello")).state
        val checkpoint10 = JSONObject().put("text", state.text)
            .put("last_sequence_number", 10)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        var resumed = ResponseIndexedCheckpoint.restore(checkpoint10)
        resumed = ResponseEventReducer.reduce(resumed, delta(11, 0, 0, " World")).state
        assertEquals("Hello World", resumed.text)
        val checkpoint11 = JSONObject().put("text", resumed.text)
            .put("last_sequence_number", 11)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(resumed))
        resumed = ResponseIndexedCheckpoint.restore(checkpoint11)
        resumed = ResponseEventReducer.reduce(resumed, delta(12, 0, 0, "!")).state
        assertEquals("Hello World!", resumed.text)
        assertEquals(1, resumed.indexedTextParts.size)
    }

    @Test fun interleavedOutputsRetainOrderingAcrossMultipleResumeOwners() {
        var state = ResponseEventState()
        state = ResponseEventReducer.reduce(state, delta(1, 2, 0, "C1")).state
        state = ResponseEventReducer.reduce(state, delta(2, 0, 0, "A1")).state
        state = ResponseEventReducer.reduce(state, delta(3, 1, 0, "B1")).state
        val saved = JSONObject().put("text", "A1B1C1")
            .put("last_sequence_number", 3)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        var worker = ResponseIndexedCheckpoint.restore(saved)
        worker = ResponseEventReducer.reduce(worker, delta(4, 2, 0, "C2")).state
        worker = ResponseEventReducer.reduce(worker, delta(5, 0, 0, "A2")).state
        assertEquals("A1A2B1C1C2", worker.text)
        val reopen = JSONObject().put("text", worker.text)
            .put("last_sequence_number", 5)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(worker))
        var activity = ResponseIndexedCheckpoint.restore(reopen)
        activity = ResponseEventReducer.reduce(activity, JSONObject()
            .put("type", "response.output_text.done")
            .put("output_index", 1).put("content_index", 0).put("text", "B-DONE")).state
        assertEquals("A1A2B-DONEC1C2", activity.text)
        activity = ResponseEventReducer.reduce(activity, JSONObject()
            .put("type", "response.completed").put("response", JSONObject()
                .put("id", "resp").put("output", org.json.JSONArray()
                    .put(JSONObject().put("type", "message")
                        .put("content", org.json.JSONArray().put(
                            JSONObject().put("type", "output_text").put("text", "AUTHORITATIVE")))))).state
        assertEquals("AUTHORITATIVE", activity.text)
    }

    @Test fun oldFlatCheckpointCanAppendUnindexedButMustRejectIndexedCorrection() {
        val old = JSONObject().put("last_sequence_number", 10).put("text", "Hello")
        var state = ResponseIndexedCheckpoint.restore(old)
        assertTrue(state.legacyFlatCheckpoint)
        state = ResponseEventReducer.reduce(state,
            JSONObject().put("type", "response.output_text.delta").put("delta", " World")).state
        assertEquals("Hello World", state.text)
        val newSnapshot = JSONObject().put("last_sequence_number", 11)
            .put("text", state.text).put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        val recovered = ResponseIndexedCheckpoint.restore(newSnapshot)
        assertTrue(recovered.legacyFlatCheckpoint)
        try {
            ResponseEventReducer.reduce(recovered, delta(12, 0, 0, "late"))
            fail("Incomplete legacy indexed context must not be reordered")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("legacy flattened"))
        }
        assertEquals("Hello World", recovered.text)
    }

    @Test fun corruptedFlatTextAndIndexedPartsAreRejectedBeforeNetwork() {
        val state = ResponseEventReducer.reduce(ResponseEventState(), delta(1, 0, 0, "Hello")).state
        val snapshot = JSONObject().put("last_sequence_number", 1)
            .put("text", "tampered")
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state))
        try {
            ResponseIndexedCheckpoint.restore(snapshot)
            fail("Tampering must fail closed")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("mismatch"))
        }
    }
}
