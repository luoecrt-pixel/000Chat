package com.example.openaichat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistent chat history and lightweight response/effort telemetry.
 * Settings remain in SharedPreferences; chat history no longer lives in one giant JSON blob.
 */
class ChatDatabase(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    companion object {
        private const val DB_NAME = "astra_chats.db"
        private const val DB_VERSION = 24
        private const val CHAT_TABLE = "chats"
        private const val MESSAGE_TABLE = "messages"
        private const val METRICS_TABLE = "effort_metrics"
        private const val REQUEST_EVENT_TABLE = "request_events"
        private const val REQUEST_ACCOUNTING_TABLE = "request_accounting"
        private const val OPENROUTER_ROUND_TABLE = "openrouter_request_rounds"
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    private fun ensureRequestEventsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $REQUEST_EVENT_TABLE (
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
                cost_kind TEXT NOT NULL DEFAULT 'unknown',
                cost_usd TEXT,
                latency_ms INTEGER NOT NULL DEFAULT 0,
                tool_summary TEXT,
                error TEXT,
                FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_request_events_chat_created ON $REQUEST_EVENT_TABLE(chat_id, created_at DESC, id DESC)")
    }

    private fun ensureRequestAccountingTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $REQUEST_ACCOUNTING_TABLE (
                chat_id TEXT NOT NULL,
                local_request_id TEXT NOT NULL,
                provider TEXT NOT NULL,
                requested_model TEXT,
                credential_source TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL,
                local_status TEXT NOT NULL DEFAULT 'running',
                provider_status TEXT,
                response_id TEXT,
                http_request_id TEXT,
                input_tokens INTEGER,
                cached_tokens INTEGER,
                output_tokens INTEGER,
                reasoning_tokens INTEGER,
                cost_kind TEXT NOT NULL DEFAULT 'unknown',
                cost_usd TEXT,
                error TEXT,
                rounds_sealed INTEGER NOT NULL DEFAULT 0,
                PRIMARY KEY(chat_id, local_request_id),
                FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_request_accounting_created ON $REQUEST_ACCOUNTING_TABLE(created_at)")
    }

    /** Each HTTP tool-loop round is distinct from its local request ID. */
    private fun ensureOpenRouterRoundsTable(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS $OPENROUTER_ROUND_TABLE (
                chat_id TEXT NOT NULL,
                local_request_id TEXT NOT NULL,
                round_index INTEGER NOT NULL,
                generation_id TEXT,
                cost_kind TEXT NOT NULL DEFAULT 'unknown',
                cost_usd TEXT,
                input_tokens INTEGER,
                output_tokens INTEGER,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(chat_id, local_request_id, round_index),
                FOREIGN KEY(chat_id, local_request_id)
                    REFERENCES $REQUEST_ACCOUNTING_TABLE(chat_id, local_request_id)
                    ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_openrouter_generation_identity ON $OPENROUTER_ROUND_TABLE(generation_id) WHERE generation_id IS NOT NULL")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureRequestEventsTable(db)
        ensureRequestAccountingTable(db)
        ensureOpenRouterRoundsTable(db)
        ensureLocalArtifactsTable(db)
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE $CHAT_TABLE (
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
                active_attachments INTEGER NOT NULL DEFAULT 0,
                active_background_enabled INTEGER NOT NULL DEFAULT 1,
                active_last_sequence_number INTEGER NOT NULL DEFAULT -1,
                active_indexed_checkpoint TEXT
            )
        """.trimIndent())
        db.execSQL("""
            CREATE TABLE $MESSAGE_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id TEXT NOT NULL,
                position INTEGER NOT NULL,
                role TEXT NOT NULL,
                text TEXT NOT NULL,
                response_id TEXT,
                request_id TEXT,
                file_ids TEXT,
                file_names TEXT,
                file_sizes TEXT,
                file_paths TEXT,
                file_mimes TEXT,
                attachments TEXT,
                image_path TEXT,
                image_file_id TEXT,
                image_name TEXT,
                image_mime TEXT,
                image_size INTEGER NOT NULL DEFAULT 0,
                annotations TEXT,
                generated_images TEXT,
                tools TEXT,
                status TEXT,
                feedback INTEGER,
                content_hash TEXT,
                provider TEXT,
                model TEXT,
                requested_model TEXT,
                response_model TEXT,
                reasoning_summary TEXT,
                error TEXT,
                tool_trace TEXT,
                usage TEXT,
                finish_reason TEXT,
                openrouter_protocol TEXT,
                protocol_context_state TEXT,
                openai_instructions_sha256 TEXT,
                openai_protocol_output TEXT,
                FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_messages_chat_position ON $MESSAGE_TABLE(chat_id, position)")
        db.execSQL("CREATE INDEX idx_messages_chat_request ON $MESSAGE_TABLE(chat_id, request_id)")
        db.execSQL("""
            CREATE TABLE $METRICS_TABLE (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                chat_id TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                effort_pref TEXT NOT NULL,
                effective_effort TEXT NOT NULL,
                auto_selected INTEGER NOT NULL,
                prompt_chars INTEGER NOT NULL,
                attachments INTEGER NOT NULL,
                latency_ms INTEGER NOT NULL,
                input_tokens INTEGER NOT NULL DEFAULT 0,
                cached_tokens INTEGER NOT NULL DEFAULT 0,
                cache_write_tokens INTEGER NOT NULL DEFAULT 0,
                output_tokens INTEGER NOT NULL DEFAULT 0,
                reasoning_tokens INTEGER NOT NULL DEFAULT 0,
                success INTEGER NOT NULL,
                error_type TEXT,
                request_id TEXT,
                task_class TEXT NOT NULL DEFAULT 'general',
                feedback INTEGER,
                estimated_cost_usd REAL NOT NULL DEFAULT 0
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_effort_metrics_created ON $METRICS_TABLE(created_at)")
        db.execSQL("CREATE INDEX idx_effort_metrics_task ON $METRICS_TABLE(task_class, effective_effort, feedback)")
        db.execSQL("CREATE UNIQUE INDEX idx_effort_metrics_chat_request ON $METRICS_TABLE(chat_id, request_id) WHERE request_id IS NOT NULL")
        db.execSQL("""
            CREATE TABLE $REQUEST_EVENT_TABLE (
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
                cost_kind TEXT NOT NULL DEFAULT 'unknown',
                cost_usd TEXT,
                latency_ms INTEGER NOT NULL DEFAULT 0,
                tool_summary TEXT,
                error TEXT,
                FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE INDEX idx_request_events_chat_created ON $REQUEST_EVENT_TABLE(chat_id, created_at DESC, id DESC)")
        ensureRequestAccountingTable(db)
        ensureOpenRouterRoundsTable(db)
        ensureLocalArtifactsTable(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 21) {
            // A legacy or partially upgraded DB can already contain these columns.
            // Do not turn an unchanged historic message into a destructive migration.
            val columns = db.rawQuery("PRAGMA table_info($MESSAGE_TABLE)", null).use { c ->
                val names = mutableSetOf<String>()
                val nameIndex = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) names.add(c.getString(nameIndex))
                names
            }
            if ("finish_reason" !in columns) db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN finish_reason TEXT")
            if ("openrouter_protocol" !in columns) db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN openrouter_protocol TEXT")
            ensureLocalArtifactsTable(db)
        }
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_file_id TEXT")
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN tools TEXT")
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN status TEXT")
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN feedback INTEGER")
            db.execSQL("ALTER TABLE $METRICS_TABLE ADD COLUMN request_id TEXT")
            db.execSQL("ALTER TABLE $METRICS_TABLE ADD COLUMN task_class TEXT NOT NULL DEFAULT 'general'")
            db.execSQL("ALTER TABLE $METRICS_TABLE ADD COLUMN feedback INTEGER")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_effort_metrics_task ON $METRICS_TABLE(task_class, effective_effort, feedback)")
        }
        if (oldVersion < 5) {
            // request_id already exists in the original messages schema; older v4
            // migration attempted to add it again, which caused a duplicate-column crash.
        }
        if (oldVersion < 6) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN file_sizes TEXT")
        }
        if (oldVersion < 7) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN generated_images TEXT")
        }
        if (oldVersion < 8) {
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_response_id TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_request_id TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_text TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_status TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_started_at INTEGER")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_effort_pref TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_effective_effort TEXT")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_auto_selected INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_prompt_chars INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_attachments INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 9) {
            // v9 reserves room for durable background/composer state; no schema change required.
        }
        if (oldVersion < 10) {
            db.execSQL("ALTER TABLE $METRICS_TABLE ADD COLUMN estimated_cost_usd REAL NOT NULL DEFAULT 0")
        }
        if (oldVersion < 11) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN file_paths TEXT")
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN file_mimes TEXT")
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_name TEXT")
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_mime TEXT")
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN image_size INTEGER NOT NULL DEFAULT 0")
        }
        if (oldVersion < 12) {
            db.execSQL("""
                DELETE FROM $METRICS_TABLE
                WHERE request_id IS NOT NULL
                  AND id NOT IN (
                      SELECT MIN(id)
                      FROM $METRICS_TABLE
                      WHERE request_id IS NOT NULL
                      GROUP BY chat_id, request_id
                  )
            """.trimIndent())
            db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_effort_metrics_chat_request ON $METRICS_TABLE(chat_id, request_id) WHERE request_id IS NOT NULL")
        }
        if (oldVersion < 13) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN content_hash TEXT")
        }
        if (oldVersion < 14) {
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_background_enabled INTEGER NOT NULL DEFAULT 1")
        }
        if (oldVersion < 15) {
            db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_last_sequence_number INTEGER NOT NULL DEFAULT -1")
        }
        if (oldVersion < 16) {
            db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN attachments TEXT")
        }
        if (oldVersion < 17) {
            MessageMetadataCodec.allKeys.filter {
                it !in setOf("finish_reason", "openrouter_protocol", "protocol_context_state",
                    "openai_instructions_sha256", "openai_protocol_output")
            }.forEach { key ->
                db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN $key TEXT")
            }
        }
        if (oldVersion < 18) {
            // Pre-v17 databases can have no request_events table.
            ensureRequestEventsTable(db)
            val columns = db.rawQuery("PRAGMA table_info($REQUEST_EVENT_TABLE)", null).use { cursor ->
                val names = mutableSetOf<String>()
                val nameColumn = cursor.getColumnIndexOrThrow("name")
                while (cursor.moveToNext()) names.add(cursor.getString(nameColumn))
                names
            }
            if ("cost_kind" !in columns) {
                db.execSQL("ALTER TABLE $REQUEST_EVENT_TABLE ADD COLUMN cost_kind TEXT NOT NULL DEFAULT 'unknown'")
            }
            if ("cost_usd" !in columns) {
                db.execSQL("ALTER TABLE $REQUEST_EVENT_TABLE ADD COLUMN cost_usd TEXT")
            }
        }
        if (oldVersion < 19) {
            // There is no trustworthy legacy local_request_id/cost coverage;
            // never fabricate ledger entries by assigning older event zeros.
            ensureRequestAccountingTable(db)
        }
        if (oldVersion < 20) {
            val columns = db.rawQuery("PRAGMA table_info($REQUEST_ACCOUNTING_TABLE)", null).use { c ->
                val found = mutableSetOf<String>()
                while (c.moveToNext()) found += c.getString(c.getColumnIndexOrThrow("name"))
                found
            }
            if ("rounds_sealed" !in columns) {
                db.execSQL("ALTER TABLE $REQUEST_ACCOUNTING_TABLE ADD COLUMN rounds_sealed INTEGER NOT NULL DEFAULT 0")
            }
            ensureOpenRouterRoundsTable(db)
        }
        if (oldVersion < 22) {
            val hasProtocolContext = db.rawQuery("PRAGMA table_info($MESSAGE_TABLE)", null).use { c ->
                val nameIndex = c.getColumnIndexOrThrow("name")
                var found = false
                while (c.moveToNext()) if (c.getString(nameIndex) == "protocol_context_state") found = true
                found
            }
            if (!hasProtocolContext) db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN protocol_context_state TEXT")
        }
        if (oldVersion < 23) {
            // Older chats never contained these typed protocol columns: retain
            // their existing history and treat missing output/digests as unknown.
            // No synthetic fingerprints or output are fabricated in migration.
            val existing = db.rawQuery("PRAGMA table_info($MESSAGE_TABLE)", null).use { c ->
                val names = mutableSetOf<String>()
                val nameColumn = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) names += c.getString(nameColumn)
                names
            }
            if ("openai_instructions_sha256" !in existing)
                db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN openai_instructions_sha256 TEXT")
            if ("openai_protocol_output" !in existing)
                db.execSQL("ALTER TABLE $MESSAGE_TABLE ADD COLUMN openai_protocol_output TEXT")
        }
        if (oldVersion < 24 && newVersion >= 24) {
            val columns = db.rawQuery("PRAGMA table_info($CHAT_TABLE)", null).use { c ->
                val names = mutableSetOf<String>()
                val nameIndex = c.getColumnIndexOrThrow("name")
                while (c.moveToNext()) names += c.getString(nameIndex)
                names
            }
            if ("active_indexed_checkpoint" !in columns)
                db.execSQL("ALTER TABLE $CHAT_TABLE ADD COLUMN active_indexed_checkpoint TEXT")
        }
    }

    /** One-time migration from v4.13's SharedPreferences chat_store JSON. */
    fun migrateLegacyPrefs(prefs: android.content.SharedPreferences) {
        if (prefs.getBoolean("chat_store_migrated_v7", false)) return
        val raw = prefs.getString("chat_store", null)
        writableDatabase.transaction { sql ->
            if (!raw.isNullOrBlank()) {
                val chats = try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
                for (i in 0 until chats.length()) {
                    val c = chats.optJSONObject(i) ?: continue
                    upsertChatObject(sql, c)
                }
            }
        }
        prefs.edit().putBoolean("chat_store_migrated_v7", true).remove("chat_store").apply()
    }

    fun ensureChat(id: String, title: String = "Новый чат") {
        if (id.isBlank()) return
        val d = writableDatabase
        d.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(id), null, null, null, "1").use { c ->
            if (c.moveToFirst()) return
        }
        val v = ContentValues().apply {
            put("id", id)
            put("title", title.ifBlank { "Новый чат" })
            putNull("response_id")
            putNull("baseline_effort")
            put("updated_at", System.currentTimeMillis())
        }
        d.insertOrThrow(CHAT_TABLE, null, v)
    }

    fun getChat(id: String): JSONObject? {
        val d = readableDatabase
        SqliteLargeTextQuery.query(d, CHAT_TABLE, null, "id=?", arrayOf(id), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) return null
            val chat = JSONObject()
                .put("id", c.getString(c.getColumnIndexOrThrow("id")))
                .put("title", c.getString(c.getColumnIndexOrThrow("title")))
            val responseId = c.getString(c.getColumnIndexOrThrow("response_id"))
            val baseline = c.getString(c.getColumnIndexOrThrow("baseline_effort"))
            if (!responseId.isNullOrBlank()) chat.put("response_id", responseId) else chat.put("response_id", JSONObject.NULL)
            if (!baseline.isNullOrBlank()) chat.put("baseline_effort", baseline) else chat.put("baseline_effort", JSONObject.NULL)
            chat.put("messages", loadMessages(id))
            return chat
        }
    }

    fun getChatTitle(id: String): String? {
        if (id.isBlank()) return null
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE, arrayOf("title"), "id=?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("title")) else null
        }
    }

    fun listChats(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE, null, null, null, null, null, "updated_at DESC, id DESC").use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val obj = JSONObject()
                    .put("id", id)
                    .put("title", c.getString(c.getColumnIndexOrThrow("title")))
                val responseId = c.getString(c.getColumnIndexOrThrow("response_id"))
                val baseline = c.getString(c.getColumnIndexOrThrow("baseline_effort"))
                if (!responseId.isNullOrBlank()) obj.put("response_id", responseId) else obj.put("response_id", JSONObject.NULL)
                if (!baseline.isNullOrBlank()) obj.put("baseline_effort", baseline) else obj.put("baseline_effort", JSONObject.NULL)
                obj.put("messages", loadMessages(id))
                out.add(obj)
            }
        }
        return out
    }

    /** Lightweight list for the chat picker; never loads full message histories. */
    fun listChatSummaries(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE,
            arrayOf("id", "title", "response_id", "baseline_effort", "updated_at"),
            null, null, null, null, "updated_at DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val obj = JSONObject()
                    .put("id", c.getString(c.getColumnIndexOrThrow("id")))
                    .put("title", c.getString(c.getColumnIndexOrThrow("title")))
                val responseId = c.getString(c.getColumnIndexOrThrow("response_id"))
                val baseline = c.getString(c.getColumnIndexOrThrow("baseline_effort"))
                if (!responseId.isNullOrBlank()) obj.put("response_id", responseId)
                if (!baseline.isNullOrBlank()) obj.put("baseline_effort", baseline)
                out.add(obj)
            }
        }
        return out
    }


    fun renameChat(id: String, title: String) {
        writableDatabase.update(CHAT_TABLE, ContentValues().apply {
            put("title", title.ifBlank { "Чат" })
            put("updated_at", System.currentTimeMillis())
        }, "id=?", arrayOf(id))
    }

    fun deleteChat(id: String) {
        writableDatabase.transaction { db ->
            db.delete(MESSAGE_TABLE, "chat_id=?", arrayOf(id))
            db.delete(METRICS_TABLE, "chat_id=?", arrayOf(id))
            // request_events and request_accounting are removed by FK CASCADE.
            db.delete(CHAT_TABLE, "id=?", arrayOf(id))
        }
    }

    fun upsertCurrentChat(id: String, title: String, responseId: String?, baselineEffort: String?, messages: List<JSONObject>) {
        if (id.isBlank()) return
        writableDatabase.transaction { db ->
            upsertCurrentChatInDb(db, id, title, responseId, baselineEffort, messages)
        }
    }

    /**
     * Ordinary UI writes must not erase an assistant already committed by a Worker.
     * Explicit Edit/Delete can use the exact replacement writer instead.
     */
    fun upsertUiSnapshotPreservingTail(
        id: String, title: String, responseId: String?,
        baselineEffort: String?, messages: List<JSONObject>
    ): Boolean {
        if (id.isBlank()) return false
        var committed = false
        writableDatabase.transaction { db ->
            // UI snapshots can be older than Worker/async metadata, even when they have
            // the same number of messages. Never rewrite existing rows here.
            val exists = db.query(CHAT_TABLE, arrayOf("id"), "id=?",
                arrayOf(id), null, null, null, "1").use { it.moveToFirst() }
            if (!exists) return@transaction
            val known = mutableSetOf<String>()
            val stoppedRequests = mutableSetOf<String>()
            db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("local_request_id"),
                "chat_id=? AND local_status='stopped'", arrayOf(id), null, null, null).use { c ->
                while (c.moveToNext()) stoppedRequests.add(c.getString(0))
            }
            var nextPosition = 0
            var existingCount = 0
            db.query(MESSAGE_TABLE, arrayOf("role","request_id","position"),
                "chat_id=?", arrayOf(id), null, null, "position ASC").use { cursor ->
                while(cursor.moveToNext()) {
                    existingCount++
                    val role = cursor.getString(0).orEmpty()
                    val requestId = cursor.getString(1).orEmpty()
                    if (requestId.isNotBlank()) known.add("$role|$requestId")
                    nextPosition = maxOf(nextPosition, cursor.getInt(2) + 1)
                }
            }
            // Reject a truly stale, shorter snapshot if it contributes no new user request.
            // A snapshot with a different new request at the same position is still appendable.
            val hasNewUser = messages.any { item ->
                val requestId = item.optString("request_id")
                item.optString("role") == "user" && requestId.isNotBlank() &&
                    requestId != "null" && requestId !in stoppedRequests && "user|$requestId" !in known
            }
            if (!hasNewUser && messages.size < existingCount) return@transaction
            // A Worker may have inserted an assistant at position N while the UI
            // produced another user at position N. Append by request identity,
            // not by the count or stale positional alignment.
            messages.forEach { snapshot ->
                val role = snapshot.optString("role")
                val requestId = snapshot.optString("request_id")
                val identity = "$role|$requestId"
                if (role == "user" && requestId.isNotBlank() && requestId != "null" &&
                    requestId !in stoppedRequests &&
                    known.add(identity)) {
                    val copy = JSONObject(snapshot.toString())
                    db.insertOrThrow(MESSAGE_TABLE, null,
                        messageContentValues(id, nextPosition++, copy,
                            ChatMessageFingerprint.of(copy)))
                }
            }
            val values = ContentValues().apply {
                if (title.isNotBlank()) put("title", title)
                // Response/effort metadata is owned by terminal writer, not stale UI.
                put("updated_at", System.currentTimeMillis())
            }
            db.update(CHAT_TABLE, values, "id=?", arrayOf(id))
            committed = true
        }
        return committed
    }

    /**
     * Atomically persists a completed response only while the same request is still active.
     * This closes the stop/delete/new-request race in background recovery.
     */
    fun upsertCurrentChatIfActiveRequest(
        id: String,
        title: String,
        responseId: String?,
        baselineEffort: String?,
        messages: List<JSONObject>,
        expectedRequestId: String
    ): Boolean {
        if (id.isBlank() || expectedRequestId.isBlank()) return false
        var committed = false
        writableDatabase.transaction { db ->
            val activeRequest = db.query(CHAT_TABLE, arrayOf("active_request_id"),
                "id=?",
                arrayOf(id),
                null, null, null, "1"
            ).use { c ->
                if (!c.moveToFirst()) null else c.getString(0)
            }
            if (!ActiveResponseWritePolicy.shouldCommit(activeRequest, expectedRequestId)) return@transaction
            upsertCurrentChatInDb(db, id, title, responseId, baselineEffort, messages)
            committed = true
        }
        return committed
    }

    /**
     * One-shot finalization claim shared by Activity, Worker and fatal recovery.
     * The request's active ownership is consumed in the *same transaction* as
     * the message checkpoint. A second terminal callback cannot commit again.
     * No new requests or deleted chats can be resurrected.
     */
    fun finalizeCurrentChatIfActiveRequest(
        id: String,
        title: String,
        responseId: String?,
        baselineEffort: String?,
        messages: List<JSONObject>,
        expectedRequestId: String
    ): Boolean {
        // Reject a single terminal assistant with a foreign/missing local request_id.
        // Otherwise a provider HTTP request ID can consume the active claim while
        // silently persisting no assistant. Multi-message snapshots may legitimately
        // contain other requests, or be empty during an explicit failure claim.
        if (messages.size == 1 && JsonProtocol.string(messages[0], "role") == "assistant" &&
            JsonProtocol.string(messages[0], "request_id") != expectedRequestId) return false
        return completeActiveRequestOnce(id, expectedRequestId) { db ->
        // This callback may hold an older full-chat snapshot. Commit only the assistant
        // belonging to the winning request, preserving newer messages/feedback/files.
        val assistant = messages.lastOrNull { item ->
            item.optString("role") == "assistant" && item.optString("request_id") == expectedRequestId
        }
        if (assistant != null) {
            val existingId = db.query(MESSAGE_TABLE, arrayOf("id"),
                "chat_id=? AND request_id=? AND role='assistant'",
                arrayOf(id, expectedRequestId), null, null, "position DESC", "1"
            ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) else null }
            if (existingId == null) {
                val nextPosition = db.rawQuery(
                    "SELECT COALESCE(MAX(position), -1) + 1 FROM $MESSAGE_TABLE WHERE chat_id=?",
                    arrayOf(id)
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                val copy = JSONObject(assistant.toString())
                db.insertOrThrow(
                    MESSAGE_TABLE, null,
                    messageContentValues(id, nextPosition, copy, ChatMessageFingerprint.of(copy))
                )
            } else {
                val position = db.query(MESSAGE_TABLE, arrayOf("position"), "id=?", arrayOf(existingId.toString()),
                    null,null,null,"1"
                ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
                db.update(MESSAGE_TABLE,
                    messageContentValues(id, position, assistant, ChatMessageFingerprint.of(assistant)),
                    "id=?", arrayOf(existingId.toString())
                )
            }
            queueAnswerNotification(db, id, expectedRequestId, assistant.optString("status") == "completed")
        }
        if (!responseId.isNullOrBlank() || !baselineEffort.isNullOrBlank()) {
            db.update(CHAT_TABLE, ContentValues().apply {
                responseId?.takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                baselineEffort?.takeIf { it.isNotBlank() }?.let { put("baseline_effort", it) }
                put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(id))
        }
        }
    }

    /** Fatal recovery with no new messages still consumes its one-shot claim. */
    fun claimActiveRequestFailure(chatId: String, expectedRequestId: String): Boolean =
        completeActiveRequestOnce(chatId, expectedRequestId) { db -> queueAnswerNotification(db, chatId, expectedRequestId, false) }

    /**
     * Stop and active-cursor release are a single SQLite transaction. A Worker
     * finalizer either wins the ownership claim first or observes Stop; the
     * two cannot both persist a terminal assistant message.
     */
    fun stopActiveRequestOnce(chatId: String, expectedRequestId: String, removeUserMessage: Boolean = false): Boolean {
        if (chatId.isBlank() || expectedRequestId.isBlank()) return false
        var stopped = false
        writableDatabase.transaction { db ->
            val active = db.query(CHAT_TABLE, arrayOf("active_request_id", "active_response_id"), "id=?",
                arrayOf(chatId), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) Pair(cursor.getString(0), cursor.getString(1)) else null
            }
            if (!ActiveResponseWritePolicy.shouldCommit(active?.first, expectedRequestId)) {
                return@transaction
            }
            // A tombstone also covers legacy requests with no accounting row.
            db.insertWithOnConflict(REQUEST_ACCOUNTING_TABLE, null, ContentValues().apply {
                put("chat_id", chatId); put("local_request_id", expectedRequestId)
                put("provider", "unknown"); put("credential_source", "unknown")
                put("created_at", System.currentTimeMillis()); put("updated_at", System.currentTimeMillis())
                put("local_status", "running")
            }, SQLiteDatabase.CONFLICT_IGNORE)
            db.update(
                REQUEST_ACCOUNTING_TABLE,
                ContentValues().apply {
                    put("local_status", "stopped")
                    put("updated_at", System.currentTimeMillis())
                    active?.second?.takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                },
                "chat_id=? AND local_request_id=? AND local_status='running'",
                arrayOf(chatId, expectedRequestId)
            )
            val cleared = ContentValues().apply {
                putNull("active_response_id")
                putNull("active_request_id")
                putNull("active_text")
                putNull("active_indexed_checkpoint")
                putNull("active_status")
                putNull("active_started_at")
                putNull("active_effort_pref")
                putNull("active_effective_effort")
                put("active_auto_selected", 0)
                put("active_prompt_chars", 0)
                put("active_attachments", 0)
                put("active_background_enabled", 1)
                put("active_last_sequence_number", -1)
                put("updated_at", System.currentTimeMillis())
            }
            stopped = db.update(
                CHAT_TABLE, cleared, "id=? AND active_request_id=?",
                arrayOf(chatId, expectedRequestId)
            ) == 1
            if (stopped) {
                if (removeUserMessage) db.delete(MESSAGE_TABLE,
                    "chat_id=? AND request_id=? AND role='user'", arrayOf(chatId, expectedRequestId))
                // One event for this exact stopped local request, within the CAS transaction.
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO $REQUEST_EVENT_TABLE (
                        chat_id, created_at, event_type, request_id, provider,
                        requested_model, model_match, status, input_tokens,
                        cached_tokens, output_tokens, reasoning_tokens,
                        cost_kind, cost_usd, latency_ms
                    )
                    SELECT chat_id, ?, 'request_stopped', local_request_id, provider,
                        requested_model, 'unknown', 'stopped',
                        COALESCE(input_tokens, 0), COALESCE(cached_tokens, 0),
                        COALESCE(output_tokens, 0), COALESCE(reasoning_tokens, 0),
                        CASE WHEN cost_kind='provider_reported' AND cost_usd IS NOT NULL
                            THEN 'provider_reported' ELSE 'unknown' END,
                        CASE WHEN cost_kind='provider_reported' THEN cost_usd ELSE NULL END,
                        MAX(0, ? - created_at)
                    FROM $REQUEST_ACCOUNTING_TABLE
                    WHERE chat_id=? AND local_request_id=?
                """.trimIndent(), arrayOf(now, now, chatId, expectedRequestId))
            }
        }
        return stopped
    }

    private fun completeActiveRequestOnce(
        chatId: String,
        expectedRequestId: String,
        persist: (SQLiteDatabase) -> Unit
    ): Boolean {
        if (chatId.isBlank() || expectedRequestId.isBlank()) return false
        var committed = false
        terminalClaimWritableDatabase().transaction { db ->
            // CAS inside an SQLite write transaction serializes simultaneous
            // Worker/Activity claims even when they use separate helpers.
            val activeId = db.query(CHAT_TABLE, arrayOf("active_request_id"), "id=?",
                arrayOf(chatId), null, null, null, "1"
            ).use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
            if (!ActiveResponseWritePolicy.shouldCommit(activeId, expectedRequestId)) {
                return@transaction
            }
            persist(db)
            val cleared = ContentValues().apply {
                putNull("active_response_id")
                putNull("active_request_id")
                putNull("active_text")
                putNull("active_indexed_checkpoint")
                putNull("active_status")
                putNull("active_started_at")
                putNull("active_effort_pref")
                putNull("active_effective_effort")
                put("active_auto_selected", 0)
                put("active_prompt_chars", 0)
                put("active_attachments", 0)
                put("active_background_enabled", 1)
                put("active_last_sequence_number", -1)
                put("updated_at", System.currentTimeMillis())
            }
            committed = db.update(
                CHAT_TABLE, cleared, "id=? AND active_request_id=?",
                arrayOf(chatId, expectedRequestId)
            ) == 1
        }
        return committed
    }

    /**
     * Activity and recovery Worker may open distinct SQLiteOpenHelper instances
     * while the app is setting its journal mode. Opening a connection can then
     * raise SQLITE_BUSY *before* our owned terminal transaction starts. Retry
     * only that side-effect-free connection open, never the HTTP request or an
     * already-started terminal claim.
     */
    private fun terminalClaimWritableDatabase(): SQLiteDatabase {
        var pauseMs = 80L
        repeat(4) { attempt ->
            try {
                return writableDatabase
            } catch (busy: android.database.sqlite.SQLiteDatabaseLockedException) {
                if (attempt == 3) throw busy
                try {
                    Thread.sleep(pauseMs)
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw busy
                }
                pauseMs *= 2L
            }
        }
        error("Unreachable terminal database opener")
    }

    private fun upsertCurrentChatInDb(
        db: SQLiteDatabase,
        id: String,
        title: String,
        responseId: String?,
        baselineEffort: String?,
        messages: List<JSONObject>
    ) {
        val exists = db.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(id), null, null, null, "1").use { it.moveToFirst() }
        val values = ContentValues().apply {
            put("id", id)
            put("title", title.ifBlank { "Новый чат" })
            if (responseId.isNullOrBlank()) putNull("response_id") else put("response_id", responseId)
            if (baselineEffort.isNullOrBlank()) putNull("baseline_effort") else put("baseline_effort", baselineEffort)
            put("updated_at", System.currentTimeMillis())
        }
        if (exists) db.update(CHAT_TABLE, values, "id=?", arrayOf(id)) else db.insertOrThrow(CHAT_TABLE, null, values)

        val existingByPosition = mutableMapOf<Int, ExistingMessageRow>()
        val existingByRequestId = mutableMapOf<String, ExistingMessageRow>()
        val existingRows = mutableListOf<ExistingMessageRow>()
        db.query(MESSAGE_TABLE, arrayOf("id", "position", "request_id", "content_hash"),
            "chat_id=?",
            arrayOf(id),
            null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val row = ExistingMessageRow(
                    id = c.getLong(0),
                    position = c.getInt(1),
                    requestId = c.getString(2),
                    contentHash = c.getString(3)
                )
                existingRows += row
                existingByPosition.putIfAbsent(row.position, row)
                row.requestId?.takeIf { it.isNotBlank() }?.let { existingByRequestId.putIfAbsent(it, row) }
            }
        }

        val usedIds = HashSet<Long>()
        messages.forEachIndexed { index, message ->
            val requestId = message.optString("request_id").takeIf { it.isNotBlank() && it != "null" }
            val candidate = requestId?.let(existingByRequestId::get)?.takeIf { !usedIds.contains(it.id) }
                ?: existingByPosition[index]?.takeIf { !usedIds.contains(it.id) }
            val contentHash = ChatMessageFingerprint.of(message)

            if (candidate == null) {
                val newId = db.insertOrThrow(MESSAGE_TABLE, null, messageContentValues(id, index, message, contentHash))
                usedIds += newId
            } else {
                usedIds += candidate.id
                if (candidate.contentHash != contentHash || candidate.position != index) {
                    db.update(
                        MESSAGE_TABLE,
                        messageContentValues(id, index, message, contentHash),
                        "id=?",
                        arrayOf(candidate.id.toString())
                    )
                }
            }
        }

        existingRows.forEach { row ->
            if (!usedIds.contains(row.id)) {
                db.delete(MESSAGE_TABLE, "id=?", arrayOf(row.id.toString()))
            }
        }
    }

data class ExistingMessageRow(
        val id: Long,
        val position: Int,
        val requestId: String?,
        val contentHash: String?
    )

    private fun messageContentValues(
        chatId: String,
        position: Int,
        message: JSONObject,
        contentHash: String
    ): ContentValues = ContentValues().apply {
        put("chat_id", chatId)
        put("position", position)
        put("role", message.optString("role"))
        put("text", JsonProtocol.string(message, "text"))
        putStringOrNull("request_id", message.optString("request_id").takeIf { it.isNotBlank() && it != "null" })
        putStringOrNull("response_id", message.optString("response_id").takeIf { it.isNotBlank() && it != "null" })
        putStringOrNull("file_ids", message.optJSONArray("file_ids")?.toString())
        putStringOrNull("file_names", message.optJSONArray("file_names")?.toString())
        putStringOrNull("file_sizes", message.optJSONArray("file_sizes")?.toString())
        putStringOrNull("file_paths", message.optJSONArray("file_paths")?.toString())
        putStringOrNull("file_mimes", message.optJSONArray("file_mimes")?.toString())
        putStringOrNull("attachments", message.optJSONArray("attachments")?.toString())
        putStringOrNull("image_path", JsonProtocol.string(message, "image_path").takeIf { it.isNotBlank() })
        putStringOrNull("image_file_id", JsonProtocol.string(message, "image_file_id").takeIf { it.isNotBlank() && it != "null" })
        putStringOrNull("image_name", JsonProtocol.string(message, "image_name").takeIf { it.isNotBlank() })
        putStringOrNull("image_mime", JsonProtocol.string(message, "image_mime").takeIf { it.isNotBlank() })
        put("image_size", message.optLong("image_size", 0L).coerceAtLeast(0L))
        putStringOrNull("annotations", message.optJSONArray("annotations")?.toString())
        putStringOrNull("generated_images", message.optJSONArray("generated_images")?.toString())
        putStringOrNull("tools", message.optJSONArray("tools")?.toString())
        putStringOrNull("status", message.optString("status").takeIf { it.isNotBlank() && it != "null" })
        MessageMetadataCodec.allKeys.forEach { key ->
            putStringOrNull(key, MessageMetadataCodec.serialized(message, key))
        }
        if (message.has("feedback") && !message.isNull("feedback")) put("feedback", message.optInt("feedback")) else putNull("feedback")
        put("content_hash", contentHash)
    }

    fun recordEffortMetric(
        requestId: String?,
        chatId: String,
        taskClass: String,
        effortPref: String,
        effectiveEffort: String,
        autoSelected: Boolean,
        promptChars: Int,
        attachments: Int,
        latencyMs: Long,
        inputTokens: Long,
        cachedTokens: Long,
        cacheWriteTokens: Long,
        outputTokens: Long,
        reasoningTokens: Long,
        success: Boolean,
        errorType: String?,
        estimatedCostUsd: Double = 0.0
    ) {
        if (chatId.isBlank()) return
        writableDatabase.transaction { db ->
            val chatExists = db.query(CHAT_TABLE, arrayOf("id"),
                "id=?",
                arrayOf(chatId),
                null, null, null, "1"
            ).use { it.moveToFirst() }
            if (!chatExists) return@transaction

            val v = ContentValues().apply {
                put("chat_id", chatId)
                put("created_at", System.currentTimeMillis())
                putStringOrNull("request_id", requestId)
                put("task_class", taskClass.ifBlank { "general" })
                put("effort_pref", effortPref)
                put("effective_effort", effectiveEffort)
                put("auto_selected", if (autoSelected) 1 else 0)
                put("prompt_chars", promptChars)
                put("attachments", attachments)
                put("latency_ms", latencyMs.coerceAtLeast(0))
                put("input_tokens", inputTokens.coerceAtLeast(0))
                put("cached_tokens", cachedTokens.coerceAtLeast(0))
                put("cache_write_tokens", cacheWriteTokens.coerceAtLeast(0))
                put("output_tokens", outputTokens.coerceAtLeast(0))
                put("reasoning_tokens", reasoningTokens.coerceAtLeast(0))
                put("success", if (success) 1 else 0)
                putStringOrNull("error_type", errorType)
                put("estimated_cost_usd", estimatedCostUsd.coerceAtLeast(0.0))
            }
            db.insert(METRICS_TABLE, null, v)
        }
    }


    /**
     * Snapshot identity at send time. No API key is retained: credentialSource is
     * only a non-secret provider slot identifier, never credentials or a hash.
     * INSERT OR IGNORE makes retries and process restarts idempotent.
     */
    fun startRequestAccounting(
        chatId: String,
        localRequestId: String,
        provider: String,
        requestedModel: String?,
        credentialSource: String
    ): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var inserted = false
        writableDatabase.transaction { db ->
            val exists = db.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(chatId), null, null, null, "1")
                .use { it.moveToFirst() }
            if (!exists) return@transaction
            val now = System.currentTimeMillis()
            val values = ContentValues().apply {
                put("chat_id", chatId)
                put("local_request_id", localRequestId)
                put("provider", provider.ifBlank { "unknown" })
                putStringOrNull("requested_model", requestedModel)
                put("credential_source", credentialSource.ifBlank { "unknown" })
                put("created_at", now)
                put("updated_at", now)
                put("local_status", "running")
                put("cost_kind", "unknown")
            }
            inserted = db.insertWithOnConflict(REQUEST_ACCOUNTING_TABLE, null, values,
                SQLiteDatabase.CONFLICT_IGNORE) != -1L
        }
        return inserted
    }

    /** Stop updates the local outcome without erasing the request's accounting identity. */
    fun markRequestAccountingStopped(chatId: String, localRequestId: String) {
        if (chatId.isBlank() || localRequestId.isBlank()) return
        writableDatabase.update(
            REQUEST_ACCOUNTING_TABLE,
            ContentValues().apply {
                put("local_status", "stopped")
                put("updated_at", System.currentTimeMillis())
            },
            "chat_id=? AND local_request_id=? AND local_status='running'",
            arrayOf(chatId, localRequestId)
        )
    }

    /**
     * Updates only a previously captured request. A stale callback cannot recreate a
     * deleted chat/request. Known zero is different from missing provider cost.
     * A later provider report may enrich a stopped request, never change its local stop.
     */
    fun recordRequestAccounting(
        chatId: String,
        localRequestId: String,
        status: String,
        responseId: String? = null,
        httpRequestId: String? = null,
        usage: JSONObject? = null,
        reportedCostUsd: Double? = null,
        error: String? = null
    ): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var written = false
        writableDatabase.transaction { db ->
            val existing = db.query(REQUEST_ACCOUNTING_TABLE,
                arrayOf("local_status", "cost_kind", "provider", "provider_status"),
                "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId),
                null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3))
            } ?: return@transaction
            val localStatus = existing[0]
            val knownCost = existing[1]
            val capturedProvider = existing[2]
            val existingProviderStatus = existing[3]
            val values = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (localStatus == "running") put("local_status", status.ifBlank { "unknown" })
                if (existingProviderStatus.isNullOrBlank() || existingProviderStatus == "in_progress") {
                    put("provider_status", status.ifBlank { "unknown" })
                }
                responseId?.takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                httpRequestId?.takeIf { it.isNotBlank() }?.let { put("http_request_id", it) }
                error?.takeIf { it.isNotBlank() }?.let { put("error", it.take(1200)) }
                // No optLong defaults: omitted counters remain SQL NULL.
                val openRouter = capturedProvider == "openrouter"
                val inputKey = if (openRouter) "prompt_tokens" else "input_tokens"
                val outputKey = if (openRouter) "completion_tokens" else "output_tokens"
                fun knownNonnegative(source: JSONObject?, key: String): Long? =
                    source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
                knownNonnegative(usage, inputKey)?.let { put("input_tokens", it) }
                knownNonnegative(usage, outputKey)?.let { put("output_tokens", it) }
                val cached = if (openRouter)
                    usage?.optJSONObject("prompt_tokens_details")?.let {
                        knownNonnegative(it, "cached_tokens")
                    }
                else usage?.optJSONObject("input_tokens_details")?.let {
                    knownNonnegative(it, "cached_tokens")
                }
                cached?.let { put("cached_tokens", it) }
                val reasoning = if (openRouter)
                    usage?.optJSONObject("completion_tokens_details")?.let {
                        knownNonnegative(it, "reasoning_tokens")
                    }
                else usage?.optJSONObject("output_tokens_details")?.let {
                    knownNonnegative(it, "reasoning_tokens")
                }
                reasoning?.let { put("reasoning_tokens", it) }
                val cost = reportedCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
                if (cost != null && knownCost != "provider_reported") {
                    put("cost_kind", "provider_reported")
                    put("cost_usd", cost.toString())
                }
            }
            written = db.update(REQUEST_ACCOUNTING_TABLE, values,
                "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId)) == 1
        }
        return written
    }

    /**
     * BILL-03: reconcile only an already-stopped, locally captured OpenAI response.
     * A matching response ID from the provider is required; the provider may
     * enrich usage/status but must never revive a chat, reset Stop, or mint cost.
     * Repeated retrieved snapshots update the same row (no request_events entry).
     */
    fun reconcileStoppedOpenAiResponse(
        chatId: String,
        localRequestId: String,
        responseId: String,
        response: JSONObject
    ): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank() || responseId.isBlank()) return false
        if (response.optString("id") != responseId) return false
        val status = response.optString("status")
        if (status !in setOf("completed", "cancelled", "failed", "incomplete", "in_progress", "queued")) return false
        val usage = response.optJSONObject("usage")
        val values = ContentValues().apply {
            put("updated_at", System.currentTimeMillis())
            // "in_progress" / "queued" are observations, not terminal outcomes.
            if (status in setOf("completed", "cancelled", "failed", "incomplete")) {
                put("provider_status", status)
            }
            fun nonnegative(source: JSONObject?, key: String): Long? =
                source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
            nonnegative(usage, "input_tokens")?.let { put("input_tokens", it) }
            nonnegative(usage, "output_tokens")?.let { put("output_tokens", it) }
            usage?.optJSONObject("input_tokens_details")?.let {
                nonnegative(it, "cached_tokens")?.let { n -> put("cached_tokens", n) }
            }
            usage?.optJSONObject("output_tokens_details")?.let {
                nonnegative(it, "reasoning_tokens")?.let { n -> put("reasoning_tokens", n) }
            }
            // Responses usage reports tokens; it does not establish a USD charge.
            // cost_kind / cost_usd / local_status / response_id are immutable here.
        }
        if (values.size() <= 1) return false // no terminal status or usage evidence
        return writableDatabase.update(
            REQUEST_ACCOUNTING_TABLE, values,
            "chat_id=? AND local_request_id=? AND response_id=? AND provider='openai' AND local_status='stopped'",
            arrayOf(chatId, localRequestId, responseId)
        ) == 1
    }

    /**
     * One row per actual attempted OpenRouter HTTP round. The generation ID
     * cannot be replaced by an unrelated SSE frame, and usage snapshots are
     * replacements (not increments) to prevent duplicate stream billing.
     * A stopped request may enrich an existing round; never start a new round
     * after Stop or create a deleted chat.
     */
    fun observeOpenRouterRound(
        chatId: String, localRequestId: String, roundIndex: Int,
        generationId: String? = null, usage: JSONObject? = null
    ): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank() || roundIndex !in 0..9999) return false
        var changed = false
        writableDatabase.transaction { db ->
            val row = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("provider", "local_status", "rounds_sealed"),
                "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId),
                null, null, null, "1").use { c ->
                if (c.moveToFirst()) Triple(c.getString(0), c.getString(1), c.getInt(2)) else null
            } ?: return@transaction
            if (row.first != "openrouter") return@transaction
            val existing = db.query(OPENROUTER_ROUND_TABLE, arrayOf("generation_id", "cost_kind"),
                "chat_id=? AND local_request_id=? AND round_index=?",
                arrayOf(chatId, localRequestId, roundIndex.toString()),
                null, null, null, "1").use { c ->
                if (c.moveToFirst()) Pair(c.getString(0), c.getString(1)) else null
            }
            if (existing == null) {
                if (row.second != "running" || row.third != 0) return@transaction
                val now = System.currentTimeMillis()
                val inserted = db.insertWithOnConflict(OPENROUTER_ROUND_TABLE, null,
                    ContentValues().apply {
                        put("chat_id", chatId)
                        put("local_request_id", localRequestId)
                        put("round_index", roundIndex)
                        put("updated_at", now)
                    }, SQLiteDatabase.CONFLICT_IGNORE)
                if (inserted == -1L) return@transaction
            }
            val knownGeneration = existing?.first
            val proposed = generationId?.takeIf {
                it.matches(Regex("[A-Za-z0-9_-]{1,128}"))
            }
            if (knownGeneration != null && proposed != null && knownGeneration != proposed) return@transaction
            val data = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (knownGeneration == null && proposed != null) put("generation_id", proposed)
                fun nonnegative(key: String): Long? =
                    usage?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
                nonnegative("prompt_tokens")?.let { put("input_tokens", it) }
                nonnegative("completion_tokens")?.let { put("output_tokens", it) }
                val cost = usage?.takeIf { it.has("cost") && !it.isNull("cost") }
                    ?.opt("cost")?.toString()?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
                if (cost != null && existing?.second != "provider_reported") {
                    put("cost_kind", "provider_reported")
                    put("cost_usd", cost.toString())
                }
            }
            changed = db.update(OPENROUTER_ROUND_TABLE, data,
                "chat_id=? AND local_request_id=? AND round_index=?",
                arrayOf(chatId, localRequestId, roundIndex.toString())) == 1
            if (changed) recomputeOpenRouterTotalsInDb(db, chatId, localRequestId)
        }
        return changed
    }

    /** A terminal success or local Stop establishes the number of attempted rounds. */
    fun sealOpenRouterRounds(chatId: String, localRequestId: String): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var sealed = false
        writableDatabase.transaction { db ->
            sealed = db.update(REQUEST_ACCOUNTING_TABLE,
                ContentValues().apply { put("rounds_sealed", 1) },
                "chat_id=? AND local_request_id=? AND provider='openrouter' AND rounds_sealed=0",
                arrayOf(chatId, localRequestId)) == 1
            if (sealed) recomputeOpenRouterTotalsInDb(db, chatId, localRequestId)
        }
        return sealed
    }

    private fun recomputeOpenRouterTotalsInDb(db: SQLiteDatabase, chatId: String, localRequestId: String) {
        val account = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("rounds_sealed"), 
            "chat_id=? AND local_request_id=? AND provider='openrouter'",
            arrayOf(chatId, localRequestId), null, null, null, "1").use { c ->
            c.moveToFirst() && c.getInt(0) == 1
        }
        if (!account) return
        db.rawQuery("""
            SELECT COUNT(*), COALESCE(SUM(CASE WHEN cost_kind='provider_reported' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN cost_kind='provider_reported' THEN CAST(cost_usd AS REAL) ELSE 0 END),0)
            FROM $OPENROUTER_ROUND_TABLE WHERE chat_id=? AND local_request_id=?
        """.trimIndent(), arrayOf(chatId, localRequestId)).use { c ->
            if (!c.moveToFirst()) return
            val count = c.getLong(0)
            val complete = count > 0 && count == c.getLong(1)
            db.update(REQUEST_ACCOUNTING_TABLE, ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (complete) {
                    put("cost_kind", "provider_reported")
                    put("cost_usd", c.getDouble(2).toString())
                } else {
                    put("cost_kind", "unknown")
                    putNull("cost_usd")
                }
            }, "chat_id=? AND local_request_id=? AND provider='openrouter'",
                arrayOf(chatId, localRequestId))
        }
    }

    /** A provider generation lookup can enrich only a previously captured ID. */
    fun reconcileOpenRouterGeneration(
        chatId: String, localRequestId: String, generationId: String,
        metadata: JSONObject
    ): Boolean {
        if (generationId.isBlank() || metadata.optString("id") != generationId) return false
        val cost = metadata.opt("total_cost")?.toString()?.toDoubleOrNull()
            ?.takeIf { it.isFinite() && it >= 0.0 } ?: return false
        var updated = false
        writableDatabase.transaction { db ->
            updated = db.update(OPENROUTER_ROUND_TABLE, ContentValues().apply {
                put("cost_kind", "provider_reported")
                put("cost_usd", cost.toString())
                put("updated_at", System.currentTimeMillis())
                listOf("tokens_prompt" to "input_tokens", "tokens_completion" to "output_tokens").forEach { (source,target) ->
                    metadata.opt(source)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }?.let { put(target, it) }
                }
            }, "chat_id=? AND local_request_id=? AND generation_id=? AND EXISTS " +
                "(SELECT 1 FROM $REQUEST_ACCOUNTING_TABLE a WHERE a.chat_id=$OPENROUTER_ROUND_TABLE.chat_id " +
                "AND a.local_request_id=$OPENROUTER_ROUND_TABLE.local_request_id AND a.provider='openrouter')",
                arrayOf(chatId, localRequestId, generationId)) == 1
            if (updated) recomputeOpenRouterTotalsInDb(db, chatId, localRequestId)
        }
        return updated
    }

    fun listOpenRouterRounds(chatId: String, localRequestId: String): List<JSONObject> {
        val rows = mutableListOf<JSONObject>()
        readableDatabase.query(OPENROUTER_ROUND_TABLE, null,
            "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId),
            null, null, "round_index ASC").use { c ->
            while (c.moveToNext()) {
                val item = JSONObject()
                for (i in 0 until c.columnCount) {
                    if (c.isNull(i)) continue
                    val name = c.getColumnName(i)
                    if (c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER) item.put(name, c.getLong(i))
                    else item.put(name, c.getString(i))
                }
                rows.add(item)
            }
        }
        return rows
    }

    fun listRequestAccounting(chatId: String): List<JSONObject> {
        if (chatId.isBlank()) return emptyList()
        val rows = mutableListOf<JSONObject>()
        readableDatabase.query(REQUEST_ACCOUNTING_TABLE, null, "chat_id=?", arrayOf(chatId),
            null, null, "created_at ASC, local_request_id ASC").use { c ->
            while (c.moveToNext()) {
                val value = JSONObject()
                for (i in 0 until c.columnCount) {
                    val key = c.getColumnName(i)
                    if (c.isNull(i)) continue
                    when (c.getType(i)) {
                        android.database.Cursor.FIELD_TYPE_INTEGER -> value.put(key, c.getLong(i))
                        android.database.Cursor.FIELD_TYPE_FLOAT -> value.put(key, c.getDouble(i))
                        else -> value.put(key, c.getString(i))
                    }
                }
                rows += value
            }
        }
        return rows
    }

    /** Only provider-reported cost contributes to totals; unknowns stay visible. */
    fun requestAccountingTotals(since: Long = 0L): JSONObject {
        readableDatabase.rawQuery("""
            SELECT COUNT(*),
                   COALESCE(SUM(CASE WHEN cost_kind='provider_reported' THEN 1 ELSE 0 END), 0),
                   COALESCE(SUM(CASE WHEN cost_kind='provider_reported'
                        THEN CAST(cost_usd AS REAL) ELSE 0 END), 0)
            FROM $REQUEST_ACCOUNTING_TABLE WHERE created_at>=?
        """.trimIndent(), arrayOf(since.coerceAtLeast(0L).toString())).use { c ->
            if (!c.moveToFirst()) return JSONObject()
            val total = c.getLong(0)
            val known = c.getLong(1)
            return JSONObject().put("requests", total)
                .put("known_cost_requests", known)
                .put("unknown_cost_requests", total - known)
                .put("provider_reported_cost_usd", c.getDouble(2))
        }
    }

    fun recordRequestEvent(
        chatId: String,
        eventType: String,
        requestId: String?,
        provider: String,
        requestedModel: String?,
        responseModel: String?,
        modelMatch: ModelCatalog.Match,
        status: String,
        inputTokens: Long = 0L,
        cachedTokens: Long = 0L,
        cacheWriteTokens: Long = 0L,
        outputTokens: Long = 0L,
        reasoningTokens: Long = 0L,
        estimatedCostUsd: Double = 0.0,
        reportedCostUsd: Double? = null,
        latencyMs: Long = 0L,
        toolSummary: String? = null,
        error: String? = null
    ) {
        if (chatId.isBlank()) return
        writableDatabase.transaction { db ->
            val chatExists = db.query(CHAT_TABLE, arrayOf("id"),
                "id=?",
                arrayOf(chatId),
                null, null, null, "1"
            ).use { it.moveToFirst() }
            if (!chatExists) return@transaction

            // Late completion/error callbacks cannot displace the stopped event.
            if (requestId != null && db.query(
                    REQUEST_ACCOUNTING_TABLE, arrayOf("local_request_id"),
                    "chat_id=? AND (local_request_id=? OR http_request_id=?) AND local_status='stopped'",
                    arrayOf(chatId, requestId, requestId), null, null, null, "1"
                ).use { it.moveToFirst() }) return@transaction

            val values = ContentValues().apply {
                put("chat_id", chatId)
                put("created_at", System.currentTimeMillis())
                put("event_type", eventType.ifBlank { "request" })
                putStringOrNull("request_id", requestId)
                put("provider", provider.ifBlank { "unknown" })
                putStringOrNull("requested_model", requestedModel)
                putStringOrNull("response_model", responseModel)
                put("model_match", modelMatch.name.lowercase())
                put("status", status.ifBlank { "unknown" })
                put("input_tokens", inputTokens.coerceAtLeast(0L))
                put("cached_tokens", cachedTokens.coerceAtLeast(0L))
                put("cache_write_tokens", cacheWriteTokens.coerceAtLeast(0L))
                put("output_tokens", outputTokens.coerceAtLeast(0L))
                put("reasoning_tokens", reasoningTokens.coerceAtLeast(0L))
                // The legacy numeric estimate is not proof of a provider charge.
                put("estimated_cost_usd", estimatedCostUsd.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
                val verifiedCost = reportedCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
                put("cost_kind", if (verifiedCost == null) "unknown" else "provider_reported")
                if (verifiedCost == null) putNull("cost_usd") else put("cost_usd", verifiedCost.toString())
                put("latency_ms", latencyMs.coerceAtLeast(0L))
                putStringOrNull("tool_summary", toolSummary)
                putStringOrNull("error", error?.take(1200))
            }
            db.insert(REQUEST_EVENT_TABLE, null, values)
        }
    }

    fun listRequestEvents(chatId: String, limit: Int = 50): List<JSONObject> {
        if (chatId.isBlank()) return emptyList()
        val safeLimit = limit.coerceIn(1, 100)
        val out = mutableListOf<JSONObject>()
        SqliteLargeTextQuery.query(readableDatabase,
            REQUEST_EVENT_TABLE,
            null,
            "chat_id=?",
            arrayOf(chatId),
            null,
            null,
            "created_at DESC, id DESC",
            safeLimit.toString()
        ).use { c ->
            while (c.moveToNext()) {
                val event = JSONObject()
                    .put("id", c.getLong(c.getColumnIndexOrThrow("id")))
                    .put("chat_id", c.getString(c.getColumnIndexOrThrow("chat_id")))
                    .put("created_at", c.getLong(c.getColumnIndexOrThrow("created_at")))
                    .put("event_type", c.getString(c.getColumnIndexOrThrow("event_type")))
                    .put("provider", c.getString(c.getColumnIndexOrThrow("provider")))
                    .put("model_match", c.getString(c.getColumnIndexOrThrow("model_match")))
                    .put("status", c.getString(c.getColumnIndexOrThrow("status")))
                    .put("input_tokens", c.getLong(c.getColumnIndexOrThrow("input_tokens")))
                    .put("cached_tokens", c.getLong(c.getColumnIndexOrThrow("cached_tokens")))
                    .put("cache_write_tokens", c.getLong(c.getColumnIndexOrThrow("cache_write_tokens")))
                    .put("output_tokens", c.getLong(c.getColumnIndexOrThrow("output_tokens")))
                    .put("reasoning_tokens", c.getLong(c.getColumnIndexOrThrow("reasoning_tokens")))
                    .put("estimated_cost_usd", c.getDouble(c.getColumnIndexOrThrow("estimated_cost_usd")))
                    .put("cost_kind", c.getString(c.getColumnIndexOrThrow("cost_kind")) ?: "unknown")
                    .put("latency_ms", c.getLong(c.getColumnIndexOrThrow("latency_ms")))
                c.getString(c.getColumnIndexOrThrow("cost_usd"))?.let { event.put("cost_usd", it) }
                c.getString(c.getColumnIndexOrThrow("request_id"))?.let { event.put("request_id", it) }
                c.getString(c.getColumnIndexOrThrow("requested_model"))?.let { event.put("requested_model", it) }
                c.getString(c.getColumnIndexOrThrow("response_model"))?.let { event.put("response_model", it) }
                c.getString(c.getColumnIndexOrThrow("tool_summary"))?.let { event.put("tool_summary", it) }
                c.getString(c.getColumnIndexOrThrow("error"))?.let { event.put("error", it) }
                out += event
            }
        }
        return out
    }

    fun clearRequestEvents(chatId: String): Int {
        if (chatId.isBlank()) return 0
        var cleared = 0
        writableDatabase.transaction { db ->
            cleared = db.delete(REQUEST_EVENT_TABLE, "chat_id=?", arrayOf(chatId))
            db.delete(REQUEST_ACCOUNTING_TABLE, "chat_id=?", arrayOf(chatId))
        }
        return cleared
    }

    fun recordFeedback(chatId: String, requestId: String, value: Int) {
        val v = value.coerceIn(-1, 1)
        if (requestId.isBlank() || v == 0) return
        writableDatabase.transaction { db ->
            db.execSQL(
                "UPDATE $METRICS_TABLE SET feedback=? WHERE chat_id=? AND request_id=?",
                arrayOf(v, chatId, requestId)
            )
            db.query(MESSAGE_TABLE, arrayOf("id"), "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, "position DESC", "1").use { c ->
                if (!c.moveToFirst()) return@use
                val id = c.getLong(c.getColumnIndexOrThrow("id"))
                val cv = ContentValues().apply { put("feedback", v) }
                db.update(MESSAGE_TABLE, cv, "id=?", arrayOf(id.toString()))
            }
        }
    }

    /** Reuse extraction persisted by a previous preparation, including a still-open UI snapshot. */
    fun cachedAttachmentManifest(chatId: String, expected: JSONObject): JSONArray? {
        val rowId = expected.optLong("_db_message_id", 0L)
        val request = JsonProtocol.nonBlank(expected, "request_id")
        if (rowId == 0L && request == null) return null
        val where = if (rowId != 0L) "chat_id=? AND id=? AND role='user'" else "chat_id=? AND request_id=? AND role='user'"
        val args = arrayOf(chatId, if (rowId != 0L) rowId.toString() else request!!)
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE, AttachmentMessageState.columns.toTypedArray(),
            where, args, null, null, "position DESC", "1").use { c ->
            if (!c.moveToFirst()) return null
            val current = JSONObject()
            AttachmentMessageState.arrays.forEach { key -> parseArrayColumn(c.getString(c.getColumnIndexOrThrow(key)))?.let { current.put(key, it) } }
            AttachmentMessageState.strings.forEach { key -> c.getString(c.getColumnIndexOrThrow(key))?.let { current.put(key, it) } }
            current.put("image_size", c.getLong(c.getColumnIndexOrThrow("image_size")))
            if (AttachmentMessageState.fingerprint(current, false) != AttachmentMessageState.fingerprint(expected, false)) return null
            return current.optJSONArray("attachments")
        }
    }

    /** Attachment CAS, preserving text and any concurrently appended worker tail. */
    fun updateMessageAttachments(chatId: String, expected: JSONObject, replacement: JSONObject, invalidateContext: Boolean): Boolean {
        if (chatId.isBlank()) return false
        if (!invalidateContext && AttachmentMessageState.fingerprint(expected, false) != AttachmentMessageState.fingerprint(replacement, false)) return false
        var changed = false
        writableDatabase.transaction { db ->
            if (invalidateContext) db.query(CHAT_TABLE, arrayOf("active_status"), "id=?", arrayOf(chatId), null, null, null).use {
                if (it.moveToFirst() && !it.getString(0).isNullOrBlank()) return@transaction
            }
            val rowId = expected.optLong("_db_message_id", 0L)
            val request = JsonProtocol.nonBlank(expected, "request_id")
            if (rowId == 0L && request == null) return@transaction
            val where = if (rowId != 0L) "chat_id=? AND id=? AND role='user'" else "chat_id=? AND request_id=? AND role='user'"
            val args = arrayOf(chatId, if (rowId != 0L) rowId.toString() else request!!)
            SqliteLargeTextQuery.query(db, MESSAGE_TABLE, (listOf("id", "position") + AttachmentMessageState.columns).toTypedArray(),
                where, args, null, null, "position DESC", "1").use { c ->
                if (!c.moveToFirst()) return@transaction
                val current = JSONObject()
                AttachmentMessageState.arrays.forEach { key -> parseArrayColumn(c.getString(c.getColumnIndexOrThrow(key)))?.let { current.put(key, it) } }
                AttachmentMessageState.strings.forEach { key -> c.getString(c.getColumnIndexOrThrow(key))?.let { current.put(key, it) } }
                current.put("image_size", c.getLong(c.getColumnIndexOrThrow("image_size")))
                if (AttachmentMessageState.fingerprint(current) != AttachmentMessageState.fingerprint(expected)) return@transaction
                val values = ContentValues().apply {
                    AttachmentMessageState.arrays.forEach { key -> putStringOrNull(key, replacement.optJSONArray(key)?.toString()) }
                    AttachmentMessageState.strings.forEach { key -> putStringOrNull(key, JsonProtocol.nonBlank(replacement, key)) }
                    put("image_size", replacement.optLong("image_size", 0L)); putNull("content_hash")
                }
                changed = db.update(MESSAGE_TABLE, values, "id=?", arrayOf(c.getLong(c.getColumnIndexOrThrow("id")).toString())) == 1
                if (changed && invalidateContext) {
                    db.update(CHAT_TABLE, ContentValues().apply { putNull("response_id"); putNull("baseline_effort") }, "id=?", arrayOf(chatId))
                    db.update(MESSAGE_TABLE, ContentValues().apply { put("protocol_context_state", "invalidated"); putNull("content_hash") },
                        "chat_id=? AND position>=? AND role='assistant' AND openrouter_protocol IS NOT NULL",
                        arrayOf(chatId, c.getLong(c.getColumnIndexOrThrow("position")).toString()))
                }
            }
        }
        return changed
    }

    fun updateUserFileLocalPath(
        chatId: String,
        requestId: String,
        index: Int,
        localPath: String,
        mimeType: String? = null,
        sizeBytes: Long? = null
    ) {
        if (chatId.isBlank() || requestId.isBlank() || index < 0 || localPath.isBlank()) return
        writableDatabase.transaction { db ->
            SqliteLargeTextQuery.query(db, MESSAGE_TABLE,
                arrayOf("id", "file_ids", "file_names", "file_sizes", "file_paths", "file_mimes"),
                "chat_id=? AND request_id=? AND role='user'",
                arrayOf(chatId, requestId), null, null, "position DESC", "1"
            ).use { c ->
                if (!c.moveToFirst()) return@transaction
                val id = c.getLong(c.getColumnIndexOrThrow("id"))
                val ids = parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_ids"))) ?: JSONArray()
                val sizes = parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_sizes"))) ?: JSONArray()
                val paths = parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_paths"))) ?: JSONArray()
                val mimes = parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_mimes"))) ?: JSONArray()
                val targetLength = maxOf(index + 1, ids.length(), paths.length(), mimes.length(), sizes.length())
                while (paths.length() < targetLength) paths.put("")
                while (mimes.length() < targetLength) mimes.put("")
                while (sizes.length() < targetLength) sizes.put(0L)
                paths.put(index, localPath)
                if (!mimeType.isNullOrBlank()) mimes.put(index, mimeType)
                if (sizeBytes != null && sizeBytes >= 0L) sizes.put(index, sizeBytes)
                val values = ContentValues().apply {
                    put("file_paths", paths.toString())
                    put("file_mimes", mimes.toString())
                    put("file_sizes", sizes.toString())
                }
                db.update(MESSAGE_TABLE, values, "id=?", arrayOf(id.toString()))
            }
        }
    }

    fun updateUserImageLocalPath(
        chatId: String,
        requestId: String,
        localPath: String,
        mimeType: String? = null,
        sizeBytes: Long? = null
    ) {
        if (chatId.isBlank() || requestId.isBlank() || localPath.isBlank()) return
        writableDatabase.transaction { db ->
            db.query(MESSAGE_TABLE, arrayOf("id"),
                "chat_id=? AND request_id=? AND role='user'",
                arrayOf(chatId, requestId), null, null, "position DESC", "1"
            ).use { c ->
                if (!c.moveToFirst()) return@transaction
                val values = ContentValues().apply {
                    put("image_path", localPath)
                    if (!mimeType.isNullOrBlank()) put("image_mime", mimeType)
                    if (sizeBytes != null && sizeBytes >= 0L) put("image_size", sizeBytes)
                }
                db.update(MESSAGE_TABLE, values, "id=?", arrayOf(c.getLong(0).toString()))
            }
        }
    }

    fun updateGeneratedFileState(
        chatId: String,
        fileId: String,
        state: String,
        localPath: String? = null,
        mimeType: String? = null,
        sizeBytes: Long? = null,
        error: String? = null,
        annotationType: String? = null
    ) {
        if (chatId.isBlank() || fileId.isBlank()) return
        writableDatabase.transaction { db ->
            SqliteLargeTextQuery.query(db, MESSAGE_TABLE, arrayOf("id", "annotations"), "chat_id=? AND annotations IS NOT NULL", arrayOf(chatId), null, null, "position ASC").use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(c.getColumnIndexOrThrow("annotations")) ?: continue
                    val anns = try { JSONArray(raw) } catch (_: Exception) { continue }
                    var changed = false
                    for (i in 0 until anns.length()) {
                        val a = anns.optJSONObject(i) ?: continue
                        val type = a.optString("type")
                        if (type != "container_file_citation" && type != "file_citation") continue
                        if (annotationType != null && type != annotationType) continue
                        if (JsonProtocol.string(a, "file_id") != fileId) continue
                        a.put("state", state)
                        if (localPath != null) a.put("local_path", localPath)
                        if (mimeType != null) a.put("mime_type", mimeType)
                        if (sizeBytes != null) a.put("size_bytes", sizeBytes)
                        if (error != null) a.put("error", error) else a.remove("error")
                        changed = true
                    }
                    if (changed) {
                        val cv = ContentValues().apply { put("annotations", anns.toString()) }
                        db.update(MESSAGE_TABLE, cv, "id=?", arrayOf(c.getLong(c.getColumnIndexOrThrow("id")).toString()))
                    }
                }
            }
        }
    }

    fun findLocalFileByFileId(fileId: String): JSONObject? {
        if (fileId.isBlank()) return null
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("chat_id", "annotations"),
            "annotations IS NOT NULL", null, null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(c.getColumnIndexOrThrow("annotations")) ?: continue
                val anns = try { JSONArray(raw) } catch (_: Exception) { continue }
                for (i in 0 until anns.length()) {
                    val a = anns.optJSONObject(i) ?: continue
                    if (JsonProtocol.string(a, "file_id") != fileId) continue
                    val path = a.optString("local_path").takeIf { it.isNotBlank() } ?: continue
                    if (!java.io.File(path).isFile) continue
                    return JSONObject()
                        .put("chat_id", c.getString(c.getColumnIndexOrThrow("chat_id")))
                        .put("file_id", fileId)
                        .put("filename", JsonProtocol.string(a, "filename"))
                        .put("path", path)
                        .put("mime_type", a.optString("mime_type"))
                        .put("size_bytes", a.optLong("size_bytes", java.io.File(path).length()))
                        .put("annotation_type", a.optString("type"))
                }
            }
        }
        return null
    }

    /**
     * Use explicit user feedback as a conservative correction to the lexical baseline.
     * With fewer than 3 ratings for a task/effort pair, the heuristic stays authoritative.
     */
    fun autoEffortHint(taskClass: String, baseline: String): String {
        val levels = listOf("low", "medium", "high", "xhigh", "max")
        val baseIndex = levels.indexOf(baseline).takeIf { it >= 0 } ?: 2
        val candidates = mutableListOf<Pair<String, Double>>()
        readableDatabase.query(
            METRICS_TABLE,
            arrayOf("effective_effort", "COUNT(*) AS n", "AVG(feedback) AS avg_feedback"),
            "task_class=? AND feedback IS NOT NULL",
            arrayOf(taskClass.ifBlank { "general" }),
            "effective_effort",
            null,
            null
        ).use { c ->
            while (c.moveToNext()) {
                val n = c.getInt(c.getColumnIndexOrThrow("n"))
                if (n >= 3) candidates.add(c.getString(0) to c.getDouble(2))
            }
        }
        if (candidates.isEmpty()) return baseline
        val best = candidates.maxByOrNull { it.second } ?: return baseline
        val bestIndex = levels.indexOf(best.first).takeIf { it >= 0 } ?: return baseline
        // Never let sparse feedback jump more than one reasoning tier from the baseline.
        if (kotlin.math.abs(bestIndex - baseIndex) > 1) return baseline
        if (best.second < 0.35) return baseline
        val baselineStats = candidates.firstOrNull { it.first == baseline }
        if (baselineStats != null && best.second - baselineStats.second < 0.35) return baseline
        return best.first
    }


    fun setActiveResponse(
        chatId: String,
        responseId: String?,
        requestId: String?,
        text: String,
        status: String,
        startedAt: Long,
        effortPref: String,
        effectiveEffort: String,
        autoSelected: Boolean,
        promptChars: Int,
        attachments: Int,
        backgroundEnabled: Boolean
    ) {
        if (chatId.isBlank()) return
        val values = ContentValues().apply {
            putStringOrNull("active_response_id", responseId)
            putStringOrNull("active_request_id", requestId)
            put("active_text", text)
            put("active_status", status)
            put("active_started_at", startedAt)
            put("active_effort_pref", effortPref)
            put("active_effective_effort", effectiveEffort)
            put("active_auto_selected", if (autoSelected) 1 else 0)
            put("active_prompt_chars", promptChars.coerceAtLeast(0))
            put("active_attachments", attachments.coerceAtLeast(0))
            put("active_background_enabled", if (backgroundEnabled) 1 else 0)
            put("active_last_sequence_number", -1)
            putNull("active_indexed_checkpoint")
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(CHAT_TABLE, values, "id=?", arrayOf(chatId))
    }

    fun updateActiveResponse(
        chatId: String,
        responseId: String? = null,
        text: String? = null,
        status: String? = null,
        lastSequenceNumber: Long? = null,
        expectedRequestId: String? = null,
        indexedCheckpoint: String? = null
    ) {
        if (chatId.isBlank()) return
        if (indexedCheckpoint != null && (text == null || lastSequenceNumber == null || expectedRequestId == null))
            throw IllegalArgumentException("Indexed checkpoint must be atomic with text, sequence and owner")
        val values = ContentValues().apply {
            if (responseId != null) putStringOrNull("active_response_id", responseId)
            if (text != null) {
                put("active_text", text)
                if (indexedCheckpoint == null) putNull("active_indexed_checkpoint")
            }
            if (indexedCheckpoint != null) put("active_indexed_checkpoint", indexedCheckpoint)
            if (status != null) put("active_status", status)
            if (lastSequenceNumber != null) put("active_last_sequence_number", lastSequenceNumber.coerceAtLeast(-1L))
            put("updated_at", System.currentTimeMillis())
        }
        // Request ownership by itself is not sufficient: Activity and Worker can
        // race with checkpoints from the *same* request. Guard the sequence and
        // terminal status in the UPDATE predicate so the comparison is atomic.
        val where = StringBuilder("id=? AND active_status IS NOT NULL")
        val args = mutableListOf(chatId)
        if (expectedRequestId != null) {
            where.append(" AND active_request_id=?")
            args.add(expectedRequestId)
        }
        where.append(" AND active_status NOT IN ('completed','incomplete','failed','cancelled')")
        val isTerminalWrite = status in setOf("completed", "incomplete", "failed", "cancelled")
        if (lastSequenceNumber != null) {
            val sequence = lastSequenceNumber.coerceAtLeast(-1L)
            where.append(" AND active_last_sequence_number <= ?")
            args.add(sequence.toString())
            // Any strictly newer event may advance in-progress state. For equal
            // sequence, a nonterminal retry must leave text/status/ID identical,
            // except a first response ID can fill an empty persisted field.
            // A terminal event may replace partial text with canonical output,
            // but may not replace a *different* response identity.
            if (!isTerminalWrite) {
                where.append(" AND (active_last_sequence_number < ? OR (")
                args.add(sequence.toString())
                if (status != null) {
                    where.append("active_status = ? AND ")
                    args.add(status)
                }
                if (text != null) {
                    where.append("active_text = ? AND ")
                    args.add(text)
                }
                if (indexedCheckpoint != null) {
                    where.append("active_indexed_checkpoint = ? AND ")
                    args.add(indexedCheckpoint)
                }
                where.append("1=1))")
            }
        } else {
            // A callback without a sequence must never roll back an established
            // SSE cursor. Before the first event, only the initial
            // starting->in_progress transition is allowed to change status.
            where.append(" AND active_last_sequence_number = -1")
            if (status != null && !isTerminalWrite) {
                where.append(" AND (active_status = ?")
                args.add(status)
                if (status == "in_progress") {
                    where.append(" OR active_status = 'starting'")
                }
                where.append(")")
            }
            if (text != null && !isTerminalWrite) {
                where.append(" AND active_text = ?")
                args.add(text)
            }
        }
        if (responseId != null) {
            where.append(" AND (active_response_id IS NULL OR active_response_id = ?)")
            args.add(responseId)
        }
        writableDatabase.update(CHAT_TABLE, values, where.toString(), args.toTypedArray())
    }

    /** Ownership checks must not materialize the accumulating answer on every SSE event. */
    fun isActiveRequest(chatId: String, requestId: String): Boolean = readableDatabase.rawQuery(
        "SELECT 1 FROM $CHAT_TABLE WHERE id=? AND active_request_id=? LIMIT 1", arrayOf(chatId, requestId)
    ).use { it.moveToFirst() }

    fun getActiveResponse(chatId: String): JSONObject? {
        if (chatId.isBlank()) return null
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE,
            arrayOf(
                "active_response_id", "active_request_id", "active_text", "active_status",
                "active_started_at", "active_effort_pref", "active_effective_effort",
                "active_auto_selected", "active_prompt_chars", "active_attachments",
                "active_background_enabled", "active_last_sequence_number", "active_indexed_checkpoint"
            ),
            "id=? AND active_status IS NOT NULL",
            arrayOf(chatId), null, null, null, "1"
        ).use { c ->
            if (!c.moveToFirst()) return null
            return JSONObject()
                .put("chat_id", chatId)
                .put("response_id", c.getString(0))
                .put("request_id", c.getString(1))
                .put("text", c.getString(2) ?: "")
                .put("status", c.getString(3))
                .put("started_at", if (c.isNull(4)) JSONObject.NULL else c.getLong(4))
                .put("effort_pref", c.getString(5) ?: "high")
                .put("effective_effort", c.getString(6) ?: "high")
                .put("auto_selected", c.getInt(7) != 0)
                .put("prompt_chars", c.getInt(8))
                .put("attachments", c.getInt(9))
                .put("background_enabled", c.getInt(10) != 0)
                .put("last_sequence_number", c.getLong(11))
                .apply { c.getString(12)?.let { put("indexed_checkpoint", it) } }
        }
    }

    fun listActiveResponses(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE,
            arrayOf(
                "id", "title", "active_response_id", "active_request_id", "active_text",
                "active_status", "active_started_at", "active_effort_pref",
                "active_effective_effort", "active_auto_selected", "active_prompt_chars",
                "active_attachments", "active_background_enabled", "active_last_sequence_number", "active_indexed_checkpoint"
            ),
            "active_status IS NOT NULL",
            null, null, null, "updated_at DESC"
        ).use { c ->
            while (c.moveToNext()) {
                out += JSONObject()
                    .put("chat_id", c.getString(0))
                    .put("title", c.getString(1))
                    .put("response_id", c.getString(2))
                    .put("request_id", c.getString(3))
                    .put("text", c.getString(4) ?: "")
                    .put("status", c.getString(5))
                    .put("started_at", if (c.isNull(6)) JSONObject.NULL else c.getLong(6))
                    .put("effort_pref", c.getString(7) ?: "high")
                    .put("effective_effort", c.getString(8) ?: "high")
                    .put("auto_selected", c.getInt(9) != 0)
                    .put("prompt_chars", c.getInt(10))
                    .put("attachments", c.getInt(11))
                    .put("background_enabled", c.getInt(12) != 0)
                    .put("last_sequence_number", c.getLong(13))
                    .apply { c.getString(14)?.let { put("indexed_checkpoint", it) } }
            }
        }
        return out
    }

    fun clearActiveResponse(chatId: String, expectedRequestId: String? = null) {
        if (chatId.isBlank()) return
        val values = ContentValues().apply {
            putNull("active_response_id")
            putNull("active_request_id")
            putNull("active_text")
                putNull("active_indexed_checkpoint")
            putNull("active_status")
            putNull("active_started_at")
            putNull("active_effort_pref")
            putNull("active_effective_effort")
            put("active_auto_selected", 0)
            put("active_prompt_chars", 0)
            put("active_attachments", 0)
            put("active_background_enabled", 1)
            put("active_last_sequence_number", -1)
            putNull("active_indexed_checkpoint")
            put("updated_at", System.currentTimeMillis())
        }
        val where = if (expectedRequestId != null) "id=? AND active_request_id=?" else "id=?"
        val args = if (expectedRequestId != null) arrayOf(chatId, expectedRequestId) else arrayOf(chatId)
        writableDatabase.update(CHAT_TABLE, values, where, args)
    }

    fun usageTotals(since: Long = 0L): JSONObject {
        readableDatabase.query(
            METRICS_TABLE,
            arrayOf(
                "COUNT(*)", "SUM(success)", "SUM(input_tokens)", "SUM(cached_tokens)",
                "SUM(cache_write_tokens)", "SUM(output_tokens)", "SUM(reasoning_tokens)",
                "SUM(estimated_cost_usd)"
            ),
            "created_at>=?", arrayOf(since.coerceAtLeast(0L).toString()),
            null, null, null
        ).use { c ->
            if (!c.moveToFirst()) return JSONObject()
            return JSONObject()
                .put("requests", c.getLong(0))
                .put("success", c.getLong(1))
                .put("input_tokens", c.getLong(2))
                .put("cached_tokens", c.getLong(3))
                .put("cache_write_tokens", c.getLong(4))
                .put("output_tokens", c.getLong(5))
                .put("reasoning_tokens", c.getLong(6))
                .put("estimated_cost_usd", c.getDouble(7))
        }
    }

    private fun ensureLocalArtifactsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS answer_notifications (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, completed INTEGER NOT NULL,
            created_at INTEGER NOT NULL, PRIMARY KEY(chat_id, request_id),
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS openrouter_checkpoints (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, protocol TEXT NOT NULL,
            PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_artifacts (
            path TEXT PRIMARY KEY NOT NULL, chat_id TEXT NOT NULL, request_id TEXT NOT NULL,
            created_at INTEGER NOT NULL, metadata TEXT NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
    }

    fun saveOpenRouterCheckpoint(chatId: String, requestId: String, protocol: JSONObject) {
        writableDatabase.transaction { db ->
            val active = db.query(CHAT_TABLE, arrayOf("active_request_id"), "id=?", arrayOf(chatId), null, null, null).use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (active != requestId) return@transaction
            db.insertWithOnConflict("openrouter_checkpoints", null, ContentValues().apply {
                put("chat_id", chatId); put("request_id", requestId); put("protocol", protocol.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    private fun queueAnswerNotification(db: SQLiteDatabase, chatId: String, requestId: String, completed: Boolean) {
        db.insertWithOnConflict("answer_notifications", null, ContentValues().apply {
            put("chat_id", chatId); put("request_id", requestId); put("completed", if (completed) 1 else 0)
            put("created_at", System.currentTimeMillis())
        }, SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun pendingAnswerNotifications(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.query("answer_notifications", arrayOf("chat_id", "request_id", "completed"), null, null, null, null, "created_at").use { c ->
            while (c.moveToNext()) out += JSONObject().put("chat_id", c.getString(0)).put("request_id", c.getString(1)).put("completed", c.getInt(2) != 0)
        }
        return out
    }
    fun acknowledgeAnswerNotification(chatId: String, requestId: String) {
        writableDatabase.delete("answer_notifications", "chat_id=? AND request_id=?", arrayOf(chatId, requestId))
    }
    fun getOpenRouterCheckpoint(chatId: String, requestId: String): JSONObject? = SqliteLargeTextQuery.query(readableDatabase, "openrouter_checkpoints", arrayOf("protocol"), "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
        keyColumns = arrayOf("chat_id", "request_id")
    ).use { c -> if (c.moveToFirst()) JSONObject(c.getString(0)) else null }
    fun clearOpenRouterCheckpoint(chatId: String, requestId: String) {
        writableDatabase.delete("openrouter_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, requestId))
    }

    /** Durable even if a tool succeeded just before Stop consumed response ownership. */
    fun registerLocalArtifact(chatId: String, requestId: String, metadata: JSONObject): Boolean {
        var saved = false
        writableDatabase.transaction { db ->
            val exists = db.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(chatId), null, null, null).use { it.moveToFirst() }
            if (!exists) return@transaction
            db.insertWithOnConflict("local_artifacts", null, ContentValues().apply {
                put("path", metadata.getString("path")); put("chat_id", chatId); put("request_id", requestId)
                put("created_at", System.currentTimeMillis()); put("metadata", metadata.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            saved = true
        }
        return saved
    }

    /** Local copies of assistant-generated/downloaded files and generated images. */
    fun listLocalFiles(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        val titles = mutableMapOf<String, String>()
        fun title(chatId: String) = titles.getOrPut(chatId) { getChatTitle(chatId).orEmpty() }
        SqliteLargeTextQuery.query(readableDatabase, "local_artifacts", arrayOf("chat_id", "metadata"),
            null, null, null, null, "created_at DESC", keyColumns = arrayOf("path")).use { c ->
            while (c.moveToNext()) {
                val a = JSONObject(c.getString(1))
                val path = a.getString("path")
                if (java.io.File(path).isFile && seen.add("file:$path")) out += a
                    .put("kind", "file").put("chat_id", c.getString(0)).put("chat_title", title(c.getString(0)))
            }
        }
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("chat_id", "annotations", "generated_images"),
            "annotations IS NOT NULL OR generated_images IS NOT NULL", null,
            null, null, "id DESC"
        ).use { cur ->
            while (cur.moveToNext()) {
                val chatId = cur.getString(0)
                val chatTitle = title(chatId)
                val annotationsRaw = cur.getString(1)
                val imagesRaw = cur.getString(2)
                val annotations = try { if (!annotationsRaw.isNullOrBlank()) JSONArray(annotationsRaw) else JSONArray() } catch (_: Exception) { JSONArray() }
                for (i in 0 until annotations.length()) {
                    val a = annotations.optJSONObject(i) ?: continue
                    val path = a.optString("local_path").takeIf { it.isNotBlank() } ?: continue
                    val file = java.io.File(path)
                    if (!file.isFile) continue
                    if (!seen.add("file:$path")) continue
                    out += JSONObject()
                        .put("kind", "file")
                        .put("chat_id", chatId)
                        .put("chat_title", chatTitle)
                        .put("filename", JsonProtocol.string(a, "filename").ifBlank { "file" })
                        .put("path", path)
                        .put("mime_type", a.optString("mime_type"))
                        .put("size_bytes", a.optLong("size_bytes", file.length()))
                }
                val images = try { if (!imagesRaw.isNullOrBlank()) JSONArray(imagesRaw) else JSONArray() } catch (_: Exception) { JSONArray() }
                for (i in 0 until images.length()) {
                    val a = images.optJSONObject(i) ?: continue
                    val path = a.optString("local_path").takeIf { it.isNotBlank() } ?: continue
                    val file = java.io.File(path)
                    if (!file.isFile || !seen.add("image:$path")) continue
                    out += JSONObject()
                        .put("kind", "image")
                        .put("chat_id", chatId)
                        .put("chat_title", chatTitle)
                        .put("filename", JsonProtocol.string(a, "filename").ifBlank { "astra_image.png" })
                        .put("path", path)
                        .put("mime_type", a.optString("mime_type").ifBlank { "image/png" })
                        .put("size_bytes", a.optLong("size_bytes", file.length()))
                }
            }
        }
        return out
    }

    fun listLocalFilePaths(chatId: String): List<String> {
        if (chatId.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        readableDatabase.query("local_artifacts", arrayOf("path"), "chat_id=?", arrayOf(chatId), null, null, null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("file_paths", "image_path", "attachments", "annotations", "generated_images"),
            "chat_id=?",
            arrayOf(chatId),
            null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val filePaths = try { JSONArray(c.getString(0).orEmpty()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until filePaths.length()) {
                    filePaths.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
                }

                c.getString(1)?.takeIf { it.isNotBlank() }?.let(out::add)

                val attachments = try { JSONArray(c.getString(2).orEmpty()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until attachments.length()) {
                    val a = attachments.optJSONObject(i) ?: continue
                    out.addAll(LocalAttachmentReferences.openRouterPaths(JSONArray().put(a)))
                }

                val annotations = try { JSONArray(c.getString(3).orEmpty()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until annotations.length()) {
                    annotations.optJSONObject(i)?.optString("local_path")
                        ?.takeIf { it.isNotBlank() }?.let(out::add)
                }

                val generatedImages = try { JSONArray(c.getString(4).orEmpty()) } catch (_: Exception) { JSONArray() }
                for (i in 0 until generatedImages.length()) {
                    generatedImages.optJSONObject(i)?.optString("local_path")
                        ?.takeIf { it.isNotBlank() }?.let(out::add)
                }
            }
        }
        return out.toList()
    }

    fun listAllLocalFilePaths(): List<String> {
        val out = linkedSetOf<String>()
        readableDatabase.query("local_artifacts", arrayOf("path"), null, null, null, null, null).use { c ->
            while (c.moveToNext()) out.add(c.getString(0))
        }
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("file_paths", "image_path", "attachments", "annotations", "generated_images"),
            null, null, null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                try { JSONArray(c.getString(0).orEmpty()).let { a -> for (i in 0 until a.length()) a.optString(i).takeIf { it.isNotBlank() }?.let(out::add) } } catch (_:Exception) {}
                c.getString(1)?.takeIf { it.isNotBlank() }?.let(out::add)
                try { out.addAll(LocalAttachmentReferences.openRouterPaths(JSONArray(c.getString(2).orEmpty()))) } catch (_: Exception) {}
                try { JSONArray(c.getString(3).orEmpty()).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("local_path")?.takeIf { it.isNotBlank() }?.let(out::add) } } catch (_:Exception) {}
                try { JSONArray(c.getString(4).orEmpty()).let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.optString("local_path")?.takeIf { it.isNotBlank() }?.let(out::add) } } catch (_:Exception) {}
            }
        }
        return out.toList()
    }

    fun removeLocalFile(path: String): Boolean {
        if (path.isBlank()) return false
        var removed = false
        writableDatabase.transaction { db ->
            if (db.delete("local_artifacts", "path=?", arrayOf(path)) > 0) removed = true
            SqliteLargeTextQuery.query(db, MESSAGE_TABLE, arrayOf("id", "annotations", "generated_images"), null, null, null, null, "id ASC").use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    var changed = false
                    val annotationsRaw = c.getString(1)
                    if (!annotationsRaw.isNullOrBlank()) {
                        val anns = try { JSONArray(annotationsRaw) } catch (_: Exception) { JSONArray() }
                        for (i in 0 until anns.length()) {
                            val a = anns.optJSONObject(i) ?: continue
                            if (a.optString("local_path") == path) {
                                a.remove("local_path")
                                a.put("state", "PENDING")
                                a.remove("size_bytes")
                                a.remove("mime_type")
                                changed = true
                                removed = true
                            }
                        }
                        if (changed) {
                            db.update(MESSAGE_TABLE, ContentValues().apply { put("annotations", anns.toString()) }, "id=?", arrayOf(id.toString()))
                        }
                    }
                    val imagesRaw = c.getString(2)
                    if (!imagesRaw.isNullOrBlank()) {
                        val images = try { JSONArray(imagesRaw) } catch (_: Exception) { JSONArray() }
                        val keep = JSONArray()
                        var imageChanged = false
                        for (i in 0 until images.length()) {
                            val a = images.optJSONObject(i) ?: continue
                            if (a.optString("local_path") == path) {
                                imageChanged = true
                                removed = true
                            } else {
                                keep.put(a)
                            }
                        }
                        if (imageChanged) {
                            db.update(MESSAGE_TABLE, ContentValues().apply { put("generated_images", keep.toString()) }, "id=?", arrayOf(id.toString()))
                        }
                    }
                }
            }
        }
        return removed
    }

    fun effortStats(days: Int = 30): String {
        val since = System.currentTimeMillis() - days.coerceIn(1, 3650) * 86_400_000L
        val sb = StringBuilder()
        val d = readableDatabase
        d.query(
            METRICS_TABLE,
            arrayOf("effective_effort", "COUNT(*) AS n", "AVG(latency_ms) AS latency", "SUM(output_tokens) AS output", "SUM(reasoning_tokens) AS reasoning", "SUM(success) AS success", "SUM(CASE WHEN feedback IS NOT NULL THEN 1 ELSE 0 END) AS ratings", "AVG(feedback) AS avg_feedback"),
            "created_at>=?",
            arrayOf(since.toString()),
            "effective_effort",
            null,
            "CASE effective_effort WHEN 'low' THEN 1 WHEN 'medium' THEN 2 WHEN 'high' THEN 3 WHEN 'xhigh' THEN 4 WHEN 'max' THEN 5 ELSE 6 END"
        ).use { c ->
            if (!c.moveToFirst()) return "Пока нет измерений."
            sb.append("Последние $days дней\n\n")
            do {
                val effort = c.getString(c.getColumnIndexOrThrow("effective_effort"))
                val n = c.getInt(c.getColumnIndexOrThrow("n"))
                val latency = c.getDouble(c.getColumnIndexOrThrow("latency"))
                val output = c.getLong(c.getColumnIndexOrThrow("output"))
                val reasoning = c.getLong(c.getColumnIndexOrThrow("reasoning"))
                val success = c.getInt(c.getColumnIndexOrThrow("success"))
                val ratings = c.getInt(c.getColumnIndexOrThrow("ratings"))
                val avgFeedback = c.getDouble(c.getColumnIndexOrThrow("avg_feedback"))
                val feedbackText = if (ratings > 0) ", оценок $ratings, avg ${"%.2f".format(avgFeedback)}" else ""
                sb.append("$effort: $n запросов, успех $success/$n, средняя задержка ${"%.1f".format(latency / 1000.0)} с, output ${formatTokens(output)}, reasoning ${formatTokens(reasoning)}$feedbackText\n")
            } while (c.moveToNext())
        }
        return sb.toString().trim()
    }

    private fun loadMessages(chatId: String): JSONArray {
        val arr = JSONArray()
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE, null, "chat_id=?", arrayOf(chatId), null, null, "position ASC, id ASC").use { c ->
            while (c.moveToNext()) {
                val m = JSONObject()
                    .put("_db_message_id", c.getLong(c.getColumnIndexOrThrow("id")))
                    .put("chat_id", chatId)
                    .put("role", c.getString(c.getColumnIndexOrThrow("role")))
                    .put("text", c.getString(c.getColumnIndexOrThrow("text")))
                c.getString(c.getColumnIndexOrThrow("response_id"))?.let { if (it.isNotBlank()) m.put("response_id", it) }
                c.getString(c.getColumnIndexOrThrow("request_id"))?.let { if (it.isNotBlank()) m.put("request_id", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_ids")))?.let { m.put("file_ids", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_names")))?.let { m.put("file_names", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_sizes")))?.let { m.put("file_sizes", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_paths")))?.let { m.put("file_paths", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_mimes")))?.let { m.put("file_mimes", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("attachments")))?.let { m.put("attachments", it) }
                c.getString(c.getColumnIndexOrThrow("image_path"))?.let { if (it.isNotBlank()) m.put("image_path", it) }
                c.getString(c.getColumnIndexOrThrow("image_file_id"))?.let { if (it.isNotBlank()) m.put("image_file_id", it) }
                c.getString(c.getColumnIndexOrThrow("image_name"))?.let { if (it.isNotBlank()) m.put("image_name", it) }
                c.getString(c.getColumnIndexOrThrow("image_mime"))?.let { if (it.isNotBlank()) m.put("image_mime", it) }
                m.put("image_size", c.getLong(c.getColumnIndexOrThrow("image_size")))
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("annotations")))?.let { m.put("annotations", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("generated_images")))?.let { m.put("generated_images", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("tools")))?.let { m.put("tools", it) }
                c.getString(c.getColumnIndexOrThrow("status"))?.let { if (it.isNotBlank()) m.put("status", it) }
                if (!c.isNull(c.getColumnIndexOrThrow("feedback"))) m.put("feedback", c.getInt(c.getColumnIndexOrThrow("feedback")))
                MessageMetadataCodec.allKeys.forEach { key ->
                    MessageMetadataCodec.decodeInto(m, key, c.getString(c.getColumnIndexOrThrow(key)))
                }
                arr.put(m)
            }
        }
        return arr
    }

    private fun upsertChatObject(db: SQLiteDatabase, c: JSONObject) {
        val id = c.optString("id").trim()
        if (id.isBlank()) return
        val title = c.optString("title", "Новый чат").ifBlank { "Новый чат" }
        val v = ContentValues().apply {
            put("id", id)
            put("title", title)
            val response = c.optString("response_id").takeIf { it.isNotBlank() && it != "null" }
            putStringOrNull("response_id", response)
            val baseline = c.optString("baseline_effort").takeIf { it.isNotBlank() && it != "null" }
            putStringOrNull("baseline_effort", baseline)
            put("updated_at", System.currentTimeMillis())
        }
        db.insertWithOnConflict(CHAT_TABLE, null, v, SQLiteDatabase.CONFLICT_REPLACE)
        db.delete(MESSAGE_TABLE, "chat_id=?", arrayOf(id))
        val arr = c.optJSONArray("messages") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val m = arr.optJSONObject(i) ?: continue
            val mv = ContentValues().apply {
                put("chat_id", id)
                put("position", i)
                put("role", m.optString("role"))
                put("text", JsonProtocol.string(m, "text"))
                putStringOrNull("response_id", m.optString("response_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("request_id", m.optString("request_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("file_ids", m.optJSONArray("file_ids")?.toString())
                putStringOrNull("file_names", m.optJSONArray("file_names")?.toString())
                putStringOrNull("file_sizes", m.optJSONArray("file_sizes")?.toString())
                putStringOrNull("file_paths", m.optJSONArray("file_paths")?.toString())
                putStringOrNull("file_mimes", m.optJSONArray("file_mimes")?.toString())
                putStringOrNull("attachments", m.optJSONArray("attachments")?.toString())
                putStringOrNull("image_path", JsonProtocol.string(m, "image_path").takeIf { it.isNotBlank() })
                putStringOrNull("image_file_id", JsonProtocol.string(m, "image_file_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("image_name", JsonProtocol.string(m, "image_name").takeIf { it.isNotBlank() })
                putStringOrNull("image_mime", JsonProtocol.string(m, "image_mime").takeIf { it.isNotBlank() })
                put("image_size", m.optLong("image_size", 0L).coerceAtLeast(0L))
                putStringOrNull("annotations", m.optJSONArray("annotations")?.toString())
                putStringOrNull("generated_images", m.optJSONArray("generated_images")?.toString())
                putStringOrNull("tools", m.optJSONArray("tools")?.toString())
                putStringOrNull("status", m.optString("status").takeIf { it.isNotBlank() && it != "null" })
                MessageMetadataCodec.allKeys.forEach { key ->
                    putStringOrNull(key, MessageMetadataCodec.serialized(m, key))
                }
                if (m.has("feedback") && !m.isNull("feedback")) put("feedback", m.optInt("feedback")) else putNull("feedback")
            }
            db.insertOrThrow(MESSAGE_TABLE, null, mv)
        }
    }

    private fun ContentValues.putStringOrNull(key: String, value: String?) {
        if (value.isNullOrBlank()) putNull(key) else put(key, value)
    }

    private fun parseArrayColumn(raw: String?): JSONArray? {
        if (raw.isNullOrBlank()) return null
        return try { JSONArray(raw) } catch (_: Exception) { null }
    }

    private fun formatTokens(n: Long): String = when {
        n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
        n >= 1_000 -> "%.1fK".format(n / 1_000.0)
        else -> n.toString()
    }

    private inline fun SQLiteDatabase.transaction(block: (SQLiteDatabase) -> Unit) {
        beginTransaction()
        try {
            block(this)
            setTransactionSuccessful()
        } finally {
            endTransaction()
        }
    }
}
