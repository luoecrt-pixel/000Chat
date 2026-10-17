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

    private fun damagedTerminalCheckpoint(db: ChatDatabase, requestId: String): String {
        val partial = ResponseEventReducer.reduce(ResponseEventState(responseId = "resp_x"),
            JSONObject().put("type", "response.output_text.delta").put("output_index", 0)
                .put("content_index", 0).put("delta", "paid fragment")).state
        val damaged = JSONObject(ResponseIndexedCheckpoint.serialize(partial))
            .put("terminal_state", "completed") // Missing the canonical terminal_response.
        damaged.getJSONArray("typed_output_items").put(JSONObject().put("output_index", 1)
            .put("item", JSONObject().put("type", "reasoning").put("id", "rs_untrusted")
                .put("encrypted_content", "exact signed bytes retained only in raw archive")))
        // Noncanonical formatting proves archival retains the original string, not a parsed rewrite.
        val raw = "\n" + damaged.toString(2) + "\n"
        db.updateActiveResponse("chat", "resp_x", partial.text, "completed", 5, requestId, raw)
        assertThrows(UnrecoverableResponsesCheckpointException::class.java) {
            ResponseIndexedCheckpoint.restore(db.getActiveResponse("chat")!!)
        }
        return raw
    }

    @Test fun damagedTerminalFailureArchivesExactIndexedStateAndCommitsFailedPartialAfterReopen() {
        lateinit var raw: String
        val requestId = ChatDatabase(context).use { db ->
            val id = prepared(db, activeText = "")
            raw = damagedTerminalCheckpoint(db, id)
            id
        }
        ChatDatabase(context).use { db ->
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId, "resp_x", "failed",
                "unrecoverable_checkpoint: Terminal Responses output was not preserved"))
            assertNull(db.getActiveResponse("chat"))
        }
        ChatDatabase(context).use { db ->
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("paid fragment", assistant.getString("text"))
            assertEquals("failed", assistant.getString("status"))
            assertTrue(assistant.getString("error").contains("not preserved"))
            // An invalid indexed state cannot become trusted signed replay for the next request.
            assertFalse(assistant.has("openai_protocol_output"))
            val archive = db.getRequestRecoveryArchive("chat", requestId)!!
            val savedState = archive.getJSONObject("unrecoverable_indexed_state")
            assertEquals(raw, savedState.getString("indexed_checkpoint"))
            assertEquals("resp_x", savedState.getString("response_id"))
            assertEquals("paid fragment", savedState.getString("text"))
            assertEquals("completed", savedState.getString("status"))
            assertEquals(5L, savedState.getLong("last_sequence_number"))
            assertEquals("failed", db.listRequestAccounting("chat").single().getString("local_status"))
            assertEquals("request_failed", db.listRequestEvents("chat").single().getString("event_type"))
            assertFalse(BackgroundRequestFailureRecorder.persist(db, "chat", requestId, "resp_x", "failed", "duplicate"))
            assertEquals(2, db.getChat("chat")!!.getJSONArray("messages").length())
            assertEquals(raw, db.getRequestRecoveryArchive("chat", requestId)!!
                .getJSONObject("unrecoverable_indexed_state").getString("indexed_checkpoint"))
        }
    }

    @Test fun damagedIndexedArchiveWriteFailureRollsBackTerminalMessageOwnershipAndPaidToolCheckpoint() {
        lateinit var raw: String
        val requestId = ChatDatabase(context).use { db ->
            val id = prepared(db, activeText = "")
            raw = damagedTerminalCheckpoint(db, id)
            val ledger = OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed")
            assertTrue(db.saveOpenAiToolCheckpoint("chat", id, checkpoint(ledger, "waiting")
                .put("prior_output", JSONArray().put(JSONObject().put("type", "reasoning").put("id", "rs_good")
                    .put("encrypted_content", "known-good-parent")))))
            db.writableDatabase.execSQL("""CREATE TRIGGER abort_damaged_archive BEFORE INSERT ON request_recovery_archive
                BEGIN SELECT RAISE(ABORT, 'injected archive failure'); END""")
            assertThrows(android.database.sqlite.SQLiteException::class.java) {
                BackgroundRequestFailureRecorder.persist(db, "chat", id, "resp_x", "failed", "unrecoverable_checkpoint")
            }
            assertEquals(raw, db.getActiveResponse("chat")!!.getString("indexed_checkpoint"))
            assertTrue(db.isActiveRequest("chat", id))
            assertNotNull(db.getOpenAiToolCheckpoint("chat", id))
            assertNull(db.getRequestRecoveryArchive("chat", id))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
            assertEquals("running", db.listRequestAccounting("chat").single().getString("local_status"))
            assertTrue(db.listRequestEvents("chat").isEmpty())
            db.writableDatabase.execSQL("DROP TRIGGER abort_damaged_archive")
            id
        }
        ChatDatabase(context).use { db ->
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", requestId, "resp_x", "failed", "unrecoverable_checkpoint"))
            assertNull(db.getActiveResponse("chat"))
            assertNull(db.getOpenAiToolCheckpoint("chat", requestId))
            val archive = db.getRequestRecoveryArchive("chat", requestId)!!
            assertEquals(raw, archive.getJSONObject("unrecoverable_indexed_state").getString("indexed_checkpoint"))
            assertEquals(100L, archive.getJSONObject("usage").getLong("input_tokens"))
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            val output = assistant.getJSONArray("openai_protocol_output")
            assertEquals(1, output.length())
            assertEquals("known-good-parent", output.getJSONObject(0).getString("encrypted_content"))
            assertEquals(100L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
        }
    }

    @Test fun staleDamagedCheckpointFailureCannotArchiveOrClearReplacementRequest() {
        ChatDatabase(context).use { db ->
            val staleRequest = prepared(db, activeText = "")
            damagedTerminalCheckpoint(db, staleRequest)
            db.setActiveResponse("chat", "resp_new", "local_replacement", "new partial", "in_progress",
                System.currentTimeMillis(), "high", "high", false, 15, 0, true)
            assertFalse(BackgroundRequestFailureRecorder.persist(db, "chat", staleRequest, "resp_x", "failed", "unrecoverable_checkpoint"))
            val active = db.getActiveResponse("chat")!!
            assertEquals("local_replacement", active.getString("request_id"))
            assertEquals("resp_new", active.getString("response_id"))
            assertEquals("new partial", active.getString("text"))
            assertNull(db.getRequestRecoveryArchive("chat", staleRequest))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
            assertTrue(db.listRequestEvents("chat").isEmpty())
        }
    }

    @Test fun rawOnlyDamagedIndexedCursorCreatesExplicitFailedAssistantAndArchiveWithoutVisibleText() {
        val raw = "malformed raw-only indexed cursor: exact original bytes"
        val requestId = ChatDatabase(context).use { db ->
            val id = prepared(db, activeText = "")
            db.updateActiveResponse("chat", "resp_x", "", "in_progress", 7, id, raw)
            assertTrue(BackgroundRequestFailureRecorder.persist(db, "chat", id, "resp_x", "failed", "unrecoverable_checkpoint"))
            assertNull(db.getActiveResponse("chat"))
            id
        }
        ChatDatabase(context).use { db ->
            val assistant = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("", assistant.getString("text"))
            assertEquals("failed", assistant.getString("status"))
            assertEquals("unrecoverable_checkpoint", assistant.getString("error"))
            assertFalse(assistant.has("openai_protocol_output"))
            val state = db.getRequestRecoveryArchive("chat", requestId)!!.getJSONObject("unrecoverable_indexed_state")
            assertEquals(raw, state.getString("indexed_checkpoint"))
            assertEquals("", state.getString("text"))
            assertEquals(7L, state.getLong("last_sequence_number"))
            assertEquals("failed", db.listRequestAccounting("chat").single().getString("local_status"))
            assertEquals("request_failed", db.listRequestEvents("chat").single().getString("event_type"))
        }
    }

    @Test fun failureSnapshotTokenCannotQuarantineDifferentCurrentDamagedBytesUnderSameRequestOwner() {
        ChatDatabase(context).use { db ->
            val requestId = prepared(db, activeText = "")
            val olderRaw = damagedTerminalCheckpoint(db, requestId)
            val currentRaw = JSONObject(olderRaw).put("newer_snapshot", true).toString()
            db.writableDatabase.execSQL("UPDATE chats SET active_indexed_checkpoint=? WHERE id=? AND active_request_id=?",
                arrayOf(currentRaw, "chat", requestId))
            val ledger = OpenAiUsageLedger.observe(null, "resp_parent", usage(100, 10), "completed")
            assertTrue(db.saveOpenAiToolCheckpoint("chat", requestId, checkpoint(ledger, "waiting")))
            val failed = JSONObject().put("role", "assistant").put("provider", "openai")
                .put("request_id", requestId).put("response_id", "resp_x").put("status", "failed")
                .put("text", "paid fragment").put("error", "unrecoverable_checkpoint")
            assertThrows(UnrecoverableResponsesCheckpointException::class.java) {
                db.finalizeCurrentChatIfActiveRequest("chat", "Worker chat", null, null, listOf(failed), requestId,
                    unrecoverableIndexedCheckpoint = olderRaw)
            }
            assertTrue(db.isActiveRequest("chat", requestId))
            assertEquals(currentRaw, db.getActiveResponse("chat")!!.getString("indexed_checkpoint"))
            assertNotNull(db.getOpenAiToolCheckpoint("chat", requestId))
            assertNull(db.getRequestRecoveryArchive("chat", requestId))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
            assertEquals("running", db.listRequestAccounting("chat").single().getString("local_status"))
            assertTrue(db.listRequestEvents("chat").isEmpty())
        }
    }

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
