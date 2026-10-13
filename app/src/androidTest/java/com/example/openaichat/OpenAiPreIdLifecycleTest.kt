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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

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

    @Test fun archivedCreatedAcrossRecreateCommitsOnceAndReconcilesNewUiWithoutLosingDraft() =
        assertDelayedCreated(Action.RECREATE)

    @Test fun archivedCreatedAfterRecreateAndStopCancelsOriginalAccountWithoutRevivingNewUi() =
        assertDelayedCreated(Action.RECREATE_STOP)

    @Test fun connectionLostDuringPreIdRecreateKeepsReceivedPrefixUnknownCostAndOneCreate() =
        assertDelayedCreated(Action.RECREATE_DISCONNECT)

    @Test fun archivedForegroundCreatedAcrossRecreateKeepsOwnerAndCommitsWithoutBackgroundRecovery() =
        assertDelayedCreated(Action.FOREGROUND_RECREATE)

    @Test fun lostForegroundConnectionAcrossRecreateKeepsReceivedPrefixAndDraftWithoutNewCreate() =
        assertDelayedCreated(Action.FOREGROUND_RECREATE_DISCONNECT)

    @Test fun oldCreatedAfterRecreateAndSecondSendCannotFinishOrClearNewActivityOwner() =
        assertDelayedCreated(Action.RECREATE_REPLACE)

    @Test fun foregroundTerminalCheckpointReopenFinalizesLocallyDespiteOptionalMetricFailure() =
        assertForegroundCheckpoint(terminal = true)

    @Test fun foregroundPartialCheckpointReopenKeepsSignedOutputAndSourceWithoutNetworkRecovery() =
        assertForegroundCheckpoint(terminal = false)

    @Test fun differentTerminalResponseCannotCommitOriginalRequestOrLoseReceivedSource() =
        assertDelayedCreated(Action.MISMATCH)

    private enum class Action { STOP_START, STOP, DELETE, REPLACE, RECREATE, RECREATE_STOP,
        RECREATE_DISCONNECT, FOREGROUND_RECREATE, FOREGROUND_RECREATE_DISCONNECT, RECREATE_REPLACE, MISMATCH }

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
        val replace = action == Action.REPLACE || action == Action.RECREATE_REPLACE
        val foreground = action == Action.FOREGROUND_RECREATE || action == Action.FOREGROUND_RECREATE_DISCONNECT
        val disconnect = action == Action.RECREATE_DISCONNECT || action == Action.FOREGROUND_RECREATE_DISCONNECT
        val recreateComplete = action == Action.RECREATE || action == Action.FOREGROUND_RECREATE
        val cancelExpected = action in setOf(Action.STOP, Action.DELETE, Action.RECREATE_STOP) || replace
        val parsedGate = recreateComplete || action == Action.RECREATE_STOP || action == Action.RECREATE_REPLACE
        val interruptedCreated = AtomicBoolean()
        val interruptedCancel = AtomicBoolean()
        val responseId = "resp_preid_fixture"
        val replacementResponseId = "resp_preid_replacement"
        fun completedStream(id: String, text: String, mismatch: Boolean = false): String {
            val terminal = JSONObject().put("id", if (mismatch) "resp_unowned" else id).put("status", "completed")
                .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                    .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text)))))
            return "data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
                .put("response", JSONObject().put("id", id)) + "\n\n" +
                (if (mismatch) "data: " + JSONObject().put("type", "response.output_text.delta").put("sequence_number", 1)
                    .put("output_index", 0).put("content_index", 0).put("delta", "Paid partial") + "\n\n" else "") +
                "data: " + JSONObject().put("type", "response.completed").put("sequence_number", if (mismatch) 2 else 1)
                    .put("response", terminal) + "\n\n"
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(completedStream(responseId, "Canonical result", action == Action.MISMATCH)))
        if (replace) server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody(completedStream(replacementResponseId, "Replacement result")))
        if (cancelExpected) server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("id", responseId).put("status", "cancelled")
                .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31)).toString()))
        val createClient = trusted.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build())
            val number = createNumber.getAndIncrement()
            if (parsedGate && number == 0) return@addInterceptor response
            val body = response.body!!
            val receivedGate = if (number == 0) entered else replacementEntered
            val releaseGate = if (number == 0) release else replacementRelease
            val gated = object : ForwardingSource(body.source()) {
                private var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (disconnect) {
                        if (first) {
                            first = false
                            // This one byte has reached the production archive;
                            // the next read is still waiting when onDestroy cancels.
                            return super.read(sink, minOf(byteCount, 1L))
                        }
                        entered.countDown()
                        if (!release.await(30, TimeUnit.SECONDS)) throw IOException("Fixture before-ID gate timed out")
                        throw IOException("Fixture before-ID source disconnected")
                    }
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
            interruptedCancel.set(Thread.currentThread().isInterrupted)
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        var chatId = ""
        var requestId = ""
        var replacementRequestId = ""
        var replacementChatId = ""
        var replacementCall: Call? = null
        var replacementGeneration = -1L
        var replacementAssistantId: String? = null
        fun sendReplacement(activity: MainActivity) {
            (field("input").get(activity) as EditText).setText("Replacement prompt")
            MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                .apply { isAccessible = true }.invoke(activity, false)
            replacementCall = field("currentCall").get(activity) as Call
            val state = (field("activeResponseState").get(activity) as ActiveResponseStateStore).state
            replacementGeneration = state.generation
            replacementAssistantId = state.assistantItemId
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = activity.prefs.getString("current_chat", "")!!
                    prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
                        .putString("reasoning", "high").putString("text_format", "text")
                        .putString("system_prompt", "").putString("instructions", "")
                        .putBoolean("background_responses", !foreground).putBoolean("file_analysis_tools", false)
                        .putBoolean("web", false).putBoolean("file_search", false)
                        .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit()
                    SecureStore(context).setApiKey("sk-only-local-pre-id")
                    field("client").set(activity, createClient)
                    field("pollingClient").set(activity, cancelClient)
                    if (parsedGate) {
                        val hook: (JSONObject) -> Unit = { event ->
                            if (event.optString("type") == "response.created") {
                                entered.countDown()
                                // Emulate a parsed callback which cannot unreceive
                                // its archived bytes when onDestroy interrupts IO.
                                val deadline = SystemClock.elapsedRealtime() + 30_000L
                                var interrupted = false
                                while (release.count > 0L && SystemClock.elapsedRealtime() < deadline) {
                                    try { release.await(100, TimeUnit.MILLISECONDS) }
                                    catch (_: InterruptedException) { interrupted = true }
                                }
                                interruptedCreated.set(interrupted)
                                if (interrupted) Thread.currentThread().interrupt()
                                if (release.count > 0L) throw IOException("Parsed callback gate timed out")
                            }
                        }
                        field("openAiReceivedEventHook").set(activity, hook)
                    }
                    (field("input").get(activity) as EditText).setText("Pre-ID prompt")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                val create = server.takeRequest(20, TimeUnit.SECONDS)!!
                assertEquals("POST", create.method)
                assertEquals(!foreground, JSONObject(create.body.readUtf8()).getBoolean("background"))
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                ChatDatabase(context).use { db ->
                    val active = db.getActiveResponse(chatId)!!
                    requestId = active.getString("request_id")
                    assertTrue(JsonProtocol.string(active, "response_id").isBlank())
                    assertTrue(OpenAiCreateCallOwners.hasLiveCall(chatId, requestId))
                    db.setActiveResponse(chatId, null, requestId, "", "starting",
                        System.currentTimeMillis() - 180_000L, "high", "high", false, 13, 0, !foreground)
                }
                when (action) {
                    Action.MISMATCH -> Unit
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
                    Action.RECREATE, Action.RECREATE_STOP, Action.RECREATE_DISCONNECT,
                    Action.FOREGROUND_RECREATE, Action.FOREGROUND_RECREATE_DISCONNECT, Action.RECREATE_REPLACE -> {
                        if (parsedGate) {
                            val archive = OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).single()
                            assertTrue("Created bytes must already be in the real archive", archive.readText().contains(responseId))
                        }
                        scenario.recreate()
                        scenario.onActivity { activity ->
                            field("client").set(activity, createClient)
                            field("pollingClient").set(activity, cancelClient)
                            SecureStore(context).setApiKey("sk-only-local-new-account")
                            if (action == Action.RECREATE_STOP || replace) {
                                MainActivity::class.java.getDeclaredMethod("stopCurrentResponse")
                                    .apply { isAccessible = true }.invoke(activity)
                                if (replace) sendReplacement(activity)
                            } else {
                                (field("input").get(activity) as EditText).setText("Recreated draft")
                                if (parsedGate) {
                                    assertFalse("New UI waits for the registered original callback", activity.send.isEnabled)
                                    ChatDatabase(context).use { db ->
                                        assertEquals(requestId, db.getActiveResponse(chatId)!!.getString("request_id"))
                                    }
                                }
                            }
                        }
                    }
                    Action.STOP, Action.REPLACE -> {
                        scenario.onActivity { activity ->
                            MainActivity::class.java.getDeclaredMethod("stopCurrentResponse")
                                .apply { isAccessible = true }.invoke(activity)
                            SecureStore(context).setApiKey("sk-only-local-new-account")
                            if (replace) sendReplacement(activity)
                        }
                        if (!replace) ChatDatabase(context).use { assertNull(it.getActiveResponse(chatId)) }
                    }
                }
                if (replace) {
                    val replacementCreate = server.takeRequest(20, TimeUnit.SECONDS)!!
                    assertEquals("POST", replacementCreate.method)
                    assertEquals("Bearer sk-only-local-new-account", replacementCreate.getHeader("Authorization"))
                    assertTrue(replacementEntered.await(20, TimeUnit.SECONDS))
                    ChatDatabase(context).use { db ->
                        replacementRequestId = db.getActiveResponse(chatId)!!.getString("request_id")
                        assertNotEquals(requestId, replacementRequestId)
                    }
                }
                release.countDown()
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (OpenAiCreateCallOwners.isRegistered(chatId, requestId) && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20)
                assertFalse("The original callback must release its registry owner",
                    OpenAiCreateCallOwners.isRegistered(chatId, requestId))
                if (cancelExpected) {
                    val cancel = server.takeRequest(20, TimeUnit.SECONDS)!!
                    assertEquals("POST", cancel.method)
                    assertEquals("/v1/responses/$responseId/cancel", cancel.path)
                    assertEquals("Bearer sk-only-local-pre-id", cancel.getHeader("Authorization"))
                    assertFalse("Late cancellation must read usage without the destroyed Create interrupt", interruptedCancel.get())
                    if (parsedGate) assertTrue("The old Activity really interrupted its Create callback", interruptedCreated.get())
                }
                // The old callback has exited: assert replacement ownership before
                // allowing its independently paid response to finish.
                if (replace) {
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
                            Action.STOP, Action.RECREATE_STOP -> db.listRequestAccounting(chatId).single().let {
                                it.optString("response_id") == responseId && it.optString("provider_status") == "cancelled"
                            }
                            else -> db.getChat(chatId)!!.getJSONArray("messages").length() == 2 &&
                                db.getActiveResponse(chatId) == null
                        }
                    }
                    if (finished) break
                    SystemClock.sleep(20)
                }
                assertTrue("Original/replacement request did not finish its owned transaction", finished)
                if (replace) {
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
                        if (cancelExpected && action != Action.DELETE) {
                            assertEquals(if (replace) 2 else 0, messages.length())
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
                        if (action == Action.STOP_START || replace || recreateComplete) {
                            assertEquals(if (replace) "Replacement result" else "Canonical result",
                                messages.getJSONObject(1).getString("text"))
                            assertEquals(if (replace) replacementResponseId else responseId,
                                messages.getJSONObject(1).getString("response_id"))
                        }
                        if (action == Action.MISMATCH) {
                            val assistant = messages.getJSONObject(1)
                            assertTrue(assistant.getBoolean("incomplete"))
                            assertEquals("failed", assistant.getString("status"))
                            assertEquals("Paid partial", assistant.getString("text"))
                            assertEquals(responseId, assistant.getString("response_id"))
                            assertFalse(assistant.has("openai_protocol_output"))
                            val source = java.io.File(assistant.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
                            assertArrayEquals(completedStream(responseId, "Canonical result", true).toByteArray(Charsets.UTF_8), source.readBytes())
                            val account = db.listRequestAccounting(chatId).single()
                            assertEquals("unknown", account.getString("cost_kind"))
                            assertFalse(account.has("cost_usd"))
                        }
                        if (disconnect) {
                            val assistant = messages.getJSONObject(1)
                            assertTrue(assistant.getBoolean("incomplete"))
                            assertEquals("", assistant.getString("text"))
                            assertFalse(assistant.has("openai_protocol_output"))
                            assertTrue(JsonProtocol.string(assistant, "response_id").isBlank())
                            val source = java.io.File(assistant.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
                            assertArrayEquals(byteArrayOf('d'.code.toByte()), source.readBytes())
                            val account = db.listRequestAccounting(chatId).single()
                            assertEquals("unknown", account.getString("cost_kind"))
                            assertFalse(account.has("cost_usd"))
                        }
                    }
                }
                var uiReady = false
                val uiDeadline = SystemClock.elapsedRealtime() + 20_000L
                while (!uiReady && SystemClock.elapsedRealtime() < uiDeadline) {
                    scenario.onActivity { uiReady = it.send.isEnabled }
                    if (!uiReady) SystemClock.sleep(20)
                }
                assertTrue("The current Activity must reconcile the original callback's transaction", uiReady)
                scenario.onActivity { activity ->
                    assertTrue(activity.send.isEnabled)
                    if (action == Action.STOP || action == Action.RECREATE_STOP) assertEquals("Pre-ID prompt", (field("input").get(activity) as EditText).text.toString())
                    if (recreateComplete || disconnect) {
                        assertEquals("Recreated draft", (field("input").get(activity) as EditText).text.toString())
                    }
                    if (action == Action.DELETE) {
                        assertEquals(replacementChatId, activity.prefs.getString("current_chat", ""))
                        assertEquals("Deleted-chat draft", (field("input").get(activity) as EditText).text.toString())
                        assertEquals(0, (field("messageAdapter").get(activity) as ChatMessageAdapter).itemCount)
                    }
                }
                assertEquals("Only explicitly requested Creates and old-response cancellation",
                    if (replace) 3 else if (cancelExpected) 2 else 1, server.requestCount)
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

    private fun assertForegroundCheckpoint(terminal: Boolean) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("app", 0)
        val previousProvider = prefs.getString("provider", null)
        val previousBackground = prefs.all["background_responses"] as? Boolean
        val previousChat = prefs.getString("current_chat", null)
        val previousKey = SecureStore(context).getApiKey()
        val requestId = "foreground-reopen-" + UUID.randomUUID()
        val responseId = "resp_foreground_checkpoint"
        val signed = "saved-signed-\u0000😀"
        val reasoning = JSONObject().put("type", "reasoning").put("id", "rs_foreground")
            .put("encrypted_content", signed)
        val originalSource = ("data: " + JSONObject().put("type", "response.output_item.done")
            .put("output_index", 0).put("item", reasoning) + "\n\n").toByteArray(Charsets.UTF_8)
        val calls = AtomicInteger()
        val metricFailures = AtomicInteger()
        val noNetwork = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet()
            throw IOException("Foreground checkpoint must not start network recovery")
        }.build()
        var chatId = ""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = activity.prefs.getString("current_chat", "")!!
                    prefs.edit().putString("provider", "openai").putBoolean("background_responses", false).commit()
                    SecureStore(context).setApiKey("sk-only-local-foreground-checkpoint")
                    field("client").set(activity, noNetwork)
                    field("pollingClient").set(activity, noNetwork)
                    ResumableResponseStreamClient::class.java.getDeclaredField("httpClient").apply {
                        isAccessible = true
                    }.set(invoke(activity, "getResumableResponseClient"), noNetwork)
                    val metricHook: () -> Unit = {
                        metricFailures.incrementAndGet()
                        throw android.database.sqlite.SQLiteDatabaseLockedException("Synthetic optional foreground metric lock")
                    }
                    field("openAiEffortMetricHook").set(activity, metricHook)
                    var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
                        .put("type", "response.created").put("response", JSONObject().put("id", responseId))).state
                    state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_item.done")
                        .put("output_index", 0).put("item", reasoning)).state
                    state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_text.delta")
                        .put("output_index", 1).put("content_index", 0).put("delta", "Saved partial")).state
                    if (terminal) {
                        val output = JSONArray().put(reasoning).put(JSONObject().put("type", "message")
                            .put("role", "assistant").put("content", JSONArray().put(JSONObject()
                                .put("type", "output_text").put("text", "Saved terminal"))))
                        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.completed")
                            .put("response", JSONObject().put("id", responseId).put("status", "completed").put("output", output)
                                .put("usage", JSONObject().put("input_tokens", 21).put("output_tokens", 45)))).state
                    }
                    val user = JSONObject().put("role", "user").put("text", "Foreground prompt")
                        .put("request_id", requestId).put("provider", "openai").put("requested_model", "gpt-6-astra")
                    ChatDatabase(context).use { db ->
                        db.upsertCurrentChat(chatId, "Foreground checkpoint", null, "high", listOf(user))
                        assertTrue(db.startRequestAccounting(chatId, requestId, "openai", "gpt-6-astra", "openai_api_key"))
                        db.setActiveResponse(chatId, responseId, requestId, "", "starting",
                            System.currentTimeMillis() - 180_000L, "high", "high", false, 17, 0, false)
                        db.updateActiveResponse(chatId, responseId, state.text,
                            if (terminal) "completed" else "in_progress", 3, requestId, ResponseIndexedCheckpoint.serialize(state))
                    }
                    // Close/reopen the seeded SQLite path before the actual
                    // Activity restore and recovery methods inspect its owner.
                    ChatDatabase(context).use { assertEquals(requestId, it.getActiveResponse(chatId)!!.getString("request_id")) }
                    OpenAiRawSseArchive.begin(ByteArrayInputStream(originalSource), context.filesDir, chatId, requestId)
                        .use { it.copyTo(ByteArrayOutputStream()) }
                    assertFalse(OpenAiCreateCallOwners.isRegistered(chatId, requestId))
                    (field("input").get(activity) as EditText).setText("Preserved reopen draft")
                    invoke(activity, "persistComposerStateNow")
                    invoke(activity, "restoreCurrentChat")
                    invoke(activity, "resumeBackgroundResponses")
                }
                val expectedStatus = if (terminal) "completed" else "disconnected"
                var finished = false
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (!finished && SystemClock.elapsedRealtime() < deadline) {
                    finished = ChatDatabase(context).use { db ->
                        !db.isActiveRequest(chatId, requestId) && db.listRequestAccounting(chatId).single()
                            .optString("local_status") == expectedStatus
                    }
                    if (!finished) SystemClock.sleep(20)
                }
                assertTrue("Foreground checkpoint must finalize before clearing ownership", finished)
                var uiReady = false
                while (!uiReady && SystemClock.elapsedRealtime() < deadline) {
                    scenario.onActivity { uiReady = it.send.isEnabled }
                    if (!uiReady) SystemClock.sleep(20)
                }
                assertTrue(uiReady)
                scenario.onActivity { activity ->
                    assertEquals("Preserved reopen draft", (field("input").get(activity) as EditText).text.toString())
                    invoke(activity, "resumeBackgroundResponses")
                }
                ChatDatabase(context).use { db ->
                    assertNull(db.getActiveResponse(chatId))
                    val messages = db.getChat(chatId)!!.getJSONArray("messages")
                    assertEquals(2, messages.length())
                    val answer = messages.getJSONObject(1)
                    assertEquals(if (terminal) "Saved terminal" else "Saved partial", answer.getString("text"))
                    assertEquals(signed, answer.getJSONArray("openai_protocol_output").getJSONObject(0).getString("encrypted_content"))
                    val account = db.listRequestAccounting(chatId).single()
                    assertEquals("unknown", account.getString("cost_kind"))
                    assertFalse(account.has("cost_usd"))
                    if (terminal) {
                        assertEquals(21L, account.getLong("input_tokens"))
                        assertEquals(45L, account.getLong("output_tokens"))
                    } else {
                        assertTrue(answer.getBoolean("incomplete"))
                        val source = java.io.File(answer.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
                        assertArrayEquals(originalSource, source.readBytes())
                    }
                }
                assertEquals(if (terminal) 1 else 0, metricFailures.get())
                assertEquals("No Create, cancel, retrieve or SSE resume", 0, calls.get())
            }
        } finally {
            SecureStore(context).setApiKey(previousKey)
            prefs.edit().apply {
                if (previousProvider == null) remove("provider") else putString("provider", previousProvider)
                if (previousBackground == null) remove("background_responses") else putBoolean("background_responses", previousBackground)
                if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat)
            }.commit()
            if (chatId.isNotBlank()) {
                ChatDatabase(context).use { it.deleteChat(chatId) }
                OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
            }
        }
    }

    private fun invoke(activity: MainActivity, name: String) = MainActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
