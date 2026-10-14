package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class OpenAiEffectiveResponseTest {
    private fun item(type: String, id: String) = JSONObject().put("type", type).put("id", id)

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
