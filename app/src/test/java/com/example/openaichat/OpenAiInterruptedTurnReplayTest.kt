package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class OpenAiInterruptedTurnReplayTest {
    @Test
    fun interruptedStreamCanResumeOnExplicitNextSendWithoutLosingReceivedFragments() {
        val partial = "  Exact streamed answer\nwith final spaces  "
        val summary = "Received summary\nline 2"
        val prior = JSONObject().put("role", "assistant").put("provider", "openai")
            .put("status", "disconnected").put("incomplete", true)
            .put("response_id", "resp_interrupted")
            .put("text", partial).put("reasoning_summary", summary)
            .put("tools", JSONArray().put("🌐 Web Search"))
        val history = listOf(
            JSONObject().put("role", "user").put("text", "Original request"),
            prior,
            JSONObject().put("role", "user").put("text", "Please continue")
        )
        val replay = OpenAiLocalContextEncoder.encode(history)
        assertEquals(3, replay.length())
        val synthetic = replay.getJSONObject(1)
        assertEquals("assistant", synthetic.getString("role"))
        val transcript = synthetic.getString("content")
        assertTrue(transcript.contains("did NOT complete successfully"))
        assertTrue(transcript.contains(partial))
        assertTrue(transcript.contains(summary))
        assertTrue(transcript.contains("🌐 Web Search"))
        assertTrue(transcript.contains("cannot be reconstructed"))
        assertTrue(transcript.contains("Do not retry or regenerate"))
        assertEquals("disconnected", prior.getString("status"))
        assertEquals(partial, prior.getString("text"))
        assertFalse(prior.has("openai_protocol_output"))
    }

    @Test
    fun interruptedWithNoVisibleTextStillHasExplicitStatusNotSilentDeletion() {
        for (status in listOf("failed", "incomplete", "disconnected")) {
            val prior = JSONObject().put("role", "assistant").put("provider", "openai")
                .put("status", status).put("text", "")
            val encoded = OpenAiLocalContextEncoder.encode(listOf(prior))
            assertEquals(1, encoded.length())
            assertTrue(encoded.getJSONObject(0).getString("content").contains(status))
        }
    }

    @Test
    fun completedLegacyWithoutFullOutputIsStillRejectedBeforeHttp() {
        val legacy = JSONObject().put("role", "assistant").put("provider", "openai")
            .put("status", "completed").put("text", "Visible text")
        try {
            OpenAiLocalContextEncoder.encode(listOf(legacy))
            fail("Missing completed protocol is not an interrupted-stream recovery")
        } catch (e: IOException) {
            assertTrue(e.message.orEmpty().contains("полного протокольного вывода"))
        }
    }

    @Test
    fun signedCompletedToolItemsAreNeverDroppedOnInterruptedTerminal() {
        val output = JSONArray()
            .put(JSONObject().put("id", "rsn_1").put("type", "reasoning")
                .put("encrypted_content", "signed-original-opaque-content"))
            .put(JSONObject().put("id", "tool_1").put("type", "web_search_call")
                .put("status", "completed"))
        val interrupted = JSONObject().put("role", "assistant").put("provider", "openai")
            .put("status", "incomplete").put("text", "Partially answered")
            .put("openai_protocol_output", output)
        val replay = OpenAiLocalContextEncoder.encode(listOf(interrupted))
        assertEquals(3, replay.length())
        assertEquals("signed-original-opaque-content",
            replay.getJSONObject(0).getString("encrypted_content"))
        assertEquals("tool_1", replay.getJSONObject(1).getString("id"))
        assertTrue(replay.getJSONObject(2).getString("content").contains("Partially answered"))
        assertEquals(2, output.length())
    }

    @Test
    fun incompleteTypedMessageKeepsAnnotationsWithoutRepeatingVisibleText() {
        val text = "Already received answer"
        val item = typedMessage("msg_1", JSONObject().put("type", "output_text")
            .put("text", text).put("annotations", JSONArray().put(JSONObject()
                .put("type", "url_citation").put("url", "https://example.test/source")
                .put("title", "Source").put("start_index", 0).put("end_index", 7))))
            .put("phase", "final").put("status", "incomplete")
        val prior = interrupted(text, JSONArray().put(item))
        val original = prior.toString()

        val replay = OpenAiLocalContextEncoder.encode(listOf(prior))

        assertEquals(2, replay.length())
        assertEquals(item.toString(), replay.getJSONObject(0).toString())
        val marker = replay.getJSONObject(1).getString("content")
        assertTrue(marker.contains("Interrupted OpenAI assistant response: incomplete"))
        assertTrue(marker.contains("did NOT complete successfully"))
        assertFalse(marker.contains(text))
        assertEquals(original, prior.toString())
    }

    @Test
    fun incompleteTypedPrefixAddsOnlyExactUnrepresentedPartialTailAndSummary() {
        val prefix = "Already represented prefix"
        val tail = "\n  Partial tail with final spaces  "
        val summary = "Received but not stored in typed output"
        val item = typedMessage("msg_1", JSONObject().put("type", "output_text").put("text", prefix))
        val prior = interrupted(prefix + tail, JSONArray().put(item)).put("reasoning_summary", summary)

        val replay = OpenAiLocalContextEncoder.encode(listOf(prior))

        assertEquals(item.toString(), replay.getJSONObject(0).toString())
        val marker = replay.getJSONObject(1).getString("content")
        assertFalse(marker.contains(prefix))
        assertTrue(marker.contains(tail))
        assertTrue(marker.contains(summary))
        assertTrue(marker.contains("Additional received assistant fragments"))
    }

    @Test
    fun incompleteMultipleMessagesAndRefusalKeepAllStructuredPartsWithoutTextDuplicates() {
        val first = "First received message"
        val refusal = "I cannot provide that information."
        val last = "Final received message"
        val output = JSONArray()
            .put(typedMessage("msg_1", JSONObject().put("type", "output_text").put("text", first)))
            .put(typedMessage("msg_2", JSONObject().put("type", "refusal").put("refusal", refusal)))
            .put(typedMessage("msg_3", JSONObject().put("type", "output_text").put("text", last)))
        val original = output.toString()

        val replay = OpenAiLocalContextEncoder.encode(listOf(interrupted(first + refusal + last, output)))

        assertEquals(4, replay.length())
        for (index in 0 until output.length()) {
            assertEquals(output.getJSONObject(index).toString(), replay.getJSONObject(index).toString())
        }
        val marker = replay.getJSONObject(3).getString("content")
        assertFalse(marker.contains(first))
        assertFalse(marker.contains(refusal))
        assertFalse(marker.contains(last))
        assertTrue(marker.contains("did NOT complete successfully"))
        assertEquals(original, output.toString())
    }

    @Test
    fun partialFragmentBetweenTypedMessagesIsKeptWithoutRepeatingEitherMessage() {
        val first = "First message"
        val second = "Second message"
        val fragment = "\n  delta missing from output  \n"
        val output = JSONArray()
            .put(typedMessage("msg_1", JSONObject().put("type", "output_text").put("text", first)))
            .put(typedMessage("msg_2", JSONObject().put("type", "output_text").put("text", second)))

        val replay = OpenAiLocalContextEncoder.encode(listOf(interrupted(first + fragment + second, output)))

        val marker = replay.getJSONObject(2).getString("content")
        assertFalse(marker.contains(first))
        assertFalse(marker.contains(second))
        assertTrue(marker.contains(fragment))
    }

    @Test
    fun typedReasoningAndToolOnlyRetainVisiblePartialAndUnrepresentedSummaryTail() {
        val reasoning = "Received summary prefix"
        val summaryTail = "\n  Received summary tail  "
        val text = "Visible partial without a typed message"
        val output = JSONArray()
            .put(JSONObject().put("id", "rsn_1").put("type", "reasoning")
                .put("encrypted_content", "opaque-original")
                .put("summary", JSONArray().put(JSONObject().put("type", "summary_text").put("text", reasoning))))
            .put(JSONObject().put("id", "tool_1").put("type", "web_search_call").put("status", "completed"))
        val prior = interrupted(text, output).put("reasoning_summary", reasoning + summaryTail)

        val replay = OpenAiLocalContextEncoder.encode(listOf(prior))

        assertEquals(3, replay.length())
        assertEquals(output.getJSONObject(0).toString(), replay.getJSONObject(0).toString())
        assertEquals(output.getJSONObject(1).toString(), replay.getJSONObject(1).toString())
        val marker = replay.getJSONObject(2).getString("content")
        assertTrue(marker.contains(text))
        assertTrue(marker.contains(summaryTail))
        assertFalse(marker.contains(reasoning))
    }

    @Test
    fun typedMessageLongerThanReceivedPartialDoesNotRepeatItsReceivedPrefix() {
        val output = JSONArray().put(typedMessage("msg_1", JSONObject().put("type", "output_text")
            .put("text", "Received prefix and authoritative terminal tail")))

        val replay = OpenAiLocalContextEncoder.encode(listOf(interrupted("Received prefix", output)))

        assertEquals(output.getJSONObject(0).toString(), replay.getJSONObject(0).toString())
        assertFalse(replay.getJSONObject(1).getString("content").contains("Received prefix"))
    }

    @Test
    fun repeatedTextInNewPartialTailIsPreservedAfterTheOwnedOccurrence() {
        val repeated = "Repeated phrase"
        val output = JSONArray().put(typedMessage("msg_1", JSONObject().put("type", "output_text")
            .put("text", repeated)))

        val replay = OpenAiLocalContextEncoder.encode(listOf(interrupted(repeated + repeated, output)))

        // The second occurrence is a received tail, not the text owned by msg_1.
        val marker = replay.getJSONObject(1).getString("content")
        assertTrue(marker.contains("\n" + repeated + "\n[End of received assistant text]"))
    }

    @Test
    fun typedReasoningSummaryAlreadyRepresentedIsNotRepeatedInTheMarker() {
        val first = "First summary"
        val second = "Second summary"
        val output = JSONArray().put(JSONObject().put("type", "reasoning")
            .put("encrypted_content", "opaque-original")
            .put("summary", JSONArray()
                .put(JSONObject().put("type", "summary_text").put("text", first))
                .put(JSONObject().put("type", "summary_text").put("text", second))))
        val prior = interrupted("Visible partial", output).put("reasoning_summary", first + "\n\n" + second)

        val replay = OpenAiLocalContextEncoder.encode(listOf(prior))

        val marker = replay.getJSONObject(1).getString("content")
        assertTrue(marker.contains("Visible partial"))
        assertFalse(marker.contains(first))
        assertFalse(marker.contains(second))
        assertFalse(marker.contains("Received reasoning summary"))
    }

    private fun typedMessage(id: String, part: JSONObject): JSONObject =
        JSONObject().put("type", "message").put("id", id).put("role", "assistant")
            .put("content", JSONArray().put(part))

    private fun interrupted(text: String, output: JSONArray): JSONObject =
        JSONObject().put("role", "assistant").put("provider", "openai")
            .put("status", "incomplete").put("text", text).put("openai_protocol_output", output)
}
