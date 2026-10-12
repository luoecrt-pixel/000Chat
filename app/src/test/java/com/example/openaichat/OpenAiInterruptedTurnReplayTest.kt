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
}
