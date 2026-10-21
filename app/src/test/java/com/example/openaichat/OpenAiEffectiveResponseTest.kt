package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiEffectiveResponseTest {
    private fun item(type: String, id: String) = JSONObject().put("type", type).put("id", id)

    @Test fun compactedTerminalIndexesKeepDistinctPaidItemsInStreamOrder() {
        val first = item("reasoning", "item_1").put("encrypted_content", "paid-signed-bytes")
        val canonical = item("message", "item_2").put("status", "completed")
        val third = item("web_search_call", "item_3").put("status", "completed")
        val fourth = item("image_generation_call", "item_4").put("result", "paid-image")
        val terminal = JSONObject().put("status", "incomplete")
            .put("output", JSONArray().put(canonical).put(JSONObject.NULL))
        val state = ResponseEventState(typedOutputItems = mapOf(
            4 to fourth, 2 to third, 1 to item("message", "item_2"), 0 to first,
            7 to item("reasoning", "item_1").put("encrypted_content", "duplicate")))

        val output = OpenAiEffectiveResponse.assemble(terminal, state).getJSONArray("output")

        assertEquals(5, output.length())
        assertSame(canonical, output.getJSONObject(0))
        assertTrue(output.isNull(1))
        assertSame(first, output.getJSONObject(2))
        assertEquals("paid-signed-bytes", output.getJSONObject(2).getString("encrypted_content"))
        assertSame(third, output.getJSONObject(3))
        assertSame(fourth, output.getJSONObject(4))
        assertEquals(2, terminal.getJSONArray("output").length())
        assertEquals(5, state.typedOutputItems.size)
    }

    @Test fun idlessItemsOnlyYieldToCanonicalOriginalIndexes() {
        val anonymousCanonical = JSONObject().put("type", "message").put("content", "canonical")
        val anonymousReceived = JSONObject().put("type", "message").put("content", "received")
        val terminal = JSONObject().put("output", JSONArray().put(anonymousCanonical))
        val output = OpenAiEffectiveResponse.assemble(terminal, ResponseEventState(typedOutputItems = mapOf(
            0 to item("reasoning", "distinct"), 1 to anonymousReceived))).getJSONArray("output")
        assertEquals(3, output.length())
        assertSame(anonymousCanonical, output.getJSONObject(0))
        assertEquals("distinct", output.getJSONObject(1).getString("id"))
        assertSame(anonymousReceived, output.getJSONObject(2))

        val collision = OpenAiEffectiveResponse.assemble(terminal,
            ResponseEventState(typedOutputItems = mapOf(0 to anonymousReceived))).getJSONArray("output")
        assertEquals(1, collision.length())
        assertSame(anonymousCanonical, collision.getJSONObject(0))
    }

    @Test fun terminalNullSlotsDoNotLetAppendedItemsOverwriteLaterCanonicalOutput() {
        val terminalItem = item("message", "canonical-tail")
        val terminal = JSONObject().put("output", JSONArray().put(0, item("reasoning", "canonical-head"))
            .put(2, terminalItem))
        val received = item("image_generation_call", "paid-image").put("result", "exact-bytes")
        val output = OpenAiEffectiveResponse.assemble(terminal,
            ResponseEventState(typedOutputItems = mapOf(0 to received))).getJSONArray("output")
        assertEquals(4, output.length())
        assertTrue(output.isNull(1))
        assertSame(terminalItem, output.getJSONObject(2))
        assertSame(received, output.getJSONObject(3))
    }

    @Test fun canonicalOutputWinsByIndexAndByIdWithoutDuplicatingReceivedItems() {
        val canonical = item("reasoning", "canonical").put("encrypted_content", "final-signed")
        val moved = item("web_search_call", "moved").put("status", "completed")
        val terminal = JSONObject().put("id", "response").put("status", "incomplete")
            .put("output", JSONArray().put(canonical).put(moved))
        val state = ResponseEventState(typedOutputItems = mapOf(
            0 to item("reasoning", "canonical").put("encrypted_content", "old-signed"),
            3 to item("web_search_call", "moved"),
            4 to item("file_search_call", "missing").put("status", "completed")))

        val effective = OpenAiEffectiveResponse.assemble(terminal, state)
        val output = effective.getJSONArray("output")
        assertEquals(3, output.length())
        assertEquals("final-signed", output.getJSONObject(0).getString("encrypted_content"))
        assertEquals("moved", output.getJSONObject(1).getString("id"))
        assertEquals("missing", output.getJSONObject(2).getString("id"))
        assertEquals(2, terminal.getJSONArray("output").length())
        assertEquals("old-signed", state.typedOutputItems.getValue(0).getString("encrypted_content"))
    }

    @Test fun missingTerminalOutputRetainsImagesToolsAndSummaryBeforeParsing() {
        val signed = item("reasoning", "signed").put("encrypted_content", "exact-paid-bytes")
            .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", "received summary")))
        val state = ResponseEventState(responseId = "response", terminalState = ResponseTerminalState.CANCELLED,
            typedOutputItems = mapOf(3 to item("image_generation_call", "image").put("result", "paid-image"),
                1 to signed, 2 to item("web_search_call", "tool")))
        val effective = OpenAiEffectiveResponse.assemble(JSONObject().put("status", "cancelled"), state)
        val parsed = AstraResponseModel.fromResponse(effective, "paid partial")
        assertEquals("response", parsed.id)
        assertEquals("cancelled", parsed.status)
        assertEquals(listOf("paid-image"), parsed.generatedImageResults)
        assertTrue(parsed.tools.contains("🌐 Web Search"))
        assertEquals("received summary", parsed.reasoningSummary)
        assertEquals("exact-paid-bytes", effective.getJSONArray("output").getJSONObject(0).getString("encrypted_content"))
    }

    @Test fun terminalEmptyOutputAndDisconnectedCreateBothRetainEveryReceivedTypedItem() {
        val state = ResponseEventState(responseId = "partial", typedOutputItems = mapOf(
            7 to item("computer_call", "computer"), 2 to item("reasoning", "reasoning")))
        for (terminal in listOf(null, JSONObject().put("output", JSONArray()))) {
            val effective = OpenAiEffectiveResponse.assemble(terminal, state)
            assertEquals("partial", effective.getString("id"))
            assertEquals("reasoning", effective.getJSONArray("output").getJSONObject(0).getString("id"))
            assertEquals("computer", effective.getJSONArray("output").getJSONObject(1).getString("id"))
            assertFalse(effective.has("status"))
        }
    }

    @Test fun receivedDoneMessageKeepsRefusalAndAnnotationsWhenTerminalOmitsOutput() {
        val message = item("message", "paid_message").put("role", "assistant")
            .put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", "partial")
                    .put("annotations", JSONArray().put(JSONObject().put("type", "url_citation")
                        .put("url", "https://example.invalid/paid").put("title", "received source"))))
                .put(JSONObject().put("type", "refusal").put("refusal", "received refusal")))
        val effective = OpenAiEffectiveResponse.assemble(JSONObject().put("status", "incomplete"),
            ResponseEventState(typedOutputItems = mapOf(5 to message)))
        val outputMessage = effective.getJSONArray("output").getJSONObject(0)
        assertEquals(message.toString(), outputMessage.toString())
        val parsed = AstraResponseModel.fromResponse(effective, "partial")
        assertEquals("https://example.invalid/paid", parsed.annotations.getJSONObject(0).getString("url"))
        assertEquals("received refusal", outputMessage.getJSONArray("content").getJSONObject(1).getString("refusal"))
    }
}
