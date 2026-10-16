package com.example.openaichat

import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Actual Activity Send + production bridge/SQLite/HTTP seams; no live API or billing. */
@RunWith(AndroidJUnit4::class)
class OpenAiForegroundToolRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var previous: Map<String, Any?>
    private var previousKey = ""
    private val chats = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val syntheticKey = "sk-synthetic-package-j-recovery"
    private val settings = listOf("provider", "model", "current_chat", "background_responses", "response_notifications",
        "background_responses_migrated_v25", "reasoning", "text_format", "system_prompt", "instructions",
        "file_analysis_tools", "code_interpreter", "file_search", "web", "image_generation", "openai_auto_compaction",
        "openai_auto_compaction_opt_in_migrated_v25")
    @Volatile private var beforeNetwork: ((okhttp3.Request) -> Unit)? = null

    @Before fun before() {
        previous = settings.associateWith { prefs.all[it] }
        previousKey = SecureStore(context).getApiKey()
        assertTrue(SecureStore(context).setApiKey(syntheticKey))
        prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("reasoning", "high").putString("text_format", "text")
            .putString("system_prompt", "").putString("instructions", "")
            .putBoolean("background_responses", false).putBoolean("background_responses_migrated_v25", true)
            .putBoolean("response_notifications", false).putBoolean("file_analysis_tools", false)
            .putBoolean("code_interpreter", false).putBoolean("file_search", false).putBoolean("web", false)
            .putBoolean("image_generation", false).putBoolean("openai_auto_compaction", false).commit()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain ->
                beforeNetwork?.invoke(chain.request())
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
        ResumableResponseStreamClient.transportForTests = http to server.url("/")
    }

    @After fun after() {
        beforeNetwork = null
        ResumableResponseStreamClient.transportForTests = null
        SecureStore(context).setApiKey(previousKey)
        ChatDatabase(context).use { db -> chats.forEach { db.deleteChat(it); prefs.edit().remove("composer_state_$it").commit() } }
        prefs.edit().apply { previous.forEach { (name, value) -> when (value) {
            is String -> putString(name, value); is Boolean -> putBoolean(name, value); else -> remove(name)
        } } }.commit()
        files.forEach { it.delete() }
        server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun field(activity: MainActivity, name: String): Any = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun call(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun source() = File(context.filesDir, "attachments/j-${UUID.randomUUID()}.txt").apply {
        parentFile!!.mkdirs(); writeText("needle in original source"); files.add(this)
    }.let { OpenRouterAttachmentStore.classifyLocalFile(context, it, it.name, "text/plain") }
    private fun toolResponse(fileId: String) = JSONObject().put("id", "resp_j_parent").put("status", "completed")
        .put("output", JSONArray().put(JSONObject().put("type", "function_call").put("id", "fc_j")
            .put("call_id", "call_j").put("name", "search_attached_file")
            .put("arguments", JSONObject().put("file_id", fileId).put("query", "needle").toString())))
    private fun finalResponse() = JSONObject().put("id", "resp_j_child").put("status", "completed").put("model", "gpt-6-astra")
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("id", "msg_j").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Recovered child")))))
    private fun sse(response: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
            .put("response", JSONObject().put("id", response.getString("id"))) + "\n\ndata: " +
            JSONObject().put("type", "response.completed").put("sequence_number", 1).put("response", response) + "\n\n")
    private fun queuedChild() = MockResponse().setBody(JSONObject().put("id", "resp_j_child").put("status", "queued").toString())
    private fun request() = server.takeRequest(15, TimeUnit.SECONDS) ?: error("Expected Responses wire request")
    private fun active(chatId: String) = ChatDatabase(context).use { it.getActiveResponse(chatId) }
    private fun assistant(chatId: String) = ChatDatabase(context).use { db -> db.getChat(chatId)?.optJSONArray("messages")?.let { all ->
        (0 until all.length()).map { all.getJSONObject(it) }.lastOrNull { it.optString("role") == "assistant" }
    } }
    private fun awaitCondition(message: String, timeoutMs: Long = 15_000L, condition: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < until) {
            if (condition()) return
            SystemClock.sleep(25L)
        }
        fail(message)
    }
    private fun send(activity: MainActivity, attachment: OpenRouterLocalAttachment): String {
        activity.newChat()
        val chatId = activity.currentChatIdForTesting().also(chats::add)
        MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
        @Suppress("UNCHECKED_CAST")
        (field(activity, "attachedOpenRouterAttachments") as MutableList<OpenRouterLocalAttachment>).add(attachment)
        (field(activity, "input") as EditText).setText("Inspect attached source")
        MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
        return chatId
    }
    private fun beginWaiting(scenario: ActivityScenario<MainActivity>, retryAfterSeconds: Int = 30): Pair<String, String> {
        val attachment = source()
        server.enqueue(sse(toolResponse(attachment.id))); server.enqueue(queuedChild())
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", retryAfterSeconds).setBody("temporary"))
        var chatId = ""
        scenario.onActivity { chatId = send(it, attachment) }
        assertEquals("POST", request().method)
        val continuation = request()
        assertEquals("POST", continuation.method)
        assertEquals("resp_j_parent", JSONObject(continuation.body.readUtf8()).getString("previous_response_id"))
        val get = request()
        assertEquals("GET", get.method); assertEquals("/v1/responses/resp_j_child", get.path)
        awaitCondition("Child waiting checkpoint was not durable") {
            ChatDatabase(context).use { db ->
                val saved = db.getOpenAiToolCheckpoint(chatId, active(chatId)?.optString("request_id").orEmpty())
                saved?.optString("phase") == "waiting"
            }
        }
        // Receiving the GET at the server alone does not prove that its 503
        // reached the retry loop. Observe this Activity's actual wait stack so
        // Stop/onStop/replacement exercise backoff, not an in-flight socket.
        awaitCondition("Foreground reader never entered GET retry backoff", 5_000L) {
            Thread.getAllStackTraces().values.any { stack -> stack.any {
                it.className == MainActivity::class.java.name && it.methodName == "waitForOpenAiToolRetry"
            } }
        }
        return chatId to active(chatId)!!.getString("request_id")
    }

    @Test fun actualForegroundSendRetriesChild429And503AsGetWithoutAnotherPaidPost() {
        val attachment = source()
        server.enqueue(sse(toolResponse(attachment.id))); server.enqueue(queuedChild())
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", 1).setBody("rate limited"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", 1).setBody("temporary"))
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = send(it, attachment) }
            val initial = request(); val continuation = request()
            assertEquals("POST", initial.method); assertFalse(JSONObject(initial.body.readUtf8()).getBoolean("background"))
            assertEquals("POST", continuation.method)
            assertEquals("resp_j_parent", JSONObject(continuation.body.readUtf8()).getString("previous_response_id"))
            repeat(3) { val get = request(); assertEquals("GET", get.method); assertEquals("/v1/responses/resp_j_child", get.path) }
            awaitCondition("Final child was not committed") { assistant(chatId)?.optString("response_id") == "resp_j_child" }
            assertEquals("Recovered child", assistant(chatId)!!.getString("text"))
            assertNull(active(chatId)); assertEquals(5, server.requestCount)
            assertTrue(assistant(chatId)!!.getJSONArray("openai_protocol_output").toString().contains("function_call_output"))
        }
    }

    @Test fun stopDuringRetryAfterCancelsCurrentChildAndDoesNotRetryGet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario)
            val child = OpenAiCreateCallOwners.call(chatId, requestId)!!
            assertEquals("GET", child.request().method)
            scenario.onActivity { call(it, "stopCurrentResponse") }
            awaitCondition("Stopped child reader retained ownership", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertTrue(child.isCanceled()); assertNull(active(chatId))
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun onStopDuringBackoffEndsOldActivityReaderAndDoesNotResumeItsGet() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario)
            val child = OpenAiCreateCallOwners.call(chatId, requestId)!!
            scenario.moveToState(Lifecycle.State.CREATED)
            awaitCondition("Stopped Activity retained child reader", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertTrue(child.isCanceled())
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun deleteDuringBackoffDoesNotRetryGetOrResurrectDeletedChat() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario)
            scenario.onActivity { activity ->
                activity.deleteChat(chatId)
                if (activity.currentChatIdForTesting() != chatId) chats.add(activity.currentChatIdForTesting())
            }
            awaitCondition("Deleted request retained child reader", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertNull(ChatDatabase(context).use { it.getChat(chatId) }); assertNull(active(chatId))
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS)); assertEquals(3, server.requestCount)
        }
    }

    @Test fun replacedRequestDuringBackoffCannotBeReadOrClearedByOldReader() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario)
            ChatDatabase(context).use { db -> db.setActiveResponse(chatId, "resp_replacement", "request_replacement", "", "in_progress",
                System.currentTimeMillis(), "high", "high", false, 1, 0, false) }
            awaitCondition("Replaced child reader retained ownership", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertEquals("request_replacement", active(chatId)!!.getString("request_id"))
            assertEquals("resp_replacement", active(chatId)!!.getString("response_id"))
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS))
            assertFalse(assistant(chatId)?.optString("response_id") == "resp_j_child")
        }
    }

    @Test fun retryAfterIsClampedToPersistedDeadlineInsteadOfWaitingThirtySeconds() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario)
            ChatDatabase(context).use { db -> db.writableDatabase.execSQL("UPDATE chats SET active_started_at=? WHERE id=? AND active_request_id=?",
                arrayOf(System.currentTimeMillis() - ResponseDeadlinePolicy.MAX_REQUEST_MS + 200L, chatId, requestId)) }
            awaitCondition("Deadline did not stop retry backoff", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertNull(active(chatId)); assertEquals("failed", assistant(chatId)!!.getString("status"))
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS))
        }
    }

    @Test fun changedCredentialDuringBackoffCannotReadChildWithReplacementKey() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val (chatId, requestId) = beginWaiting(scenario, retryAfterSeconds = 1)
            assertTrue(SecureStore(context).setApiKey("sk-synthetic-replacement-account"))
            awaitCondition("Changed credential was not rejected", 5_000L) { !OpenAiCreateCallOwners.isRegistered(chatId, requestId) }
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS)); assertEquals(3, server.requestCount)
            assertNull(active(chatId)); assertEquals("failed", assistant(chatId)!!.getString("status"))
        }
    }

    @Test fun fatalChildGet403IsNotRetried() {
        val attachment = source()
        server.enqueue(sse(toolResponse(attachment.id))); server.enqueue(queuedChild())
        server.enqueue(MockResponse().setResponseCode(403).setBody("denied"))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = send(it, attachment) }
            assertEquals("POST", request().method); assertEquals("POST", request().method); assertEquals("GET", request().method)
            awaitCondition("Fatal GET did not finalize as failure") { assistant(chatId)?.optString("status") == "failed" }
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS)); assertEquals(3, server.requestCount)
            assertNull(active(chatId))
        }
    }

    @Test fun initialCreate503RetryAfterZeroIsNotAutomaticallyPostedAgain() =
        assertInitialCreateNotReposted(MockResponse().setResponseCode(503).setHeader("Retry-After", 0).setBody("temporary"))

    @Test fun initialCreate408IsNotAutomaticallyPostedAgain() =
        assertInitialCreateNotReposted(MockResponse().setResponseCode(408).setBody("timeout"))

    @Test fun initialCreateAmbiguousDisconnectAfterBodyIsNotAutomaticallyPostedAgain() =
        assertInitialCreateNotReposted(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))

    private fun assertInitialCreateNotReposted(response: MockResponse) {
        server.enqueue(response)
        // An accidentally retried POST would receive a success, exposing both its wire count and result.
        server.enqueue(sse(finalResponse()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = send(it, source()) }
            assertEquals("POST", request().method)
            awaitCondition("Initial failed POST did not finalize") { assistant(chatId)?.optString("status") == "failed" }
            assertNull(server.takeRequest(1_200, TimeUnit.MILLISECONDS)); assertEquals(1, server.requestCount)
            assertNull(active(chatId))
        }
    }

    @Test fun resumedFinalizerPublishesLocalToolProgressThroughCapturedUiSnapshot() = assertResumedStatus(null)
    @Test fun resumedProgressQueuedBeforeGenerationChangeCannotOverwriteNewUi() = assertResumedStatus("generation")
    @Test fun resumedProgressQueuedBeforeAssistantReplacementCannotOverwriteNewUi() = assertResumedStatus("assistant")
    @Test fun resumedProgressQueuedBeforeChatSwitchCannotOverwriteNewUi() = assertResumedStatus("chat")
    @Test fun resumedProgressQueuedBeforeVisibilityLossCannotOverwriteHiddenUi() = assertResumedStatus("visibility")

    /** Queue the real bridge's onStatus while the UI thread is held, then change one ownership dimension. */
    private fun assertResumedStatus(invalidate: String?) {
        val networkStarted = CountDownLatch(1); val releaseNetwork = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        val attachment = source()
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        beforeNetwork = { request -> if (request.method == "POST") {
            networkStarted.countDown()
            check(releaseNetwork.await(10, TimeUnit.SECONDS)) { "UI callback fixture never released child POST" }
        } }
        var thread: Thread? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                lateinit var snapshot: BackgroundResponseUiSnapshot
                var originalChat = ""
                scenario.onActivity { activity ->
                    activity.newChat(); originalChat = activity.currentChatIdForTesting().also(chats::add)
                    MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
                    val fingerprint = OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("", "", null), syntheticKey, "gpt-6-astra")
                    val db = field(activity, "chatDb") as ChatDatabase
                    val requestId = "request-j-resume-${UUID.randomUUID()}"
                    db.upsertCurrentChat(originalChat, "Resume", null, null, listOf(JSONObject().put("role", "user").put("text", "Inspect")
                        .put("provider", "openai").put("request_id", requestId).put("model", "gpt-6-astra")
                        .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint).put("attachments", JSONArray().put(attachment.toJson()))))
                    db.setActiveResponse(originalChat, "resp_j_parent", requestId, "", "completed", System.currentTimeMillis(), "high", "high", false, 7, 1, true)
                    OpenAiLocalFileToolBridge.configure(db, originalChat, requestId,
                        JSONObject().put("model", "gpt-6-astra").put("store", true).put("background", true), listOf(attachment))
                    val response = toolResponse(attachment.id)
                    val responseState = ResponseEventReducer.reduce(ResponseEventState(), JSONObject().put("type", "response.completed").put("response", response)).state
                    db.updateActiveResponse(originalChat, "resp_j_parent", responseState.text, "completed", 1, requestId, ResponseIndexedCheckpoint.serialize(responseState))
                    call(activity, "restoreCurrentChat")
                    val store = field(activity, "activeResponseState") as ActiveResponseStateStore
                    store.setAssistantItemId("j-resume-item")
                    snapshot = BackgroundResponseUiSnapshot(originalChat, store.state.generation, "j-resume-item")
                    (field(activity, "status") as TextView).text = "new UI sentinel"
                    (field(activity, "toolLabel") as TextView).text = "new tool sentinel"
                    thread = Thread {
                        try {
                            MainActivity::class.java.getDeclaredMethod("finalizeBackgroundResponse", JSONObject::class.java,
                                JSONObject::class.java, BackgroundResponseUiSnapshot::class.java).apply { isAccessible = true }
                                .invoke(activity, db.getActiveResponse(originalChat)!!, response, snapshot)
                        } catch (error: Throwable) { failure.set(if (error is InvocationTargetException) error.targetException else error) }
                    }.apply { start() }
                    assertTrue("Bridge did not reach child POST after status callback", networkStarted.await(10, TimeUnit.SECONDS))
                    when (invalidate) {
                        "generation" -> store.nextGeneration()
                        "assistant" -> store.setAssistantItemId("replacement-item")
                        "chat" -> { activity.newChat(); chats.add(activity.currentChatIdForTesting()) }
                        "visibility" -> MainActivity::class.java.getDeclaredField("activityVisible").apply { isAccessible = true }.setBoolean(activity, false)
                    }
                    (field(activity, "status") as TextView).text = "new UI sentinel"
                    (field(activity, "toolLabel") as TextView).text = "new tool sentinel"
                }
                // ActivityScenario schedules this after the queued runOnUiThread callback.
                scenario.onActivity { activity ->
                    val status = (field(activity, "status") as TextView).text.toString()
                    val tools = (field(activity, "toolLabel") as TextView).text.toString()
                    if (invalidate == null) {
                        assertTrue(status.contains("Локальные файлы (1)")); assertTrue(tools.contains("search_attached_file"))
                    } else {
                        assertEquals("new UI sentinel", status); assertEquals("new tool sentinel", tools)
                    }
                    if (invalidate == "visibility") MainActivity::class.java.getDeclaredField("activityVisible")
                        .apply { isAccessible = true }.setBoolean(activity, true)
                }
                releaseNetwork.countDown(); thread!!.join(10_000)
                assertFalse("Finalizer did not exit", thread!!.isAlive)
                failure.get()?.let { throw AssertionError("Finalizer failed", it) }
                assertEquals("POST", request().method)
                assertEquals("resp_j_child", assistant(originalChat)?.optString("response_id"))
            }
        } finally {
            releaseNetwork.countDown(); thread?.join(10_000)
        }
    }
}
