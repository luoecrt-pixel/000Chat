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
    private val appContext: Context = context.applicationContext ?: context

    companion object {
        private const val DB_NAME = "astra_chats.db"
        // The app opens this file via independent SQLiteOpenHelpers in Activity,
        // WorkManager and lifecycle callbacks. Android sets PRAGMA journal_mode
        // during connection initialization. Concurrent helper opens can race
        // for its exclusive journal-mode transition and raise SQLITE_BUSY.
        private val CONNECTION_OPEN_LOCK = Any()
        private val ARTIFACT_RECONCILIATION_RUNNING = java.util.concurrent.atomic.AtomicBoolean(false)
        private const val DB_VERSION = 26
        private const val CHAT_TABLE = "chats"
        private const val MESSAGE_TABLE = "messages"
        private const val LOCAL_FILE_REFERENCE_SELECTION = "file_paths IS NOT NULL OR image_path IS NOT NULL OR attachments IS NOT NULL OR annotations IS NOT NULL OR generated_images IS NOT NULL"
        private const val METRICS_TABLE = "effort_metrics"
        private const val REQUEST_EVENT_TABLE = "request_events"
        private const val REQUEST_ACCOUNTING_TABLE = "request_accounting"
        private const val OPENROUTER_ROUND_TABLE = "openrouter_request_rounds"
        private const val DEEPSEEK_ROUND_TABLE = "deepseek_request_rounds"
        private val TOKEN_COLUMNS = listOf("input_tokens", "cached_tokens", "cache_write_tokens", "output_tokens", "reasoning_tokens")
    }

    init {
        // All Activity, Worker and instrumentation helpers open the same
        // persistent database. Using the platform's rollback journal default
        // makes SQLiteConnection.setJournalMode attempt exclusive PRAGMA
        // journal_mode transitions while other owners have active writes.
        // WAL preserves atomic CAS/transactions while allowing readers to
        // open during a writer's transaction. Set it on EVERY helper before
        // its first database open, never by running PRAGMA in an active query.
        setWriteAheadLoggingEnabled(true)
    }

    /**
     * Serialize only opening/configuring SQLite connections, NOT user queries
     * or transactions. An open helper may race an already-running transaction
     * in another helper; retry SQLITE_BUSY only while the helper itself is
     * opening/configuring its connection (including idempotent onOpen schema
     * ensures). Never replay a caller's transaction or mutate a chat on retry.
     * Non-lock SQL/upgrade errors propagate untouched.
     */
    private fun <T> openConnection(operation: () -> T): T = synchronized(CONNECTION_OPEN_LOCK) {
        var pauseMs = 35L
        repeat(8) { attempt ->
            try {
                return@synchronized operation()
            } catch (e: android.database.sqlite.SQLiteDatabaseLockedException) {
                if (attempt == 7) throw e
                try { Thread.sleep(pauseMs) } catch (interrupt: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw e
                }
                pauseMs = (pauseMs * 2).coerceAtMost(1_000L)
            }
        }
        error("Unreachable SQLite connection opener")
    }

    override fun getWritableDatabase(): SQLiteDatabase = openConnection { super.getWritableDatabase() }

    override fun getReadableDatabase(): SQLiteDatabase = openConnection { super.getReadableDatabase() }

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
                cache_write_tokens INTEGER,
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
                usage_snapshot TEXT,
                updated_at INTEGER NOT NULL,
                PRIMARY KEY(chat_id, local_request_id, round_index),
                FOREIGN KEY(chat_id, local_request_id)
                    REFERENCES $REQUEST_ACCOUNTING_TABLE(chat_id, local_request_id)
                    ON DELETE CASCADE
            )
        """.trimIndent())
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_openrouter_generation_identity ON $OPENROUTER_ROUND_TABLE(generation_id) WHERE generation_id IS NOT NULL")
    }

    private fun ensureDeepSeekRoundsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS $DEEPSEEK_ROUND_TABLE (
            chat_id TEXT NOT NULL, local_request_id TEXT NOT NULL, round_index INTEGER NOT NULL,
            usage_snapshot TEXT, usage_complete INTEGER NOT NULL DEFAULT 0, updated_at INTEGER NOT NULL,
            PRIMARY KEY(chat_id, local_request_id, round_index),
            FOREIGN KEY(chat_id, local_request_id) REFERENCES $REQUEST_ACCOUNTING_TABLE(chat_id, local_request_id) ON DELETE CASCADE
        )""")
    }

    override fun onOpen(db: SQLiteDatabase) {
        super.onOpen(db)
        ensureLocalArtifactsTable(db)
        ensureTelemetryAndCostColumns(db)
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
        ensureTelemetryAndCostColumns(db)
    }

    /** Additive, idempotent for existing v25 installs as well as migration from v24. */
    private fun ensureTelemetryAndCostColumns(db: SQLiteDatabase) {
        ensureRequestEventsTable(db)
        ensureRequestAccountingTable(db)
        ensureOpenRouterRoundsTable(db)
        // Older/partially migrated installs can contain chat history before optional metrics exist.
        // Reconstruct the optional empty table rather than failing the additive history migration.
        db.execSQL("""CREATE TABLE IF NOT EXISTS $METRICS_TABLE (
            id INTEGER PRIMARY KEY AUTOINCREMENT, chat_id TEXT NOT NULL, created_at INTEGER NOT NULL,
            effort_pref TEXT NOT NULL, effective_effort TEXT NOT NULL, auto_selected INTEGER NOT NULL,
            prompt_chars INTEGER NOT NULL, attachments INTEGER NOT NULL, latency_ms INTEGER NOT NULL,
            input_tokens INTEGER NOT NULL DEFAULT 0, cached_tokens INTEGER NOT NULL DEFAULT 0,
            cache_write_tokens INTEGER NOT NULL DEFAULT 0, output_tokens INTEGER NOT NULL DEFAULT 0,
            reasoning_tokens INTEGER NOT NULL DEFAULT 0, success INTEGER NOT NULL, error_type TEXT,
            request_id TEXT, task_class TEXT NOT NULL DEFAULT 'general', feedback INTEGER,
            estimated_cost_usd REAL NOT NULL DEFAULT 0,
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_effort_metrics_created ON $METRICS_TABLE(created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_effort_metrics_task ON $METRICS_TABLE(task_class, effective_effort, feedback)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_effort_metrics_chat_request ON $METRICS_TABLE(chat_id, request_id) WHERE request_id IS NOT NULL")
        val columnsByTable = mutableMapOf<String, MutableSet<String>>()
        fun column(table: String, name: String, definition: String = "TEXT") {
            val columns = columnsByTable.getOrPut(table) {
                db.rawQuery("PRAGMA table_info($table)", null).use { cursor ->
                    val index = cursor.getColumnIndexOrThrow("name")
                    mutableSetOf<String>().apply {
                        while (cursor.moveToNext()) add(cursor.getString(index))
                    }
                }
            }
            if (name !in columns) {
                db.execSQL("ALTER TABLE $table ADD COLUMN $name $definition")
                columns.add(name)
            }
        }
        column(MESSAGE_TABLE, "telemetry_summary")
        column(MESSAGE_TABLE, "openai_usage_ledger")
        column(MESSAGE_TABLE, "deepseek_protocol")
        column(REQUEST_EVENT_TABLE, "usage_complete", "INTEGER NOT NULL DEFAULT 1")
        column(REQUEST_ACCOUNTING_TABLE, "usage_complete", "INTEGER NOT NULL DEFAULT 0")
        column(METRICS_TABLE, "usage_complete", "INTEGER NOT NULL DEFAULT 1")
        ensureDeepSeekRoundsTable(db)
        column(REQUEST_EVENT_TABLE, "telemetry_summary")
        column(REQUEST_ACCOUNTING_TABLE, "telemetry_summary")
        column(REQUEST_ACCOUNTING_TABLE, "cache_write_tokens", "INTEGER")
        column(OPENROUTER_ROUND_TABLE, "usage_snapshot")
        column(METRICS_TABLE, "cost_kind", "TEXT NOT NULL DEFAULT 'unknown'")
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
                    "openai_instructions_sha256", "openai_protocol_output", "telemetry_summary", "openai_usage_ledger", "deepseek_protocol")
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
        if (oldVersion < 26) {
            ensureTelemetryAndCostColumns(db)
            // Existing optional counters retain their meaning. No old row is assigned DeepSeek data.
            db.execSQL("UPDATE $REQUEST_ACCOUNTING_TABLE SET usage_complete=1 WHERE provider!='deepseek' AND input_tokens IS NOT NULL AND output_tokens IS NOT NULL")
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

    /** Recovery needs only this small binding, never another copy of the full history. */
    fun getOpenAiRequestFingerprint(chatId: String, requestId: String): String? {
        if (chatId.isBlank() || requestId.isBlank()) return null
        readableDatabase.query(MESSAGE_TABLE, arrayOf(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD),
            "chat_id=? AND request_id=? AND role='user' AND provider='openai' " +
                "AND length(openai_instructions_sha256)=132",
            arrayOf(chatId, requestId), null, null, "position ASC", "1").use { cursor ->
            return if (cursor.moveToFirst()) cursor.getString(0) else null
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

    private fun activeComposerDraftPreviews(): Map<String, String> {
        val prefs = try {
            appContext.getSharedPreferences("app", Context.MODE_PRIVATE)
        } catch (_: Exception) {
            return emptyMap()
        }
        val result = mutableMapOf<String, String>()
        for ((key, raw) in prefs.all) {
            if (!key.startsWith("composer_state_") || raw !is String || raw.isBlank()) continue
            val chatId = key.removePrefix("composer_state_").takeIf { it.isNotBlank() } ?: continue
            val json = try { JSONObject(raw) } catch (_: Exception) { continue }
            val draftText = JsonProtocol.string(json, "text").trim()
            val hasAttachments =
                (json.optJSONArray("openrouter_attachments")?.length() ?: 0) > 0 ||
                (json.optJSONArray("pending_attachments")?.length() ?: 0) > 0 ||
                (json.optJSONArray("file_ids")?.length() ?: 0) > 0 ||
                (json.optJSONArray("file_paths")?.length() ?: 0) > 0 ||
                (json.optJSONArray("pending_uploads")?.length() ?: 0) > 0 ||
                JsonProtocol.string(json, "image_path").isNotBlank() ||
                JsonProtocol.nonBlank(json, "image_file_id")?.takeIf { it != "null" } != null
            if (draftText.isNotEmpty() || hasAttachments) {
                val preview = if (draftText.isNotEmpty()) {
                    "Черновик: " + draftText.replace(Regex("\\s+"), " ").take(28)
                } else {
                    "Черновик (вложение)"
                }
                result[chatId] = preview
            }
        }
        return result
    }

    /**
     * Lightweight list for the chat picker; never loads full message histories.
     * Task 2: Truly empty provisional sessions stay hidden, while sessions with
     * sent messages, active requests, custom titles, or non-empty composer drafts
     * remain reachable in the drawer.
     */
    fun listChatSummaries(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val draftPreviews = activeComposerDraftPreviews()
        val publishedIds = HashSet<String>()
        readableDatabase.rawQuery(
            """
            SELECT id FROM $CHAT_TABLE
            WHERE title != 'Новый чат'
               OR active_status IS NOT NULL
               OR response_id IS NOT NULL
               OR EXISTS (SELECT 1 FROM $MESSAGE_TABLE m WHERE m.chat_id=$CHAT_TABLE.id)
               OR EXISTS (SELECT 1 FROM $REQUEST_ACCOUNTING_TABLE a WHERE a.chat_id=$CHAT_TABLE.id)
            """.trimIndent(),
            null
        ).use { c ->
            while (c.moveToNext()) publishedIds.add(c.getString(0))
        }
        SqliteLargeTextQuery.query(readableDatabase, CHAT_TABLE,
            arrayOf("id", "title", "response_id", "baseline_effort", "updated_at"),
            null, null, null, null, "updated_at DESC, id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val id = c.getString(c.getColumnIndexOrThrow("id"))
                val isPublished = id in publishedIds
                val draftPreview = draftPreviews[id]
                if (!isPublished && draftPreview == null) continue
                val dbTitle = c.getString(c.getColumnIndexOrThrow("title"))
                val displayTitle = if (!isPublished && dbTitle == "Новый чат" && draftPreview != null) {
                    draftPreview
                } else {
                    dbTitle
                }
                val obj = JSONObject()
                    .put("id", id)
                    .put("title", displayTitle)
                if (!isPublished && draftPreview != null) {
                    obj.put("is_draft", true)
                }
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
        val normalizedTitle = title.ifBlank { "Чат" }
        writableDatabase.update(CHAT_TABLE, ContentValues().apply {
            put("title", normalizedTitle)
            put("updated_at", System.currentTimeMillis())
        }, "id=? AND title!=?", arrayOf(id, normalizedTitle))
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
            val storedTitle = SqliteLargeTextQuery.query(db, CHAT_TABLE, arrayOf("title"), "id=?",
                arrayOf(id), null, null, null, "1").use { c -> if (c.moveToFirst()) c.getString(0) else null }
            if (storedTitle == null) return@transaction
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
            var appended = false
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
                    appended = true
                }
            }
            val titleChanged = title.isNotBlank() && title != storedTitle
            if (appended || titleChanged) {
                val values = ContentValues().apply {
                    if (titleChanged) put("title", title)
                    // Opening/leaving a chat and saving settings are not content changes.
                    // Response/effort metadata is owned by terminal writer, not stale UI.
                    put("updated_at", System.currentTimeMillis())
                }
                db.update(CHAT_TABLE, values, "id=?", arrayOf(id))
            }
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
        expectedRequestId: String,
        unrecoverableIndexedCheckpoint: String? = null
    ): Boolean {
        // Reject a single terminal assistant with a foreign/missing local request_id.
        // Otherwise a provider HTTP request ID can consume the active claim while
        // silently persisting no assistant. Multi-message snapshots may legitimately
        // contain other requests, or be empty during an explicit failure claim.
        if (messages.size == 1 && JsonProtocol.string(messages[0], "role") == "assistant" &&
            JsonProtocol.string(messages[0], "request_id") != expectedRequestId) return false
        return completeActiveRequestOnce(id, expectedRequestId) { db ->
        val provided = messages.lastOrNull { it.optString("role") == "assistant" && it.optString("request_id") == expectedRequestId }
        val assistant = recoverAssistantInDb(db, id, expectedRequestId, provided,
            provided?.optString("status") ?: "failed", unrecoverableIndexedCheckpoint)
        if (assistant != null) {
            persistTerminalAssistantInDb(db, id, expectedRequestId, assistant)
            queueAnswerNotification(db, id, expectedRequestId, assistant.optString("status") == "completed")
        }
        val clearOpenAiChain = assistant?.optString("provider") == "openai" &&
            assistant.optString("status") != "completed"
        if (!responseId.isNullOrBlank() || !baselineEffort.isNullOrBlank() || clearOpenAiChain) {
            db.update(CHAT_TABLE, ContentValues().apply {
                if (clearOpenAiChain) putNull("response_id")
                else responseId?.takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                baselineEffort?.takeIf { it.isNotBlank() }?.let { put("baseline_effort", it) }
                put("updated_at", System.currentTimeMillis())
            }, "id=?", arrayOf(id))
        }
        }
    }

    /** Fatal recovery with no new messages still consumes its one-shot claim. */
    fun claimActiveRequestFailure(chatId: String, expectedRequestId: String): Boolean =
        completeActiveRequestOnce(chatId, expectedRequestId) { db ->
            recoverAssistantInDb(db, chatId, expectedRequestId, null, "failed")?.let {
                persistTerminalAssistantInDb(db, chatId, expectedRequestId, it)
            }
            db.update(REQUEST_ACCOUNTING_TABLE, ContentValues().apply {
                put("local_status", "failed"); put("updated_at", System.currentTimeMillis())
            }, "chat_id=? AND local_request_id=? AND local_status='running'", arrayOf(chatId, expectedRequestId))
            queueAnswerNotification(db, chatId, expectedRequestId, false)
        }

    private fun readCheckpointInDb(db: SQLiteDatabase, table: String, field: String,
        chatId: String, requestId: String): JSONObject? =
        SqliteLargeTextQuery.query(db, table, arrayOf(field), "chat_id=? AND request_id=?",
            arrayOf(chatId, requestId), null, null, null, "1", arrayOf("chat_id", "request_id")).use { c ->
            // A malformed paid checkpoint must roll back terminal compaction, not be silently deleted.
            if (c.moveToFirst()) JSONObject(c.getString(0)) else null
        }

    private fun readAccountingTelemetryInDb(db: SQLiteDatabase, chatId: String, requestId: String): String? =
        SqliteLargeTextQuery.query(db, REQUEST_ACCOUNTING_TABLE, arrayOf("telemetry_summary"),
            "chat_id=? AND local_request_id=?", arrayOf(chatId, requestId), null, null, null,
            "1", arrayOf("chat_id", "local_request_id")).use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Repair optional accounting diagnostics from the winning durable terminal, never from raw totals. */
    private fun restoredOpenAiAccountingTelemetryInDb(db: SQLiteDatabase, chatId: String, requestId: String,
        raw: String?): JSONObject {
        val telemetry = raw?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        if (OpenAiUsageLedger.total(telemetry.optJSONObject(OpenAiUsageLedger.FIELD)) != null) return telemetry
        // A Stop archive is its winning terminal claim and is newer than any prior visible partial.
        // Read paid archive JSON strictly: corruption there must fail closed, unlike optional diagnostics.
        val archivedLedger = SqliteLargeTextQuery.query(db, "request_recovery_archive", arrayOf("recovery_message"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
            "1", arrayOf("chat_id", "request_id")).use { c ->
            if (!c.moveToFirst()) null else JSONObject(c.getString(0)).let { message ->
                message.optJSONObject(OpenAiUsageLedger.FIELD)
                    ?: message.optJSONObject("telemetry_summary")?.optJSONObject(OpenAiUsageLedger.FIELD)
            }
        }?.takeIf { OpenAiUsageLedger.total(it) != null }
        val durableLedger = archivedLedger ?: SqliteLargeTextQuery.query(db, MESSAGE_TABLE,
            arrayOf(OpenAiUsageLedger.FIELD, "telemetry_summary"),
            "chat_id=? AND request_id=? AND role='assistant' AND provider='openai' " +
                "AND status IN ('completed','cancelled','failed','incomplete','stopped')",
            arrayOf(chatId, requestId), null, null, "position DESC", "1", arrayOf("id")).use { c ->
            if (!c.moveToFirst()) null else {
                val explicit = if (c.isNull(0)) null else JSONObject(c.getString(0))
                explicit?.takeIf { OpenAiUsageLedger.total(it) != null } ?: c.getString(1)?.let {
                    runCatching { JSONObject(it) }.getOrNull()?.optJSONObject(OpenAiUsageLedger.FIELD)
                }?.takeIf { OpenAiUsageLedger.total(it) != null }
            }
        }
        durableLedger?.let { telemetry.put(OpenAiUsageLedger.FIELD, OpenAiUsageLedger.merge(null, it)) }
        return telemetry
    }

    private fun readAccountingInDb(db: SQLiteDatabase, chatId: String, requestId: String): JSONObject? =
        SqliteLargeTextQuery.query(db, REQUEST_ACCOUNTING_TABLE,
            arrayOf("local_status", "cost_kind", "cost_usd", "input_tokens", "cached_tokens", "cache_write_tokens", "output_tokens",
                "reasoning_tokens", "telemetry_summary", "usage_complete", "provider"), "chat_id=? AND local_request_id=?",
            arrayOf(chatId, requestId), null, null, null, "1", arrayOf("chat_id", "local_request_id")).use { c ->
            if (!c.moveToFirst()) null else JSONObject().apply {
                for (i in 0 until c.columnCount) if (!c.isNull(i)) put(c.getColumnName(i),
                    if (c.getType(i) == android.database.Cursor.FIELD_TYPE_INTEGER) c.getLong(i) else c.getString(i))
            }
        }

    /** A late metric/event must reflect the already committed ledger, not an older callback snapshot. */
    private fun enrichFromAccounting(values: ContentValues, accounting: JSONObject?, metric: Boolean = false) {
        if (accounting == null) return
        if (accounting.optString("provider") == "deepseek" && accounting.has("usage_complete")) values.put("usage_complete", accounting.optInt("usage_complete"))
        for (key in listOf("input_tokens", "cached_tokens", "cache_write_tokens", "output_tokens", "reasoning_tokens")) {
            if (accounting.has(key)) values.put(key, accounting.getLong(key))
        }
        if (accounting.optString("cost_kind") == "provider_reported") {
            val cost = accounting.optString("cost_usd").toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }
            if (cost != null) {
                values.put("cost_kind", "provider_reported")
                if (metric) values.put("estimated_cost_usd", cost) else values.put("cost_usd", cost.toString())
            }
        }
        if (metric && accounting.optString("local_status") == "stopped") values.put("success", 0)
    }

    /** Restores paid terminal output as well as the current indexed SSE partial before deleting any cursor. */
    private fun recoverAssistantInDb(db: SQLiteDatabase, chatId: String, requestId: String,
        provided: JSONObject?, terminalStatus: String, unrecoverableIndexedCheckpoint: String? = null): JSONObject? {
        val oa = readCheckpointInDb(db, "openai_tool_checkpoints", "request_checkpoint", chatId, requestId)
        val or = readCheckpointInDb(db, "openrouter_checkpoints", "protocol", chatId, requestId)
        val ds = readCheckpointInDb(db, "deepseek_checkpoints", "protocol", chatId, requestId)
        val capturedProvider = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("provider"),
            "chat_id=? AND local_request_id=?", arrayOf(chatId, requestId), null, null, null, "1")
            .use { if (it.moveToFirst()) it.getString(0) else null }
        val router = capturedProvider == "openrouter" || (capturedProvider == null && provided?.optString("provider") == "openrouter")
        val deepSeek = capturedProvider == "deepseek" || (capturedProvider == null && provided?.optString("provider") == "deepseek")
        val providedProvider = provided?.let { JsonProtocol.nonBlank(it, "provider") }
        require(capturedProvider !in setOf("openai", "openrouter", "deepseek") || providedProvider == null || providedProvider == capturedProvider) {
            "Terminal provider does not match captured request provider"
        }
        val active = SqliteLargeTextQuery.query(db, CHAT_TABLE,
            arrayOf("active_response_id", "active_text", "active_indexed_checkpoint",
                "active_last_sequence_number", "active_status"), "id=? AND active_request_id=?",
            arrayOf(chatId, requestId), null, null, null, "1").use { c ->
            if (!c.moveToFirst()) JSONObject() else JSONObject().apply {
                c.getString(0)?.let { put("response_id", it) }
                put("text", c.getString(1) ?: "")
                c.getString(2)?.let { put("indexed_checkpoint", it) }
                put("last_sequence_number", c.getLong(3))
                c.getString(4)?.let { put("status", it) }
            }
        }
        var durable = if (!router && !deepSeek) OpenAiLocalFileToolBridge.durableResponse(oa) else null
        var unrecoverableIndexedState: JSONObject? = null
        if (!router && !deepSeek && JsonProtocol.nonBlank(active, "indexed_checkpoint") != null) {
            val state = try {
                ResponseIndexedCheckpoint.restore(active)
            } catch (error: UnrecoverableResponsesCheckpointException) {
                // Only the failure recorder that observed THIS exact snapshot can quarantine it.
                // Stop, generic failure claims and ordinary terminal callbacks still fail closed.
                if (provided == null || terminalStatus != "failed" || unrecoverableIndexedCheckpoint == null ||
                    JsonProtocol.string(active, "indexed_checkpoint") != unrecoverableIndexedCheckpoint) throw error
                unrecoverableIndexedState = JsonProtocol.objectCopy(active)
                null
            }
            if (state != null) {
                val current = OpenAiEffectiveResponse.assemble(state.terminalResponse, state)
                val canonicalReceived = oa?.optJSONObject("received_response")
                val canonicalReceivedWins = canonicalReceived?.optString("status") in
                    setOf("completed", "cancelled", "failed", "incomplete") &&
                    canonicalReceived?.let { JsonProtocol.nonBlank(it, "id") } == JsonProtocol.nonBlank(current, "id")
                // Bridge received_response is persisted after reading the provider envelope. An older
                // indexed terminal cursor for the same ID must not replace its paid text/images/usage.
                if (oa?.optString("phase") != "finished" && !canonicalReceivedWins) {
                    val prior = oa?.optJSONArray("prior_output") ?: JSONArray()
                    val output = JsonProtocol.arrayCopy(prior)
                    if (JsonProtocol.nonBlank(current, "id") != oa?.let { JsonProtocol.nonBlank(it, "parent_id") }) {
                        current.optJSONArray("output")?.let { items ->
                            for (i in 0 until items.length()) output.put(JsonProtocol.copy(items.get(i)))
                        }
                    }
                    if (output.length() > 0) current.put("output", output)
                    val ledger = OpenAiUsageLedger.resolve(oa, current)
                    OpenAiUsageLedger.attach(current, ledger)
                    durable = current
                }
            }
        }
        val message = provided?.let(JsonProtocol::objectCopy) ?: JSONObject()
            .put("role", "assistant").put("request_id", requestId).put("status", terminalStatus)
            .put("provider", if (deepSeek) "deepseek" else if (router) "openrouter" else "openai")
            .put("text", JsonProtocol.string(active, "text"))
        if (capturedProvider in setOf("openai", "openrouter", "deepseek")) message.put("provider", capturedProvider)
        val output = durable?.optJSONArray("output")
        if (output != null && (oa?.optString("phase") == "finished" || !message.has("openai_protocol_output"))) {
            message.put("openai_protocol_output", OpenAiLocalFileToolBridge.completePendingOutputs(output))
        }
        if (durable != null) {
            val fallbackText = OpenAiSseStreamReader.terminalOutputText(durable)
            val model = AstraResponseModel.fromResponse(durable, fallbackText, requestId, terminalStatus)
            val restored = model.toChatJson()
            for (key in listOf("response_id", "response_model", "reasoning_summary", "annotations", "tools")) {
                if (!message.has(key) || message.isNull(key)) restored.opt(key)?.let { message.put(key, JsonProtocol.copy(it)) }
            }
            if (JsonProtocol.string(message, "text").isBlank() && fallbackText.isNotBlank()) message.put("text", fallbackText)
            if (!message.has("generated_images") && model.generatedImageResults.isNotEmpty()) {
                // Keep one FAILED entry and the original encoded output when an individual image
                // cannot be saved. A damaged image must not abort Stop or discard paid text/usage.
                message.put("generated_images", GeneratedImageStore.save(appContext.filesDir, model.generatedImageResults) { _, _ -> })
            }
        }
        if (router && or != null && !message.has("openrouter_protocol"))
            message.put("openrouter_protocol", OpenRouterProtocolCodec.withVisibleTail(or, message))
        val accountingTelemetryRaw = readAccountingTelemetryInDb(db, chatId, requestId)
        if (router) {
            aggregateOpenRouterUsageInDb(db, chatId, requestId)?.let { persistedUsage ->
                OpenRouterUsageAccumulator.mergeSnapshot(message.optJSONObject("usage"), persistedUsage)
                    ?.let { merged ->
                        // mergeSnapshot preserves sparse observations within ONE round; here the
                        // persisted aggregate is authoritative about whether ALL rounds have cost.
                        if (!persistedUsage.has("cost")) merged.remove("cost")
                        message.put("usage", merged)
                    }
                val breakdown = OpenRouterUsageSnapshot.extractCostDetails(message.optJSONObject("usage"))
                FileBackedIoTelemetry.mergeTelemetryJson(message.optJSONObject("telemetry_summary")?.toString(),
                    FileBackedIoTelemetry.mergeCompactTelemetry(costDetails = breakdown))
                    ?.let { message.put("telemetry_summary", JSONObject(it)) }
            }
        }
        if (deepSeek) {
            val confirmedArtifacts = confirmedArtifactsInDb(db, chatId, requestId)
            if (confirmedArtifacts.length() > 0) message.put("confirmed_artifacts", confirmedArtifacts)
            if (ds != null && !message.has(DeepSeekProtocolCodec.FIELD)) {
                message.put(DeepSeekProtocolCodec.FIELD, DeepSeekProtocolCodec.withVisibleTail(ds, message))
            }
            val protocol = message.optJSONObject(DeepSeekProtocolCodec.FIELD)
            if (JsonProtocol.nonBlank(message, "reasoning_summary") == null) {
                DeepSeekProtocolCodec.extractReasoningSummary(protocol).takeIf { it.isNotBlank() }
                    ?.let { message.put("reasoning_summary", it) }
            }
            aggregateDeepSeekUsageInDb(db, chatId, requestId)?.let { message.put("usage", it) }
            val usage = message.optJSONObject("usage")
            val complete = isDeepSeekUsageComplete(usage)
            val telemetry = deepSeekUsageTelemetry(usage, complete)
            FileBackedIoTelemetry.mergeTelemetryJson(message.optJSONObject("telemetry_summary")?.toString(), telemetry)
                ?.let { message.put("telemetry_summary", JSONObject(it)) }
        }
        if (!router && !deepSeek) {
            // Marked aggregates merge as ledgers; they are never reinserted under a single response ID.
            val ledger = OpenAiUsageLedger.resolve(oa, message)
            val paidLedger = OpenAiUsageLedger.merge(ledger, durable?.optJSONObject(OpenAiUsageLedger.FIELD))
            val existingLedger = accountingTelemetryRaw?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject(OpenAiUsageLedger.FIELD)
            val combined = OpenAiUsageLedger.merge(existingLedger, paidLedger)
            OpenAiUsageLedger.total(combined)?.let { message.put("usage", it) }
            if (combined.length() > 0) message.put(OpenAiUsageLedger.FIELD, combined)
            val tools = OpenAiLocalFileToolBridge.extractTelemetrySummary(oa)
            val incoming = message.optJSONObject("telemetry_summary") ?: JSONObject()
            if (combined.length() > 0) incoming.put(OpenAiUsageLedger.FIELD, combined)
            FileBackedIoTelemetry.mergeTelemetryJson(tools?.toString(), incoming)?.let {
                message.put("telemetry_summary", JSONObject(it))
            }
        }
        // Initial request I/O can be persisted by Activity before Worker handoff. Preserve it in
        // the durable message as well as accounting when the resumed caller has no in-memory body.
        FileBackedIoTelemetry.mergeTelemetryJson(accountingTelemetryRaw, message.optJSONObject("telemetry_summary"))
            ?.let { message.put("telemetry_summary", JSONObject(it)) }
        val evidence = provided != null || oa != null || or != null || ds != null ||
            JsonProtocol.string(message, "text").isNotBlank() || message.has("openai_protocol_output")
        unrecoverableIndexedState?.let { source ->
            // Keep the original raw bytes and envelope out of model replay, but durably available
            // before the active cursor is released. An archive write failure rolls everything back.
            persistTerminalAssistantInDb(db, chatId, requestId,
                JsonProtocol.objectCopy(message).put("unrecoverable_indexed_state", source), archive = true)
        }
        return message.takeIf { evidence }
    }

    private fun persistTerminalAssistantInDb(db: SQLiteDatabase, chatId: String, requestId: String,
        assistant: JSONObject, archive: Boolean = false) {
        val deepSeekArchive = assistant.optString("provider") == "deepseek" &&
            assistant.optJSONObject(DeepSeekProtocolCodec.FIELD)?.optBoolean("replayable", false) == false
        if (archive || deepSeekArchive) {
            check(db.insertWithOnConflict("request_recovery_archive", null, ContentValues().apply {
                put("chat_id", chatId); put("request_id", requestId)
                put("recovery_message", assistant.toString()); put("created_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L) { "Recovery archive was not persisted" }
        }
        if (!archive) {
            val existing = db.query(MESSAGE_TABLE, arrayOf("id", "position"),
                "chat_id=? AND request_id=? AND role='assistant'", arrayOf(chatId, requestId),
                null, null, "position DESC", "1").use { c -> if (c.moveToFirst()) Pair(c.getLong(0), c.getInt(1)) else null }
            val position = existing?.second ?: db.rawQuery(
                "SELECT COALESCE(MAX(position), -1) + 1 FROM $MESSAGE_TABLE WHERE chat_id=?", arrayOf(chatId)
            ).use { c -> c.moveToFirst(); c.getInt(0) }
            val values = messageContentValues(chatId, position, assistant, ChatMessageFingerprint.of(assistant))
            if (existing == null) db.insertOrThrow(MESSAGE_TABLE, null, values)
            else check(db.update(MESSAGE_TABLE, values, "id=?", arrayOf(existing.first.toString())) == 1)
        }
        val telemetry = FileBackedIoTelemetry.mergeTelemetryJson(readAccountingTelemetryInDb(db, chatId, requestId),
            assistant.optJSONObject("telemetry_summary"))
        val usage = assistant.optJSONObject("usage")
        val router = assistant.optString("provider") == "openrouter"
        val deepSeek = assistant.optString("provider") == "deepseek"
        val complete = if (deepSeek) isDeepSeekUsageComplete(usage) else hasTokenPair(usage, router)
        db.update(REQUEST_ACCOUNTING_TABLE, ContentValues().apply {
            put("local_status", assistant.optString("status").ifBlank { "unknown" })
            put("updated_at", System.currentTimeMillis())
            JsonProtocol.nonBlank(assistant, "response_id")?.let { put("response_id", it) }
            JsonProtocol.nonBlank(assistant, "error")?.let { put("error", it.take(1200)) }
            telemetry?.let { put("telemetry_summary", it) }
            put("usage_complete", if (complete) 1 else 0)
            if (!deepSeek || complete) putUsageValues(this, usage, router || deepSeek)
            else for (key in TOKEN_COLUMNS) putNull(key)
            if (router) OpenRouterUsageSnapshot.from(usage).reportedCostUsd?.let {
                put("cost_kind", "provider_reported"); put("cost_usd", it.toString())
            }
        }, "chat_id=? AND local_request_id=? AND local_status='running'", arrayOf(chatId, requestId))
    }

    private fun putUsageValues(values: ContentValues, usage: JSONObject?, router: Boolean = false) {
        fun nonnegative(source: JSONObject?, key: String): Long? =
            source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
        nonnegative(usage, if (router) "prompt_tokens" else "input_tokens")?.let { values.put("input_tokens", it) }
        nonnegative(usage, if (router) "completion_tokens" else "output_tokens")?.let { values.put("output_tokens", it) }
        nonnegative(usage?.optJSONObject(if (router) "prompt_tokens_details" else "input_tokens_details"), "cached_tokens")?.let { values.put("cached_tokens", it) }
        if (router) nonnegative(usage?.optJSONObject("prompt_tokens_details"), "cache_write_tokens")?.let { values.put("cache_write_tokens", it) }
        nonnegative(usage?.optJSONObject(if (router) "completion_tokens_details" else "output_tokens_details"), "reasoning_tokens")?.let { values.put("reasoning_tokens", it) }
    }

    /** Explicit recovery archive, independent of visible bubbles; deleted with its owning chat. */
    fun getRequestRecoveryArchive(chatId: String, requestId: String): JSONObject? {
        val db = readableDatabase
        return SqliteLargeTextQuery.query(db, "request_recovery_archive", arrayOf("recovery_message"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
            "1", arrayOf("chat_id", "request_id")).use { c ->
                if (!c.moveToFirst()) null else JSONObject(c.getString(0)).apply {
                    if (optString("provider") == "deepseek") {
                        val confirmed = confirmedArtifactsInDb(db, chatId, requestId)
                        if (confirmed.length() > 0) put("confirmed_artifacts", confirmed)
                    }
                }
            }
    }

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
            recoverAssistantInDb(db, chatId, expectedRequestId, null, "stopped")?.let {
                // Stop intentionally removes its visible bubble. Keep the paid data in an explicit archive.
                persistTerminalAssistantInDb(db, chatId, expectedRequestId, it, archive = true)
            }
            db.update(
                REQUEST_ACCOUNTING_TABLE,
                ContentValues().apply {
                    put("local_status", "stopped")
                    put("updated_at", System.currentTimeMillis())
                    active?.second?.takeIf { it.isNotBlank() }?.let { put("response_id", it) }
                },
                "chat_id=? AND local_request_id=? AND local_status IN ('running','stopped')",
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
                sealOpenRouterRoundsInDb(db, chatId, expectedRequestId)
                sealDeepSeekRoundsInDb(db, chatId, expectedRequestId)
                db.delete("openai_tool_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
                db.delete("openrouter_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
                db.delete("deepseek_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
                if (removeUserMessage) db.delete(MESSAGE_TABLE,
                    "chat_id=? AND request_id=? AND role='user'", arrayOf(chatId, expectedRequestId))
                // One event for this exact stopped local request, within the CAS transaction.
                val now = System.currentTimeMillis()
                db.execSQL("""
                    INSERT INTO $REQUEST_EVENT_TABLE (
                        chat_id, created_at, event_type, request_id, provider,
                        requested_model, model_match, status, input_tokens,
                        cached_tokens, cache_write_tokens, output_tokens, reasoning_tokens,
                        cost_kind, cost_usd, latency_ms, telemetry_summary, usage_complete
                    )
                    SELECT chat_id, ?, 'request_stopped', local_request_id, provider,
                        requested_model, 'unknown', 'stopped',
                        COALESCE(input_tokens, 0), COALESCE(cached_tokens, 0), COALESCE(cache_write_tokens, 0),
                        COALESCE(output_tokens, 0), COALESCE(reasoning_tokens, 0),
                        CASE WHEN cost_kind='provider_reported' AND cost_usd IS NOT NULL
                            THEN 'provider_reported' ELSE 'unknown' END,
                        CASE WHEN cost_kind='provider_reported' THEN cost_usd ELSE NULL END,
                        MAX(0, ? - created_at), telemetry_summary, usage_complete
                    FROM $REQUEST_ACCOUNTING_TABLE
                    WHERE chat_id=? AND local_request_id=?
                """.trimIndent(), arrayOf(now, now, chatId, expectedRequestId))
            }
        }
        if (stopped) cleanupReleasedPreparedAttachmentFiles()
        return stopped
    }

    /** Checkpoint owners disappear only after the winning terminal transaction commits. */
    private fun cleanupReleasedPreparedAttachmentFiles() {
        try {
            val prefs = appContext.getSharedPreferences("app", Context.MODE_PRIVATE)
            PreparedAttachmentFiles.cleanupPending(appContext) {
                PreparedAttachmentFiles.Owners(
                    durable = listAllLocalFilePaths().toSet() + LocalAttachmentReferences.allComposerPaths(prefs.all),
                    active = OpenRouterResponseRuntime.localFilePaths() + listActiveOpenAiToolAttachmentPaths()
                )
            }
        } catch (_: Exception) {
            // Keep the ownership journal for the next healthy cleanup pass.
        }
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
            sealOpenRouterRoundsInDb(db, chatId, expectedRequestId)
            sealDeepSeekRoundsInDb(db, chatId, expectedRequestId)
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
            check(committed) { "Active owner changed inside terminal transaction" }
            db.delete("openai_tool_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
            db.delete("openrouter_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
            db.delete("deepseek_checkpoints", "chat_id=? AND request_id=?", arrayOf(chatId, expectedRequestId))
        }
        if (committed) cleanupReleasedPreparedAttachmentFiles()
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
        db.query(MESSAGE_TABLE, arrayOf("id", "position", "role", "request_id", "content_hash"),
            "chat_id=?",
            arrayOf(id),
            null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                val row = ExistingMessageRow(
                    id = c.getLong(0),
                    position = c.getInt(1),
                    role = c.getString(2).orEmpty(),
                    requestId = c.getString(3),
                    contentHash = c.getString(4)
                )
                existingRows += row
                existingByPosition.putIfAbsent(row.position, row)
                row.requestId?.takeIf { it.isNotBlank() }?.let {
                    existingByRequestId.putIfAbsent("${row.role}|$it", row)
                }
            }
        }

        val usedIds = HashSet<Long>()
        messages.forEachIndexed { index, message ->
            val role = message.optString("role")
            val requestId = message.optString("request_id").takeIf { it.isNotBlank() && it != "null" }
            val candidate = requestId?.let { existingByRequestId["$role|$it"] }?.takeIf { !usedIds.contains(it.id) }
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
        val role: String = "",
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
        estimatedCostUsd: Double? = null,
        costKind: String = if (estimatedCostUsd != null) "estimated" else "unknown"
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
                val validCost = estimatedCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
                put("estimated_cost_usd", validCost ?: 0.0)
                put("cost_kind", if (validCost == null) "unknown" else costKind.takeIf { it in setOf("estimated", "provider_reported") } ?: "unknown")
            }
            requestId?.let { enrichFromAccounting(v, readAccountingInDb(db, chatId, it), metric = true) }
            db.insert(METRICS_TABLE, null, v)
            requestId?.let { recomputeOpenRouterTotalsInDb(db, chatId, it); recomputeDeepSeekTotalsInDb(db, chatId, it) }
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
        error: String? = null,
        telemetrySummary: JSONObject? = null
    ): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var written = false
        writableDatabase.transaction { db ->
            val existing = db.query(REQUEST_ACCOUNTING_TABLE,
                arrayOf("local_status", "cost_kind", "provider", "provider_status", "response_id"),
                "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId),
                null, null, null, "1").use { c ->
                if (!c.moveToFirst()) null else listOf(c.getString(0), c.getString(1), c.getString(2), c.getString(3), c.getString(4))
            } ?: return@transaction
            val localStatus = existing[0]
            val knownCost = existing[1]
            val capturedProvider = existing[2]
            val existingProviderStatus = existing[3]
            val rawSavedTelemetry = readAccountingTelemetryInDb(db, chatId, localRequestId)
            val savedTelemetry = if (capturedProvider == "openai" && localStatus != "running") {
                restoredOpenAiAccountingTelemetryInDb(db, chatId, localRequestId, rawSavedTelemetry)
                    .takeIf { it.length() > 0 }?.toString()
            } else rawSavedTelemetry
            val merged = FileBackedIoTelemetry.mergeTelemetryJson(savedTelemetry, telemetrySummary)?.let(::JSONObject)
            if (capturedProvider == "openai" && localStatus != "running") {
                val savedLedger = savedTelemetry?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?.optJSONObject(OpenAiUsageLedger.FIELD)
                val incomingLedger = telemetrySummary?.optJSONObject(OpenAiUsageLedger.FIELD)
                if (savedLedger != null && incomingLedger != null) {
                    // A delayed aggregate callback may predate the final GET reconciliation. Existing
                    // terminal entries win; an explicitly owned raw response can still correct them below.
                    merged?.put(OpenAiUsageLedger.FIELD, OpenAiUsageLedger.merge(incomingLedger, savedLedger))
                }
            }
            var effectiveUsage = usage
            if (capturedProvider == "openai") {
                var ledger = merged?.optJSONObject(OpenAiUsageLedger.FIELD)
                if (ledger != null && OpenAiUsageLedger.total(ledger) != null) {
                    // A supplied ledger marks usage as an aggregate. Otherwise an explicit response ID
                    // identifies a raw provider snapshot. Never attribute an unmarked total to one round.
                    if (telemetrySummary?.optJSONObject(OpenAiUsageLedger.FIELD) == null &&
                        !responseId.isNullOrBlank() && usage != null) {
                        val covered = ledger.optJSONArray("legacy_covered_ids") ?: JSONArray()
                        val knownId = ledger.optJSONObject("responses")?.has(responseId) == true ||
                            (0 until covered.length()).any { covered.optString(it) == responseId }
                        val ownsActiveResponse = localStatus == "running" && db.query(CHAT_TABLE,
                            arrayOf("id"), "id=? AND active_request_id=? AND active_response_id=?",
                            arrayOf(chatId, localRequestId, responseId), null, null, null, "1").use { it.moveToFirst() }
                        if (knownId || responseId == existing[4] || ownsActiveResponse) {
                            ledger = OpenAiUsageLedger.observe(ledger, responseId, usage, status)
                            merged?.put(OpenAiUsageLedger.FIELD, ledger)
                        }
                    }
                    effectiveUsage = OpenAiUsageLedger.total(ledger)
                }
            }
            val mergedTelemetry = merged?.toString()
            val values = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                mergedTelemetry?.let { put("telemetry_summary", it) }
                if (localStatus == "running") put("local_status", status.ifBlank { "unknown" })
                if (existingProviderStatus.isNullOrBlank() || existingProviderStatus == "in_progress") {
                    put("provider_status", status.ifBlank { "unknown" })
                }
                responseId?.takeIf { it.isNotBlank() && (localStatus == "running" || existing[4].isNullOrBlank()) }
                    ?.let { put("response_id", it) }
                httpRequestId?.takeIf { it.isNotBlank() }?.let { put("http_request_id", it) }
                error?.takeIf { it.isNotBlank() }?.let { put("error", it.take(1200)) }
                // No optLong defaults: omitted counters remain SQL NULL.
                val deepSeek = capturedProvider == "deepseek"
                val openRouter = capturedProvider in setOf("openrouter", "deepseek")
                val complete = if (deepSeek) isDeepSeekUsageComplete(effectiveUsage) else hasTokenPair(effectiveUsage, openRouter)
                if (effectiveUsage != null || deepSeek) put("usage_complete", if (complete) 1 else 0)
                if (deepSeek && !complete) {
                    for (key in TOKEN_COLUMNS) putNull(key)
                    effectiveUsage = null
                }
                val inputKey = if (openRouter) "prompt_tokens" else "input_tokens"
                val outputKey = if (openRouter) "completion_tokens" else "output_tokens"
                fun knownNonnegative(source: JSONObject?, key: String): Long? =
                    source?.opt(key)?.toString()?.toLongOrNull()?.takeIf { it >= 0L }
                knownNonnegative(effectiveUsage, inputKey)?.let { put("input_tokens", it) }
                knownNonnegative(effectiveUsage, outputKey)?.let { put("output_tokens", it) }
                val cached = if (openRouter)
                    effectiveUsage?.optJSONObject("prompt_tokens_details")?.let {
                        knownNonnegative(it, "cached_tokens")
                    }
                else effectiveUsage?.optJSONObject("input_tokens_details")?.let {
                    knownNonnegative(it, "cached_tokens")
                }
                cached?.let { put("cached_tokens", it) }
                if (openRouter) knownNonnegative(effectiveUsage?.optJSONObject("prompt_tokens_details"), "cache_write_tokens")
                    ?.let { put("cache_write_tokens", it) }
                val reasoning = if (openRouter)
                    effectiveUsage?.optJSONObject("completion_tokens_details")?.let {
                        knownNonnegative(it, "reasoning_tokens")
                    }
                else effectiveUsage?.optJSONObject("output_tokens_details")?.let {
                    knownNonnegative(it, "reasoning_tokens")
                }
                reasoning?.let { put("reasoning_tokens", it) }
                val cost = if (deepSeek) null else reportedCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
                if (cost != null && knownCost != "provider_reported") {
                    put("cost_kind", "provider_reported")
                    put("cost_usd", cost.toString())
                }
            }
            written = db.update(REQUEST_ACCOUNTING_TABLE, values,
                "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId)) == 1
            if (written && capturedProvider == "openrouter") recomputeOpenRouterTotalsInDb(db, chatId, localRequestId)
            if (written && capturedProvider == "deepseek") recomputeDeepSeekTotalsInDb(db, chatId, localRequestId)
        }
        return written
    }

    /** Merge diagnostics without touching lifecycle, provider status, or ownership. */
    fun recordRequestTelemetry(chatId: String, localRequestId: String, telemetrySummary: JSONObject): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var updated = false
        writableDatabase.transaction { db ->
            val merged = FileBackedIoTelemetry.mergeTelemetryJson(
                readAccountingTelemetryInDb(db, chatId, localRequestId), telemetrySummary) ?: return@transaction
            updated = db.update(REQUEST_ACCOUNTING_TABLE, ContentValues().apply {
                put("telemetry_summary", merged); put("updated_at", System.currentTimeMillis())
            }, "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId)) == 1
        }
        return updated
    }

    /**
     * Bind an ID delivered by the original Create after local Stop. Only its
     * existing, stopped OpenAI accounting row with no captured ID can change;
     * this never recreates a row, replaces an ID, or touches the active owner.
     */
    fun captureStoppedOpenAiResponseId(chatId: String, localRequestId: String, responseId: String): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank() ||
            !Regex("resp_[A-Za-z0-9_-]+").matches(responseId)) return false
        return writableDatabase.update(
            REQUEST_ACCOUNTING_TABLE,
            ContentValues().apply {
                put("response_id", responseId)
                put("updated_at", System.currentTimeMillis())
            },
            "chat_id=? AND local_request_id=? AND provider='openai' AND local_status='stopped' " +
                "AND (response_id IS NULL OR response_id='')",
            arrayOf(chatId, localRequestId)
        ) == 1
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
        if (chatId.isBlank() || localRequestId.isBlank() || responseId.isBlank() || response.optString("id") != responseId) return false
        val status = response.optString("status")
        if (status !in setOf("completed", "cancelled", "failed", "incomplete", "in_progress", "queued")) return false
        val terminal = status in setOf("completed", "cancelled", "failed", "incomplete")
        val usage = response.optJSONObject("usage")
        if (!terminal && usage == null) return false
        var updated = false
        writableDatabase.transaction { db ->
            val captured = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("response_id"),
                "chat_id=? AND local_request_id=? AND response_id=? AND provider='openai' AND local_status='stopped'",
                arrayOf(chatId, localRequestId, responseId), null, null, null, "1").use { it.moveToFirst() }
            if (!captured) return@transaction
            val raw = readAccountingTelemetryInDb(db, chatId, localRequestId)
            val telemetry = restoredOpenAiAccountingTelemetryInDb(db, chatId, localRequestId, raw)
            val ledger = OpenAiUsageLedger.observe(telemetry.optJSONObject(OpenAiUsageLedger.FIELD), responseId, usage, status)
            val total = OpenAiUsageLedger.total(ledger)
            if (ledger.length() > 0) telemetry.put(OpenAiUsageLedger.FIELD, ledger)
            val values = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                if (terminal) put("provider_status", status)
                if (telemetry.length() > 0) put("telemetry_summary", telemetry.toString())
                putUsageValues(this, total)
            }
            updated = db.update(REQUEST_ACCOUNTING_TABLE, values,
                "chat_id=? AND local_request_id=? AND response_id=? AND provider='openai' AND local_status='stopped'",
                arrayOf(chatId, localRequestId, responseId)) == 1
            if (updated) {
                val eventValues = ContentValues().apply {
                    if (telemetry.length() > 0) put("telemetry_summary", telemetry.toString())
                    putUsageValues(this, total)
                }
                if (eventValues.size() > 0) db.update(REQUEST_EVENT_TABLE, eventValues,
                    "chat_id=? AND request_id=? AND event_type='request_stopped'", arrayOf(chatId, localRequestId))
            }
        }
        return updated
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
            val previousUsage = SqliteLargeTextQuery.query(db, OPENROUTER_ROUND_TABLE, arrayOf("usage_snapshot"),
                "chat_id=? AND local_request_id=? AND round_index=?", arrayOf(chatId, localRequestId, roundIndex.toString()),
                null, null, null, "1", arrayOf("chat_id", "local_request_id", "round_index")).use { c ->
                if (c.moveToFirst() && !c.isNull(0)) JSONObject(c.getString(0)) else null
            }
            val snapshot = OpenRouterUsageAccumulator.mergeSnapshot(previousUsage, usage)
            val data = ContentValues().apply {
                put("updated_at", System.currentTimeMillis())
                snapshot?.let { put("usage_snapshot", it.toString()) }
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
        writableDatabase.transaction { db -> sealed = sealOpenRouterRoundsInDb(db, chatId, localRequestId) }
        return sealed
    }

    /** Closing the owner and sealing attempted HTTP rounds must survive the same process crash. */
    private fun sealOpenRouterRoundsInDb(db: SQLiteDatabase, chatId: String, localRequestId: String): Boolean {
        val sealed = db.update(REQUEST_ACCOUNTING_TABLE,
            ContentValues().apply { put("rounds_sealed", 1) },
            "chat_id=? AND local_request_id=? AND provider='openrouter' AND rounds_sealed=0",
            arrayOf(chatId, localRequestId)) == 1
        if (sealed) recomputeOpenRouterTotalsInDb(db, chatId, localRequestId)
        return sealed
    }

    /** Each stored row is one raw provider usage snapshot, never an accumulated multi-round total. */
    private fun aggregateOpenRouterUsageInDb(db: SQLiteDatabase, chatId: String, localRequestId: String): JSONObject? {
        val snapshots = mutableListOf<JSONObject?>()
        SqliteLargeTextQuery.query(db, OPENROUTER_ROUND_TABLE,
            arrayOf("usage_snapshot", "input_tokens", "output_tokens", "cost_kind", "cost_usd"),
            "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId), null, null, "round_index ASC",
            keyColumns = arrayOf("chat_id", "local_request_id", "round_index")).use { c ->
            while (c.moveToNext()) {
                val snapshot = if (!c.isNull(0)) JSONObject(c.getString(0)) else null
                // Existing numeric columns also cover pre-K rows and later generation reconciliation.
                val authoritative = JSONObject().apply {
                    if (!c.isNull(1)) put("prompt_tokens", c.getLong(1))
                    if (!c.isNull(2)) put("completion_tokens", c.getLong(2))
                    if (c.getString(3) == "provider_reported" && !c.isNull(4)) {
                        c.getString(4)?.toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }?.let { put("cost", it) }
                    }
                }
                snapshots += OpenRouterUsageAccumulator.mergeSnapshot(snapshot, authoritative)
            }
        }
        return OpenRouterUsageAccumulator.aggregate(snapshots)
    }

    private fun recomputeOpenRouterTotalsInDb(db: SQLiteDatabase, chatId: String, localRequestId: String) {
        val sealed = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("rounds_sealed"),
            "chat_id=? AND local_request_id=? AND provider='openrouter'", arrayOf(chatId, localRequestId),
            null, null, null, "1").use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!sealed) return
        // No round ledger means there is no authoritative aggregate. Preserve direct terminal
        // accounting (including confirmed zero) for legacy/preflight and single-shot callers.
        val hasRound = db.query(OPENROUTER_ROUND_TABLE, arrayOf("round_index"),
            "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId),
            null, null, null, "1").use { it.moveToFirst() }
        if (!hasRound) return
        val usage = aggregateOpenRouterUsageInDb(db, chatId, localRequestId)
        // A partial sum may contain both counters from different rounds. Only a
        // confirmed pair for every round proves the request's full token total.
        val usageComplete = db.rawQuery("""SELECT COUNT(*), COUNT(input_tokens), COUNT(output_tokens)
            FROM $OPENROUTER_ROUND_TABLE WHERE chat_id=? AND local_request_id=?""",
            arrayOf(chatId, localRequestId)).use { c ->
            c.moveToFirst()
            c.getLong(0) > 0L && c.getLong(0) == c.getLong(1) && c.getLong(0) == c.getLong(2)
        }
        val cost = OpenRouterUsageSnapshot.from(usage).reportedCostUsd
        val partialUsage = if (!usageComplete && usage != null) JSONObject()
            .put("usage_incomplete", true).put("observed_partial_usage", JsonProtocol.objectCopy(usage)) else null
        val summary = FileBackedIoTelemetry.mergeCompactTelemetry(toolTelemetry = partialUsage,
            costDetails = OpenRouterUsageSnapshot.extractCostDetails(usage))
        val telemetry = FileBackedIoTelemetry.mergeTelemetryJson(readAccountingTelemetryInDb(db, chatId, localRequestId), summary)
            ?.let { raw -> JSONObject(raw).apply {
                // The raw observation remains diagnostic history after reconciliation,
                // but the current completeness marker must reflect the latest ledger.
                if (usageComplete) remove("usage_incomplete")
            }.toString() }
        val usageValues = ContentValues().apply {
            putUsageValues(this, usage, router = true)
            put("usage_complete", if (usageComplete) 1 else 0)
        }
        db.update(REQUEST_ACCOUNTING_TABLE, ContentValues(usageValues).apply {
            put("updated_at", System.currentTimeMillis())
            put("cost_kind", if (cost != null) "provider_reported" else "unknown")
            if (cost == null) putNull("cost_usd") else put("cost_usd", cost.toString())
            telemetry?.let { put("telemetry_summary", it) }
        }, "chat_id=? AND local_request_id=? AND provider='openrouter'", arrayOf(chatId, localRequestId))
        db.update(REQUEST_EVENT_TABLE, ContentValues(usageValues).apply {
            put("cost_kind", if (cost != null) "provider_reported" else "unknown")
            if (cost == null) putNull("cost_usd") else put("cost_usd", cost.toString())
            telemetry?.let { put("telemetry_summary", it) }
        }, "chat_id=? AND request_id=?", arrayOf(chatId, localRequestId))
        db.update(METRICS_TABLE, ContentValues(usageValues).apply {
            put("cost_kind", if (cost != null) "provider_reported" else "unknown")
            put("estimated_cost_usd", cost ?: 0.0)
        }, "chat_id=? AND request_id=?", arrayOf(chatId, localRequestId))
    }

    private fun hasTokenPair(usage: JSONObject?, chatCompletion: Boolean = true): Boolean {
        fun known(key: String) = usage?.opt(key)?.toString()?.toLongOrNull()?.let { it >= 0L } == true
        return known(if (chatCompletion) "prompt_tokens" else "input_tokens") &&
            known(if (chatCompletion) "completion_tokens" else "output_tokens")
    }

    private fun isDeepSeekUsageComplete(usage: JSONObject?): Boolean =
        usage?.optBoolean("usage_complete", false) == true && hasTokenPair(usage)

    private fun deepSeekUsageTelemetry(usage: JSONObject?, complete: Boolean): JSONObject = JSONObject().apply {
        if (!complete) put("usage_incomplete", true)
        for (key in listOf("rounds_with_usage", "rounds_total")) {
            if (usage?.has(key) == true) put(key, usage.optInt(key))
        }
        usage?.optJSONObject("observed_partial_usage")?.let {
            put("observed_partial_usage", JsonProtocol.objectCopy(it))
        }
    }

    /** One durable row per attempted DeepSeek POST; observations replace snapshots, never add them. */
    fun observeDeepSeekRound(chatId: String, localRequestId: String, roundIndex: Int, usage: JSONObject? = null): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank() || roundIndex < 0) return false
        var changed = false
        writableDatabase.transaction { db ->
            val owner = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("local_status", "rounds_sealed"),
                "chat_id=? AND local_request_id=? AND provider='deepseek'", arrayOf(chatId, localRequestId),
                null, null, null, "1").use { c -> if (c.moveToFirst()) Pair(c.getString(0), c.getInt(1)) else null }
                ?: return@transaction
            val previous = SqliteLargeTextQuery.query(db, DEEPSEEK_ROUND_TABLE, arrayOf("usage_snapshot"),
                "chat_id=? AND local_request_id=? AND round_index=?", arrayOf(chatId, localRequestId, roundIndex.toString()),
                null, null, null, "1", arrayOf("chat_id", "local_request_id", "round_index")).use { c ->
                if (!c.moveToFirst()) null else Pair(true, if (c.isNull(0)) null else JSONObject(c.getString(0)))
            }
            if (previous == null && (owner.first != "running" || owner.second != 0)) return@transaction
            val snapshot = OpenRouterUsageAccumulator.mergeSnapshot(previous?.second, usage)?.apply { remove("cost") }
            changed = db.insertWithOnConflict(DEEPSEEK_ROUND_TABLE, null, ContentValues().apply {
                put("chat_id", chatId); put("local_request_id", localRequestId); put("round_index", roundIndex)
                if (snapshot == null) putNull("usage_snapshot") else put("usage_snapshot", snapshot.toString())
                put("usage_complete", if (hasTokenPair(snapshot)) 1 else 0)
                put("updated_at", System.currentTimeMillis())
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L
            if (changed) recomputeDeepSeekTotalsInDb(db, chatId, localRequestId)
        }
        return changed
    }

    fun sealDeepSeekRounds(chatId: String, localRequestId: String): Boolean {
        if (chatId.isBlank() || localRequestId.isBlank()) return false
        var changed = false
        writableDatabase.transaction { db -> changed = sealDeepSeekRoundsInDb(db, chatId, localRequestId) }
        return changed
    }

    private fun sealDeepSeekRoundsInDb(db: SQLiteDatabase, chatId: String, localRequestId: String): Boolean {
        val changed = db.update(REQUEST_ACCOUNTING_TABLE, ContentValues().apply { put("rounds_sealed", 1) },
            "chat_id=? AND local_request_id=? AND provider='deepseek' AND rounds_sealed=0", arrayOf(chatId, localRequestId)) == 1
        if (changed) recomputeDeepSeekTotalsInDb(db, chatId, localRequestId)
        return changed
    }

    /** A partial round remains diagnostic; a known subtotal is never presented as the full request. */
    private fun aggregateDeepSeekUsageInDb(db: SQLiteDatabase, chatId: String, localRequestId: String): JSONObject? {
        val snapshots = mutableListOf<JSONObject?>()
        SqliteLargeTextQuery.query(db, DEEPSEEK_ROUND_TABLE, arrayOf("usage_snapshot"),
            "chat_id=? AND local_request_id=?", arrayOf(chatId, localRequestId), null, null, "round_index ASC",
            keyColumns = arrayOf("chat_id", "local_request_id", "round_index")).use { c ->
            while (c.moveToNext()) snapshots += if (c.isNull(0)) null else JSONObject(c.getString(0))
        }
        if (snapshots.isEmpty()) return null
        val known = snapshots.count { hasTokenPair(it) }
        val complete = known == snapshots.size
        val observed = OpenRouterUsageAccumulator.aggregate(snapshots.filterNotNull())?.apply { remove("cost") }
        return (if (complete) observed ?: JSONObject() else JSONObject())
            .put("usage_complete", complete).put("rounds_with_usage", known).put("rounds_total", snapshots.size)
            .apply {
                if (!complete && observed != null && observed.length() > 0) put("observed_partial_usage", observed)
                if (!complete) put("observed_rounds", JSONArray().apply {
                    snapshots.forEachIndexed { index, snapshot ->
                        put(JSONObject().put("round_index", index).put("usage_complete", hasTokenPair(snapshot))
                            .apply { if (snapshot != null) put("usage", JsonProtocol.objectCopy(snapshot)) })
                    }
                })
            }
    }

    private fun recomputeDeepSeekTotalsInDb(db: SQLiteDatabase, chatId: String, localRequestId: String) {
        val sealed = db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("rounds_sealed"),
            "chat_id=? AND local_request_id=? AND provider='deepseek'", arrayOf(chatId, localRequestId),
            null, null, null, "1").use { it.moveToFirst() && it.getInt(0) == 1 }
        if (!sealed) return
        val usage = aggregateDeepSeekUsageInDb(db, chatId, localRequestId) ?: return
        val complete = isDeepSeekUsageComplete(usage)
        val telemetry = FileBackedIoTelemetry.mergeTelemetryJson(readAccountingTelemetryInDb(db, chatId, localRequestId),
            deepSeekUsageTelemetry(usage, complete))
        val counters = ContentValues().apply {
            put("usage_complete", if (complete) 1 else 0)
            if (complete) putUsageValues(this, usage, router = true)
        }
        db.update(REQUEST_ACCOUNTING_TABLE, ContentValues(counters).apply {
            if (!complete) for (key in TOKEN_COLUMNS) putNull(key)
            put("updated_at", System.currentTimeMillis()); put("cost_kind", "unknown"); putNull("cost_usd")
            telemetry?.let { put("telemetry_summary", it) }
        }, "chat_id=? AND local_request_id=? AND provider='deepseek'", arrayOf(chatId, localRequestId))
        db.update(REQUEST_EVENT_TABLE, ContentValues(counters).apply {
            // The old physical schema is NOT NULL; the flag makes these storage sentinels unavailable to sums/UI.
            if (!complete) for (key in TOKEN_COLUMNS) put(key, 0L)
            put("cost_kind", "unknown"); putNull("cost_usd")
            telemetry?.let { put("telemetry_summary", it) }
        }, "chat_id=? AND request_id=? AND provider='deepseek'", arrayOf(chatId, localRequestId))
        db.update(METRICS_TABLE, ContentValues(counters).apply {
            if (!complete) for (key in TOKEN_COLUMNS) put(key, 0L)
            put("cost_kind", "unknown"); put("estimated_cost_usd", 0.0)
        }, "chat_id=? AND request_id=?", arrayOf(chatId, localRequestId))
    }

    /** Only the current DeepSeek request may persist a working protocol cursor. */
    fun saveDeepSeekCheckpoint(chatId: String, requestId: String, protocol: JSONObject): Boolean {
        if (chatId.isBlank() || requestId.isBlank()) return false
        var saved = false
        writableDatabase.transaction { db ->
            val owns = db.rawQuery("""SELECT 1 FROM $CHAT_TABLE c JOIN $REQUEST_ACCOUNTING_TABLE a
                ON a.chat_id=c.id AND a.local_request_id=c.active_request_id
                WHERE c.id=? AND c.active_request_id=? AND a.provider='deepseek' AND a.local_status='running'""",
                arrayOf(chatId, requestId)).use { it.moveToFirst() }
            if (!owns) return@transaction
            saved = db.insertWithOnConflict("deepseek_checkpoints", null, ContentValues().apply {
                put("chat_id", chatId); put("request_id", requestId); put("protocol", protocol.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE) != -1L
        }
        return saved
    }

    fun getDeepSeekCheckpoint(chatId: String, requestId: String): JSONObject? =
        SqliteLargeTextQuery.query(readableDatabase, "deepseek_checkpoints", arrayOf("protocol"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
            keyColumns = arrayOf("chat_id", "request_id")).use { if (it.moveToFirst()) JSONObject(it.getString(0)) else null }

    private fun confirmedArtifactsInDb(db: SQLiteDatabase, chatId: String, requestId: String): JSONArray {
        val result = JSONArray()
        SqliteLargeTextQuery.query(db, "local_artifacts", arrayOf("metadata"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, "created_at ASC, path ASC",
            keyColumns = arrayOf("path")).use { c -> while (c.moveToNext()) result.put(JSONObject(c.getString(0))) }
        return result
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

    fun listRequestAccounting(chatId: String, localRequestIds: Set<String>? = null): List<JSONObject> {
        if (chatId.isBlank() || localRequestIds?.isEmpty() == true) return emptyList()
        val rows = mutableListOf<JSONObject>()
        val requestIds = localRequestIds?.toList()
        val selection = "chat_id=?" + if (requestIds == null) "" else
            " AND local_request_id IN (${requestIds.joinToString(",") { "?" }})"
        val args = arrayOf(chatId) + requestIds.orEmpty()
        SqliteLargeTextQuery.query(readableDatabase, REQUEST_ACCOUNTING_TABLE, null, selection, args,
            null, null, "created_at ASC, local_request_id ASC", keyColumns = arrayOf("chat_id", "local_request_id")).use { c ->
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
        estimatedCostUsd: Double? = null,
        reportedCostUsd: Double? = null,
        latencyMs: Long = 0L,
        toolSummary: String? = null,
        error: String? = null,
        telemetrySummary: JSONObject? = null,
        usageComplete: Boolean = true
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
                put("usage_complete", if (usageComplete) 1 else 0)
                put("input_tokens", inputTokens.coerceAtLeast(0L))
                put("cached_tokens", cachedTokens.coerceAtLeast(0L))
                put("cache_write_tokens", cacheWriteTokens.coerceAtLeast(0L))
                put("output_tokens", outputTokens.coerceAtLeast(0L))
                put("reasoning_tokens", reasoningTokens.coerceAtLeast(0L))
                // The legacy numeric estimate is not proof of a provider charge.
                put("estimated_cost_usd", estimatedCostUsd?.takeIf { it.isFinite() && it >= 0.0 } ?: 0.0)
                val verifiedCost = reportedCostUsd?.takeIf { it.isFinite() && it >= 0.0 }
                put("cost_kind", if (verifiedCost == null) "unknown" else "provider_reported")
                if (verifiedCost == null) putNull("cost_usd") else put("cost_usd", verifiedCost.toString())
                put("latency_ms", latencyMs.coerceAtLeast(0L))
                putStringOrNull("tool_summary", toolSummary)
                putStringOrNull("error", error?.take(1200))
                telemetrySummary?.let { put("telemetry_summary", it.toString()) }
            }
            requestId?.let { id ->
                val accounting = readAccountingInDb(db, chatId, id)
                enrichFromAccounting(values, accounting)
                FileBackedIoTelemetry.mergeTelemetryJson(accounting?.optString("telemetry_summary"), telemetrySummary)
                    ?.let { values.put("telemetry_summary", it) }
            }
            db.insert(REQUEST_EVENT_TABLE, null, values)
            requestId?.let { recomputeOpenRouterTotalsInDb(db, chatId, it); recomputeDeepSeekTotalsInDb(db, chatId, it) }
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
                val complete = c.getInt(c.getColumnIndexOrThrow("usage_complete")) == 1
                event.put("usage_complete", complete)
                if (!complete) for (key in TOKEN_COLUMNS) event.put(key, JSONObject.NULL)
                c.getString(c.getColumnIndexOrThrow("cost_usd"))?.let { event.put("cost_usd", it) }
                c.getString(c.getColumnIndexOrThrow("request_id"))?.let { event.put("request_id", it) }
                c.getString(c.getColumnIndexOrThrow("requested_model"))?.let { event.put("requested_model", it) }
                c.getString(c.getColumnIndexOrThrow("response_model"))?.let { event.put("response_model", it) }
                c.getString(c.getColumnIndexOrThrow("tool_summary"))?.let { event.put("tool_summary", it) }
                c.getString(c.getColumnIndexOrThrow("error"))?.let { event.put("error", it) }
                c.getString(c.getColumnIndexOrThrow("telemetry_summary"))?.let { raw ->
                    event.put("telemetry_summary", runCatching { JSONObject(raw) }.getOrElse { raw })
                }
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
            db.query(MESSAGE_TABLE, arrayOf("id"), "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, "CASE WHEN role='assistant' THEN 0 ELSE 1 END ASC, position DESC", "1").use { c ->
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
        target: HistoricalAttachmentDownloadTarget,
        localPath: String,
        mimeType: String? = null,
        sizeBytes: Long? = null
    ): Boolean {
        val index = target.index ?: return false
        if (localPath.isBlank()) return false
        var changed = false
        writableDatabase.transaction { db ->
            val current = historicalDownloadMessage(db, target) ?: return@transaction
            if (!target.matches(current)) return@transaction
            val ids = current.optJSONArray("file_ids") ?: JSONArray()
            val sizes = current.optJSONArray("file_sizes") ?: JSONArray()
            val paths = current.optJSONArray("file_paths") ?: JSONArray()
            val mimes = current.optJSONArray("file_mimes") ?: JSONArray()
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
                putNull("content_hash")
            }
            changed = db.update(MESSAGE_TABLE, values, "id=?", arrayOf(current.getLong("_db_message_id").toString())) == 1
        }
        return changed
    }

    fun updateUserImageLocalPath(
        target: HistoricalAttachmentDownloadTarget,
        localPath: String,
        mimeType: String? = null,
        sizeBytes: Long? = null
    ): Boolean {
        if (!target.image || localPath.isBlank()) return false
        var changed = false
        writableDatabase.transaction { db ->
            val current = historicalDownloadMessage(db, target) ?: return@transaction
            if (!target.matches(current)) return@transaction
            val values = ContentValues().apply {
                put("image_path", localPath)
                if (!mimeType.isNullOrBlank()) put("image_mime", mimeType)
                if (sizeBytes != null && sizeBytes >= 0L) put("image_size", sizeBytes)
                putNull("content_hash")
            }
            changed = db.update(MESSAGE_TABLE, values, "id=?", arrayOf(current.getLong("_db_message_id").toString())) == 1
        }
        return changed
    }


    /** Recheck on the UI thread: a repair can commit after HTTP/SQLite, before the posted callback. */
    fun isHistoricalAttachmentDownloadCurrent(target: HistoricalAttachmentDownloadTarget, downloadedPath: String? = null): Boolean =
        historicalDownloadMessage(readableDatabase, target)?.let { target.matches(it, downloadedPath) } == true

    private fun historicalDownloadMessage(db: SQLiteDatabase, target: HistoricalAttachmentDownloadTarget): JSONObject? {
        val identity = if (target.messageId > 0L) "id=?" else "request_id=?"
        val args = arrayOf(target.chatId, if (target.messageId > 0L) target.messageId.toString() else target.requestId!!)
        return SqliteLargeTextQuery.query(db, MESSAGE_TABLE,
            (listOf("id", "request_id") + AttachmentMessageState.columns.filter { it != "attachments" }).toTypedArray(),
            "chat_id=? AND role='user' AND $identity", args, null, null, "position DESC", "1").use { c ->
            if (!c.moveToFirst()) return@use null
            JSONObject().apply {
                put("_db_message_id", c.getLong(c.getColumnIndexOrThrow("id")))
                c.getString(c.getColumnIndexOrThrow("request_id"))?.let { put("request_id", it) }
                AttachmentMessageState.arrays.filter { it != "attachments" }.forEach { key ->
                    parseArrayColumn(c.getString(c.getColumnIndexOrThrow(key)))?.let { put(key, it) }
                }
                AttachmentMessageState.strings.forEach { key -> c.getString(c.getColumnIndexOrThrow(key))?.let { put(key, it) } }
                put("image_size", c.getLong(c.getColumnIndexOrThrow("image_size")))
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

    /**
     * OpenRouter's sole runtime writer assigns a local monotonic snapshot cursor.
     * It has no OpenAI server sequence, so it must not use that stream's stricter
     * unsequenced-write policy. Ownership, running accounting and the cursor are
     * checked in the same UPDATE as the text; Stop/replacement cannot revive it.
     */
    fun checkpointOpenRouterResponse(
        chatId: String,
        expectedRequestId: String,
        text: String,
        localSequence: Long
    ): Boolean {
        if (chatId.isBlank() || expectedRequestId.isBlank() || localSequence < 0L) return false
        val values = ContentValues().apply {
            put("active_text", text)
            put("active_status", "in_progress")
            put("active_last_sequence_number", localSequence)
            put("updated_at", System.currentTimeMillis())
        }
        return writableDatabase.update(CHAT_TABLE, values,
            """id=? AND active_request_id=? AND active_status IN ('starting','in_progress')
                AND active_last_sequence_number < ? AND active_response_id IS NULL
                AND active_indexed_checkpoint IS NULL
                AND EXISTS (SELECT 1 FROM $REQUEST_ACCOUNTING_TABLE a
                    WHERE a.chat_id=$CHAT_TABLE.id AND a.local_request_id=$CHAT_TABLE.active_request_id
                    AND a.provider IN ('openrouter','deepseek') AND a.local_status='running')""".trimIndent(),
            arrayOf(chatId, expectedRequestId, localSequence.toString())) == 1
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

    private fun activeProviderInDb(db: SQLiteDatabase, chatId: String, requestId: String?): String? {
        if (requestId.isNullOrBlank()) return null
        return db.query(REQUEST_ACCOUNTING_TABLE, arrayOf("provider"), "chat_id=? AND local_request_id=?",
            arrayOf(chatId, requestId), null, null, null, "1").use { if (it.moveToFirst()) it.getString(0) else null }
    }

    fun getActiveResponse(chatId: String): JSONObject? {
        if (chatId.isBlank()) return null
        val db = readableDatabase
        SqliteLargeTextQuery.query(db, CHAT_TABLE,
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
                .apply {
                    c.getString(12)?.let { put("indexed_checkpoint", it) }
                    activeProviderInDb(db, chatId, c.getString(1))?.let { put("provider", it) }
                }
        }
    }

    fun listActiveResponses(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val db = readableDatabase
        SqliteLargeTextQuery.query(db, CHAT_TABLE,
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
                    .apply {
                        c.getString(14)?.let { put("indexed_checkpoint", it) }
                        activeProviderInDb(db, c.getString(0), c.getString(3))?.let { put("provider", it) }
                    }
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
        readableDatabase.rawQuery("""
            SELECT COUNT(*), COALESCE(SUM(success),0), COALESCE(SUM(CASE WHEN usage_complete=1 THEN input_tokens ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN usage_complete=1 THEN cached_tokens ELSE 0 END),0), COALESCE(SUM(CASE WHEN usage_complete=1 THEN cache_write_tokens ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN usage_complete=1 THEN output_tokens ELSE 0 END),0), COALESCE(SUM(CASE WHEN usage_complete=1 THEN reasoning_tokens ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN cost_kind='provider_reported' THEN estimated_cost_usd ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN cost_kind='provider_reported' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN cost_kind='estimated' THEN estimated_cost_usd ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN cost_kind='estimated' THEN 1 ELSE 0 END),0),
                COALESCE(SUM(CASE WHEN usage_complete=0 THEN 1 ELSE 0 END),0)
            FROM $METRICS_TABLE WHERE created_at>=?
        """.trimIndent(), arrayOf(since.coerceAtLeast(0L).toString())).use { c ->
            if (!c.moveToFirst()) return JSONObject()
            val total = c.getLong(0); val known = c.getLong(8); val estimates = c.getLong(10)
            return JSONObject().put("requests",total).put("success",c.getLong(1))
                .put("input_tokens",c.getLong(2)).put("cached_tokens",c.getLong(3))
                .put("cache_write_tokens",c.getLong(4)).put("output_tokens",c.getLong(5)).put("reasoning_tokens",c.getLong(6))
                .put("provider_reported_cost_usd",c.getDouble(7)).put("known_cost_requests",known)
                .put("estimated_cost_usd",c.getDouble(9)).put("estimated_cost_requests",estimates)
                .put("unknown_cost_requests",total-known-estimates).put("unknown_usage_requests",c.getLong(11))
        }
    }

    private fun ensureLocalArtifactsTable(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS answer_notifications (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, completed INTEGER NOT NULL,
            created_at INTEGER NOT NULL, PRIMARY KEY(chat_id, request_id),
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS openai_tool_checkpoints (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, request_checkpoint TEXT NOT NULL,
            PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS openrouter_checkpoints (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, protocol TEXT NOT NULL,
            PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS deepseek_checkpoints (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, protocol TEXT NOT NULL,
            PRIMARY KEY(chat_id, request_id), FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS request_recovery_archive (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, recovery_message TEXT NOT NULL,
            created_at INTEGER NOT NULL, PRIMARY KEY(chat_id, request_id),
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_artifact_intents (
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, call_id TEXT NOT NULL,
            metadata TEXT NOT NULL, created_at INTEGER NOT NULL,
            recovery_state TEXT NOT NULL DEFAULT 'pending', recovery_error TEXT,
            PRIMARY KEY(chat_id, request_id, call_id),
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_artifacts (
            path TEXT PRIMARY KEY NOT NULL, chat_id TEXT NOT NULL, request_id TEXT NOT NULL,
            created_at INTEGER NOT NULL, metadata TEXT NOT NULL,
            FOREIGN KEY(chat_id) REFERENCES $CHAT_TABLE(id) ON DELETE CASCADE
        )""")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_artifacts_chat_created ON local_artifacts(chat_id, created_at DESC)")
        val intentColumns = db.rawQuery("PRAGMA table_info(local_artifact_intents)", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) }
        }
        if ("recovery_state" !in intentColumns) db.execSQL("ALTER TABLE local_artifact_intents ADD COLUMN recovery_state TEXT NOT NULL DEFAULT 'pending'")
        if ("recovery_error" !in intentColumns) db.execSQL("ALTER TABLE local_artifact_intents ADD COLUMN recovery_error TEXT")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_artifact_intents_recovery ON local_artifact_intents(recovery_state, created_at)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_local_artifact_intents_scan ON local_artifact_intents(recovery_state, created_at, chat_id, request_id, call_id)")
        db.execSQL("""CREATE TABLE IF NOT EXISTS local_artifact_recovery_cursor (
            id INTEGER PRIMARY KEY CHECK(id=1), created_at INTEGER NOT NULL,
            chat_id TEXT NOT NULL, request_id TEXT NOT NULL, call_id TEXT NOT NULL
        )""")
    }

    fun getOpenAiToolCheckpoint(chatId: String, requestId: String): JSONObject? =
        SqliteLargeTextQuery.query(readableDatabase, "openai_tool_checkpoints", arrayOf("request_checkpoint"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
            keyColumns = arrayOf("chat_id", "request_id")).use { c ->
            if (c.moveToFirst()) JSONObject(c.getString(0)) else null
        }

    fun saveOpenAiToolCheckpoint(chatId: String, requestId: String, checkpoint: JSONObject): Boolean {
        var saved = false
        writableDatabase.transaction { db ->
            if (!isActiveRequest(chatId, requestId)) return@transaction
            db.insertWithOnConflict("openai_tool_checkpoints", null, ContentValues().apply {
                put("chat_id", chatId); put("request_id", requestId)
                put("request_checkpoint", checkpoint.toString())
            }, SQLiteDatabase.CONFLICT_REPLACE)
            saved = true
        }
        return saved
    }

    fun compareOpenAiToolCheckpoint(chatId: String, requestId: String, before: String, after: JSONObject): Boolean {
        var changed = false
        writableDatabase.transaction { db ->
            if (!isActiveRequest(chatId, requestId)) return@transaction
            changed = db.update("openai_tool_checkpoints", ContentValues().apply {
                put("request_checkpoint", after.toString())
            }, "chat_id=? AND request_id=? AND request_checkpoint=?", arrayOf(chatId, requestId, before)) == 1
        }
        return changed
    }

    /** A tool round has its own response identity and SSE cursor; only its current owner may advance it. */
    fun advanceOpenAiToolResponse(chatId: String, requestId: String, previousId: String,
        nextId: String, checkpoint: JSONObject): Boolean {
        var advanced = false
        writableDatabase.transaction { db ->
            val count = db.update(CHAT_TABLE, ContentValues().apply {
                put("active_response_id", nextId); put("active_status", "in_progress")
                put("active_text", ""); put("active_last_sequence_number", -1)
                putNull("active_indexed_checkpoint")
            }, "id=? AND active_request_id=? AND active_response_id=? AND active_status IN ('completed','in_progress','starting','queued')",
                arrayOf(chatId, requestId, previousId))
            if (count != 1) return@transaction
            check(saveOpenAiToolCheckpoint(chatId, requestId, checkpoint))
            advanced = true
        }
        return advanced
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

    /** Persist the planned identity before a file-producing DeepSeek operation writes any bytes. */
    fun saveLocalArtifactIntentIfActive(chatId: String, requestId: String, callId: String, metadata: JSONObject): Boolean {
        if (chatId.isBlank() || requestId.isBlank() || callId.isBlank()) return false
        require(JsonProtocol.string(metadata, "tool_name") in setOf("create_local_text_file", "patch_local_file") && JsonProtocol.string(metadata, "tool_call_id") == callId)
        require(JsonProtocol.nonBlank(metadata, "arguments_sha256") != null && verifiedIntentPath(metadata) != null)
        require(StreamingJsonRequestBody.countBytes(metadata) <= 256 * 1024) { "Artifact intent metadata is too large" }
        var saved = false
        writableDatabase.transaction { db ->
            val owns = db.rawQuery("""SELECT 1 FROM $CHAT_TABLE c JOIN $REQUEST_ACCOUNTING_TABLE a
                ON a.chat_id=c.id AND a.local_request_id=c.active_request_id
                WHERE c.id=? AND c.active_request_id=? AND a.provider='deepseek' AND a.local_status='running'""",
                arrayOf(chatId, requestId)).use { it.moveToFirst() }
            if (!owns) return@transaction
            val prior = SqliteLargeTextQuery.query(db, "local_artifact_intents", arrayOf("metadata"),
                "chat_id=? AND request_id=? AND call_id=?", arrayOf(chatId, requestId, callId), null, null, null,
                "1", arrayOf("chat_id", "request_id", "call_id")).use { if (it.moveToFirst()) it.getString(0) else null }
            if (prior != null) {
                check(prior == metadata.toString()) { "Artifact intent identity changed" }
                saved = true
            } else {
                db.insertOrThrow("local_artifact_intents", null, ContentValues().apply {
                    put("chat_id", chatId); put("request_id", requestId); put("call_id", callId)
                    put("metadata", metadata.toString()); put("created_at", System.currentTimeMillis())
                })
                saved = true
            }
        }
        return saved
    }

    /** Validate only app-owned generated paths and precommitted hashes; never trust arbitrary filesystem paths. */
    private fun verifiedIntentPath(metadata: JSONObject): java.io.File? {
        val rawPath = JsonProtocol.nonBlank(metadata, "path") ?: return null
        val sha = JsonProtocol.nonBlank(metadata, "sha256") ?: return null
        if (!Regex("[0-9a-f]{64}").matches(sha) || metadata.optLong("size_bytes", -1L) < 0L) return null
        val file = java.io.File(rawPath)
        val directory = java.io.File(appContext.filesDir, "generated").canonicalFile
        if (file.canonicalFile.parentFile != directory) return null
        val attachment = metadata.optJSONObject("attachment") ?: return null
        if (JsonProtocol.string(attachment, "path") != rawPath || JsonProtocol.string(attachment, "sha256") != sha) return null
        if (JsonProtocol.string(metadata, "tool_name") == "patch_local_file") {
            for (key in listOf("source_sha256", "operation_sha256", "patch_sidecar_sha256")) {
                if (!Regex("[0-9a-f]{64}").matches(JsonProtocol.string(metadata, key))) return null
            }
            if (JsonProtocol.nonBlank(metadata, "source_file_id") == null ||
                JsonProtocol.nonBlank(metadata, "source_path") == null ||
                metadata.optLong("patch_sidecar_size_bytes", -1L) !in 0L..(2L * 1024L * 1024L)) return null
            val source = java.io.File(metadata.getString("source_path"))
            val sidecar = JsonProtocol.nonBlank(metadata, "patch_diff_path")?.let { java.io.File(it) } ?: return null
            if (source.canonicalFile == file.canonicalFile ||
                sidecar.canonicalFile.parentFile != java.io.File(directory, "patch-diffs").canonicalFile) return null
        }
        return file
    }

    data class ArtifactIntentRecoveryBatch(val examined: Int, val recovered: Int, val quarantined: Int,
        val hasMore: Boolean, val cancelled: Boolean)

    /** Optional background work. Opening SQLite never reads intent bodies or hashes generated files. */
    fun reconcilePublishedArtifactIntents(
        shouldContinue: () -> Boolean = { true },
        requestIsRunning: (String) -> Boolean = { OpenRouterResponseRuntime.contains(it) },
        maxRows: Int = 8,
        maxMetadataBytes: Long = 512L * 1024L,
        maxFileBytes: Long = 4L * 1024L * 1024L,
        maxWorkMillis: Long = 200L
    ): ArtifactIntentRecoveryBatch {
        require(maxRows > 0 && maxMetadataBytes > 0L && maxFileBytes > 0L && maxWorkMillis > 0L)
        if (!ARTIFACT_RECONCILIATION_RUNNING.compareAndSet(false, true))
            return ArtifactIntentRecoveryBatch(0, 0, 0, true, false)
        try {
        val rowBudget = maxRows.coerceAtMost(32)
        val metadataBudget = maxMetadataBytes.coerceAtMost(1024L * 1024L)
        val fileBudget = maxFileBytes.coerceAtMost(32L * 1024L * 1024L)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(maxWorkMillis.coerceAtMost(500L))
        var examined = 0; var recovered = 0; var quarantined = 0
        var metadataRead = 0L; var fileRead = 0L
        fun running() = shouldContinue() && !Thread.currentThread().isInterrupted && System.nanoTime() < deadline
        if (!running()) return ArtifactIntentRecoveryBatch(0, 0, 0, true, true)
        val db = writableDatabase
        data class Candidate(val chatId: String, val requestId: String, val callId: String, val bytes: Long, val createdAt: Long)
        val candidates = mutableListOf<Candidate>()
        val previousCursor = db.query("local_artifact_recovery_cursor", arrayOf("created_at", "chat_id", "request_id", "call_id"),
            "id=1", null, null, null, null, "1").use { c ->
            if (c.moveToFirst()) arrayOf(c.getLong(0).toString(), c.getString(1), c.getString(2), c.getString(3)) else null
        }
        fun selectCandidates(cursor: Array<String>?) {
            db.query("local_artifact_intents", arrayOf("chat_id", "request_id", "call_id", "length(CAST(metadata AS BLOB))", "created_at"),
                "recovery_state='pending'" + if (cursor == null) "" else " AND (created_at,chat_id,request_id,call_id)>(?,?,?,?)",
                cursor, null, null, "created_at ASC,chat_id ASC,request_id ASC,call_id ASC", rowBudget.toString()).use { c ->
                while (c.moveToNext()) candidates += Candidate(c.getString(0), c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
            }
        }
        selectCandidates(previousCursor)
        if (candidates.isEmpty() && previousCursor != null && running()) selectCandidates(null)
        var lastVisited: Candidate? = null
        for (candidate in candidates) {
            if (!running()) break
            val (chatId, requestId, callId, bytes) = candidate
            lastVisited = candidate
            examined++
            if (requestIsRunning(requestId)) continue
            val identity = arrayOf(chatId, requestId, callId)
            fun quarantine(reason: String) {
                quarantined += db.update("local_artifact_intents", ContentValues().apply {
                    put("recovery_state", "quarantined"); put("recovery_error", reason)
                }, "chat_id=? AND request_id=? AND call_id=? AND recovery_state='pending'", identity)
            }
            if (bytes > 256L * 1024L) { quarantine("metadata_size"); continue }
            if (metadataRead + bytes > metadataBudget) break
            metadataRead += bytes
            val raw = SqliteLargeTextQuery.query(db, "local_artifact_intents", arrayOf("metadata"),
                "chat_id=? AND request_id=? AND call_id=? AND recovery_state='pending'", identity, null, null, null,
                "1", arrayOf("chat_id", "request_id", "call_id")).use { if (it.moveToFirst()) it.getString(0) else null } ?: continue
            val metadata = try { JSONObject(raw) } catch (_: org.json.JSONException) { quarantine("invalid_metadata"); continue }
            if (JsonProtocol.string(metadata, "tool_call_id") != callId ||
                JsonProtocol.string(metadata, "tool_name") !in setOf("create_local_text_file", "patch_local_file") ||
                JsonProtocol.nonBlank(metadata, "arguments_sha256") == null) {
                quarantine("invalid_operation_identity"); continue
            }
            val file = try { verifiedIntentPath(metadata) } catch (_: java.io.IOException) { null }
            if (file == null) { quarantine("invalid_path_or_identity"); continue }
            val size = metadata.getLong("size_bytes")
            if (!file.isFile || file.length() != size) { quarantine("unconfirmed_publication"); continue }
            if (size > BoundedAttachmentCopy.MAX_INPUT_BYTES) { quarantine("file_size"); continue }
            if (fileRead + size > fileBudget) break
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            var interrupted = false
            var fileBytes = 0L
            val actualSha = try {
                file.inputStream().use { input ->
                    val buffer = ByteArray(32 * 1024)
                    while (true) {
                        if (fileBytes == size) break
                        if (!running() || requestIsRunning(requestId)) { interrupted = true; break }
                        val remaining = fileBudget - fileRead
                        if (remaining <= 0L) { interrupted = true; break }
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                        if (count < 0) break
                        fileRead += count
                        fileBytes += count
                        if (fileRead > fileBudget) { interrupted = true; break }
                        digest.update(buffer, 0, count)
                    }
                }
                if (interrupted) null else LocalUtf8FilePatch.hex(digest.digest())
            } catch (_: java.io.IOException) { quarantine("unreadable_file"); continue }
            if (interrupted) break
            if (fileBytes != size || file.length() != size) { quarantine("unconfirmed_publication"); continue }
            if (actualSha != metadata.getString("sha256")) { quarantine("digest_mismatch"); continue }
            if (!running() || requestIsRunning(requestId)) break
            val recoveredMetadata = JsonProtocol.objectCopy(metadata)
            if (JsonProtocol.string(metadata, "tool_name") == "patch_local_file") {
                val sidecar = java.io.File(metadata.getString("patch_diff_path"))
                if (!sidecar.exists()) {
                    // The exact version is proven; never invent a missing diff or rerun its patch.
                    recoveredMetadata.put("patch_diff_state", "missing")
                } else {
                    val expectedSidecarSize = metadata.getLong("patch_sidecar_size_bytes")
                    if (!sidecar.isFile || sidecar.length() != expectedSidecarSize) { quarantine("patch_sidecar_size"); continue }
                    if (fileRead + expectedSidecarSize > fileBudget) break
                    val sidecarBytes = try { sidecar.inputStream().use { input ->
                        val output = java.io.ByteArrayOutputStream(expectedSidecarSize.toInt())
                        val buffer = ByteArray(32 * 1024)
                        var remaining = expectedSidecarSize
                        while (remaining > 0L && running()) {
                            val count = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                            if (count < 0) break
                            output.write(buffer, 0, count); remaining -= count; fileRead += count
                        }
                        output.toByteArray()
                    } } catch (_: java.io.IOException) { quarantine("unreadable_patch_sidecar"); continue }
                    if (!running()) break
                    val sidecarSha = LocalUtf8FilePatch.hex(java.security.MessageDigest.getInstance("SHA-256").digest(sidecarBytes))
                    if (sidecarBytes.size.toLong() != expectedSidecarSize || sidecar.length() != expectedSidecarSize ||
                        sidecarSha != metadata.getString("patch_sidecar_sha256")) { quarantine("patch_sidecar_digest"); continue }
                    val saved = try { JSONObject(sidecarBytes.toString(Charsets.UTF_8)) }
                        catch (_: org.json.JSONException) { quarantine("invalid_patch_sidecar"); continue }
                    val savedArtifact = saved.optJSONObject("artifact")
                    if (saved.optInt("version") != 1 || savedArtifact == null ||
                        JsonProtocol.string(saved, "operation_sha256") != JsonProtocol.string(metadata, "operation_sha256") ||
                        JsonProtocol.string(saved, "result_sha256") != JsonProtocol.string(metadata, "sha256") ||
                        JsonProtocol.string(saved, "source_file_id") != JsonProtocol.string(metadata, "source_file_id") ||
                        JsonProtocol.string(saved, "source_sha256") != JsonProtocol.string(metadata, "source_sha256") ||
                        JsonProtocol.string(savedArtifact, "path") != JsonProtocol.string(metadata, "path") ||
                        JsonProtocol.string(savedArtifact, "patch_diff_path") != JsonProtocol.string(metadata, "patch_diff_path") ||
                        JsonProtocol.string(savedArtifact, "sha256") != JsonProtocol.string(metadata, "sha256")) {
                        quarantine("patch_sidecar_identity"); continue
                    }
                    recoveredMetadata.put("patch_diff_state", "confirmed")
                }
            }
            if (!running() || requestIsRunning(requestId)) break
            db.transaction { sql ->
                val stillPending = sql.query("local_artifact_intents", arrayOf("call_id"),
                    "chat_id=? AND request_id=? AND call_id=? AND metadata=? AND recovery_state='pending'",
                    identity + raw, null, null, null, "1").use { it.moveToFirst() }
                if (!stillPending) return@transaction
                val exists = sql.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(chatId), null, null, null, "1").use { it.moveToFirst() }
                if (!exists) return@transaction
                val previous = sql.query("local_artifacts", arrayOf("chat_id", "request_id"), "path=?", arrayOf(file.path),
                    null, null, null, "1").use { if (it.moveToFirst()) Pair(it.getString(0), it.getString(1)) else null }
                check(previous == null || previous == Pair(chatId, requestId)) { "Artifact recovery ownership changed" }
                if (previous == null) sql.insertOrThrow("local_artifacts", null, ContentValues().apply {
                    put("path", file.path); put("chat_id", chatId); put("request_id", requestId)
                    put("created_at", System.currentTimeMillis()); put("metadata", recoveredMetadata.toString())
                })
                // Confirmations live durably in local_artifacts. Archive retrieval joins them;
                // recovery must not copy an unbounded protocol archive on this optional batch.
                sql.delete("local_artifact_intents", "chat_id=? AND request_id=? AND call_id=?", identity)
                recovered++
            }
        }
        lastVisited?.let { visited ->
            db.insertWithOnConflict("local_artifact_recovery_cursor", null, ContentValues().apply {
                put("id", 1); put("created_at", visited.createdAt); put("chat_id", visited.chatId)
                put("request_id", visited.requestId); put("call_id", visited.callId)
            }, SQLiteDatabase.CONFLICT_REPLACE)
        }
        val hasMore = db.query("local_artifact_intents", arrayOf("call_id"), "recovery_state='pending'", null,
            null, null, null, "1").use { it.moveToFirst() }
        return ArtifactIntentRecoveryBatch(examined, recovered, quarantined, hasMore, !running())
        } finally { ARTIFACT_RECONCILIATION_RUNNING.set(false) }
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
            // A late successful side effect enriches its archive, never rewrites the original protocol tail.
            readCheckpointInDb(db, "request_recovery_archive", "recovery_message", chatId, requestId)?.let { archived ->
                if (archived.optString("provider") == "deepseek") {
                    archived.put("confirmed_artifacts", confirmedArtifactsInDb(db, chatId, requestId))
                    db.update("request_recovery_archive", ContentValues().apply { put("recovery_message", archived.toString()) },
                        "chat_id=? AND request_id=?", arrayOf(chatId, requestId))
                }
            }
        }
        return saved
    }

    /** Cheap chat-scoped metadata presence. Availability is checked only by optional evidence work. */
    fun hasLocalEvidenceCandidates(chatId: String): Boolean {
        if (chatId.isBlank()) return false
        fun readable(path: String?, name: String?, mime: String?, kind: String? = null): Boolean {
            if (path.isNullOrBlank()) return false
            val filename = name?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/')
            val type = mime.orEmpty().lowercase()
            val extension = TextFileFormatPolicy.extension(filename)
            if (kind in setOf("image", "pdf", "container", "container_document") || type == "application/pdf" ||
                type.startsWith("image/") && type != "image/svg+xml" || type.startsWith("audio/") || type.startsWith("video/") ||
                extension in setOf("pdf", "png", "jpg", "jpeg", "webp", "gif", "zip", "mp3", "mp4")) return false
            if (kind in setOf("text", "document", "table")) return true
            if (extension in TextFileFormatPolicy.office + setOf("doc", "xls", "ppt")) return false
            return TextFileFormatPolicy.knownText(filename) || type.startsWith("text/") ||
                type in setOf("application/json", "application/xml", "application/javascript") ||
                type.endsWith("+json") || type.endsWith("+xml")
        }
        fun manifest(value: JSONObject): Boolean = readable(JsonProtocol.nonBlank(value, "path"),
            JsonProtocol.nonBlank(value, "name"), JsonProtocol.nonBlank(value, "mime"), JsonProtocol.nonBlank(value, "kind"))
        fun metadata(value: JSONObject, fallbackPath: String? = null): Boolean {
            val attachment = value.optJSONObject("attachment_manifest") ?: value.optJSONObject("attachment")
            if (attachment != null && manifest(attachment)) return true
            return readable(JsonProtocol.nonBlank(value, "local_path") ?: JsonProtocol.nonBlank(value, "path") ?: fallbackPath,
                JsonProtocol.nonBlank(value, "filename"), JsonProtocol.nonBlank(value, "mime_type"))
        }
        fun array(raw: String?): JSONArray? = if (raw.isNullOrBlank()) null else try { JSONArray(raw) } catch (_: org.json.JSONException) { null }
        SqliteLargeTextQuery.query(readableDatabase, "local_artifacts", arrayOf("path", "metadata"),
            "chat_id=?", arrayOf(chatId), null, null, null, keyColumns = arrayOf("path")).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(1)
                val item = if (raw.isNullOrBlank()) null else try { JSONObject(raw) } catch (_: org.json.JSONException) { null }
                if (item != null && metadata(item, c.getString(0))) return true
            }
        }
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("attachments", "file_paths", "file_names", "file_mimes", "annotations"),
            "chat_id=? AND (attachments IS NOT NULL OR file_paths IS NOT NULL OR annotations IS NOT NULL)",
            arrayOf(chatId), null, null, null).use { c ->
            while (c.moveToNext()) {
                val attachments = array(c.getString(0))
                if (attachments != null) for (i in 0 until attachments.length())
                    if (attachments.optJSONObject(i)?.let(::manifest) == true) return true
                val paths = array(c.getString(1))
                if (paths != null && paths.length() > 0) {
                    val names = array(c.getString(2)); val mimes = array(c.getString(3))
                    for (i in 0 until paths.length()) if (readable(JsonProtocol.text(paths, i),
                        names?.let { JsonProtocol.text(it, i) }, mimes?.let { JsonProtocol.text(it, i) })) return true
                }
                val annotations = array(c.getString(4))
                if (annotations != null) for (i in 0 until annotations.length())
                    if (annotations.optJSONObject(i)?.let { metadata(it) } == true) return true
            }
        }
        return false
    }

    /** A narrow projection for local evidence; never loads protocol history or materializes files. */
    fun localAttachmentManifests(chatId: String): List<OpenRouterLocalAttachment> {
        if (chatId.isBlank()) return emptyList()
        val out = mutableListOf<OpenRouterLocalAttachment>()
        val legacy = linkedMapOf<String, OpenRouterLocalAttachment>()
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("attachments", "file_paths", "file_names", "file_mimes"),
            "chat_id=? AND (attachments IS NOT NULL OR file_paths IS NOT NULL)", arrayOf(chatId),
            null, null, "position ASC").use { c ->
            fun array(index: Int): JSONArray? {
                val raw = c.getString(index)
                return if (raw.isNullOrBlank()) null else JSONArray(raw)
            }
            while (c.moveToNext()) {
                array(0)?.let { manifest ->
                    for (i in 0 until manifest.length()) manifest.optJSONObject(i)?.let { out.add(OpenRouterLocalAttachment.fromJson(it)) }
                }
                val paths = array(1) ?: continue
                val names = array(2); val mimes = array(3)
                for (i in 0 until paths.length()) {
                    val path = JsonProtocol.text(paths, i)?.takeIf { it.isNotBlank() } ?: continue
                    if (path in legacy) continue
                    val file = java.io.File(path)
                    val name = names?.let { JsonProtocol.text(it, i) }?.takeIf { it.isNotBlank() } ?: file.name
                    val mime = mimes?.let { JsonProtocol.text(it, i) }.orEmpty()
                    val extension = TextFileFormatPolicy.extension(name)
                    val binary = extension in TextFileFormatPolicy.office + setOf("pdf", "doc", "xls", "ppt", "zip", "png", "jpg", "jpeg", "webp", "gif", "mp3", "mp4") ||
                        mime == "application/pdf" || mime.startsWith("audio/") || mime.startsWith("video/") ||
                        mime.startsWith("image/") && mime != "image/svg+xml"
                    val textual = mime.startsWith("text/") || mime in setOf("application/json", "application/xml", "application/javascript") ||
                        mime.endsWith("+json") || mime.endsWith("+xml") || TextFileFormatPolicy.knownText(name)
                    if (!binary && textual) legacy[path] = OpenRouterLocalAttachment("local_" + file.name,
                        name, "text", path, mime.ifBlank { "text/plain" }, file.length(), "", textEncoding = "UTF-8")
                }
            }
        }
        // A later durable manifest must beat a legacy raw-path fallback for the same source.
        val represented = out.mapTo(mutableSetOf()) { it.path }
        return out + legacy.values.filter { it.path !in represented }
    }

    fun persistLocalArtifactAttachment(chatId: String, path: String, expectedSize: Long,
        expectedSha: String, attachment: OpenRouterLocalAttachment): Boolean {
        if (expectedSize < 0 || !expectedSha.matches(Regex("[0-9a-f]{64}")) ||
            attachment.path != path || attachment.size != expectedSize || attachment.sha256 != expectedSha) return false
        var saved = false
        writableDatabase.transaction { sql ->
            val raw = SqliteLargeTextQuery.query(sql, "local_artifacts", arrayOf("metadata"),
                "chat_id=? AND path=?", arrayOf(chatId, path), null, null, null,
                keyColumns = arrayOf("path")).use { if (it.moveToFirst()) it.getString(0) else null }
            if (raw == null) {
                saved = persistAnnotationAttachment(sql, chatId, path, expectedSize, attachment)
                return@transaction
            }
            val meta = JSONObject(raw)
            if (JsonProtocol.string(meta, "path") != path || (meta.has("size_bytes") && meta.optLong("size_bytes", -1) != expectedSize)) return@transaction
            meta.put("attachment_manifest", attachment.toJson())
            saved = sql.update("local_artifacts", ContentValues().apply { put("metadata", meta.toString()) },
                "chat_id=? AND path=? AND metadata=?", arrayOf(chatId, path, raw)) == 1
        }
        return saved
    }

    private fun persistAnnotationAttachment(sql: SQLiteDatabase, chatId: String, path: String,
        expectedSize: Long, attachment: OpenRouterLocalAttachment): Boolean {
        var saved = false
        SqliteLargeTextQuery.query(sql, MESSAGE_TABLE, arrayOf("id", "annotations", "generated_images"),
            "chat_id=? AND (annotations IS NOT NULL OR generated_images IS NOT NULL)", arrayOf(chatId),
            null, null, null).use { c -> while (c.moveToNext()) {
            for ((index, column) in listOf(1 to "annotations", 2 to "generated_images")) {
                val raw = c.getString(index) ?: continue
                val entries = parseArrayColumn(raw) ?: continue
                var changed = false
                for (i in 0 until entries.length()) {
                    val entry = entries.optJSONObject(i) ?: continue
                    if (JsonProtocol.string(entry, "local_path") == path &&
                        entry.optLong("size_bytes", expectedSize) == expectedSize) {
                        entry.put("attachment_manifest", attachment.toJson()); changed = true
                    }
                }
                if (changed && sql.update(MESSAGE_TABLE, ContentValues().apply { put(column, entries.toString()) },
                    "id=? AND chat_id=? AND $column=?", arrayOf(c.getLong(0).toString(), chatId, raw)) == 1) saved = true
            }
        } }
        return saved
    }

    fun getLocalArtifactToolResult(chatId: String, requestId: String, callId: String): JSONObject? {
        var found: JSONObject? = null
        SqliteLargeTextQuery.query(readableDatabase, "local_artifacts", arrayOf("metadata"),
            "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, null,
            keyColumns = arrayOf("path")).use { c -> while (c.moveToNext()) {
            val meta = JSONObject(c.getString(0))
            if (JsonProtocol.string(meta, "tool_call_id") == callId) {
                check(found == null) { "Conflicting durable tool identities" }
                found = meta
            }
        } }
        return found
    }

    /** Writes and replay share request ownership; an already committed call is never executed twice. */
    fun registerLocalArtifactToolResultIfActive(chatId: String, requestId: String, callId: String,
        result: JSONObject): JSONObject? {
        require(callId.isNotBlank() && JsonProtocol.nonBlank(result, "tool_name") != null &&
            JsonProtocol.nonBlank(result, "arguments_sha256") != null)
        var committed: JSONObject? = null
        writableDatabase.transaction { sql ->
            if (!isActiveRequest(chatId, requestId)) return@transaction
            val existing = getLocalArtifactToolResult(chatId, requestId, callId)
            if (existing != null) {
                check(existing.optString("tool_name") == result.optString("tool_name") &&
                    existing.optString("arguments_sha256") == result.optString("arguments_sha256")) { "Tool call identity changed" }
                committed = existing
                return@transaction
            }
            val value = JsonProtocol.objectCopy(result).put("tool_call_id", callId)
            sql.insertOrThrow("local_artifacts", null, ContentValues().apply {
                put("path", value.getString("path")); put("chat_id", chatId); put("request_id", requestId)
                put("created_at", System.currentTimeMillis()); put("metadata", value.toString())
            })
            sql.delete("local_artifact_intents", "chat_id=? AND request_id=? AND call_id=?", arrayOf(chatId, requestId, callId))
            committed = value
        }
        return committed
    }

    private fun localArtifactPaths(chatId: String? = null): Set<String> {
        val out = linkedSetOf<String>()
        SqliteLargeTextQuery.query(readableDatabase, "local_artifacts", arrayOf("path", "metadata"),
            if (chatId == null) null else "chat_id=?", chatId?.let { arrayOf(it) }, null, null, null,
            keyColumns = arrayOf("path")).use { c -> while (c.moveToNext()) {
            c.getString(0)?.takeIf { it.isNotBlank() }?.let(out::add)
            // A damaged reference record stops cleanup instead of deleting paid versions/derivatives.
            val meta = JSONObject(c.getString(1))
            for (key in listOf("attachment", "attachment_manifest")) {
                if (meta.has(key) && !meta.isNull(key)) {
                    val manifest = meta.optJSONObject(key) ?: error("Invalid artifact manifest")
                    for (field in listOf("path", "text_path", "wire_path"))
                        if (manifest.has(field) && !manifest.isNull(field) && JsonProtocol.text(manifest, field) == null)
                            error("Invalid artifact manifest reference")
                    out.addAll(LocalAttachmentReferences.openRouterPaths(JSONArray().put(manifest)))
                }
            }
            for (key in listOf("source_path", "diff_path", "patch_diff_path")) {
                if (meta.has(key) && !meta.isNull(key)) {
                    val path = JsonProtocol.text(meta, key) ?: error("Invalid artifact reference")
                    if (path.isNotBlank()) out.add(path)
                }
            }
        } }
        return out
    }

    /** Local copies of assistant-generated/downloaded files and generated images. */
    fun listLocalFiles(chatId: String? = null): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        val titles = mutableMapOf<String, String>()
        fun title(chatId: String) = titles.getOrPut(chatId) { getChatTitle(chatId).orEmpty() }
        SqliteLargeTextQuery.query(readableDatabase, "local_artifacts", arrayOf("chat_id", "metadata"),
            if (chatId == null) null else "chat_id=?", chatId?.let { arrayOf(it) },
            null, null, "created_at DESC", keyColumns = arrayOf("path")).use { c ->
            while (c.moveToNext()) {
                val a = JSONObject(c.getString(1))
                val path = a.getString("path")
                if (java.io.File(path).isFile && seen.add("file:$path")) out += a
                    .put("kind", "file").put("chat_id", c.getString(0)).put("chat_title", title(c.getString(0)))
            }
        }
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("chat_id", "annotations", "generated_images"),
            if (chatId == null) "annotations IS NOT NULL OR generated_images IS NOT NULL"
                else "chat_id=? AND (annotations IS NOT NULL OR generated_images IS NOT NULL)",
            chatId?.let { arrayOf(it) },
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
                        .put("size_bytes", a.optLong("size_bytes", file.length())).apply {
                            a.optJSONObject("attachment_manifest")?.let { put("attachment_manifest", it) }
                        }
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
                        .put("size_bytes", a.optLong("size_bytes", file.length())).apply {
                            a.optJSONObject("attachment_manifest")?.let { put("attachment_manifest", it) }
                        }
                }
            }
        }
        return out
    }

    private fun recoveryArchivePaths(chatId: String? = null): Set<String> {
        val out = linkedSetOf<String>()
        SqliteLargeTextQuery.query(readableDatabase, "request_recovery_archive", arrayOf("recovery_message"),
            if (chatId == null) null else "chat_id=?", chatId?.let { arrayOf(it) }, null, null, null,
            keyColumns = arrayOf("chat_id", "request_id")).use { cursor ->
            while (cursor.moveToNext()) {
                // Ownership must fail closed; ignoring a damaged archive could delete its paid files.
                val message = JSONObject(cursor.getString(0))
                out.addAll(LocalAttachmentReferences.composerPaths(message))
                out.addAll(LocalAttachmentReferences.openRouterPaths(message.optJSONArray("attachments")))
                for (key in listOf("annotations", "generated_images")) {
                    message.optJSONArray(key)?.let { items -> for (i in 0 until items.length()) {
                        items.optJSONObject(i)?.let { item ->
                            JsonProtocol.nonBlank(item, "local_path")?.let(out::add)
                            for (manifestKey in listOf("attachment", "attachment_manifest")) {
                                if (item.has(manifestKey) && !item.isNull(manifestKey)) {
                                    val manifest = item.optJSONObject(manifestKey) ?: error("Invalid archived artifact manifest")
                                    out.addAll(LocalAttachmentReferences.openRouterPaths(JSONArray().put(manifest)))
                                }
                            }
                            for (pathKey in listOf("source_path", "patch_diff_path")) JsonProtocol.nonBlank(item, pathKey)?.let(out::add)
                        }
                    } }
                }
            }
        }
        return out
    }

    fun listLocalFilePaths(chatId: String): List<String> {
        if (chatId.isBlank()) return emptyList()
        val out = linkedSetOf<String>()
        out.addAll(localArtifactPaths(chatId))
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("file_paths", "image_path", "attachments", "annotations", "generated_images"),
            "chat_id=? AND ($LOCAL_FILE_REFERENCE_SELECTION)",
            arrayOf(chatId),
            null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                collectMessageLocalFilePaths(c, out)
            }
        }
        SqliteLargeTextQuery.query(readableDatabase, "openai_tool_checkpoints", arrayOf("request_checkpoint"),
            "chat_id=?", arrayOf(chatId), null, null, null,
            keyColumns = arrayOf("chat_id", "request_id")).use { c ->
            while (c.moveToNext()) {
                try {
                    val cp = JSONObject(c.getString(0).orEmpty())
                    out.addAll(LocalAttachmentReferences.openRouterPaths(cp.optJSONArray("attachments")))
                } catch (_: Exception) {}
            }
        }
        out.addAll(recoveryArchivePaths(chatId))
        return out.toList()
    }

    fun listAllLocalFilePaths(): List<String> {
        val out = linkedSetOf<String>()
        out.addAll(localArtifactPaths())
        SqliteLargeTextQuery.query(readableDatabase, MESSAGE_TABLE,
            arrayOf("file_paths", "image_path", "attachments", "annotations", "generated_images"),
            LOCAL_FILE_REFERENCE_SELECTION, null, null, null, "position ASC"
        ).use { c ->
            while (c.moveToNext()) {
                collectMessageLocalFilePaths(c, out)
            }
        }
        out.addAll(recoveryArchivePaths())
        return out.toList()
    }

    /** Null/empty legacy columns carry no paths and must not enter the JSON parser. */
    private fun collectMessageLocalFilePaths(cursor: android.database.Cursor, out: MutableSet<String>) {
        fun array(index: Int) = parseArrayColumn(cursor.getString(index))
        array(0)?.let { paths ->
            for (i in 0 until paths.length()) paths.optString(i).takeIf { it.isNotBlank() }?.let(out::add)
        }
        cursor.getString(1)?.takeIf { it.isNotBlank() }?.let(out::add)
        out.addAll(LocalAttachmentReferences.openRouterPaths(array(2)))
        for (index in 3..4) array(index)?.let { items ->
            for (i in 0 until items.length()) items.optJSONObject(i)?.let { entry ->
                entry.optString("local_path").takeIf { it.isNotBlank() }?.let(out::add)
                if (entry.has("attachment_manifest") && !entry.isNull("attachment_manifest")) {
                    val manifest = entry.optJSONObject("attachment_manifest") ?: error("Invalid annotation manifest")
                    out.addAll(LocalAttachmentReferences.openRouterPaths(JSONArray().put(manifest)))
                }
            }
        }
    }

    fun listActiveOpenAiToolAttachmentPaths(): Set<String> {
        val out = linkedSetOf<String>()
        SqliteLargeTextQuery.query(readableDatabase, "openai_tool_checkpoints", arrayOf("request_checkpoint"),
            null, null, null, null, null,
            keyColumns = arrayOf("chat_id", "request_id")).use { c ->
            while (c.moveToNext()) {
                try {
                    val cp = JSONObject(c.getString(0).orEmpty())
                    out.addAll(LocalAttachmentReferences.openRouterPaths(cp.optJSONArray("attachments")))
                } catch (_: Exception) {}
            }
        }
        return out
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
            arrayOf("effective_effort", "COUNT(*) AS n", "AVG(latency_ms) AS latency", "SUM(CASE WHEN usage_complete=1 THEN output_tokens ELSE 0 END) AS output", "SUM(CASE WHEN usage_complete=1 THEN reasoning_tokens ELSE 0 END) AS reasoning", "SUM(success) AS success", "SUM(CASE WHEN feedback IS NOT NULL THEN 1 ELSE 0 END) AS ratings", "AVG(feedback) AS avg_feedback"),
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
                // SQLite stores the durable OpenAI assistant status, not the
                // transient JSON flag "incomplete". Reconstruct that flag
                // only for interrupted OpenAI assistant turns on reopen.
                // Without it, a Worker failure saved with incomplete=true
                // reopens as an apparently complete assistant message.
                if (m.optString("role") == "assistant" &&
                    m.optString("provider") == "openai" &&
                    m.optString("status") in setOf("failed", "disconnected", "incomplete", "cancelled")) {
                    m.put("incomplete", true)
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
