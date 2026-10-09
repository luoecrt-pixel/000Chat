package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
class OpenAiCancelReconcilerTest {
    private lateinit var context: Context

    @Before fun before() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    @After fun after() {
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }

    private fun makeStopped(db: ChatDatabase, chat: String = "chat", request: String = "local_one",
                            response: String = "resp_one") {
        db.ensureChat(chat)
        db.upsertCurrentChat(chat, "Original", null, "high", listOf(
            JSONObject().put("role", "user").put("text", "prompt").put("request_id", request)
        ))
        db.setActiveResponse(chat, response, request, "partial", "in_progress",
            123L, "high", "high", false, 6, 0, true)
        assertTrue(db.startRequestAccounting(chat, request, "openai", "gpt-model", "openai_api_key"))
        assertTrue(db.stopActiveRequestOnce(chat, request))
        assertNull(db.getActiveResponse(chat))
        val snapshot = db.listRequestAccounting(chat).single()
        assertEquals("stopped", snapshot.getString("local_status"))
        assertEquals(response, snapshot.getString("response_id"))
    }

    private fun cancel(server: MockWebServer, db: ChatDatabase, chat: String = "chat",
                       request: String = "local_one", response: String = "resp_one") =
        OpenAiCancelReconciler(OkHttpClient(), server.url("/v1/")).reconcile(response, "test-secret") {
            db.reconcileStoppedOpenAiResponse(chat, request, response, it)
        }

