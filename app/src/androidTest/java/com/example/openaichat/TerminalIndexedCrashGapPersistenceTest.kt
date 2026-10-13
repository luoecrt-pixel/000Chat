package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises durable checkpoint -> process/DB reopen -> one-shot terminal claim
 * using the same SQLite APIs called by Activity and WorkManager. Never uses
 * an API key, paid remote request or emulator-side network.
 */
@RunWith(AndroidJUnit4::class)
class TerminalIndexedCrashGapPersistenceTest {
    private fun freshChat() = "crash-gap-" + UUID.randomUUID()

    @Test fun paidCanonicalTerminalSurvivesCrashAndCanBeFinalizedExactlyOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = freshChat()
        val requestId = "req_paid"
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_1")
            .put("encrypted_content", "retained-signed-reasoning")
        val message = JSONObject().put("type", "message").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                .put("text", "corrected")))
        val terminal = JSONObject().put("id", "resp_paid")
            .put("status", "completed")
            .put("output", JSONArray().put(reasoning).put(message))
            .put("usage", JSONObject().put("input_tokens", 21).put("output_tokens", 45))
        val prior = JSONObject().put("role", "user").put("text", "Original prompt")
            .put("request_id", requestId).put("provider", "openai")
        try {
            var state = ResponseEventReducer.reduce(ResponseEventState(),
                JSONObject().put("type", "response.output_text.delta")
                    .put("output_index", 0).put("content_index", 0)
                    .put("delta", "partial")).state
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.completed").put("response", terminal)).state
            assertEquals("corrected", state.text)
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Crash gap")
                db.upsertCurrentChat(chatId, "Crash gap", null, "high", listOf(prior))
                db.setActiveResponse(chatId, "resp_paid", requestId, "",
                    "starting", 1L, "high", "high", false, 10, 0, true)
                db.updateActiveResponse(chatId, "resp_paid", state.text,
                    "completed", 11, requestId, ResponseIndexedCheckpoint.serialize(state))
                val saved = db.getActiveResponse(chatId)!!
                assertEquals("completed", saved.getString("status"))
                assertEquals(11L, saved.getLong("last_sequence_number"))
            }
            // The original Activity/Worker is now gone. Reopen the DB and
            // finalize only from the persisted terminal response, no SSE replay.
            ChatDatabase(context).use { db ->
                val active = db.getActiveResponse(chatId)!!
                val restored = ResponseIndexedCheckpoint.restore(active)
                val output = restored.terminalResponse!!.getJSONArray("output")
                assertEquals("retained-signed-reasoning",
                    output.getJSONObject(0).getString("encrypted_content"))
                assertEquals(45, restored.terminalResponse!!.getJSONObject("usage")
                    .getInt("output_tokens"))
                assertEquals("corrected", restored.text)
                val assistant = JSONObject().put("role", "assistant")
                    .put("provider", "openai").put("request_id", requestId)
                    .put("response_id", "resp_paid").put("status", "completed")
                    .put("text", restored.text).put("openai_protocol_output", output)
                val items = listOf(prior, assistant)
                assertTrue(db.finalizeCurrentChatIfActiveRequest(
                    chatId, "Crash gap", "resp_paid", "high", items, requestId))
                assertFalse("Another terminal owner cannot double commit",
                    db.finalizeCurrentChatIfActiveRequest(
                        chatId, "Crash gap", "resp_paid", "high", items, requestId))
                assertNull(db.getActiveResponse(chatId))
                val restoredChat = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals(2, restoredChat.length())
                assertEquals("corrected", restoredChat.getJSONObject(1).getString("text"))
                assertEquals("retained-signed-reasoning",
                    restoredChat.getJSONObject(1).getJSONArray("openai_protocol_output")
                        .getJSONObject(0).getString("encrypted_content"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

    @Test fun unrecoverableLegacyIndexedContinuationPreservesVisiblePartialOnce() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = freshChat()
        val requestId = "req_old"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Old stream")
                val user = JSONObject().put("role", "user").put("text", "Old prompt")
                    .put("request_id", requestId).put("provider", "openai")
                db.upsertCurrentChat(chatId, "Old stream", null, null, listOf(user))
                db.setActiveResponse(chatId, "resp_old", requestId, "",
                    "starting", 1L, "high", "high", false, 10, 0, true)
                // v23 historical state: only flat text+cursor, no part indices.
                db.updateActiveResponse(chatId, "resp_old", "Hello",
                    "in_progress", 10, requestId)
            }
            ChatDatabase(context).use { db ->
                val old = db.getActiveResponse(chatId)!!
                val recovered = ResponseIndexedCheckpoint.restore(old)
                assertTrue(recovered.legacyFlatCheckpoint)
                try {
                    ResponseEventReducer.reduce(recovered, JSONObject()
                        .put("type", "response.output_text.delta")
                        .put("output_index", 0).put("content_index", 0)
                        .put("delta", " World"))
                    fail("Old cursor must not silently reverse visible text")
                } catch (e: UnrecoverableResponsesCheckpointException) {
                    assertTrue(e.message.orEmpty().contains("legacy flattened"))
                }
                assertTrue(BackgroundRequestFailureRecorder.persist(
                    db, chatId, requestId, "resp_old", "failed",
                    "unrecoverable_checkpoint"))
                assertNull(db.getActiveResponse(chatId))
                assertFalse(BackgroundRequestFailureRecorder.persist(
                    db, chatId, requestId, "resp_old", "failed",
                    "unrecoverable_checkpoint"))
                val history = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals(2, history.length())
                assertEquals("Hello", history.getJSONObject(1).getString("text"))
                assertEquals("failed", history.getJSONObject(1).getString("status"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }
    @Test fun workerFailureWithNoVisibleTextStillPreservesSignedAndReadableReasoning() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = freshChat()
        val requestId = "req_signed_without_text"
        try {
            var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
                .put("type", "response.reasoning_summary_text.delta")
                .put("delta", "Received reasoning summary")).state
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.output_item.done").put("output_index", 0)
                .put("item", JSONObject().put("type", "reasoning")
                    .put("id", "signed_only")
                    .put("encrypted_content", "signed-before-text"))).state
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.output_item.done").put("output_index", 1)
                .put("item", JSONObject().put("type", "web_search_call")
                    .put("id", "tool_before_text").put("status", "completed"))).state
            assertEquals("", state.text)
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Signed no text")
                db.upsertCurrentChat(chatId, "Signed no text", null, null,
                    listOf(JSONObject().put("role", "user").put("text", "Prompt")
                        .put("request_id", requestId).put("provider", "openai")))
                db.setActiveResponse(chatId, "resp_signed_only", requestId, "",
                    "starting", 1L, "high", "high", false, 6, 0, true)
                db.updateActiveResponse(chatId, "resp_signed_only", "",
                    "in_progress", 3, requestId, ResponseIndexedCheckpoint.serialize(state))
            }
            ChatDatabase(context).use { db ->
                assertTrue(BackgroundRequestFailureRecorder.persist(
                    db, chatId, requestId, "resp_signed_only", "failed", "network_broken"))
                assertNull(db.getActiveResponse(chatId))
                val messages = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals("Never discard signed output just because text is empty", 2, messages.length())
                val assistant = messages.getJSONObject(1)
                assertEquals("", assistant.getString("text"))
                assertTrue(assistant.getBoolean("incomplete"))
                assertEquals("Received reasoning summary", assistant.getString("reasoning_summary"))
                val output = assistant.getJSONArray("openai_protocol_output")
                assertEquals("signed-before-text", output.getJSONObject(0)
                    .getString("encrypted_content"))
                assertEquals("tool_before_text", output.getJSONObject(1).getString("id"))
                val replay = OpenAiLocalContextEncoder.encode(
                    listOf(assistant, JSONObject().put("role", "user").put("text", "Next")))
                assertEquals("reasoning", replay.getJSONObject(0).getString("type"))
                assertEquals("web_search_call", replay.getJSONObject(1).getString("type"))
                assertTrue(replay.getJSONObject(2).toString().contains("Received reasoning summary"))
                assertFalse(BackgroundRequestFailureRecorder.persist(
                    db, chatId, requestId, "resp_signed_only", "failed", "duplicate"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

    @Test fun workerFailureAfterReopenPreservesReceivedSignedAndToolItems() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = freshChat()
        val requestId = "req_signed_partial"
        val reasoning = JSONObject().put("type", "reasoning")
            .put("id", "r_paid").put("encrypted_content", "signed-actual-bytes")
        val tool = JSONObject().put("type", "web_search_call")
            .put("id", "tool_paid").put("status", "completed")
        try {
            var state = ResponseEventState()
            for ((index, item) in listOf(reasoning, tool).withIndex()) {
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.output_item.done")
                    .put("output_index", index).put("item", item)).state
            }
            state = ResponseEventReducer.reduce(state, JSONObject()
                .put("type", "response.output_text.delta")
                .put("output_index", 2).put("content_index", 0)
                .put("delta", "Received fragment")).state
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Signed partial")
                db.upsertCurrentChat(chatId, "Signed partial", null, null,
                    listOf(JSONObject().put("role", "user").put("text", "Prompt")
                        .put("request_id", requestId).put("provider", "openai")))
                db.setActiveResponse(chatId, "resp_signed", requestId, "",
                    "starting", 1L, "high", "high", false, 6, 0, true)
                db.updateActiveResponse(chatId, "resp_signed", state.text,
                    "in_progress", 3, requestId, ResponseIndexedCheckpoint.serialize(state))
            }
            ChatDatabase(context).use { db ->
                val before = ResponseIndexedCheckpoint.restore(db.getActiveResponse(chatId)!!)
                assertEquals(2, before.typedOutputItems.size)
                assertTrue(BackgroundRequestFailureRecorder.persist(db, chatId,
                    requestId, "resp_signed", "failed", "disconnect_after_signed_item"))
                assertNull(db.getActiveResponse(chatId))
                val restored = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(1)
                assertEquals("Received fragment", restored.getString("text"))
                assertTrue(restored.getBoolean("incomplete"))
                val output = restored.getJSONArray("openai_protocol_output")
                assertEquals("signed-actual-bytes", output.getJSONObject(0)
                    .getString("encrypted_content"))
                assertEquals("tool_paid", output.getJSONObject(1).getString("id"))
                val replay = OpenAiLocalContextEncoder.encode(
                    listOf(restored, JSONObject().put("role", "user").put("text", "Next")))
                assertEquals("reasoning", replay.getJSONObject(0).getString("type"))
                assertEquals("web_search_call", replay.getJSONObject(1).getString("type"))
                assertTrue(replay.getJSONObject(2).toString().contains("Received fragment"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

}
