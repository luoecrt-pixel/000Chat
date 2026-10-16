package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class BackgroundRequestFailureRecorderTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun prepared(db: ChatDatabase, chatId: String = "chat", activeText: String = "partial text"): String {
        val requestId = "local_worker_request"
        db.ensureChat(chatId)
        db.upsertCurrentChat(chatId, "Worker chat", null, "high", listOf(
            JSONObject().put("role", "user").put("text", "original prompt")
                .put("request_id", requestId).put("provider", "openai")
                .put("requested_model", "test-actual-model")
        ))
        db.setActiveResponse(
            chatId = chatId, responseId = "resp_x", requestId = requestId,
            text = activeText, status = "in_progress",
            startedAt = System.currentTimeMillis(), effortPref = "high",
            effectiveEffort = "high", autoSelected = false,
            promptChars = 15, attachments = 0, backgroundEnabled = true
        )
        assertTrue(db.startRequestAccounting(chatId, requestId, "openai",
            "test-actual-model", "openai_api_key"))
        return requestId
    }

    @Test fun timeoutAuthGoneAndFatalRetainPartialAndReportedOutcome() {
        for ((status, error) in listOf(
            "failed" to "recovery_timeout",
            "failed" to "recovery_auth_401",
            "unknown" to "response_gone_404",
            "failed" to "recovery_http_418"
        )) {
            val chatId = "chat_${status}_${error}"
            ChatDatabase(context).use { db ->
                val id = prepared(db, chatId)
                assertTrue(BackgroundRequestFailureRecorder.persist(
                    db, chatId, id, "resp_x", status, error
                ))
                db.clearActiveResponse(chatId, expectedRequestId = id)
                val chat = db.getChat(chatId)!!
                val assistant = chat.getJSONArray("messages").getJSONObject(1)
                assertEquals("partial text", assistant.getString("text"))
                assertEquals(status, assistant.getString("status"))
                assertEquals(error, assistant.getString("error"))
                assertEquals("test-actual-model", assistant.getString("requested_model"))
                val event = db.listRequestEvents(chatId).single()
                assertEquals("openai", event.getString("provider"))
                assertEquals("test-actual-model", event.getString("requested_model"))
                assertEquals(error, event.getString("error"))
                val row = db.listRequestAccounting(chatId).single()
                assertEquals(status, row.getString("local_status"))
                assertEquals("unknown", row.getString("cost_kind"))
                assertFalse(row.has("cost_usd"))
                assertFalse(BackgroundRequestFailureRecorder.persist(
                    db, chatId, id, "resp_x", status, error
                ))
                assertEquals(1, db.listRequestEvents(chatId).size)
            }
        }
    }

    @Test fun stoppedOrDeletedRequestMustNeverRegenerateAssistantBubble() {
        ChatDatabase(context).use { db ->
            val stopped = prepared(db, "stopped")
            db.markRequestAccountingStopped("stopped", stopped)
            db.clearActiveResponse("stopped", stopped)
            assertFalse(BackgroundRequestFailureRecorder.persist(
                db, "stopped", stopped, "resp_x", "failed", "late_callback"))
            assertEquals(1, db.getChat("stopped")!!.getJSONArray("messages").length())
            assertEquals("stopped", db.listRequestAccounting("stopped").single().getString("local_status"))

            val deleted = prepared(db, "deleted")
            db.deleteChat("deleted")
            assertFalse(BackgroundRequestFailureRecorder.persist(
                db, "deleted", deleted, "resp_x", "failed", "late_callback"))
            assertTrue(db.listRequestAccounting("deleted").isEmpty())
        }
    }

    private fun usage(input: Long, output: Long = 0L) = JSONObject()
        .put("input_tokens", input).put("output_tokens", output)
        .put("input_tokens_details", JSONObject().put("cached_tokens", input / 10))
        .put("output_tokens_details", JSONObject().put("reasoning_tokens", output / 2))

    private fun checkpoint(ledger: JSONObject, phase: String) = JSONObject()
        .put("version", 1).put("phase", phase).put("round", 1)
        .put("parent_id", "resp_parent").put("response_id", "resp_x")
        .put("usage", OpenAiUsageLedger.total(ledger))
        .put(OpenAiUsageLedger.FIELD, ledger)
        .put("prior_output", JSONArray())
        .put("telemetry", JSONObject().put("rounds_completed", 1).put("calls_executed", 1)
            .put("executed_tools", JSONArray().put("read_attached_file")))

    @Test fun finishedDurableResponseSurvivesFailureAndCompactionWithoutCountingAggregateAsChildUsage() {
        var requestId = ""
        ChatDatabase(context).use { db ->
            requestId = prepared(db)
            val ledger = OpenAiUsageLedger.observe(
                OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed"),
                "resp_x", usage(20, 4), "completed")
            val signed = JSONObject().put("type", "reasoning").put("id", "rs_paid_final")
                .put("encrypted_content", "exact-paid-final-signed")
            val output = JSONArray().put(signed).put(JSONObject().put("type", "message")
                .put("role", "assistant").put("content", JSONArray().put(JSONObject()
                    .put("type", "output_text").put("text", "Paid final answer"))))
            val terminal = OpenAiUsageLedger.attach(JSONObject().put("id", "resp_x")
                .put("status", "completed").put("model", "test-actual-model").put("output", output), ledger)
            assertTrue(db.saveOpenAiToolCheckpoint("chat", requestId,
                checkpoint(ledger, "finished").put("final_response", terminal)))
        }
        ChatDatabase(context).use { db ->
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId,
                "resp_x", "failed", "local_finalization_failure"))
            assertNull(db.getOpenAiToolCheckpoint("chat", requestId))
            assertNull(db.getActiveResponse("chat"))
        }
        ChatDatabase(context).use { db ->
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("Paid final answer", assistant.getString("text"))
            assertEquals("exact-paid-final-signed", assistant.getJSONArray("openai_protocol_output")
                .getJSONObject(0).getString("encrypted_content"))
            assertEquals(120L, assistant.getJSONObject("usage").getLong("input_tokens"))
            assertEquals(120L, OpenAiUsageLedger.total(assistant.getJSONObject(OpenAiUsageLedger.FIELD))!!
                .getLong("input_tokens"))
            assertEquals(120L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
            val event = db.listRequestEvents("chat").single()
            assertEquals(120L, event.getLong("input_tokens"))
            assertEquals(1, event.getJSONObject("telemetry_summary").getInt("calls_executed"))
            assertFalse(BackgroundRequestFailureRecorder.persist(db, "chat", requestId,
                "resp_x", "failed", "duplicate_callback"))
            assertEquals(2, db.getChat("chat")!!.getJSONArray("messages").length())
        }
    }

    @Test fun rawCurrentChildUsageAndIndexedPaidOutputArePreservedOnceWhenLaterRecoveryFails() {
        val requestId = ChatDatabase(context).use { db ->
            val request = prepared(db)
            val ledger = OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed")
            val parentSigned = JSONObject().put("type", "reasoning").put("id", "rs_parent")
                .put("encrypted_content", "signed-parent")
            val childSigned = JSONObject().put("type", "reasoning").put("id", "rs_child")
                .put("encrypted_content", "signed-current-child")
            val terminal = JSONObject().put("id", "resp_x").put("status", "failed")
                .put("usage", usage(20, 4)).put("output", JSONArray().put(childSigned))
            val state = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_x"),
                JSONObject().put("type", "response.failed").put("response", terminal)).state
            db.updateActiveResponse("chat", "resp_x", state.text, "failed", 2, request,
                ResponseIndexedCheckpoint.serialize(state))
            assertEquals("failed", db.getActiveResponse("chat")!!.getString("status"))
            assertTrue(db.saveOpenAiToolCheckpoint("chat", request,
                checkpoint(ledger, "waiting").put("prior_output", JSONArray().put(parentSigned))))
            request
        }
        ChatDatabase(context).use { db ->
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId,
                "resp_x", "failed", "fatal_read_failure"))
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("", assistant.getString("text"))
            val output = assistant.getJSONArray("openai_protocol_output")
            assertEquals(2, output.length())
            assertEquals("signed-parent", output.getJSONObject(0).getString("encrypted_content"))
            assertEquals("signed-current-child", output.getJSONObject(1).getString("encrypted_content"))
            assertEquals(120L, assistant.getJSONObject("usage").getLong("input_tokens"))
            val row = db.listRequestAccounting("chat").single()
            assertEquals(120L, row.getLong("input_tokens"))
            assertEquals(14L, row.getLong("output_tokens"))
            assertEquals(12L, row.getLong("cached_tokens"))
            assertEquals(7L, row.getLong("reasoning_tokens"))
            assertNull(db.getOpenAiToolCheckpoint("chat", requestId))
        }
    }

    @Test fun usageOnlyFailureCreatesDurableAssistantAndLedgerWithoutFabricatingTypedOutput() {
        ChatDatabase(context).use { db ->
            val requestId = prepared(db, activeText = "")
            assertEquals(requestId, db.getActiveResponse("chat")!!.getString("request_id"))
            assertEquals("", db.getActiveResponse("chat")!!.getString("text"))
            val ledger = OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed")
            assertTrue(db.saveOpenAiToolCheckpoint("chat", requestId, checkpoint(ledger, "posting")))
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId,
                "resp_x", "failed", "ambiguous_continuation_post"))
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("", assistant.getString("text"))
            assertFalse(assistant.has("openai_protocol_output"))
            assertEquals(100L, assistant.getJSONObject("usage").getLong("input_tokens"))
            assertEquals(100L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
            assertNull(db.getOpenAiToolCheckpoint("chat", requestId))
        }
    }

    @Test fun receivedChildTerminalWinsOlderIndexedEnvelopeAndRetainsPaidImageArtifact() {
        val imagesDir = kotlin.io.path.createTempDirectory("worker-failed-images").toFile()
        try {
            ChatDatabase(context).use { db ->
                val requestId = prepared(db)
                val ledger = OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed")
                val oldTerminal = JSONObject().put("id", "resp_x").put("status", "failed")
                    .put("usage", usage(5, 1)).put("output", JSONArray().put(JSONObject()
                        .put("type", "message").put("id", "msg_same_child").put("role", "assistant")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "old partial")))))
                val oldState = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_x"),
                    JSONObject().put("type", "response.failed").put("response", oldTerminal)).state
                db.updateActiveResponse("chat", "resp_x", oldState.text, "failed", 2, requestId,
                    ResponseIndexedCheckpoint.serialize(oldState))
                assertEquals("failed", db.getActiveResponse("chat")!!.getString("status"))
                val imageBytes = TestPngFixtures.png(3, 2, 0x225fc7)
                val paid = JSONObject().put("id", "resp_x").put("status", "completed")
                    .put("usage", usage(20, 4)).put("output", JSONArray()
                        .put(JSONObject().put("type", "message").put("id", "msg_same_child").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Paid new text"))))
                        .put(JSONObject().put("type", "image_generation_call").put("id", "ig_paid")
                            .put("result", java.util.Base64.getEncoder().encodeToString(imageBytes))))
                assertTrue(db.saveOpenAiToolCheckpoint("chat", requestId,
                    checkpoint(ledger, "waiting").put("received_response", paid)))
                assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId,
                    "resp_x", "failed", "tool_validation_failed", imagesDir))
                val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
                assertEquals("Paid new text", assistant.getString("text"))
                assertEquals(120L, assistant.getJSONObject("usage").getLong("input_tokens"))
                val generated = assistant.getJSONArray("generated_images").getJSONObject(0)
                assertEquals("READY", generated.getString("state"))
                assertArrayEquals(imageBytes, File(generated.getString("local_path")).readBytes())
                assertEquals(2, assistant.getJSONArray("openai_protocol_output").length())
                assertEquals(120L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
                assertNull(db.getOpenAiToolCheckpoint("chat", requestId))
            }
        } finally { imagesDir.deleteRecursively() }
    }
}
