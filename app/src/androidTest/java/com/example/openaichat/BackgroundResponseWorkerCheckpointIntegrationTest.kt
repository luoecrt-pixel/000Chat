package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.ListenableWorker
import androidx.work.Data
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Exercises the production CoroutineWorker.doWork() with actual SQLite and
 * signed terminal output from a crash-gap checkpoint. No mock Worker reducer
 * and no API key or paid HTTP Create is available in this test.
 *
 * This validates Worker-owned local completion; WorkManager scheduler/process
 * death and a live Responses endpoint remain separate acceptance boundaries.
 */
@RunWith(AndroidJUnit4::class)
class BackgroundResponseWorkerCheckpointIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun worker(chatId: String, responseId: String, requestId: String) =
        TestListenableWorkerBuilder<BackgroundResponseWorker>(context)
            .setInputData(Data.Builder()
                .putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId)
                .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, responseId)
                .putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId)
                .build())
            .build()

    @Test fun completedTerminalOnDiskIsFinalizedByRealWorkerWithoutNetworkOrDoubleCommit() {
        val chatId = "worker-terminal-" + UUID.randomUUID()
        val requestId = "req_completed"
        val responseId = "resp_terminal_mock"
        val signed = JSONObject().put("type", "reasoning")
            .put("id", "reasoning_paid").put("encrypted_content", "signed-provider-bytes")
        val tool = JSONObject().put("type", "web_search_call")
            .put("id", "tool_paid").put("status", "completed")
        val message = JSONObject().put("type", "message")
            .put("role", "assistant").put("content", JSONArray()
                .put(JSONObject().put("type", "output_text").put("text", "corrected")))
        val terminal = JSONObject().put("id", responseId)
            .put("status", "completed")
            .put("usage", JSONObject().put("input_tokens", 11).put("output_tokens", 19))
            .put("output", JSONArray().put(signed).put(tool).put(message))
        try {
            var state = ResponseEventReducer.reduce(ResponseEventState(),
                JSONObject().put("type", "response.output_text.delta")
                    .put("output_index", 2).put("content_index", 0)
                    .put("delta", "partial")).state
            state = ResponseEventReducer.reduce(state,
                JSONObject().put("type", "response.completed").put("response", terminal)).state
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Terminal worker")
                db.upsertCurrentChat(chatId, "Terminal worker", null, "high",
                    listOf(JSONObject().put("role", "user").put("text", "Prompt")
                        .put("provider", "openai").put("request_id", requestId)))
                db.setActiveResponse(chatId, responseId, requestId, "",
                    "starting", System.currentTimeMillis(), "high", "high",
                    false, 6, 0, true)
                db.updateActiveResponse(chatId, responseId, state.text,
                    "completed", 5, requestId,
                    ResponseIndexedCheckpoint.serialize(state))
            }
            val result = runBlocking { worker(chatId, responseId, requestId).doWork() }
            assertEquals(ListenableWorker.Result.success(), result)
            ChatDatabase(context).use { db ->
                assertNull("Terminal owner should release the active claim",
                    db.getActiveResponse(chatId))
                val messages = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals(2, messages.length())
                val assistant = messages.getJSONObject(1)
                assertEquals("corrected", assistant.getString("text"))
                assertEquals(responseId, assistant.getString("response_id"))
                assertEquals("signed-provider-bytes", assistant
                    .getJSONArray("openai_protocol_output").getJSONObject(0)
                    .getString("encrypted_content"))
                assertEquals("tool_paid", assistant.getJSONArray("openai_protocol_output")
                    .getJSONObject(1).getString("id"))
            }
            assertEquals(ListenableWorker.Result.success(),
                runBlocking { worker(chatId, responseId, requestId).doWork() })
            ChatDatabase(context).use { db ->
                assertEquals(2, db.getChat(chatId)!!.getJSONArray("messages").length())
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

    @Test fun stoppedOrReplacedOwnerCannotBeRevivedByRealWorker() {
        val chatId = "worker-stopped-" + UUID.randomUUID()
        val oldRequest = "req_stopped"
        val newRequest = "req_new_owner"
        val responseId = "resp_old"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Stopped")
                db.setActiveResponse(chatId, responseId, oldRequest, "",
                    "starting", System.currentTimeMillis(), "high", "high", false, 1, 0, true)
                assertTrue(db.stopActiveRequestOnce(chatId, oldRequest))
                db.setActiveResponse(chatId, "resp_new", newRequest, "",
                    "starting", System.currentTimeMillis(), "high", "high", false, 2, 0, true)
            }
            assertEquals(ListenableWorker.Result.success(),
                runBlocking { worker(chatId, responseId, oldRequest).doWork() })
            ChatDatabase(context).use { db ->
                val active = db.getActiveResponse(chatId)!!
                assertEquals(newRequest, active.getString("request_id"))
                assertEquals("resp_new", active.getString("response_id"))
                assertEquals("starting", active.getString("status"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }
}
