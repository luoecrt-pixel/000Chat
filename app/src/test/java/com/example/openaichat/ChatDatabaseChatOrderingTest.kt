package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
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
class ChatDatabaseChatOrderingTest {
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

    private fun user(request: String, text: String = request) = JSONObject()
        .put("role", "user").put("request_id", request).put("text", text)

    private fun seed(db: ChatDatabase) {
        db.upsertCurrentChat("older", "Older", "response-existing", "high", listOf(user("old-request")))
        db.upsertCurrentChat("newer", "Newer", null, null, listOf(user("new-request")))
        // Earlier than the clock epoch, including Robolectric's possible zero wall clock.
        timestamp(db, "older", -200L)
        timestamp(db, "newer", -100L)
    }

    private fun timestamp(db: ChatDatabase, chatId: String, value: Long) {
        db.writableDatabase.execSQL("UPDATE chats SET updated_at=? WHERE id=?", arrayOf(value, chatId))
    }

    private fun timestamp(db: ChatDatabase, chatId: String): Long = db.readableDatabase.rawQuery(
        "SELECT updated_at FROM chats WHERE id=?", arrayOf(chatId)
    ).use { c -> assertTrue(c.moveToFirst()); c.getLong(0) }

    private fun order(db: ChatDatabase) = db.listChatSummaries().map { it.getString("id") }

    @Test fun unchangedUiSnapshotsKeepModificationOrderAndDurableMetadataAcrossReopen() {
        ChatDatabase(context).use { db ->
            seed(db)
            // switchChat/newChat use this same persistCurrentChat database seam.
            repeat(3) {
                assertTrue(db.upsertUiSnapshotPreservingTail("older", "Older", "stale-response", "low", listOf(user("old-request"))))
                assertTrue(db.upsertUiSnapshotPreservingTail("newer", "Newer", null, null, listOf(user("new-request"))))
            }
            assertEquals(listOf("newer", "older"), order(db))
            assertEquals(-200L, timestamp(db, "older"))
            assertEquals(-100L, timestamp(db, "newer"))
        }
        ChatDatabase(context).use { db ->
            assertEquals(listOf("newer", "older"), order(db))
            assertEquals("response-existing", db.getChat("older")!!.getString("response_id"))
            assertEquals("high", db.getChat("older")!!.getString("baseline_effort"))
        }
    }

    @Test fun acceptedNewUserMovesChatOnceAndDuplicateSnapshotDoesNotMoveItAgain() {
        ChatDatabase(context).use { db ->
            seed(db)
            val sent = listOf(user("old-request"), user("next-request", "Actual new message"))
            assertTrue(db.upsertUiSnapshotPreservingTail("older", "Older", null, null, sent))
            assertEquals(listOf("older", "newer"), order(db))
            assertTrue(timestamp(db, "older") > -100L)
            timestamp(db, "older", -50L)
            assertTrue(db.upsertUiSnapshotPreservingTail("older", "Older", null, null, sent))
            assertEquals(-50L, timestamp(db, "older"))
            val messages = db.getChat("older")!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("Actual new message", messages.getJSONObject(1).getString("text"))
        }
    }

    @Test fun workerCompletionMovesChatButStaleAndUnchangedUiCannotReorderOrEraseItsTail() {
        ChatDatabase(context).use { db ->
            seed(db)
            db.setActiveResponse("older", null, "old-request", "", "starting", 1L,
                "high", "high", false, 0, 0, true)
            timestamp(db, "older", -200L)
            val assistant = JSONObject().put("role", "assistant").put("request_id", "old-request")
                .put("text", "Worker result").put("status", "completed")
            assertTrue(db.finalizeCurrentChatIfActiveRequest("older", "Older", "response-final", "high",
                listOf(assistant), "old-request"))
            assertEquals(listOf("older", "newer"), order(db))
            timestamp(db, "older", -200L)
            assertFalse(db.upsertUiSnapshotPreservingTail("older", "Older", null, null, listOf(user("old-request"))))
            val staleAssistant = JSONObject(assistant.toString()).put("text", "Old visible partial")
            assertTrue(db.upsertUiSnapshotPreservingTail("older", "Older", null, null,
                listOf(user("old-request"), staleAssistant)))
            assertEquals(-200L, timestamp(db, "older"))
            assertEquals(listOf("newer", "older"), order(db))
            assertEquals("Worker result", db.getChat("older")!!.getJSONArray("messages").getJSONObject(1).getString("text"))
            assertEquals("response-final", db.getChat("older")!!.getString("response_id"))
        }
    }

    @Test fun ownedProgressChangesOrderWhileRejectedCheckpointAndUnchangedUiDoNot() {
        ChatDatabase(context).use { db ->
            seed(db)
            db.setActiveResponse("older", "response-active", "old-request", "", "in_progress", 1L,
                "high", "high", false, 0, 0, true)
            timestamp(db, "older", -200L)
            db.updateActiveResponse("older", text = "Received content", status = "in_progress",
                lastSequenceNumber = 5L, expectedRequestId = "old-request")
            assertEquals(listOf("older", "newer"), order(db))
            timestamp(db, "older", -200L)
            db.updateActiveResponse("older", text = "Stale content", status = "in_progress",
                lastSequenceNumber = 4L, expectedRequestId = "old-request")
            db.updateActiveResponse("older", text = "Foreign content", status = "in_progress",
                lastSequenceNumber = 6L, expectedRequestId = "replacement-request")
            assertTrue(db.upsertUiSnapshotPreservingTail("older", "Older", null, null, listOf(user("old-request"))))
            assertEquals(-200L, timestamp(db, "older"))
            assertEquals(listOf("newer", "older"), order(db))
            assertEquals("Received content", db.getActiveResponse("older")!!.getString("text"))
            assertEquals(5L, db.getActiveResponse("older")!!.getLong("last_sequence_number"))
        }
    }

    @Test fun actualRenameMovesChatButSavingSameTitleDoesNot() {
        ChatDatabase(context).use { db ->
            seed(db)
            db.renameChat("older", "Older")
            assertEquals(-200L, timestamp(db, "older"))
            db.renameChat("older", "Changed title")
            assertEquals(listOf("older", "newer"), order(db))
            timestamp(db, "older", -50L)
            db.renameChat("older", "Changed title")
            assertEquals(-50L, timestamp(db, "older"))
            assertEquals("Changed title", db.getChatTitle("older"))
        }
    }
}
