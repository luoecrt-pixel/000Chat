package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class ChatDatabaseRequestAccountingTest {
    private lateinit var context: Context
    private val dbName = "astra_chats.db"

    @Before fun start() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    @After fun finish() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    private fun begin(db: ChatDatabase, chat: String, request: String, provider: String = "openrouter") =
        db.startRequestAccounting(chat, request, provider, "fixed-model",
            if (provider == "openai") "openai_api_key" else "openrouter_api_key")

    @Test fun missingAndReportedZeroAreDifferentEvenAfterReopen() {
        ChatDatabase(context).use { db ->
            db.ensureChat("chat-1")
            assertTrue(begin(db, "chat-1", "local_1"))
            assertTrue(begin(db, "chat-1", "local_2"))
            assertTrue(db.recordRequestAccounting("chat-1", "local_1", "completed",
                usage = JSONObject().put("prompt_tokens", 9).put("completion_tokens", 2)))
            assertTrue(db.recordRequestAccounting("chat-1", "local_2", "completed",
                usage = JSONObject().put("prompt_tokens", 7).put("completion_tokens", 1),
                reportedCostUsd = 0.0))
        }
        ChatDatabase(context).use { db ->
            val rows = db.listRequestAccounting("chat-1").associateBy { it.getString("local_request_id") }
            assertEquals("unknown", rows.getValue("local_1").getString("cost_kind"))
            assertFalse(rows.getValue("local_1").has("cost_usd"))
            assertEquals("provider_reported", rows.getValue("local_2").getString("cost_kind"))
            assertEquals(0.0, rows.getValue("local_2").getString("cost_usd").toDouble(), 0.0)
            assertEquals(9L, rows.getValue("local_1").getLong("input_tokens"))
            assertFalse(rows.getValue("local_1").has("cached_tokens"))
            val total = db.requestAccountingTotals()
            assertEquals(2, total.getInt("requests"))
            assertEquals(1, total.getInt("known_cost_requests"))
            assertEquals(1, total.getInt("unknown_cost_requests"))
            assertEquals(0.0, total.getDouble("provider_reported_cost_usd"), 0.0)
        }
    }

    @Test fun sameLocalRequestIdCannotBeDoubleCountedOrChangeCapturedSource() {
        ChatDatabase(context).use { db ->
            db.ensureChat("chat-1")
            assertTrue(begin(db, "chat-1", "local_same"))
            assertFalse(db.startRequestAccounting("chat-1", "local_same", "openai", "other-model", "wrong_slot"))
            assertTrue(db.recordRequestAccounting("chat-1", "local_same", "completed",
                responseId = "resp_1", httpRequestId = "req_http_1",
                usage = JSONObject().put("prompt_tokens", 3),
                reportedCostUsd = 0.00000002))
            assertTrue(db.recordRequestAccounting("chat-1", "local_same", "completed",
                responseId = "resp_1", httpRequestId = "req_http_1",
                usage = JSONObject().put("prompt_tokens", 3),
                reportedCostUsd = 0.00000002))
            val row = db.listRequestAccounting("chat-1").single()
            assertEquals("openrouter", row.getString("provider"))
            assertEquals("fixed-model", row.getString("requested_model"))
            assertEquals("openrouter_api_key", row.getString("credential_source"))
            assertEquals("local_same", row.getString("local_request_id"))
            assertEquals("resp_1", row.getString("response_id"))
            assertEquals("req_http_1", row.getString("http_request_id"))
            assertEquals(0.00000002, row.getString("cost_usd").toDouble(), 0.0)
            assertEquals(1, db.requestAccountingTotals().getInt("known_cost_requests"))
        }
    }

    @Test fun stoppedRequestCanAcquireLateProviderCostButNeverResurrectsLocalState() {
        ChatDatabase(context).use { db ->
            db.ensureChat("chat-1")
            assertTrue(begin(db, "chat-1", "local_stopped"))
            db.markRequestAccountingStopped("chat-1", "local_stopped")
        }
        ChatDatabase(context).use { db ->
            val before = db.listRequestAccounting("chat-1").single()
            assertEquals("stopped", before.getString("local_status"))
            assertFalse(before.has("provider_status"))
            assertTrue(db.recordRequestAccounting("chat-1", "local_stopped", "completed",
                responseId = "resp_after_stop",
                usage = JSONObject().put("input_tokens", 21).put("output_tokens", 5),
                reportedCostUsd = 0.0))
            val after = db.listRequestAccounting("chat-1").single()
            assertEquals("stopped", after.getString("local_status"))
            assertEquals("completed", after.getString("provider_status"))
            assertEquals("resp_after_stop", after.getString("response_id"))
            assertEquals(21L, after.getLong("input_tokens"))
            assertEquals(0.0, after.getString("cost_usd").toDouble(), 0.0)
        }
    }

    @Test fun deleteAndClearHistoryBlockStaleCallbacks() {
        ChatDatabase(context).use { db ->
            db.ensureChat("deleted")
            assertTrue(begin(db, "deleted", "local_deleted"))
            db.deleteChat("deleted")
            assertFalse(db.recordRequestAccounting("deleted", "local_deleted", "completed",
                reportedCostUsd = 2.0))
            assertFalse(begin(db, "deleted", "local_deleted"))
            assertTrue(db.listRequestAccounting("deleted").isEmpty())

            db.ensureChat("cleared")
            assertTrue(begin(db, "cleared", "local_cleared"))
            db.clearRequestEvents("cleared")
            assertFalse(db.recordRequestAccounting("cleared", "local_cleared", "completed",
                reportedCostUsd = 1.0))
            assertTrue(db.listRequestAccounting("cleared").isEmpty())
        }
    }

    @Test fun migrationFromV18PreservesEventsWithoutInventingLedgerData() {
        ChatDatabase(context).use { db ->
            db.ensureChat("legacy")
            db.recordRequestEvent("legacy", "request_completed", "http_not_local",
                "openai", "model", "model", ModelCatalog.Match.EXACT,
                "completed", estimatedCostUsd = 0.125)
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(dbName).path, null,
            SQLiteDatabase.OPEN_READWRITE).use { raw ->
            raw.execSQL("DROP TABLE request_accounting")
            raw.version = 18
        }
        ChatDatabase(context).use { db ->
            val legacy = db.listRequestEvents("legacy").single()
            assertEquals("unknown", legacy.getString("cost_kind"))
            assertEquals(0.125, legacy.getDouble("estimated_cost_usd"), 0.000001)
            assertTrue(db.listRequestAccounting("legacy").isEmpty())
            assertEquals(0, db.requestAccountingTotals().getInt("requests"))
            assertTrue(begin(db, "legacy", "local_new"))
        }
        ChatDatabase(context).use { db ->
            assertEquals(1, db.listRequestAccounting("legacy").size)
        }
    }

    @Test fun capturedProviderControlsNullableUsageEvenForHybridFields() {
        ChatDatabase(context).use { db ->
            db.ensureChat("multi")
            assertTrue(begin(db, "multi", "local_openai", "openai"))
            assertTrue(begin(db, "multi", "local_router", "openrouter"))
            val hybrid = JSONObject()
                .put("input_tokens", 17).put("output_tokens", 4)
                .put("prompt_tokens", 300).put("completion_tokens", 200)
            assertTrue(db.recordRequestAccounting("multi", "local_openai", "completed", usage = hybrid))
            assertTrue(db.recordRequestAccounting("multi", "local_router", "completed", usage = hybrid))
            val rows = db.listRequestAccounting("multi").associateBy { it.getString("local_request_id") }
            assertEquals(17L, rows.getValue("local_openai").getLong("input_tokens"))
            assertEquals(4L, rows.getValue("local_openai").getLong("output_tokens"))
            assertEquals(300L, rows.getValue("local_router").getLong("input_tokens"))
            assertEquals(200L, rows.getValue("local_router").getLong("output_tokens"))
        }
    }

    @Test fun invalidCostsNeverBecomeVerified() {
        ChatDatabase(context).use { db ->
            db.ensureChat("invalid")
            for ((index, cost) in listOf(Double.NaN, Double.POSITIVE_INFINITY, -0.1).withIndex()) {
                val id = "local_$index"
                assertTrue(begin(db, "invalid", id))
                assertTrue(db.recordRequestAccounting("invalid", id, "completed",
                    reportedCostUsd = cost))
            }
            val summary = db.requestAccountingTotals()
            assertEquals(0, summary.getInt("known_cost_requests"))
            assertEquals(3, summary.getInt("unknown_cost_requests"))
        }
    }
}
