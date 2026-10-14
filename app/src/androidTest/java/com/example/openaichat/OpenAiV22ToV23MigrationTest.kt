package com.example.openaichat

import android.content.Context
import android.content.ContextWrapper
import android.database.DatabaseErrorHandler
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

/** Opens a real existing v22 file via SQLiteOpenHelper.onUpgrade(22, 24). */
@RunWith(AndroidJUnit4::class)
class OpenAiV22ToV23MigrationTest {
    @Test fun nonDestructiveUpgradePreservesLegacyMessagesAttachmentsAndLedger() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val folder = File(base.cacheDir, "v22-fixture-" + UUID.randomUUID()).apply { mkdirs() }
        val path = File(folder, "astra_chats.db")
        val chatId = "old-chat-" + UUID.randomUUID()
        val localRequestId = "old-req-" + UUID.randomUUID()
        val attachmentPaths = JSONArray().put("/unchanged/legacy/owned-file.txt").toString()
        val annotations = JSONArray().put(org.json.JSONObject()
            .put("type", "file_citation").put("filename", "existing.pdf")).toString()
        val imageManifest = JSONArray().put(org.json.JSONObject()
            .put("state", "READY").put("local_path", "/unchanged/paid.png")).toString()

        // Only the columns added in v23 are absent; the rest is the real v22
        // chats/messages/accounting schema, not a v23 DB relabeled as v22.
        SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
            db.execSQL("""CREATE TABLE chats (
                id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL, response_id TEXT,
                baseline_effort TEXT, updated_at INTEGER NOT NULL, active_response_id TEXT,
                active_request_id TEXT, active_text TEXT, active_status TEXT,
                active_started_at INTEGER, active_effort_pref TEXT, active_effective_effort TEXT,
                active_auto_selected INTEGER NOT NULL DEFAULT 0, active_prompt_chars INTEGER NOT NULL DEFAULT 0,
                active_attachments INTEGER NOT NULL DEFAULT 0, active_background_enabled INTEGER NOT NULL DEFAULT 1,
                active_last_sequence_number INTEGER NOT NULL DEFAULT -1)""")
            db.execSQL("""CREATE TABLE messages (
                id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL,
                position INTEGER NOT NULL, role TEXT NOT NULL, text TEXT NOT NULL,
                response_id TEXT, request_id TEXT, file_ids TEXT, file_names TEXT,
                file_sizes TEXT, file_paths TEXT, file_mimes TEXT, attachments TEXT,
                image_path TEXT, image_file_id TEXT, image_name TEXT, image_mime TEXT,
                image_size INTEGER NOT NULL DEFAULT 0, annotations TEXT,
                generated_images TEXT, tools TEXT, status TEXT, feedback INTEGER,
                content_hash TEXT, provider TEXT, model TEXT, requested_model TEXT,
                response_model TEXT, reasoning_summary TEXT, error TEXT,
                tool_trace TEXT, usage TEXT, finish_reason TEXT,
                openrouter_protocol TEXT, protocol_context_state TEXT,
                FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE)""")
            db.execSQL("""CREATE TABLE request_accounting (
                chat_id TEXT NOT NULL, local_request_id TEXT NOT NULL,
                provider TEXT NOT NULL, requested_model TEXT,
                credential_source TEXT NOT NULL, created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL, local_status TEXT NOT NULL DEFAULT 'running',
                provider_status TEXT, response_id TEXT, http_request_id TEXT,
                input_tokens INTEGER, cached_tokens INTEGER, output_tokens INTEGER,
                reasoning_tokens INTEGER, cost_kind TEXT NOT NULL DEFAULT 'unknown',
                cost_usd TEXT, error TEXT, rounds_sealed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(chat_id, local_request_id),
                FOREIGN KEY(chat_id) REFERENCES chats(id) ON DELETE CASCADE)""")
            db.execSQL("INSERT INTO chats (id,title,response_id,updated_at) VALUES (?,?,?,?)",
                arrayOf(chatId, "Old saved chat", "resp_old", 123456L))
            db.execSQL("""INSERT INTO messages (
                chat_id,position,role,text,response_id,request_id,file_paths,
                annotations,generated_images,status,provider,requested_model
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, 0, "assistant", "Preserved original answer",
                    "resp_old", localRequestId, attachmentPaths, annotations,
                    imageManifest, "completed", "openai", "old-model"))
            db.execSQL("""INSERT INTO request_accounting (
                chat_id,local_request_id,provider,credential_source,created_at,
                updated_at,local_status,input_tokens,output_tokens,cost_kind,cost_usd
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?)""",
                arrayOf(chatId, localRequestId, "openai", "openai_api_key", 10L,
                    20L, "completed", 125, 250, "provider_reported", "0.025"))
            db.version = 22
        }
        val isolatedContext = object : ContextWrapper(base) {
            override fun getDatabasePath(name: String): File = path
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(path.absolutePath,
                factory, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY)
            override fun openOrCreateDatabase(
                name: String, mode: Int, factory: SQLiteDatabase.CursorFactory?,
                errorHandler: DatabaseErrorHandler?
            ): SQLiteDatabase = SQLiteDatabase.openDatabase(path.absolutePath,
                factory, SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY, errorHandler)
        }
        try {
            ChatDatabase(isolatedContext).use { upgraded ->
                assertEquals(24, upgraded.readableDatabase.version)
                val chat = upgraded.getChat(chatId)!!
                val restored = chat.getJSONArray("messages")
                assertEquals(1, restored.length())
                val old = restored.getJSONObject(0)
                assertEquals("Preserved original answer", old.getString("text"))
                assertEquals("resp_old", old.getString("response_id"))
                assertEquals(attachmentPaths, old.getJSONArray("file_paths").toString())
                assertEquals(annotations, old.getJSONArray("annotations").toString())
                assertEquals(imageManifest, old.getJSONArray("generated_images").toString())
                assertFalse("No historical signed output can be fabricated", old.has("openai_protocol_output"))
                assertFalse("No old instruction digest can be fabricated",
                    old.has(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
                upgraded.readableDatabase.rawQuery(
                    "SELECT input_tokens,output_tokens,cost_kind,cost_usd FROM request_accounting WHERE local_request_id=?",
                    arrayOf(localRequestId)).use { cursor ->
                    assertTrue(cursor.moveToFirst())
                    assertEquals(125, cursor.getInt(0))
                    assertEquals(250, cursor.getInt(1))
                    assertEquals("provider_reported", cursor.getString(2))
                    assertEquals("0.025", cursor.getString(3))
                }
                // Reopening the migrated file must not re-run the migration.
            }
            ChatDatabase(isolatedContext).use { reopened ->
                assertEquals(24, reopened.readableDatabase.version)
                assertEquals("Preserved original answer",
                    reopened.getChat(chatId)!!.getJSONArray("messages")
                        .getJSONObject(0).getString("text"))
            }
        } finally {
            path.delete()
            folder.deleteRecursively()
        }
    }
}
