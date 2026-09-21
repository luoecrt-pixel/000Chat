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
        private const val DB_VERSION = 11
        private const val CHAT_TABLE = "chats"
        private const val MESSAGE_TABLE = "messages"
        private const val METRICS_TABLE = "effort_metrics"
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
                active_attachments INTEGER NOT NULL DEFAULT 0
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
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
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
        d.query(CHAT_TABLE, null, "id=?", arrayOf(id), null, null, null, "1").use { c ->
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
        readableDatabase.query(CHAT_TABLE, arrayOf("title"), "id=?", arrayOf(id), null, null, null, "1").use { c ->
            return if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("title")) else null
        }
    }

    fun listChats(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.query(CHAT_TABLE, null, null, null, null, null, "updated_at DESC, id DESC").use { c ->
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
        readableDatabase.query(
            CHAT_TABLE,
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
            db.delete(CHAT_TABLE, "id=?", arrayOf(id))
        }
    }

    fun upsertCurrentChat(id: String, title: String, responseId: String?, baselineEffort: String?, messages: List<JSONObject>) {
        if (id.isBlank()) return
        writableDatabase.transaction { db ->
            val exists = db.query(CHAT_TABLE, arrayOf("id"), "id=?", arrayOf(id), null, null, null, "1").use { it.moveToFirst() }
            val values = ContentValues().apply {
                put("id", id)
                put("title", title.ifBlank { "Новый чат" })
                if (responseId.isNullOrBlank()) putNull("response_id") else put("response_id", responseId)
                if (baselineEffort.isNullOrBlank()) putNull("baseline_effort") else put("baseline_effort", baselineEffort)
                put("updated_at", System.currentTimeMillis())
            }
            if (exists) db.update(CHAT_TABLE, values, "id=?", arrayOf(id)) else db.insertOrThrow(CHAT_TABLE, null, values)
            db.delete(MESSAGE_TABLE, "chat_id=?", arrayOf(id))
            messages.forEachIndexed { index, m ->
                val mv = ContentValues().apply {
                    put("chat_id", id)
                    put("position", index)
                    put("role", m.optString("role"))
                    put("text", m.optString("text"))
                    putStringOrNull("response_id", m.optString("response_id").takeIf { it.isNotBlank() && it != "null" })
                    putStringOrNull("request_id", m.optString("request_id").takeIf { it.isNotBlank() && it != "null" })
                    putStringOrNull("file_ids", m.optJSONArray("file_ids")?.toString())
                    putStringOrNull("file_names", m.optJSONArray("file_names")?.toString())
                    putStringOrNull("file_sizes", m.optJSONArray("file_sizes")?.toString())
                    putStringOrNull("file_paths", m.optJSONArray("file_paths")?.toString())
                    putStringOrNull("file_mimes", m.optJSONArray("file_mimes")?.toString())
                    putStringOrNull("image_path", m.optString("image_path").takeIf { it.isNotBlank() })
                    putStringOrNull("image_file_id", m.optString("image_file_id").takeIf { it.isNotBlank() && it != "null" })
                    putStringOrNull("image_name", m.optString("image_name").takeIf { it.isNotBlank() })
                    putStringOrNull("image_mime", m.optString("image_mime").takeIf { it.isNotBlank() })
                    put("image_size", m.optLong("image_size", 0L).coerceAtLeast(0L))
                    putStringOrNull("annotations", m.optJSONArray("annotations")?.toString())
                    putStringOrNull("generated_images", m.optJSONArray("generated_images")?.toString())
                    putStringOrNull("tools", m.optJSONArray("tools")?.toString())
                    putStringOrNull("status", m.optString("status").takeIf { it.isNotBlank() && it != "null" })
                    if (m.has("feedback") && !m.isNull("feedback")) put("feedback", m.optInt("feedback")) else putNull("feedback")
                }
                db.insertOrThrow(MESSAGE_TABLE, null, mv)
            }
        }
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
        writableDatabase.insert(METRICS_TABLE, null, v)
    }

    fun recordFeedback(chatId: String, requestId: String, value: Int) {
        val v = value.coerceIn(-1, 1)
        if (requestId.isBlank() || v == 0) return
        writableDatabase.transaction { db ->
            db.execSQL(
                "UPDATE $METRICS_TABLE SET feedback=? WHERE chat_id=? AND request_id=?",
                arrayOf(v, chatId, requestId)
            )
            db.query(MESSAGE_TABLE, arrayOf("id", "annotations", "feedback"), "chat_id=? AND request_id=?", arrayOf(chatId, requestId), null, null, "position DESC", "1").use { c ->
                if (!c.moveToFirst()) return@use
                val id = c.getLong(c.getColumnIndexOrThrow("id"))
                val cv = ContentValues().apply { put("feedback", v) }
                db.update(MESSAGE_TABLE, cv, "id=?", arrayOf(id.toString()))
            }
        }
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
            db.query(
                MESSAGE_TABLE,
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
            db.query(
                MESSAGE_TABLE,
                arrayOf("id"),
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
            db.query(MESSAGE_TABLE, arrayOf("id", "annotations"), "chat_id=? AND annotations IS NOT NULL", arrayOf(chatId), null, null, "position ASC").use { c ->
                while (c.moveToNext()) {
                    val raw = c.getString(c.getColumnIndexOrThrow("annotations")) ?: continue
                    val anns = try { JSONArray(raw) } catch (_: Exception) { continue }
                    var changed = false
                    for (i in 0 until anns.length()) {
                        val a = anns.optJSONObject(i) ?: continue
                        val type = a.optString("type")
                        if (type != "container_file_citation" && type != "file_citation") continue
                        if (annotationType != null && type != annotationType) continue
                        if (a.optString("file_id") != fileId) continue
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
        readableDatabase.query(
            MESSAGE_TABLE,
            arrayOf("chat_id", "annotations"),
            "annotations IS NOT NULL", null, null, null, "id DESC"
        ).use { c ->
            while (c.moveToNext()) {
                val raw = c.getString(c.getColumnIndexOrThrow("annotations")) ?: continue
                val anns = try { JSONArray(raw) } catch (_: Exception) { continue }
                for (i in 0 until anns.length()) {
                    val a = anns.optJSONObject(i) ?: continue
                    if (a.optString("file_id") != fileId) continue
                    val path = a.optString("local_path").takeIf { it.isNotBlank() } ?: continue
                    if (!java.io.File(path).isFile) continue
                    return JSONObject()
                        .put("chat_id", c.getString(c.getColumnIndexOrThrow("chat_id")))
                        .put("file_id", fileId)
                        .put("filename", a.optString("filename"))
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
        attachments: Int
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
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(CHAT_TABLE, values, "id=?", arrayOf(chatId))
    }

    fun updateActiveResponse(
        chatId: String,
        responseId: String? = null,
        text: String? = null,
        status: String? = null
    ) {
        if (chatId.isBlank()) return
        val values = ContentValues().apply {
            if (responseId != null) putStringOrNull("active_response_id", responseId)
            if (text != null) put("active_text", text)
            if (status != null) put("active_status", status)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(CHAT_TABLE, values, "id=?", arrayOf(chatId))
    }

    fun getActiveResponse(chatId: String): JSONObject? {
        if (chatId.isBlank()) return null
        readableDatabase.query(
            CHAT_TABLE,
            arrayOf(
                "active_response_id", "active_request_id", "active_text", "active_status",
                "active_started_at", "active_effort_pref", "active_effective_effort",
                "active_auto_selected", "active_prompt_chars", "active_attachments"
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
        }
    }

    fun listActiveResponses(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        readableDatabase.query(
            CHAT_TABLE,
            arrayOf(
                "id", "title", "active_response_id", "active_request_id", "active_text",
                "active_status", "active_started_at", "active_effort_pref",
                "active_effective_effort", "active_auto_selected", "active_prompt_chars",
                "active_attachments"
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
            }
        }
        return out
    }

    fun clearActiveResponse(chatId: String) {
        if (chatId.isBlank()) return
        val values = ContentValues().apply {
            putNull("active_response_id")
            putNull("active_request_id")
            putNull("active_text")
            putNull("active_status")
            putNull("active_started_at")
            putNull("active_effort_pref")
            putNull("active_effective_effort")
            put("active_auto_selected", 0)
            put("active_prompt_chars", 0)
            put("active_attachments", 0)
            put("updated_at", System.currentTimeMillis())
        }
        writableDatabase.update(CHAT_TABLE, values, "id=?", arrayOf(chatId))
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

    /** Local copies of assistant-generated/downloaded files and generated images. */
    fun listLocalFiles(): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        val seen = mutableSetOf<String>()
        readableDatabase.rawQuery(
            """SELECT m.chat_id, c.title, m.annotations, m.generated_images
               FROM $MESSAGE_TABLE m
               JOIN $CHAT_TABLE c ON c.id = m.chat_id
               WHERE m.annotations IS NOT NULL OR m.generated_images IS NOT NULL
               ORDER BY m.id DESC""",
            null
        ).use { cur ->
            while (cur.moveToNext()) {
                val chatId = cur.getString(0)
                val chatTitle = cur.getString(1)
                val annotationsRaw = cur.getString(2)
                val imagesRaw = cur.getString(3)
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
                        .put("filename", a.optString("filename").ifBlank { "file" })
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
                        .put("filename", a.optString("filename").ifBlank { "astra_image.png" })
                        .put("path", path)
                        .put("mime_type", a.optString("mime_type").ifBlank { "image/png" })
                        .put("size_bytes", a.optLong("size_bytes", file.length()))
                }
            }
        }
        return out
    }

    fun removeLocalFile(path: String): Boolean {
        if (path.isBlank()) return false
        var removed = false
        writableDatabase.transaction { db ->
            db.query(MESSAGE_TABLE, arrayOf("id", "annotations", "generated_images"), null, null, null, null, "id ASC").use { c ->
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
        readableDatabase.query(MESSAGE_TABLE, null, "chat_id=?", arrayOf(chatId), null, null, "position ASC, id ASC").use { c ->
            while (c.moveToNext()) {
                val m = JSONObject()
                    .put("role", c.getString(c.getColumnIndexOrThrow("role")))
                    .put("text", c.getString(c.getColumnIndexOrThrow("text")))
                c.getString(c.getColumnIndexOrThrow("response_id"))?.let { if (it.isNotBlank()) m.put("response_id", it) }
                c.getString(c.getColumnIndexOrThrow("request_id"))?.let { if (it.isNotBlank()) m.put("request_id", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_ids")))?.let { m.put("file_ids", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_names")))?.let { m.put("file_names", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_sizes")))?.let { m.put("file_sizes", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_paths")))?.let { m.put("file_paths", it) }
                parseArrayColumn(c.getString(c.getColumnIndexOrThrow("file_mimes")))?.let { m.put("file_mimes", it) }
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
                put("text", m.optString("text"))
                putStringOrNull("response_id", m.optString("response_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("request_id", m.optString("request_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("file_ids", m.optJSONArray("file_ids")?.toString())
                putStringOrNull("file_names", m.optJSONArray("file_names")?.toString())
                putStringOrNull("file_sizes", m.optJSONArray("file_sizes")?.toString())
                putStringOrNull("file_paths", m.optJSONArray("file_paths")?.toString())
                putStringOrNull("file_mimes", m.optJSONArray("file_mimes")?.toString())
                putStringOrNull("image_path", m.optString("image_path").takeIf { it.isNotBlank() })
                putStringOrNull("image_file_id", m.optString("image_file_id").takeIf { it.isNotBlank() && it != "null" })
                putStringOrNull("image_name", m.optString("image_name").takeIf { it.isNotBlank() })
                putStringOrNull("image_mime", m.optString("image_mime").takeIf { it.isNotBlank() })
                put("image_size", m.optLong("image_size", 0L).coerceAtLeast(0L))
                putStringOrNull("annotations", m.optJSONArray("annotations")?.toString())
                putStringOrNull("generated_images", m.optJSONArray("generated_images")?.toString())
                putStringOrNull("tools", m.optJSONArray("tools")?.toString())
                putStringOrNull("status", m.optString("status").takeIf { it.isNotBlank() && it != "null" })
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
