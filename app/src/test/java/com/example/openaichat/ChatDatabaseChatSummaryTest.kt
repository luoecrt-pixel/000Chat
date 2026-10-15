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
    }

    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
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
