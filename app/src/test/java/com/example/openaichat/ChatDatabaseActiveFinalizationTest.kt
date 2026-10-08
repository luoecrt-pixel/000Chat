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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ChatDatabaseActiveFinalizationTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun prepare(db: ChatDatabase, chatId: String = "chat", requestId: String = "local_1") {
        db.ensureChat(chatId, "Original")
        db.upsertCurrentChat(chatId, "Original", null, "high", listOf(
            JSONObject().put("role", "user").put("text", "original")
                .put("request_id", requestId).put("requested_model", "gpt-test")
        ))
        db.setActiveResponse(
            chatId = chatId, responseId = "resp_1", requestId = requestId,
            text = "partial", status = "in_progress",
            startedAt = 123L, effortPref = "high", effectiveEffort = "high",
            autoSelected = false, promptChars = 8, attachments = 0, backgroundEnabled = true
        )
        assertTrue(db.startRequestAccounting(chatId, requestId, "openai", "gpt-test", "openai_api_key"))
    }

    private fun finalizedMessages(requestId: String, content: String): List<JSONObject> =
        listOf(
            JSONObject().put("role", "user").put("text", "original").put("request_id", requestId),
            JSONObject().put("role", "assistant").put("text", content)
                .put("request_id", requestId).put("response_id", "resp_1")
                .put("status", "completed")
        )

    private fun commitOnce(db: ChatDatabase, chat: String = "chat", request: String = "local_1", text: String = "answer"): Boolean {
        val committed = db.finalizeCurrentChatIfActiveRequest(
            chat, "Original", "resp_1", "high",
            finalizedMessages(request, text), request
        )
        if (committed) {
            db.recordRequestAccounting(chat, request, "completed",
                responseId = "resp_1", usage = JSONObject().put("input_tokens", 15))
            db.recordRequestEvent(
                chat, "request_completed", request, "openai", "gpt-test", "gpt-test",
                ModelCatalog.Match.EXACT, "completed", inputTokens = 15
            )
        }
        return committed
    }

    @Test fun duplicateRecoveryCallbackOnlyOneWinnerAndOneEvent() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(commitOnce(db, text = "worker answer"))
            assertFalse(commitOnce(db, text = "activity duplicate"))
            assertNull(db.getActiveResponse("chat"))
            val messages = db.getChat("chat")!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            assertEquals("worker answer", messages.getJSONObject(1).getString("text"))
            assertEquals(1, db.listRequestEvents("chat").size)
            assertEquals(1, db.listRequestAccounting("chat").size)
            assertEquals("completed", db.listRequestAccounting("chat").single().getString("local_status"))
        }
    }

    @Test fun stopAndFinalizationAreMutuallyExclusive() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(db.stopActiveRequestOnce("chat", "local_1"))
            assertNull(db.getActiveResponse("chat"))
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_1"))
            assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
            assertEquals("stopped", db.listRequestAccounting("chat").single().getString("local_status"))
            assertTrue(db.listRequestEvents("chat").isEmpty())
        }
    }

    @Test fun replacedOrDeletedChatCannotResurrectOldRequest() {
        ChatDatabase(context).use { db ->
            prepare(db)
            db.setActiveResponse("chat", "resp_2", "local_2", "", "in_progress", 124L,
                "medium", "medium", false, 0, 0, true)
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_1"))
            assertEquals("local_2", db.getActiveResponse("chat")!!.getString("request_id"))
            db.deleteChat("chat")
            assertFalse(commitOnce(db))
            assertFalse(db.stopActiveRequestOnce("chat", "local_2"))
            assertNull(db.getChat("chat"))
        }
    }

    @Test fun fatalWorkerRecoveryClaimsBeforeActivityFinalization() {
        ChatDatabase(context).use { db ->
            prepare(db)
            assertTrue(BackgroundRequestFailureRecorder.persist(
                db, "chat", "local_1", "resp_1", "failed", "recovery_auth_401"
            ))
            assertFalse(commitOnce(db))
            assertFalse(BackgroundRequestFailureRecorder.persist(
                db, "chat", "local_1", "resp_1", "failed", "duplicate"
            ))
            assertEquals(1, db.listRequestEvents("chat").size)
            assertEquals(1, db.listRequestAccounting("chat").size)
            assertEquals("partial", db.getChat("chat")!!.getJSONArray("messages")
                .getJSONObject(1).getString("text"))
        }
    }

    @Test fun simultaneousWorkerActivityClaimsAreSerialized() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val first = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db, text = "worker")
                }
                val second = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db, text = "activity")
                }
                start.countDown()
                val winners = listOf(first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS))
                assertEquals(1, winners.count { it })
                assertEquals(1, db.listRequestEvents("chat").size)
                assertEquals(1, db.listRequestAccounting("chat").size)
                assertEquals(2, db.getChat("chat")!!.getJSONArray("messages").length())
                assertNull(db.getActiveResponse("chat"))
            } finally {
                pool.shutdownNow()
            }
        }
    }

    @Test fun stopRacingTerminalOnlyOneOwnerCanFinish() {
        ChatDatabase(context).use { db ->
            prepare(db)
            val start = CountDownLatch(1)
            val pool = Executors.newFixedThreadPool(2)
            try {
                val stop = pool.submit<Boolean> {
                    start.await()
                    db.stopActiveRequestOnce("chat", "local_1")
                }
                val finish = pool.submit<Boolean> {
                    start.await()
                    commitOnce(db)
                }
                start.countDown()
                val stopWon = stop.get(15, TimeUnit.SECONDS)
                val finishWon = finish.get(15, TimeUnit.SECONDS)
                assertTrue(stopWon.xor(finishWon))
                assertNull(db.getActiveResponse("chat"))
                assertEquals(if (finishWon) 2 else 1, db.getChat("chat")!!.getJSONArray("messages").length())
                assertEquals(if (finishWon) 1 else 0, db.listRequestEvents("chat").size)
                assertEquals(if (stopWon) "stopped" else "completed",
                    db.listRequestAccounting("chat").single().getString("local_status"))
            } finally {
                pool.shutdownNow()
            }
        }
    }
}
