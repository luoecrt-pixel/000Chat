package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseLifecycleTest {
    private lateinit var context: Context
    private var database: ChatDatabase? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        resetDatabaseFile()
    }

    @After
    fun tearDown() {
        database?.close()
        database = null
        resetDatabaseFile()
    }

    @Test
    fun migratesV13ToV15AndPreservesActiveResponseOwnership() {
        createLegacyChatsDatabase(version = 13)

        database = ChatDatabase(context)
        val active = database!!.getActiveResponse("chat-a")

        assertNotNull(active)
        assertEquals("req-a", active!!.optString("request_id"))
        assertEquals("resp-a", active.optString("response_id"))
        assertEquals("partial-v13", active.optString("text"))
        assertEquals("in_progress", active.optString("status"))
        assertTrue(active.optBoolean("background_enabled", false))
        assertEquals(-1L, active.optLong("last_sequence_number", Long.MIN_VALUE))
        assertColumnExists("chats", "active_background_enabled")
        assertColumnExists("chats", "active_last_sequence_number")
    }

    @Test
    fun migratesV14ToV15WithSequenceCursorDefault() {
        createLegacyChatsDatabase(version = 14, includeBackgroundEnabled = true)

        database = ChatDatabase(context)
        assertColumnExists("chats", "active_last_sequence_number")

        database!!.writableDatabase.execSQL(
            "INSERT INTO chats (id, title, updated_at, active_status) VALUES ('chat-b', 'Чат', 456, 'in_progress')"
        )
        database!!.readableDatabase.query(
            "chats",
            arrayOf("active_background_enabled", "active_last_sequence_number"),
            "id=?",
            arrayOf("chat-b"),
            null, null, null, "1"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(1, cursor.getInt(0))
            assertEquals(-1L, cursor.getLong(1))
        }
    }
    @Test
    fun partialResponseAndSequenceCursorSurviveCloseAndReopen() {
        database = ChatDatabase(context)
        database!!.ensureChat("chat-a", "Чат")

        database!!.setActiveResponse(
            chatId = "chat-a",
            responseId = "resp-a",
            requestId = "req-a",
            text = "partial",
            status = "in_progress",
            startedAt = 123L,
            effortPref = "high",
            effectiveEffort = "high",
            autoSelected = false,
            promptChars = 7,
            attachments = 0,
            backgroundEnabled = true
        )
        database!!.updateActiveResponse(
            chatId = "chat-a",
            text = "partial response",
            lastSequenceNumber = 7L,
            expectedRequestId = "req-a"
        )
        database!!.close()
        database = null

        database = ChatDatabase(context)
        val active = database!!.getActiveResponse("chat-a")

        assertNotNull(active)
        assertEquals("partial response", active!!.optString("text"))
        assertEquals(7L, active.optLong("last_sequence_number"))
        assertEquals("req-a", active.optString("request_id"))
        assertEquals("resp-a", active.optString("response_id"))
    }

    @Test
    fun staleRequestCannotOverwritePartialResponseAfterNewRequestOwnsChat() {
        database = ChatDatabase(context)
        database!!.ensureChat("chat-a", "Чат")
        database!!.setActiveResponse(
            chatId = "chat-a",
            responseId = "resp-new",
            requestId = "req-new",
            text = "new",
            status = "in_progress",
            startedAt = 456L,
            effortPref = "high",
            effectiveEffort = "high",
            autoSelected = false,
            promptChars = 3,
            attachments = 0,
            backgroundEnabled = true
        )

        database!!.updateActiveResponse(
            chatId = "chat-a",
            text = "stale overwrite",
            lastSequenceNumber = 99L,
            expectedRequestId = "req-old"
        )

        val active = database!!.getActiveResponse("chat-a")
        assertNotNull(active)
        assertEquals("req-new", active!!.optString("request_id"))
        assertEquals("resp-new", active.optString("response_id"))
        assertEquals("new", active.optString("text"))
        assertEquals(-1L, active.optLong("last_sequence_number"))
        assertTrue(active.optBoolean("background_enabled", false))
    }

    private fun resetDatabaseFile() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath(DB_NAME))
    }

    private fun createLegacyChatsDatabase(version: Int, includeBackgroundEnabled: Boolean = false) {
        resetDatabaseFile()

        val db = context.openOrCreateDatabase(DB_NAME, Context.MODE_PRIVATE, null)
        val backgroundColumn = if (includeBackgroundEnabled) {
            ", active_background_enabled INTEGER NOT NULL DEFAULT 1"
        } else {
            ""
        }
        db.execSQL(
            """
            CREATE TABLE chats (
                id TEXT PRIMARY KEY NOT NULL,
                title TEXT NOT NULL,
                response_id TEXT,
                baseline_effort TEXT,
                updated_at INTEGER NOT NULL,
                active_response_id TEXT,
                active_request_id TEXT,
                active_text TEXT,
                active_status TEXT,
                active_started_at INTEGER,
                active_effort_pref TEXT,
                active_effective_effort TEXT,
                active_auto_selected INTEGER NOT NULL DEFAULT 0,
                active_prompt_chars INTEGER NOT NULL DEFAULT 0,
                active_attachments INTEGER NOT NULL DEFAULT 0
                $backgroundColumn
            )
            """.trimIndent()
        )
        val backgroundInsertColumn = if (includeBackgroundEnabled) ", active_background_enabled" else ""
        val backgroundInsertValue = if (includeBackgroundEnabled) ", 1" else ""
        db.execSQL(
            """
            INSERT INTO chats (
                id, title, updated_at, active_response_id, active_request_id, active_text, active_status,
                active_started_at, active_effort_pref, active_effective_effort,
                active_auto_selected, active_prompt_chars, active_attachments$backgroundInsertColumn
            ) VALUES (
                'chat-a', 'Чат', 123, 'resp-a', 'req-a', 'partial-v13', 'in_progress',
                123, 'high', 'high', 0, 12, 0$backgroundInsertValue
            )
            """.trimIndent()
        )
        db.version = version
        db.close()
    }

    private fun assertColumnExists(table: String, column: String) {
        val db = SQLiteDatabase.openDatabase(
            context.getDatabasePath(dbName).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        )
        db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
            var found = false
            while (cursor.moveToNext()) {
                if (cursor.getString(cursor.getColumnIndexOrThrow("name")) == column) {
                    found = true
                    break
                }
            }
            assertTrue("Missing $table.$column", found)
        }
        db.close()
    }

    companion object {
        private const val DB_NAME = "astra_chats.db"
        
    }
}
