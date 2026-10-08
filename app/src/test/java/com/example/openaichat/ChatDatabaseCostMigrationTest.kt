package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseCostMigrationTest {
    private val dbName = "astra_chats.db"
    private lateinit var context: Context

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    @After fun tearDown() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(dbName))
    }

    @Test fun v17LegacyRowsPreserveHistoryButNeverBecomeReportedCosts() {
        val fresh = ChatDatabase(context)
        fresh.ensureChat("legacy-cost", "Old cost history")
        fresh.close()
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { sql ->
            // Reconstruct the real v17 event layout while preserving the chat row.
            sql.execSQL("DROP TABLE request_events")
            sql.execSQL("""
                CREATE TABLE request_events (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    event_type TEXT NOT NULL,
                    request_id TEXT,
                    provider TEXT NOT NULL,
                    requested_model TEXT,
                    response_model TEXT,
                    model_match TEXT NOT NULL,
                    status TEXT NOT NULL,
                    input_tokens INTEGER NOT NULL DEFAULT 0,
                    cached_tokens INTEGER NOT NULL DEFAULT 0,
                    cache_write_tokens INTEGER NOT NULL DEFAULT 0,
                    output_tokens INTEGER NOT NULL DEFAULT 0,
                    reasoning_tokens INTEGER NOT NULL DEFAULT 0,
                    estimated_cost_usd REAL NOT NULL DEFAULT 0,
                    latency_ms INTEGER NOT NULL DEFAULT 0,
                    tool_summary TEXT,
                    error TEXT,
                    FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE
                )
            """.trimIndent())
            for ((i, amount) in listOf(0.0, 0.125).withIndex()) {
                sql.execSQL(
                    """INSERT INTO request_events (
                        chat_id, created_at, event_type, request_id,
                        provider, model_match, status, estimated_cost_usd
                    ) VALUES (?, ?, 'request_completed', ?, 'openai', 'unknown', 'completed', ?)""",
                    arrayOf("legacy-cost", i + 1, "old-$i", amount)
                )
            }
            sql.version = 17
        }

        ChatDatabase(context).use { upgraded ->
            val events = upgraded.listRequestEvents("legacy-cost", 10)
            assertEquals(2, events.size)
            assertEquals(setOf("old-0", "old-1"), events.map { it.getString("request_id") }.toSet())
            events.forEach { event ->
                assertEquals("unknown", event.getString("cost_kind"))
                assertFalse(event.has("cost_usd"))
                assertTrue(RequestEventFormatter.summary(event).contains("Расход неизвестен"))
            }
            assertTrue(events.any { it.optDouble("estimated_cost_usd") > 0.0 })
        }
        ChatDatabase(context).use { reopened ->
            assertEquals(2, reopened.listRequestEvents("legacy-cost").size)
        }
    }

    @Test fun v17WithAlreadyPresentV18CostColumnsDoesNotDuplicateColumns() {
        ChatDatabase(context).use { fresh ->
            fresh.ensureChat("preexisting-cost", "Partially upgraded")
            fresh.recordRequestEvent(
                chatId = "preexisting-cost", eventType = "request_completed",
                requestId = "known", provider = "openrouter",
                requestedModel = "test-model", responseModel = "test-model",
                modelMatch = ModelCatalog.Match.EXACT, status = "completed",
                reportedCostUsd = 0.0
            )
        }
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path, null, SQLiteDatabase.OPEN_READWRITE
        ).use { it.version = 17 }
        ChatDatabase(context).use { upgraded ->
            val event = upgraded.listRequestEvents("preexisting-cost").single()
            assertEquals("provider_reported", event.getString("cost_kind"))
            assertEquals(0.0, event.getString("cost_usd").toDouble(), 0.0)
        }
    }

    @Test fun explicitZeroTinyAndInvalidAmountsSurviveReopenAndDelete() {
        ChatDatabase(context).use { db ->
            db.ensureChat("cost-chat", "Costs")
            fun record(id: String, cost: Double?) {
                db.recordRequestEvent(
                    chatId = "cost-chat", eventType = "request_completed",
                    requestId = id, provider = "openrouter",
                    requestedModel = "test-model", responseModel = "test-model",
                    modelMatch = ModelCatalog.Match.EXACT, status = "completed",
                    reportedCostUsd = cost
                )
            }
            record("zero", 0.0)
            record("tiny", 0.00000001)
            record("missing", null)
            record("invalid", Double.NaN)
            record("negative", -1.0)
        }
        ChatDatabase(context).use { reopened ->
            val events = reopened.listRequestEvents("cost-chat").associateBy { it.getString("request_id") }
            assertEquals(5, events.size)
            assertEquals("provider_reported", events.getValue("zero").getString("cost_kind"))
            assertEquals(0.0, events.getValue("zero").getString("cost_usd").toDouble(), 0.0)
            assertTrue(RequestEventFormatter.summary(events.getValue("tiny")).contains("< $0.0001"))
            for (id in listOf("missing", "invalid", "negative")) {
                assertEquals("unknown", events.getValue(id).getString("cost_kind"))
                assertFalse(events.getValue(id).has("cost_usd"))
            }
            reopened.deleteChat("cost-chat")
            reopened.recordRequestEvent(
                chatId = "cost-chat", eventType = "request_completed",
                requestId = "too-late", provider = "openrouter",
                requestedModel = "test-model", responseModel = null,
                modelMatch = ModelCatalog.Match.UNKNOWN, status = "completed",
                reportedCostUsd = 0.1
            )
            assertTrue(reopened.listRequestEvents("cost-chat").isEmpty())
        }
    }
}
