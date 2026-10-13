package com.example.openaichat

import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Non-destructive upgrade of a genuine v23 active flat response cursor. */
@RunWith(AndroidJUnit4::class)
class OpenAiV23ToV24ActiveCheckpointMigrationTest {
    @Test fun preexistingFlatTextAndSequenceStayUntouchedAndIndexedStateStaysUnknown() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "v23-indexed-" + UUID.randomUUID()).apply { mkdirs() }
        val file = File(folder, "astra_chats.db")
        val chatId = "old-active-" + UUID.randomUUID()
        val requestId = "owned-v23"
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("""CREATE TABLE chats (
                id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL,
                response_id TEXT, baseline_effort TEXT,
                updated_at INTEGER NOT NULL,
                active_response_id TEXT, active_request_id TEXT,
                active_text TEXT, active_status TEXT,
                active_started_at INTEGER, active_effort_pref TEXT,
                active_effective_effort TEXT,
                active_auto_selected INTEGER NOT NULL DEFAULT 0,
                active_prompt_chars INTEGER NOT NULL DEFAULT 0,
                active_attachments INTEGER NOT NULL DEFAULT 0,
                active_background_enabled INTEGER NOT NULL DEFAULT 1,
                active_last_sequence_number INTEGER NOT NULL DEFAULT -1)""")
            db.execSQL("""INSERT INTO chats(
                id,title,updated_at,active_response_id,active_request_id,
                active_text,active_status,active_started_at,active_last_sequence_number)
                VALUES (?,?,?, ?,?,?,?,?,?)""",
                arrayOf(chatId, "Unfinished", 1L, "resp_v23", requestId,
                    "Hello", "in_progress", 1L, 10))
            db.version = 23
        }
        val isolated = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = file
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, factory,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(file.absolutePath, factory,
                SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try {
            ChatDatabase(isolated).use { db ->
                assertEquals(24, db.readableDatabase.version)
                val active = db.getActiveResponse(chatId)!!
                assertEquals("Hello", active.getString("text"))
                assertEquals(10L, active.getLong("last_sequence_number"))
                assertEquals(requestId, active.getString("request_id"))
                assertFalse(active.has("indexed_checkpoint"))
                assertTrue(ResponseIndexedCheckpoint.restore(active).legacyFlatCheckpoint)
            }
            ChatDatabase(isolated).use { db ->
                assertEquals("Hello", db.getActiveResponse(chatId)!!.getString("text"))
                assertEquals(24, db.readableDatabase.version)
            }
        } finally {
            file.delete()
            folder.deleteRecursively()
        }
    }
}
