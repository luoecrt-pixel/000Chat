package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream

class ResponseAccumulatorMemoryBudgetTest {
    @Test fun thousandsOfUnicodeDeltasAndSignedStateStayExactThroughTerminalCheckpoint() {
        val started = System.nanoTime()
        // Positive native-processing fixture within the real heap budget;
        // oversized output is separately covered by full-source refusal tests.
        val characterBudget = Runtime.getRuntime().maxMemory() / 3L / 32L
        // Terminal processing simultaneously owns old/canonical text, typed
        // items and terminal JSON. Keep that combined allocation within the
        // budget while exercising large, escaped signed JSON on small heaps.
        val fragment = "x".repeat(minOf(256L, characterBudget / 12L / 4_096L).toInt()) + "😀\u0000"
        val signedFragment = "opaque-signature-\u0000😀" + "\u0000".repeat(12)
        val signed = signedFragment.repeat(minOf(65_536L,
            characterBudget / 4L / signedFragment.length).toInt())
        val item = JSONObject().put("type", "reasoning").put("encrypted_content", signed)
        var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
            .put("type", "response.output_item.done").put("output_index", 0).put("item", item)).state
        repeat(4_096) {
            state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_text.delta")
                .put("output_index", 1).put("content_index", 0).put("delta", fragment)).state
        }
        val expected = fragment.repeat(4_096)
        assertEquals(expected, state.text)
        val output = JSONArray().put(item).put(JSONObject().put("type", "message")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", expected))))
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.completed")
            .put("response", JSONObject().put("id", "resp_large").put("status", "completed").put("output", output))).state
        val restored = ResponseIndexedCheckpoint.restore(JSONObject().put("text", state.text)
            .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state)))
        assertEquals(expected, restored.text)
        assertEquals(signed, restored.typedOutputItems.getValue(0).getString("encrypted_content"))
        assertEquals(signed, restored.terminalResponse!!.getJSONArray("output")
            .getJSONObject(0).getString("encrypted_content"))
        println("accumulator_profile deltas=4096 textChars=${expected.length} signedChars=${signed.length} " +
            "elapsedMs=${(System.nanoTime() - started) / 1_000_000L}")
    }

    @Test fun smallIndexedDeltasFailBeforeMutationAndRetainTheCompletePaidBody() {
        val signed = "data: " + JSONObject().put("type", "response.output_item.done").put("output_index", 0)
            .put("item", JSONObject().put("type", "reasoning").put("encrypted_content", "signed-exact")) + "\n\n"
        val delta = "data: " + JSONObject().put("type", "response.output_text.delta")
            .put("output_index", 1).put("content_index", 0).put("delta", "x".repeat(256)) + "\n\n"
        val terminal = "data: {\"type\":\"response.completed\",\"response\":{\"id\":\"resp_tail\",\"status\":\"completed\"}}\n\n"
        val bytes = (signed + delta.repeat(32) + terminal).toByteArray()
        val root = kotlin.io.path.createTempDirectory("accumulator-body-").toFile()
        var state = ResponseEventState()
        var callbacks = 0
        try {
            val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", "request")
            assertThrows(ResponsesSseLimitException::class.java) {
                OpenAiSseUtf8Reader.open(archive).use { reader ->
                    OpenAiReceivedSseReader.read(reader, archive) { event ->
                        state = ResponseEventReducer.reduce(state, event, 64 * 1024L).state
                        callbacks++
                        true
                    }
                }
            }
            assertTrue(callbacks > 1 && callbacks < 33)
            assertEquals("x".repeat((callbacks - 1) * 256), state.text)
            assertNull(state.terminalState)
            assertEquals("signed-exact", state.typedOutputItems.getValue(0).getString("encrypted_content"))
            assertArrayEquals(bytes, archive.file.readBytes())
            val restored = ResponseIndexedCheckpoint.restore(JSONObject().put("text", state.text)
                .put("indexed_checkpoint", ResponseIndexedCheckpoint.serialize(state)))
            assertEquals(state.text, restored.text)
            assertEquals("signed-exact", restored.typedOutputItems.getValue(0).getString("encrypted_content"))
        } finally { root.deleteRecursively() }
    }

    @Test fun refusalCorrectionFitsWithoutChargingTheReplacedTextTwice() {
        val initial = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
            .put("type", "response.output_text.delta").put("output_index", 0).put("content_index", 0)
            .put("delta", "x".repeat(1_000)), 40 * 1024L).state
        val corrected = ResponseEventReducer.reduce(initial, JSONObject().put("type", "response.refusal.done")
            .put("output_index", 0).put("content_index", 0).put("refusal", "corrected"), 40 * 1024L).state
        assertEquals("corrected", corrected.text)
        assertEquals("x".repeat(1_000), initial.text)
        assertThrows(ResponsesSseLimitException::class.java) {
            ResponseEventReducer.reduce(corrected, JSONObject().put("type", "response.output_text.delta")
                .put("output_index", 1).put("content_index", 0).put("delta", "y".repeat(2_000)), 40 * 1024L)
        }
        assertEquals("corrected", corrected.text)
    }

    @Test fun signedItemsAndReasoningSummaryShareTheRetainedStateBudget() {
        val initial = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
            .put("type", "response.output_item.done").put("output_index", 0)
            .put("item", JSONObject().put("type", "reasoning").put("encrypted_content", "s".repeat(1_000))),
            64 * 1024L).state
        assertThrows(ResponsesSseLimitException::class.java) {
            ResponseEventReducer.reduce(initial, JSONObject().put("type", "response.reasoning_summary_text.delta")
                .put("delta", "r".repeat(2_000)), 64 * 1024L)
        }
        assertThrows(ResponsesSseLimitException::class.java) {
            ResponseEventReducer.reduce(initial, JSONObject().put("type", "response.output_item.done")
                .put("output_index", 1).put("item", JSONObject().put("type", "reasoning")
                    .put("encrypted_content", "t".repeat(1_000))), 64 * 1024L)
        }
        val corrected = ResponseEventReducer.reduce(initial, JSONObject().put("type", "response.output_item.done")
            .put("output_index", 0).put("item", JSONObject().put("type", "reasoning")
                .put("encrypted_content", "replacement")), 64 * 1024L).state
        assertEquals("replacement", corrected.typedOutputItems.getValue(0).getString("encrypted_content"))
        assertEquals("s".repeat(1_000), initial.typedOutputItems.getValue(0).getString("encrypted_content"))
        assertEquals("", initial.reasoningSummary)
    }

    @Test fun terminalTextAndStructureShareBudgetWithReceivedTypedItemsBeforeCanonicalAllocation() {
        val signed = JSONObject().put("type", "reasoning").put("id", "rs_paid")
            .put("encrypted_content", "s".repeat(1_000))
        val initial = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_owned"),
            JSONObject().put("type", "response.output_item.done").put("output_index", 0)
                .put("item", signed), 64 * 1024L).state
        val terminal = JSONObject().put("id", "resp_owned").put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "message")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                    .put("text", "c".repeat(700))))))
        val event = JSONObject().put("type", "response.completed").put("response", terminal)
        // The terminal on its own is small enough. The retained signed item
        // makes the simultaneous allocation unsafe and must survive refusal.
        assertEquals("c".repeat(700), ResponseEventReducer.reduce(
            ResponseEventState(responseId = "resp_owned"), event, 64 * 1024L).state.text)
        assertThrows(ResponsesSseLimitException::class.java) {
            ResponseEventReducer.reduce(initial, event, 64 * 1024L)
        }
        assertNull(initial.terminalResponse)
        assertEquals("s".repeat(1_000), initial.typedOutputItems.getValue(0)
            .getString("encrypted_content"))
    }

    @Test fun oversizedCanonicalTerminalRefusesAllTerminalKindsAndArchivesCompleteSource() {
        for (type in listOf("response.completed", "response.incomplete", "response.failed",
            "response.cancelled", "error", "response.error")) {
            val response = JSONObject().put("id", "resp_owned").put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "message")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                        .put("text", "c".repeat(5_000))))))
            val prefix = JSONObject().put("type", "response.output_text.delta")
                .put("output_index", 0).put("content_index", 0).put("delta", "received partial")
            val terminal = JSONObject().put("type", type).put("response", response)
                .put("error", JSONObject().put("message", "provider failure"))
            val bytes = ("data: $prefix\n\ndata: $terminal\n\n" +
                "data: {\"type\":\"response.output_text.delta\",\"delta\":\"raw tail\"}\n\n").toByteArray()
            val root = kotlin.io.path.createTempDirectory("terminal-budget-").toFile()
            var state = ResponseEventState(responseId = "resp_owned")
            try {
                val archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), root, "chat", type)
                assertThrows(type, ResponsesSseLimitException::class.java) {
                    OpenAiSseUtf8Reader.open(archive).use { reader ->
                        OpenAiReceivedSseReader.read(reader, archive) { event ->
                            state = ResponseEventReducer.reduce(state, event, 64 * 1024L).state
                            true
                        }
                    }
                }
                assertEquals("received partial", state.text)
                assertNull(state.terminalState)
                assertArrayEquals(bytes, archive.file.readBytes())
            } finally { root.deleteRecursively() }
        }
    }
}
