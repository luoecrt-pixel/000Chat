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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekDatabaseIntegrationTest {
    private lateinit var context: Context
    private val databaseName = "astra_chats.db"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName))
    }
    @After fun cleanup() { SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName)) }

    private fun begin(db: ChatDatabase, request: String = "req", provider: String = "deepseek") {
        db.ensureChat("chat", "Original title")
        assertTrue(db.startRequestAccounting("chat", request, provider, "deepseek-test", "${provider}_api_key"))
        db.setActiveResponse("chat", null, request, "", "starting", 1L, "high", "high", false, 1, 0, false)
    }

    private fun rawUsage(prompt: Int, completion: Int) = JSONObject().put("prompt_tokens", prompt).put("completion_tokens", completion)
    private fun call(id: String, name: String, arguments: String) = JSONObject().put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", arguments))
    private fun checkpoint(tail: JSONArray = JSONArray(), uncommitted: JSONObject? = null) = JSONObject()
        .put("version", 1).put("replayable", false).put("thinking_enabled", true).put("tail", tail)
        .apply { if (uncommitted != null) put("uncommitted_assistant", uncommitted) }

    @Test fun incompleteSecondRoundCannotBecomeZeroOrConfirmedSubtotalAfterReopen() {
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.observeDeepSeekRound("chat", "req", 0, rawUsage(12, 4)))
            assertTrue(db.observeDeepSeekRound("chat", "req", 1, JSONObject().put("prompt_tokens", 5)))
            assertTrue(db.finalizeCurrentChatIfActiveRequest("chat", "", null, null, listOf(
                JSONObject().put("role", "assistant").put("provider", "deepseek").put("request_id", "req")
                    .put("text", "Visible answer").put("status", "incomplete")), "req"))
            db.recordRequestEvent("chat", "request_incomplete", "req", "deepseek", "deepseek-test", null,
                ModelCatalog.Match.UNKNOWN, "incomplete", usageComplete = false)
            // A stale caller cannot make the metric claim these are full totals.
            db.recordEffortMetric("req", "chat", "general", "high", "high", false, 1, 0, 10,
                17, 0, 0, 4, 0, false, "missing_usage")
        }
        ChatDatabase(context).use { db ->
            val accounting = db.listRequestAccounting("chat").single()
            assertEquals(0, accounting.getInt("usage_complete"))
            assertFalse(accounting.has("input_tokens"))
            assertFalse(accounting.has("output_tokens"))
            val usage = db.getChat("chat")!!.getJSONArray("messages").getJSONObject(0).getJSONObject("usage")
            assertFalse(usage.getBoolean("usage_complete"))
            assertFalse(usage.has("prompt_tokens"))
            assertEquals(17, usage.getJSONObject("observed_partial_usage").getInt("prompt_tokens"))
            val event = db.listRequestEvents("chat").single()
            assertFalse(event.getBoolean("usage_complete"))
            assertTrue(event.isNull("input_tokens"))
            assertTrue(RequestEventFormatter.summary(event).contains("токены неизвестны"))
            assertTrue(RequestEventFormatter.details(event).contains("input_tokens=unknown"))
            assertEquals(0, db.usageTotals().getInt("input_tokens"))
            assertEquals(1, db.usageTotals().getInt("unknown_usage_requests"))
            assertTrue(db.listOpenRouterRounds("chat", "req").isEmpty())
        }
    }

    @Test fun completeDeepSeekUsageUsesChatCompletionColumnsWithoutProviderCostOrDuplicateRounds() {
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.observeDeepSeekRound("chat", "req", 0, rawUsage(11, 3)))
            assertTrue(db.observeDeepSeekRound("chat", "req", 0, rawUsage(11, 3)))
            assertTrue(db.observeDeepSeekRound("chat", "req", 1, rawUsage(7, 2)))
            assertFalse(db.observeOpenRouterRound("chat", "req", 0, "foreign", rawUsage(900, 900)))
            assertTrue(db.finalizeCurrentChatIfActiveRequest("chat", "", null, null, listOf(
                JSONObject().put("role", "assistant").put("provider", "deepseek").put("request_id", "req")
                    .put("text", "answer").put("status", "completed")), "req"))
            assertTrue(db.recordRequestAccounting("chat", "req", "completed",
                usage = rawUsage(18, 5).put("usage_complete", true)))
        }
        ChatDatabase(context).use { db ->
            val row = db.listRequestAccounting("chat").single()
            assertEquals(1, row.getInt("usage_complete"))
            assertEquals(18, row.getInt("input_tokens"))
            assertEquals(5, row.getInt("output_tokens"))
            assertFalse(row.has("cost_usd"))
            assertEquals("unknown", row.getString("cost_kind"))
            assertFalse(db.observeDeepSeekRound("chat", "req", 2, rawUsage(1, 1)))
            assertTrue(db.listOpenRouterRounds("chat", "req").isEmpty())
        }
    }

    @Test fun stopArchivesExactToolOrderAndArgumentsBeforeRemovingWorkingCheckpoint() {
        val originalTail = JSONArray().put(JSONObject().put("role", "assistant").put("reasoning_content", "plan")
            .put("content", "").put("tool_calls", JSONArray().put(call("A", "first", "{\"value\":1}"))
                .put(call("B", "second", "{\"value\":2}"))))
            .put(JSONObject().put("role", "tool").put("tool_call_id", "A").put("content", "confirmed"))
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.saveDeepSeekCheckpoint("chat", "req", checkpoint(originalTail)))
            assertTrue(db.stopActiveRequestOnce("chat", "req"))
            assertNull(db.getDeepSeekCheckpoint("chat", "req"))
            assertFalse(db.saveDeepSeekCheckpoint("chat", "req", checkpoint()))
            assertFalse(db.stopActiveRequestOnce("chat", "req"))
        }
        ChatDatabase(context).use { db ->
            val archived = db.getRequestRecoveryArchive("chat", "req")!!
            val protocol = archived.getJSONObject(DeepSeekProtocolCodec.FIELD)
            assertEquals(originalTail.toString(), protocol.getJSONArray("tail").toString())
            assertFalse(protocol.getBoolean("replayable"))
            assertEquals("plan", archived.getString("reasoning_summary"))
            assertEquals(1, db.listRequestEvents("chat").size)
            assertEquals(0, db.getChat("chat")!!.getJSONArray("messages").length())
        }
    }

    @Test fun processReopenRecoversCommittedToolArgumentPrefixAndLateArtifactWithoutResurrection() {
        val partial = JSONObject().put("role", "assistant").put("content", "").put("tool_calls",
            JSONArray().put(call("B", "patch_local_file", "{\"new_text\":\"prefix")))
        ChatDatabase(context).use { db ->
            begin(db)
            assertTrue(db.saveDeepSeekCheckpoint("chat", "req", checkpoint(uncommitted = partial)))
        }
        val artifactFile = java.io.File(context.filesDir, "confirmed.txt").apply { writeText("confirmed output") }
        ChatDatabase(context).use { db ->
            assertTrue(db.stopActiveRequestOnce("chat", "req"))
            assertTrue(db.registerLocalArtifact("chat", "req", JSONObject().put("path", artifactFile.path)
                .put("tool_name", "patch_local_file").put("tool_call_id", "local_artifact_0")
                .put("arguments_sha256", "confirmed-arguments")))
        }
        ChatDatabase(context).use { db ->
            val archived = db.getRequestRecoveryArchive("chat", "req")!!
            val protocol = archived.getJSONObject(DeepSeekProtocolCodec.FIELD)
            val saved = protocol.optJSONObject("uncommitted_assistant") ?: protocol.getJSONObject("interrupted_assistant")
            assertEquals(partial.toString(), saved.toString())
            assertEquals(artifactFile.path, archived.getJSONArray("confirmed_artifacts").getJSONObject(0).getString("path"))
            assertEquals("confirmed output", artifactFile.readText())
            assertNull(db.getActiveResponse("chat"))
            assertEquals(0, db.getChat("chat")!!.getJSONArray("messages").length())
            db.deleteChat("chat")
            assertFalse(db.registerLocalArtifact("chat", "req", JSONObject().put("path", artifactFile.path)))
            assertNull(db.getRequestRecoveryArchive("chat", "req"))
        }
    }

    @Test fun version25UpgradeIsAdditiveAndReopeningKeepsHistoryAndMetrics() {
        ChatDatabase(context).use { db ->
            db.ensureChat("old", "Old title")
            db.upsertCurrentChat("old", "Old title", null, "high", listOf(
                JSONObject().put("role", "user").put("text", "original question")))
            db.recordEffortMetric("legacy", "old", "general", "high", "high", false, 1, 0, 2,
                20, 0, 0, 3, 0, true, null)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("DROP TABLE deepseek_request_rounds")
            sql.execSQL("DROP TABLE deepseek_checkpoints")
            sql.execSQL("ALTER TABLE messages DROP COLUMN deepseek_protocol")
            sql.execSQL("ALTER TABLE request_accounting DROP COLUMN usage_complete")
            sql.execSQL("ALTER TABLE request_events DROP COLUMN usage_complete")
            sql.execSQL("ALTER TABLE effort_metrics DROP COLUMN usage_complete")
            sql.version = 25
        }
        repeat(2) {
            ChatDatabase(context).use { db ->
                assertEquals(26, db.readableDatabase.version)
                assertEquals("original question", db.getChat("old")!!.getJSONArray("messages").getJSONObject(0).getString("text"))
                assertEquals(20, db.usageTotals().getInt("input_tokens"))
                assertEquals(3, db.usageTotals().getInt("output_tokens"))
                assertTrue(db.observeDeepSeekRound("old", "missing", 0, null).not())
            }
        }
    }
}
