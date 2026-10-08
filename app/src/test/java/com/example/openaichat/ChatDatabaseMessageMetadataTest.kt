package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseMessageMetadataTest {
    private lateinit var context: Context
    private var database: ChatDatabase? = null

    @Before fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("astra_chats.db")
    }

    @After fun tearDown() {
        database?.close()
        database = null
        context.deleteDatabase("astra_chats.db")
    }

    private fun db(): ChatDatabase = database ?: ChatDatabase(context).also { database = it }
    private fun reopen(): ChatDatabase {
        database?.close()
        database = ChatDatabase(context)
        return database!!
    }

    private fun message(): JSONObject = JSONObject()
        .put("role", "assistant")
        .put("text", "Checked the repository")
        .put("request_id", "req-42")
        .put("provider", "openrouter")
        .put("model", "anthropic/claude-opus-5.5")
        .put("requested_model", "anthropic/claude-opus-5.5")
        .put("response_model", "anthropic/claude-opus-5.5-20261001")
        .put("reasoning_summary", "Looked at files without publishing data")
        .put("error", "A recoverable warning")
        .put("tool_trace", JSONArray().put(JSONObject()
            .put("name", "github_get_file")
            .put("arguments", JSONObject().put("path", "README.md"))
            .put("result", "content preview")))
        .put("usage", JSONObject().put("prompt_tokens", 345).put("completion_tokens", 80).put("cost", 0.021))

    @Test fun modelTraceReasoningErrorAndUsageSurviveSqliteReopen() {
        db().upsertCurrentChat("chat-meta", "Audit", null, null, listOf(message()))
        val persisted = reopen().getChat("chat-meta")!!.getJSONArray("messages").getJSONObject(0)
        assertEquals("openrouter", persisted.getString("provider"))
        assertEquals("anthropic/claude-opus-5.5", persisted.getString("model"))
        assertEquals("anthropic/claude-opus-5.5", persisted.getString("requested_model"))
        assertEquals("anthropic/claude-opus-5.5-20261001", persisted.getString("response_model"))
        assertEquals("Looked at files without publishing data", persisted.getString("reasoning_summary"))
        assertEquals("A recoverable warning", persisted.getString("error"))
        val trace = persisted.getJSONArray("tool_trace")
        assertEquals(1, trace.length())
        assertEquals("github_get_file", trace.getJSONObject(0).getString("name"))
        assertEquals("README.md", trace.getJSONObject(0).getJSONObject("arguments").getString("path"))
        assertEquals(345, persisted.getJSONObject("usage").getInt("prompt_tokens"))
        assertEquals(0.021, persisted.getJSONObject("usage").getDouble("cost"), 0.000001)
    }

    @Test fun updateOnlyMetadataIsNotSkippedByFingerprint() {
        val original = message()
        db().upsertCurrentChat("chat-meta", "Audit", null, null, listOf(original))
        val updated = JSONObject(original.toString())
            .put("response_model", "anthropic/claude-opus-5.5-corrected")
            .put("tool_trace", JSONArray().put(JSONObject().put("name", "github_list_directory")))
            .put("reasoning_summary", "New summary")
        db().upsertCurrentChat("chat-meta", "Audit", null, null, listOf(updated))
        val saved = reopen().getChat("chat-meta")!!.getJSONArray("messages").getJSONObject(0)
        assertEquals("anthropic/claude-opus-5.5-corrected", saved.getString("response_model"))
        assertEquals("github_list_directory", saved.getJSONArray("tool_trace").getJSONObject(0).getString("name"))
        assertEquals("New summary", saved.getString("reasoning_summary"))
    }

    @Test fun migratesExistingV16HistoryWithoutInventingMetadata() {
        createV16Fixture()
        val loaded = db().getChat("legacy-chat")!!.getJSONArray("messages").getJSONObject(0)
        assertEquals("Old answer", loaded.getString("text"))
        assertFalse(loaded.has("response_model"))
        assertFalse(loaded.has("tool_trace"))

        loaded.put("provider", "openai").put("requested_model", "gpt-6")
            .put("response_model", "gpt-6-luna")
        db().upsertCurrentChat("legacy-chat", "Before migration", null, null, listOf(loaded))
        val saved = reopen().getChat("legacy-chat")!!.getJSONArray("messages").getJSONObject(0)
        assertEquals("Old answer", saved.getString("text"))
        assertEquals("gpt-6-luna", saved.getString("response_model"))
        assertEquals("openai", saved.getString("provider"))
    }

    @Test fun legacyPrefsImportPreservesMessageMetadata() {
        val prefs = context.getSharedPreferences("metadata-import-fixture", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val legacyChat = JSONObject().put("id", "import-chat").put("title", "Imported")
            .put("messages", JSONArray().put(message()))
        prefs.edit().putString("chat_store", JSONArray().put(legacyChat).toString()).commit()

        db().migrateLegacyPrefs(prefs)
        val loaded = reopen().getChat("import-chat")!!.getJSONArray("messages").getJSONObject(0)
        assertEquals("anthropic/claude-opus-5.5-20261001", loaded.getString("response_model"))
        assertEquals("github_get_file", loaded.getJSONArray("tool_trace").getJSONObject(0).getString("name"))
        assertEquals(80, loaded.getJSONObject("usage").getInt("completion_tokens"))
        prefs.edit().clear().commit()
    }

    private fun createV16Fixture() {
        val file = context.getDatabasePath("astra_chats.db")
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { sql ->
            sql.execSQL("""
                CREATE TABLE chats (
                    id TEXT PRIMARY KEY NOT NULL, title TEXT NOT NULL,
                    response_id TEXT, baseline_effort TEXT, updated_at INTEGER NOT NULL
                )
            """.trimIndent())
            sql.execSQL("""
                CREATE TABLE messages (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    chat_id TEXT NOT NULL, position INTEGER NOT NULL,
                    role TEXT NOT NULL, text TEXT NOT NULL,
                    response_id TEXT, request_id TEXT,
                    file_ids TEXT, file_names TEXT, file_sizes TEXT, file_paths TEXT,
                    file_mimes TEXT, attachments TEXT, image_path TEXT, image_file_id TEXT,
                    image_name TEXT, image_mime TEXT, image_size INTEGER NOT NULL DEFAULT 0,
                    annotations TEXT, generated_images TEXT, tools TEXT,
                    status TEXT, feedback INTEGER, content_hash TEXT
                )
            """.trimIndent())
            sql.execSQL("INSERT INTO chats (id, title, updated_at) VALUES ('legacy-chat','Before migration',1)")
            sql.execSQL("INSERT INTO messages (chat_id, position, role, text) VALUES ('legacy-chat',0,'assistant','Old answer')")
            sql.version = 16
        }
    }
}
