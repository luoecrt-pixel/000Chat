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
class ChatDatabaseTelemetryMigrationTest {
    private lateinit var context: Context
    private val databaseName = "astra_chats.db"

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName))
    }

    @After fun cleanup() { SQLiteDatabase.deleteDatabase(context.getDatabasePath(databaseName)) }

    /** Rebuilds only the new additive columns away, preserving each old column, index and row. */
    private fun removeNewColumns(sql: SQLiteDatabase, table: String, omitted: Set<String>) {
        val create = sql.rawQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table))
            .use { assertTrue(it.moveToFirst()); it.getString(0) }
        val indexes = sql.rawQuery("SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name=? AND sql IS NOT NULL", arrayOf(table))
            .use { cursor -> buildList { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        val columns = sql.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            buildList { while (cursor.moveToNext()) {
                val name = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                if (name !in omitted) add(name)
            } }
        }
        var legacyCreate = create.replaceFirst("CREATE TABLE $table", "CREATE TABLE legacy_$table")
        for (name in omitted) legacyCreate = legacyCreate.replace(
            Regex(",\\s*" + name + "\\s+TEXT(?:\\s+NOT NULL\\s+DEFAULT 'unknown')?"), "")
        sql.execSQL(legacyCreate)
        val names = columns.joinToString(",") { "\"$it\"" }
        sql.execSQL("INSERT INTO legacy_$table ($names) SELECT $names FROM $table")
        sql.execSQL("DROP TABLE $table")
        sql.execSQL("ALTER TABLE legacy_$table RENAME TO $table")
        indexes.forEach(sql::execSQL)
    }

    private fun seedLegacy(version: Int) {
        ChatDatabase(context).use { db ->
            db.ensureChat("legacy", "History must survive")
            val typed = JSONArray().put(JSONObject().put("type", "reasoning").put("encrypted_content", "signed-old"))
            db.upsertCurrentChat("legacy", "History must survive", "resp_old", "high", listOf(
                JSONObject().put("role", "user").put("request_id", "local_old").put("text", "question"),
                JSONObject().put("role", "assistant").put("request_id", "local_old").put("text", "paid answer")
                    .put("provider", "openai").put("status", "completed").put("openai_protocol_output", typed)))
            db.recordEffortMetric("local_old", "legacy", "general", "high", "high", false,
                8, 0, 25, 10, 0, 0, 5, 0, true, null, 0.125, "estimated")
            assertTrue(db.startRequestAccounting("legacy", "local_old", "openai", "gpt-test", "slot"))
            assertTrue(db.recordRequestAccounting("legacy", "local_old", "completed", reportedCostUsd = 0.0))
        }
        SQLiteDatabase.openDatabase(context.getDatabasePath(databaseName).path, null, SQLiteDatabase.OPEN_READWRITE).use { sql ->
            sql.execSQL("PRAGMA foreign_keys=OFF")
            removeNewColumns(sql, "messages", setOf("telemetry_summary", "openai_usage_ledger"))
            removeNewColumns(sql, "request_accounting", setOf("telemetry_summary"))
            removeNewColumns(sql, "request_events", setOf("telemetry_summary"))
            removeNewColumns(sql, "effort_metrics", setOf("cost_kind"))
            sql.execSQL("DROP TABLE request_recovery_archive")
            sql.version = version
        }
    }

    private fun assertUpgradeAndRepeatedOpen() {
        repeat(2) {
            ChatDatabase(context).use { db ->
                assertEquals(25, db.readableDatabase.version)
                val messages = db.getChat("legacy")!!.getJSONArray("messages")
                assertEquals(2, messages.length())
                assertEquals("paid answer", messages.getJSONObject(1).getString("text"))
                assertEquals("signed-old", messages.getJSONObject(1).getJSONArray("openai_protocol_output")
                    .getJSONObject(0).getString("encrypted_content"))
                val totals = db.usageTotals()
                assertEquals(1, totals.getInt("unknown_cost_requests"))
                assertEquals(0, totals.getInt("known_cost_requests"))
                assertEquals(0.0, totals.getDouble("provider_reported_cost_usd"), 0.0)
                assertEquals("provider_reported", db.listRequestAccounting("legacy").single().getString("cost_kind"))
                assertTrue(db.recordRequestTelemetry("legacy", "local_old", JSONObject().put("calls_executed", 4)))
                assertEquals(4, JSONObject(db.listRequestAccounting("legacy").single()
                    .getString("telemetry_summary")).getInt("calls_executed"))
                assertNull(db.getRequestRecoveryArchive("legacy", "local_old"))
            }
        }
    }

    @Test fun upgrade24To25PreservesHistoryAndTreatsUnclassifiedOldMetricCostAsUnknown() {
        seedLegacy(24)
        assertUpgradeAndRepeatedOpen()
    }

    @Test fun existing25InstallReceivesAdditiveColumnsOnOpenWithoutVersionDowngrade() {
        // J already shipped version 25; the ensure must also repair that exact installed version.
        seedLegacy(25)
        assertUpgradeAndRepeatedOpen()
    }
}
