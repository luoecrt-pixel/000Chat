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
class BackgroundRequestFailureRecorderTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun prepared(db: ChatDatabase, chatId: String = "chat"): String {
        val requestId = "local_worker_request"
        db.ensureChat(chatId)
        db.upsertCurrentChat(chatId, "Worker chat", null, "high", listOf(
            JSONObject().put("role", "user").put("text", "original prompt")
                .put("request_id", requestId).put("provider", "openai")
                .put("requested_model", "test-actual-model")
        ))
        db.setActiveResponse(
            chatId = chatId, responseId = "resp_x", requestId = requestId,
            text = "partial text", status = "in_progress",
            startedAt = System.currentTimeMillis(), effortPref = "high",
            effectiveEffort = "high", autoSelected = false,
            promptChars = 15, attachments = 0, backgroundEnabled = true
        )
        assertTrue(db.startRequestAccounting(chatId, requestId, "openai",
            "test-actual-model", "openai_api_key"))
        return requestId
    }

    @Test fun timeoutAuthGoneAndFatalRetainPartialAndReportedOutcome() {
        for ((status, error) in listOf(
            "failed" to "recovery_timeout",
            "failed" to "recovery_auth_401",
            "unknown" to "response_gone_404",
            "failed" to "recovery_http_418"
        )) {
            val chatId = "chat_${status}_${error}"
            ChatDatabase(context).use { db ->
                val id = prepared(db, chatId)
                assertTrue(BackgroundRequestFailureRecorder.persist(
                    db, chatId, id, "resp_x", status, error
                ))
                db.clearActiveResponse(chatId, expectedRequestId = id)
                val chat = db.getChat(chatId)!!
                val assistant = chat.getJSONArray("messages").getJSONObject(1)
                assertEquals("partial text", assistant.getString("text"))
                assertEquals(status, assistant.getString("status"))
                assertEquals(error, assistant.getString("error"))
                assertEquals("test-actual-model", assistant.getString("requested_model"))
                val event = db.listRequestEvents(chatId).single()
                assertEquals("openai", event.getString("provider"))
                assertEquals("test-actual-model", event.getString("requested_model"))
                assertEquals(error, event.getString("error"))
                val row = db.listRequestAccounting(chatId).single()
                assertEquals(status, row.getString("local_status"))
                assertEquals("unknown", row.getString("cost_kind"))
                assertFalse(row.has("cost_usd"))
                assertFalse(BackgroundRequestFailureRecorder.persist(
                    db, chatId, id, "resp_x", status, error
                ))
                assertEquals(1, db.listRequestEvents(chatId).size)
            }
        }
    }

    @Test fun stoppedOrDeletedRequestMustNeverRegenerateAssistantBubble() {
        ChatDatabase(context).use { db ->
            val stopped = prepared(db, "stopped")
            db.markRequestAccountingStopped("stopped", stopped)
            db.clearActiveResponse("stopped", stopped)
            assertFalse(BackgroundRequestFailureRecorder.persist(
                db, "stopped", stopped, "resp_x", "failed", "late_callback"))
            assertEquals(1, db.getChat("stopped")!!.getJSONArray("messages").length())
            assertEquals("stopped", db.listRequestAccounting("stopped").single().getString("local_status"))

            val deleted = prepared(db, "deleted")
            db.deleteChat("deleted")
            assertFalse(BackgroundRequestFailureRecorder.persist(
                db, "deleted", deleted, "resp_x", "failed", "late_callback"))
            assertTrue(db.listRequestAccounting("deleted").isEmpty())
        }
    }
}
