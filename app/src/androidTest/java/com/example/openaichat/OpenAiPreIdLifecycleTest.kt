package com.example.openaichat

import android.os.SystemClock
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.Call
import okhttp3.MediaType
import okhttp3.OkHttpClient
import okhttp3.ResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class OpenAiPreIdLifecycleTest {
    @Test fun liveCreateOlderThanTwoMinutesKeepsOwnerAcrossStopStartThenCommitsTerminal() =
        assertDelayedCreated(Action.STOP_START)

    @Test fun receivedCreatedAfterStopCancelsOriginalAccountWithoutRevivingUiOrCreatingAgain() =
        assertDelayedCreated(Action.STOP)

    @Test fun receivedCreatedAfterDeleteCancelsOriginalAccountWithoutRestoringDeletedChat() =
        assertDelayedCreated(Action.DELETE)

    @Test fun receivedCreatedAfterSecondSendCannotFinishOrClearReplacementOwner() =
        assertDelayedCreated(Action.REPLACE)

    private enum class Action { STOP_START, STOP, DELETE, REPLACE }

    private fun assertDelayedCreated(action: Action) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app", 0)
        val settings = listOf("provider", "model", "reasoning", "text_format", "background_responses",
            "system_prompt", "instructions", "file_analysis_tools", "web", "file_search", "code_interpreter", "image_generation")
        val previous = settings.associateWith { prefs.all[it] }
        val previousKey = SecureStore(context).getApiKey()
        val previousChat = prefs.getString("current_chat", null)
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val trusted = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val replacementEntered = CountDownLatch(1)
        val replacementRelease = CountDownLatch(1)
        val createNumber = AtomicInteger()
        val responseId = "resp_preid_fixture"
        val replacementResponseId = "resp_preid_replacement"
        fun completedStream(id: String, text: String): String {
            val terminal = JSONObject().put("id", id).put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
            return "data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
                .put("response", JSONObject().put("id", id)) + "\n\n" +
                "data: " + JSONObject().put("type", "response.completed").put("sequence_number", 1)
                    .put("response", terminal) + "\n\n"
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(completedStream(responseId, "Canonical result")))
        if (action == Action.REPLACE) server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(completedStream(replacementResponseId, "Replacement result")))
        if (action != Action.STOP_START) server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("id", responseId).put("status", "cancelled")
                .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31)).toString()))
        val createClient = trusted.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build())
            val body = response.body!!
            val number = createNumber.getAndIncrement()
            val receivedGate = if (number == 0) entered else replacementEntered
            val releaseGate = if (number == 0) release else replacementRelease
            val gated = object : ForwardingSource(body.source()) {
                private var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val received = Buffer()
                    val count = super.read(received, byteCount)
                    if (first && count > 0L) {
                        first = false
                        receivedGate.countDown()
                        if (!releaseGate.await(30, TimeUnit.SECONDS)) throw IOException("Fixture gate was not released")
                    }
                    if (count > 0L) sink.write(received, count)
                    return count
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = body.contentLength()
                override fun source(): BufferedSource = gated
            }).build()
        }.build()
        val cancelClient = trusted.newBuilder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        var chatId = ""
        var requestId = ""
        var replacementRequestId = ""
        var replacementChatId = ""
        var replacementCall: Call? = null
        var replacementGeneration = -1L
        var replacementAssistantId: String? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = activity.prefs.getString("current_chat", "")!!
                    prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
                        .putString("reasoning", "high").putString("text_format", "text")
                        .putString("system_prompt", "").putString("instructions", "")
                        .putBoolean("background_responses", true).putBoolean("file_analysis_tools", false)
                        .putBoolean("web", false).putBoolean("file_search", false)
                        .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit()
                    SecureStore(context).setApiKey("sk-only-local-pre-id")
                    field("client").set(activity, createClient)
                    field("pollingClient").set(activity, cancelClient)
                    (field("input").get(activity) as EditText).setText("Pre-ID prompt")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                val create = server.takeRequest(20, TimeUnit.SECONDS)!!
                assertEquals("POST", create.method)
                assertTrue(JSONObject(create.body.readUtf8()).getBoolean("background"))
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                ChatDatabase(context).use { db ->
                    val active = db.getActiveResponse(chatId)!!
                    requestId = active.getString("request_id")
                    assertTrue(JsonProtocol.string(active, "response_id").isBlank())
                    assertTrue(OpenAiCreateCallOwners.hasLiveCall(chatId, requestId))
                    db.setActiveResponse(chatId, null, requestId, "", "starting",
                        System.currentTimeMillis() - 180_000L, "high", "high", false, 13, 0, true)
                }
                when (action) {
                    Action.STOP_START -> {
                        scenario.moveToState(Lifecycle.State.CREATED)
                        scenario.moveToState(Lifecycle.State.RESUMED)
                        ChatDatabase(context).use { db ->
                            assertEquals("A live pre-ID call is not an orphan after 120s", requestId,
                                db.getActiveResponse(chatId)!!.getString("request_id"))
                        }
                    }
                    Action.DELETE -> {
                        scenario.onActivity { activity ->
                            activity.deleteChat(chatId)
                            SecureStore(context).setApiKey("sk-only-local-new-account")
                            replacementChatId = activity.prefs.getString("current_chat", "")!!
                            assertNotEquals(chatId, replacementChatId)
                            (field("input").get(activity) as EditText).setText("Deleted-chat draft")
                        }
                        ChatDatabase(context).use { assertNull(it.getChat(chatId)) }
                    }
                    Action.STOP, Action.REPLACE -> {
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("stopCurrentResponse")
                                .apply { isAccessible = true }.invoke(activity)
                            SecureStore(context).setApiKey("sk-only-local-new-account")
                            if (action == Action.REPLACE) {
                                (field("input").get(activity) as EditText).setText("Replacement prompt")
                                MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                                    .apply { isAccessible = true }.invoke(activity, false)
                                replacementCall = field("currentCall").get(activity) as Call
                                val state = (field("activeResponseState").get(activity) as ActiveResponseStateStore).state
                                replacementGeneration = state.generation
                                replacementAssistantId = state.assistantItemId
                            }
                        }
                        if (action == Action.REPLACE) {
                            val replacementCreate = server.takeRequest(20, TimeUnit.SECONDS)!!
                            assertEquals("POST", replacementCreate.method)
                            assertEquals("Bearer sk-only-local-new-account", replacementCreate.getHeader("Authorization"))
                            assertTrue(replacementEntered.await(20, TimeUnit.SECONDS))
                            ChatDatabase(context).use { db ->
                                replacementRequestId = db.getActiveResponse(chatId)!!.getString("request_id")
                                assertNotEquals(requestId, replacementRequestId)
                            }
                        } else ChatDatabase(context).use { assertNull(it.getActiveResponse(chatId)) }
                    }
                }
                release.countDown()
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (OpenAiCreateCallOwners.isRegistered(chatId, requestId) && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20)
                assertFalse("The original callback must release its registry owner",
                    OpenAiCreateCallOwners.isRegistered(chatId, requestId))
                if (action != Action.STOP_START) {
                    val cancel = server.takeRequest(20, TimeUnit.SECONDS)!!
                    assertEquals("POST", cancel.method)
                    assertEquals("/v1/responses/$responseId/cancel", cancel.path)
                    assertEquals("Bearer sk-only-local-pre-id", cancel.getHeader("Authorization"))
                }
                // The old callback has exited: assert replacement ownership before
                // allowing its independently paid response to finish.
                if (action == Action.REPLACE) {
                    InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                    ChatDatabase(context).use { db ->
                        val active = db.getActiveResponse(chatId)!!
                        assertEquals(replacementRequestId, active.getString("request_id"))
                        assertTrue(JsonProtocol.string(active, "response_id").isBlank())
                        val messages = db.getChat(chatId)!!.getJSONArray("messages")
                        assertEquals(1, messages.length())
                        assertEquals("Replacement prompt", messages.getJSONObject(0).getString("text"))
                    }
                    scenario.onActivity { activity ->
                        val state = (field("activeResponseState").get(activity) as ActiveResponseStateStore).state
                        assertEquals(replacementGeneration, state.generation)
                        assertEquals(replacementAssistantId, state.assistantItemId)
                        assertSame(replacementCall, field("currentCall").get(activity))
                        assertFalse(replacementCall!!.isCanceled())
                        assertFalse("Old completion must not enable Send for the busy replacement", activity.send.isEnabled)
                    }
                    replacementRelease.countDown()
                }
                // Wait for the final owned transaction/reconciliation, not just HTTP.
                var finished = false
                val commitDeadline = SystemClock.elapsedRealtime() + 20_000L
                while (SystemClock.elapsedRealtime() < commitDeadline) {
                    finished = ChatDatabase(context).use { db ->
                        when (action) {
                            Action.DELETE -> db.getChat(chatId) == null
                            Action.STOP -> db.listRequestAccounting(chatId).single().optString("response_id") == responseId
                            else -> db.getChat(chatId)!!.getJSONArray("messages").length() == 2 &&
                                db.getActiveResponse(chatId) == null
                        }
                    }
                    if (finished) break
                    SystemClock.sleep(20)
                }
                assertTrue("Original/replacement request did not finish its owned transaction", finished)
                if (action == Action.REPLACE) {
                    while (OpenAiCreateCallOwners.isRegistered(chatId, replacementRequestId) &&
                        SystemClock.elapsedRealtime() < commitDeadline) SystemClock.sleep(20)
                    assertFalse(OpenAiCreateCallOwners.isRegistered(chatId, replacementRequestId))
                }
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                ChatDatabase(context).use { db ->
                    assertNull(db.getActiveResponse(chatId))
                    if (action == Action.DELETE) {
                        assertNull("A received ID cannot restore a deleted chat", db.getChat(chatId))
                        assertTrue(db.listRequestAccounting(chatId).isEmpty())
                        assertEquals(0, db.getChat(replacementChatId)!!.getJSONArray("messages").length())
                    } else {
                        val messages = db.getChat(chatId)!!.getJSONArray("messages")
                        if (action == Action.STOP || action == Action.REPLACE) {
                            assertEquals(if (action == Action.STOP) 0 else 2, messages.length())
                            val accounting = db.listRequestAccounting(chatId).single {
                                it.getString("local_request_id") == requestId
                            }
                            assertEquals("stopped", accounting.getString("local_status"))
                            assertEquals(responseId, accounting.getString("response_id"))
                            assertEquals("cancelled", accounting.getString("provider_status"))
                            assertEquals(17L, accounting.getLong("input_tokens"))
                            assertEquals(31L, accounting.getLong("output_tokens"))
                            assertEquals("unknown", accounting.getString("cost_kind"))
                            assertFalse(accounting.has("cost_usd"))
                        }
                        if (action == Action.STOP_START || action == Action.REPLACE) {
                            assertEquals(if (action == Action.REPLACE) "Replacement result" else "Canonical result",
                                messages.getJSONObject(1).getString("text"))
                            assertEquals(if (action == Action.REPLACE) replacementResponseId else responseId,
                                messages.getJSONObject(1).getString("response_id"))
                        }
                    }
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.send.isEnabled)
                    if (action == Action.STOP) assertEquals("Pre-ID prompt", (field("input").get(activity) as EditText).text.toString())
                    if (action == Action.DELETE) {
                        assertEquals(replacementChatId, activity.prefs.getString("current_chat", ""))
                        assertEquals("Deleted-chat draft", (field("input").get(activity) as EditText).text.toString())
                        assertEquals(0, (field("messageAdapter").get(activity) as ChatMessageAdapter).itemCount)
                    }
                }
                assertEquals("Only explicitly requested Creates and old-response cancellation",
                    when (action) { Action.STOP_START -> 1; Action.REPLACE -> 3; else -> 2 }, server.requestCount)
            }
        } finally {
            release.countDown()
            replacementRelease.countDown()
            SecureStore(context).setApiKey(previousKey)
            prefs.edit().apply {
                previous.forEach { (key, value) -> when (value) {
                    null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                } }
                if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat)
            }.commit()
            if (chatId.isNotBlank()) {
                ChatDatabase(context).use { it.deleteChat(chatId) }
                listOf(requestId, replacementRequestId).filter { it.isNotBlank() }.forEach { id ->
                    OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, id).forEach { it.delete() }
                }
                if (replacementChatId.isNotBlank()) ChatDatabase(context).use { it.deleteChat(replacementChatId) }
            }
            server.shutdown()
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