    @Test fun cancelledBodyWithExplicitZeroUsageSurvivesSqliteReopenNoBillingZero() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"cancelled","usage":{"input_tokens":0,"output_tokens":0,"input_tokens_details":{"cached_tokens":0}}}"""))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(200, result.cancelStatus)
                assertEquals(0, result.retrievals)
                assertEquals(1, result.acceptedSnapshots)
                assertEquals("cancelled", result.terminalStatus)
                assertTrue(result.hasUsage)
            }
            ChatDatabase(context).use { db ->
                val row = db.listRequestAccounting("chat").single()
                assertEquals("stopped", row.getString("local_status"))
                assertEquals("cancelled", row.getString("provider_status"))
                assertEquals(0L, row.getLong("input_tokens"))
                assertEquals(0L, row.getLong("output_tokens"))
                assertEquals(0L, row.getLong("cached_tokens"))
                assertEquals("unknown", row.getString("cost_kind"))
                assertFalse(row.has("cost_usd"))
                assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
                assertEquals(0, db.requestAccountingTotals().getInt("known_cost_requests"))
                assertEquals(1, db.requestAccountingTotals().getInt("unknown_cost_requests"))
                assertTrue(db.listRequestEvents("chat").isEmpty())
            }
            val req = server.takeRequest()
            assertEquals("POST", req.method)
            assertEquals("/v1/responses/resp_one/cancel", req.path)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun acceptedCancelAndTwoBoundedGetsCaptureLateUsageButNeverOutput() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202)
                .setBody("""{"id":"resp_one","status":"in_progress"}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"in_progress"}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"completed","usage":{"input_tokens":51,"output_tokens":6},"output":[{"type":"message","content":[{"type":"output_text","text":"must not appear"}]}]}"""))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(2, result.retrievals)
                assertEquals(3, result.acceptedSnapshots)
                assertEquals("completed", result.terminalStatus)
                assertTrue(result.hasUsage)
                val same = JSONObject("""{"id":"resp_one","status":"completed","usage":{"input_tokens":51,"output_tokens":6}}""")
                assertTrue(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one", same))
                assertEquals(1, db.listRequestAccounting("chat").size)
                assertEquals(1, db.getChat("chat")!!.getJSONArray("messages").length())
                assertTrue(db.listRequestEvents("chat").isEmpty())
                val row = db.listRequestAccounting("chat").single()
                assertEquals("completed", row.getString("provider_status"))
                assertEquals("stopped", row.getString("local_status"))
                assertEquals(51L, row.getLong("input_tokens"))
                assertEquals(6L, row.getLong("output_tokens"))
                assertFalse(row.has("cost_usd"))
            }
            assertEquals(3, server.requestCount)
            assertEquals("POST", server.takeRequest().method)
            repeat(2) {
                val req = server.takeRequest()
                assertEquals("GET", req.method)
                assertEquals("/v1/responses/resp_one", req.path)
            }
        }
    }

    @Test fun notFoundCancelCanRetrieveTerminalResponseWithUsage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("{}"))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"incomplete","usage":{"input_tokens":7}}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(404, result.cancelStatus)
                assertEquals(2, result.retrievals)
                val row = db.listRequestAccounting("chat").single()
                assertEquals("incomplete", row.getString("provider_status"))
                assertEquals(7L, row.getLong("input_tokens"))
                assertFalse(row.has("output_tokens"))
                assertEquals("unknown", row.getString("cost_kind"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun intermediateUsageCannotPretendToBeTerminalUsage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"cancelled"}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"in_progress","usage":{"input_tokens":3,"output_tokens":1}}"""))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"cancelled","usage":{"input_tokens":12,"output_tokens":4}}"""))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(2, result.retrievals)
                assertEquals(3, result.acceptedSnapshots)
                assertEquals("cancelled", result.terminalStatus)
                val row = db.listRequestAccounting("chat").single()
                assertEquals("stopped", row.getString("local_status"))
                assertEquals(12L, row.getLong("input_tokens"))
                assertEquals(4L, row.getLong("output_tokens"))
                assertEquals("unknown", row.getString("cost_kind"))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun terminalMissingUsageNeverBecomesZeroAndGetFailureIsBounded() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"cancelled"}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(1, result.retrievals)
                val row = db.listRequestAccounting("chat").single()
                assertEquals("cancelled", row.getString("provider_status"))
                assertFalse(row.has("input_tokens"))
                assertFalse(row.has("output_tokens"))
                assertFalse(row.has("cost_usd"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun acceptedEmptyCancelBodyUsesSingleRetrieve() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(202))
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_one","status":"cancelled","usage":{"input_tokens":1,"output_tokens":0}}"""))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(202, result.cancelStatus)
                assertEquals(1, result.retrievals)
                assertEquals("cancelled", result.terminalStatus)
                assertEquals(1L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
                assertEquals("unknown", db.listRequestAccounting("chat").single().getString("cost_kind"))
            }
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun authFailureDoesNotRetrieveOrUpdateLedger() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid"}}"""))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(401, result.cancelStatus)
                assertEquals(0, result.retrievals)
                val row = db.listRequestAccounting("chat").single()
                assertFalse(row.has("provider_status"))
                assertFalse(row.has("input_tokens"))
                assertEquals("stopped", row.getString("local_status"))
            }
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun mismatchedResponseAndMalformedBodyCannotAttributeUsage() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(200)
                .setBody("""{"id":"resp_other","status":"completed","usage":{"input_tokens":999}}"""))
            server.enqueue(MockResponse().setResponseCode(200).setBody("not-json"))
            server.enqueue(MockResponse().setResponseCode(404))
            ChatDatabase(context).use { db ->
                makeStopped(db)
                val result = cancel(server, db)
                assertEquals(0, result.acceptedSnapshots)
                assertFalse(result.hasUsage)
                val row = db.listRequestAccounting("chat").single()
                assertFalse(row.has("input_tokens"))
                assertFalse(row.has("provider_status"))
                assertFalse(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one",
                    JSONObject("""{"id":"resp_other","status":"completed","usage":{"input_tokens":4}}""")))
            }
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun afterDeletionOrRecreatedSameChatTheOldResponseIsNotAccepted() {
        ChatDatabase(context).use { db ->
            makeStopped(db)
            val late = JSONObject("""{"id":"resp_one","status":"completed","usage":{"input_tokens":20}}""")
            db.deleteChat("chat")
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one", late))
            db.ensureChat("chat")
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one", late))
            db.startRequestAccounting("chat", "new_request", "openai", "m", "openai_api_key")
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "new_request", "resp_one", late))
        }
    }

    @Test fun onlyMatchingStoppedOpenAiRecordCanReceiveCancelledSnapshot() {
        ChatDatabase(context).use { db ->
            makeStopped(db)
            val result = JSONObject("""{"id":"resp_one","status":"cancelled","usage":{"input_tokens":0}}""")
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "wrong_local", "resp_one", result))
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_other", result))
            assertFalse(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one",
                JSONObject("""{"id":"resp_one","status":"nonsense","usage":{"input_tokens":0}}""")))
            assertTrue(db.reconcileStoppedOpenAiResponse("chat", "local_one", "resp_one", result))
            assertEquals(0L, db.listRequestAccounting("chat").single().getLong("input_tokens"))
        }
    }

    @Test fun rejectsPathInjectionResponseIdentifiers() {
        val server = MockWebServer()
        server.start()
        try {
            assertThrows(IllegalArgumentException::class.java) {
                OpenAiCancelReconciler(OkHttpClient(), server.url("/v1/"))
                    .reconcile("resp_x/../../other", "test-secret") { }
            }
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
