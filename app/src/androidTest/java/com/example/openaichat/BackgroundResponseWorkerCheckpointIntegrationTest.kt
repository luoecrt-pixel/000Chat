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

    @Test fun everyTerminalEventSurvivesSQLiteReopenAndRealWorkerFinalizesItOnlyOnce() {
        for (type in listOf("response.completed", "response.incomplete", "response.failed",
            "response.cancelled", "error", "response.error")) {
            val chatId = "worker-six-terminals-" + UUID.randomUUID()
            val requestId = "req_" + type
            val responseId = "resp_" + type
            val status = when (type) {
                "error", "response.error" -> "failed"
                else -> type.removePrefix("response.")
            }
            try {
                var state = ResponseEventState(responseId = responseId)
                val signed = JSONObject().put("type", "reasoning").put("id", "rs_paid")
                    .put("encrypted_content", "signed-exact-$type")
                val tool = JSONObject().put("type", "web_search_call").put("id", "tool_paid")
                    .put("status", "completed")
                for ((index, item) in listOf(signed, tool).withIndex()) {
                    state = ResponseEventReducer.reduce(state, JSONObject()
                        .put("type", "response.output_item.done").put("output_index", index)
                        .put("item", item)).state
                }
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.reasoning_summary_text.delta")
                    .put("delta", "Readable received summary")).state
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.output_text.delta").put("output_index", 2)
                    .put("content_index", 0).put("delta", "paid partial")).state
                val receivedMessage = JSONObject().put("type", "message").put("id", "msg_paid")
                    .put("role", "assistant").put("content", JSONArray()
                        .put(JSONObject().put("type", "output_text").put("text", "paid ")
                            .put("annotations", JSONArray().put(JSONObject().put("type", "url_citation")
                                .put("url", "https://example.test/source").put("title", "Paid citation"))))
                        .put(JSONObject().put("type", "refusal").put("refusal", "partial")))
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.output_item.done").put("output_index", 2)
                    .put("item", receivedMessage)).state
                val event = JSONObject().put("type", type)
                if (type == "error" || type == "response.error") {
                    event.put("error", JSONObject().put("code", "server_error")
                        .put("message", "provider failure"))
                } else {
                    // A valid terminal may omit output already received in
                    // done events. Finalization must merge the stored fallback.
                    event.put("response", JSONObject().put("id", responseId).put("status", status)
                        .put("usage", JSONObject().put("input_tokens", 13).put("output_tokens", 23))
                        .apply {
                            if (status == "failed") put("error", JSONObject().put("message", "provider failure"))
                            if (status == "incomplete") put("incomplete_details", JSONObject().put("reason", "max_output_tokens"))
                        })
                }
                state = ResponseEventReducer.reduce(state, event).state
                ChatDatabase(context).use { db ->
                    db.ensureChat(chatId, "Six terminal kinds")
                    db.upsertCurrentChat(chatId, "Six terminal kinds", null, "high",
                        listOf(JSONObject().put("role", "user").put("text", "Prompt")
                            .put("provider", "openai").put("request_id", requestId)))
                    db.setActiveResponse(chatId, responseId, requestId, "", "starting",
                        System.currentTimeMillis(), "high", "high", false, 6, 0, true)
                    db.updateActiveResponse(chatId, responseId, state.text, status, 6,
                        requestId, ResponseIndexedCheckpoint.serialize(state))
                }
                ChatDatabase(context).use { db ->
                    val active = db.getActiveResponse(chatId)!!
                    assertEquals(type, status, active.getString("status"))
                    val reopened = ResponseIndexedCheckpoint.restore(active)
                    assertEquals(type, status, reopened.terminalState!!.wireStatus)
                    assertEquals(responseId, reopened.terminalResponse!!.getString("id"))
                    assertEquals("paid partial", reopened.text)
                    if (status == "failed") assertEquals("provider failure", reopened.completionError)
                }
                val expectedResult = if (status == "failed") ListenableWorker.Result.failure()
                    else ListenableWorker.Result.success()
                assertEquals(type, expectedResult, runBlocking { worker(chatId, responseId, requestId).doWork() })
                ChatDatabase(context).use { db ->
                    assertNull(type, db.getActiveResponse(chatId))
                    val chat = db.getChat(chatId)!!
                    val messages = chat.getJSONArray("messages")
                    assertEquals(type, 2, messages.length())
                    val assistant = messages.getJSONObject(1)
                    assertEquals(type, status, assistant.getString("status"))
                    assertEquals("paid partial", assistant.getString("text"))
                    assertEquals("Readable received summary", assistant.getString("reasoning_summary"))
                    assertEquals("signed-exact-$type", assistant.getJSONArray("openai_protocol_output")
                        .getJSONObject(0).getString("encrypted_content"))
                    assertEquals("tool_paid", assistant.getJSONArray("openai_protocol_output")
                        .getJSONObject(1).getString("id"))
                    assertTrue(assistant.getJSONArray("tools").toString().contains("Web Search"))
                    val retainedMessage = assistant.getJSONArray("openai_protocol_output").getJSONObject(2)
                    assertEquals("message", retainedMessage.getString("type"))
                    assertEquals("partial", retainedMessage.getJSONArray("content")
                        .getJSONObject(1).getString("refusal"))
                    assertEquals("https://example.test/source", assistant.getJSONArray("annotations")
                        .getJSONObject(0).getString("url"))
                    val replay = OpenAiLocalContextEncoder.encode(listOf(assistant))
                    assertEquals(receivedMessage.toString(), replay.getJSONObject(2).toString())
                    val occurrences = Regex("paid partial").findAll(replay.toString()).count()
                    assertEquals("Interrupted marker must not repeat structured visible text", 0, occurrences)
                    if (status == "completed") assertEquals(responseId, JsonProtocol.nonBlank(chat, "response_id"))
                    else assertNull("Only completed terminals can extend the server chain",
                        JsonProtocol.nonBlank(chat, "response_id"))
                }
                assertEquals(ListenableWorker.Result.success(),
                    runBlocking { worker(chatId, responseId, requestId).doWork() })
                ChatDatabase(context).use { db ->
                    assertEquals(type, 2, db.getChat(chatId)!!.getJSONArray("messages").length())
                }
            } finally { ChatDatabase(context).use { it.deleteChat(chatId) } }
        }
    }

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

    @Test fun damagedTerminalWithoutProviderResponseFailsClosedAfterSQLiteReopen() {
        val chatId = "worker-damaged-terminal-" + UUID.randomUUID()
        val requestId = "req_damaged_terminal"
        val responseId = "resp_damaged_terminal"
        try {
            val partial = ResponseEventReducer.reduce(ResponseEventState(responseId = responseId),
                JSONObject().put("type", "response.output_text.delta").put("output_index", 0)
                    .put("content_index", 0).put("delta", "paid fragment")).state
            val damaged = JSONObject(ResponseIndexedCheckpoint.serialize(partial))
                .put("terminal_state", "completed") // canonical response was lost
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Damaged terminal")
                db.upsertCurrentChat(chatId, "Damaged terminal", null, "high",
                    listOf(JSONObject().put("role", "user").put("text", "Prompt")
                        .put("provider", "openai").put("request_id", requestId)))
                db.setActiveResponse(chatId, responseId, requestId, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 6, 0, true)
                db.updateActiveResponse(chatId, responseId, partial.text, "completed", 5,
                    requestId, damaged.toString())
            }
            assertEquals(ListenableWorker.Result.failure(),
                runBlocking { worker(chatId, responseId, requestId).doWork() })
            ChatDatabase(context).use { db ->
                assertNull(db.getActiveResponse(chatId))
                val chat = db.getChat(chatId)!!
                assertNull(JsonProtocol.nonBlank(chat, "response_id"))
                val assistant = chat.getJSONArray("messages").getJSONObject(1)
                assertEquals("paid fragment", assistant.getString("text"))
                assertEquals("failed", assistant.getString("status"))
                assertTrue(assistant.getString("error").contains("not preserved"))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chatId) } }
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
    @Test fun preIdStopAndLaterKnownIdCannotResurrectOldChat() {
        val chatId = "worker-preid-" + UUID.randomUUID()
        val requestId = "pre_id_stopped"
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Stopped before response.created")
                db.setActiveResponse(chatId, null, requestId, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 1, 0, true)
                assertTrue("Stop must consume pending local ownership",
                    db.stopActiveRequestOnce(chatId, requestId))
                assertNull(db.getActiveResponse(chatId))
            }
            // No provider response_id existed when Stop won. A late worker
            // callback supplied with an ID must not resurrect a deleted claim.
            assertEquals(ListenableWorker.Result.success(),
                runBlocking { worker(chatId, "resp_created_too_late", requestId).doWork() })
            ChatDatabase(context).use { db ->
                assertNull(db.getActiveResponse(chatId))
                assertEquals(0, db.getChat(chatId)!!.getJSONArray("messages").length())
                val stopped = db.listRequestAccounting(chatId)
                    .firstOrNull { it.optString("local_request_id") == requestId }
                assertEquals("stopped", stopped?.optString("local_status"))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

}
