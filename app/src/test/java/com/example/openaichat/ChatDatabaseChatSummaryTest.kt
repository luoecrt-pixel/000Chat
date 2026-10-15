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
class ChatDatabaseChatSummaryTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        clearComposerStates()
    }

    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        clearComposerStates()
    }

    private fun clearComposerStates() {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("composer_state_") }.forEach { editor.remove(it) }
        editor.commit()
    }

    @Test fun nonEmptyTextDraftRemainsReachableAcrossEmptyChatsAndDisappearsWhenCleared() {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val text = "  Неотправленный\nчерновик А  "
        ChatDatabase(context).use { db ->
            for (id in listOf("draft-a", "draft-b", "draft-c")) db.ensureChat(id)
            prefs.edit().putString("composer_state_draft-a", JSONObject().put("text", text).toString()).commit()
            val draft = db.listChatSummaries().single()
            assertEquals("draft-a", draft.getString("id"))
            assertEquals("Черновик: Неотправленный черновик А", draft.getString("title"))
            assertTrue(draft.getBoolean("is_draft"))
            assertFalse(draft.has("messages"))
        }
        ChatDatabase(context).use { db ->
            assertEquals("draft-a", db.listChatSummaries().single().getString("id"))
            assertEquals("Новый чат", db.getChatTitle("draft-a"))
            assertEquals(text, JSONObject(prefs.getString("composer_state_draft-a", "{}")!!).getString("text"))
            assertEquals(0, db.getChat("draft-a")!!.getJSONArray("messages").length())
            prefs.edit().putString("composer_state_draft-a", JSONObject().put("text", " \n\t ").toString()).commit()
            assertTrue(db.listChatSummaries().isEmpty())
            assertNotNull(db.getChat("draft-a"))
        }
    }

    @Test fun attachmentOnlyDraftsRemainVisibleAcrossEveryComposerAttachmentSlot() {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        val drafts = listOf(
            JSONObject().put("openrouter_attachments", JSONArray().put(JSONObject().put("id", "local"))),
            JSONObject().put("file_ids", JSONArray().put("file-1")),
            JSONObject().put("file_paths", JSONArray().put("/local/notes.txt")),
            JSONObject().put("pending_uploads", JSONArray().put(JSONObject().put("uri", "content://draft/1"))),
            JSONObject().put("image_path", "/local/image.png"),
            JSONObject().put("image_file_id", "file-image")
        )
        ChatDatabase(context).use { db ->
            val editor = prefs.edit()
            drafts.forEachIndexed { index, draft ->
                db.ensureChat("attachment-$index")
                editor.putString("composer_state_attachment-$index", draft.toString())
            }
            editor.commit()
            val summaries = db.listChatSummaries()
            assertEquals(drafts.size, summaries.size)
            assertEquals(drafts.indices.map { "attachment-$it" }.toSet(), summaries.map { it.getString("id") }.toSet())
            summaries.forEach {
                assertTrue(it.getBoolean("is_draft"))
                assertEquals("Черновик (вложение)", it.getString("title"))
            }
            clearComposerStates()
            assertTrue(db.listChatSummaries().isEmpty())
            assertEquals(drafts.size, db.listChats().size)
        }
    }

    @Test fun emptyMalformedAndOrphanComposerStatesNeverCreateDrawerEntries() {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        ChatDatabase(context).use { db ->
            val drafts = mapOf(
                "metadata" to "{\"text\":\"  \",\"saved_at\":123}",
                "empty-arrays" to "{\"file_ids\":[],\"file_paths\":[],\"openrouter_attachments\":[],\"pending_uploads\":[]}",
                "null-image" to "{\"image_file_id\":\"null\",\"image_path\":null,\"text\":null}",
                "malformed" to "{broken",
                "blank" to ""
            )
            val editor = prefs.edit()
            drafts.forEach { (id, raw) ->
                db.ensureChat(id)
                editor.putString("composer_state_$id", raw)
            }
            db.ensureChat("wrong-type")
            editor.putInt("composer_state_wrong-type", 42)
                .putString("composer_state_orphan", "{\"text\":\"no SQLite chat\"}")
                .putString("composer_state_", "{\"text\":\"no chat id\"}")
                .commit()
            assertTrue(db.listChatSummaries().isEmpty())
            assertEquals(drafts.size + 1, db.listChats().size)
        }
    }

    @Test fun publishedChatsKeepDatabaseTitlesAndMetadataWhenComposerHasDraftText() {
        val prefs = context.getSharedPreferences("app", Context.MODE_PRIVATE)
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat("sent", "Новый чат", "resp-1", "high",
                listOf(JSONObject().put("role", "user").put("text", "Sent")))
            db.ensureChat("renamed", "Research")
            for (id in listOf("sent", "renamed")) {
                prefs.edit().putString("composer_state_$id", "{\"text\":\"next unsent turn\"}").commit()
            }
            val summaries = db.listChatSummaries().associateBy { it.getString("id") }
            assertEquals(setOf("sent", "renamed"), summaries.keys)
            assertEquals("Новый чат", summaries.getValue("sent").getString("title"))
            assertEquals("Research", summaries.getValue("renamed").getString("title"))
            assertEquals("resp-1", summaries.getValue("sent").getString("response_id"))
            assertEquals("high", summaries.getValue("sent").getString("baseline_effort"))
            assertTrue(summaries.values.none { it.has("is_draft") })
        }
    }

    @Test fun emptyDraftsStayHiddenAcrossReopenWithoutDeletingTheirState() {
        ChatDatabase(context).use { db ->
            repeat(3) { db.ensureChat("draft-$it") }
            db.upsertCurrentChat("draft-1", "Новый чат", null, "high", emptyList())
            assertTrue(db.listChatSummaries().isEmpty())
            assertEquals(3, db.listChats().size)
        }
        ChatDatabase(context).use { db ->
            assertTrue(db.listChatSummaries().isEmpty())
            assertEquals("Новый чат", db.getChatTitle("draft-1"))
            val draft = db.getChat("draft-1")!!
            assertEquals("high", draft.getString("baseline_effort"))
            assertEquals(0, draft.getJSONArray("messages").length())
        }
    }

    @Test fun firstAcceptedMessagePublishesChatOnceIncludingAttachmentOnlySend() {
        ChatDatabase(context).use { db ->
            db.ensureChat("text")
            db.ensureChat("attachment")
            db.ensureChat("unsent")
            assertTrue(db.listChatSummaries().isEmpty())
            val text = JSONObject().put("role", "user").put("text", "Hello")
            val attachment = JSONObject().put("role", "user").put("text", "")
                .put("file_paths", JSONArray().put("/local/notes.txt"))
            repeat(2) {
                db.upsertCurrentChat("text", "Новый чат", null, null, listOf(text))
                db.upsertCurrentChat("attachment", "Новый чат", null, null, listOf(attachment))
            }
            val summaries = db.listChatSummaries()
            assertEquals(2, summaries.size)
            assertEquals(setOf("text", "attachment"), summaries.map { it.getString("id") }.toSet())
            assertTrue(summaries.none { it.has("messages") })
            assertNotNull(db.getChat("unsent"))
        }
        ChatDatabase(context).use { db ->
            assertEquals(setOf("text", "attachment"), db.listChatSummaries().map { it.getString("id") }.toSet())
        }
    }

    @Test fun renamedActiveResponseAndAccountedChatsRemainPublishedWithoutMessages() {
        ChatDatabase(context).use { db ->
            for (id in listOf("renamed", "active", "response", "accounted", "draft")) db.ensureChat(id)
            db.upsertCurrentChat("renamed", "Research", null, null, emptyList())
            db.upsertCurrentChat("response", "Новый чат", "resp-existing", null, emptyList())
            db.setActiveResponse("active", null, "request-active", "", "starting", 123L,
                "high", "high", false, 0, 0, true)
            assertTrue(db.startRequestAccounting("accounted", "request-accounted", "openai", "model", "openai_api_key"))
            db.markRequestAccountingStopped("accounted", "request-accounted")
        }
        ChatDatabase(context).use { db ->
            assertEquals(setOf("renamed", "active", "response", "accounted"),
                db.listChatSummaries().map { it.getString("id") }.toSet())
            assertNotNull(db.getChat("draft"))
        }
    }
}
