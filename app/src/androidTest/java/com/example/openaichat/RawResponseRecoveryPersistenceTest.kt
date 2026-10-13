package com.example.openaichat

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import android.os.SystemClock
import android.widget.EditText

@RunWith(AndroidJUnit4::class)
class RawResponseRecoveryPersistenceTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext

    @Test fun actualSendAllocationFailurePreservesSignedPartialAndCompleteReceivedSourceAfterReopen() =
        assertActualSendMemoryFailure(accumulated = false)

    @Test fun actualSendAccumulationFailurePreservesSignedPartialAndCompleteReceivedSourceAfterReopen() =
        assertActualSendMemoryFailure(accumulated = true)

    private fun assertActualSendMemoryFailure(accumulated: Boolean) {
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
        val endpoint = server.url("/v1/responses")
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(endpoint).build()) }.build()
        // Same real heap boundary, exercised as one oversized frame or many
        // individually small indexed events. No huge test-only budget is used.
        val payloadChars = (Runtime.getRuntime().maxMemory() / 3L / 32L + 4096L).toInt()
        val prefix = "data: {\"type\":\"response.created\",\"sequence_number\":0,\"response\":{\"id\":\"resp_memory_fixture\"}}\n\n" +
            "data: {\"type\":\"response.output_item.done\",\"sequence_number\":1,\"output_index\":0,\"item\":{\"type\":\"reasoning\",\"id\":\"signed_memory\",\"encrypted_content\":\"exact-signed-memory-bytes\"}}\n\n" +
            "data: {\"type\":\"response.output_text.delta\",\"sequence_number\":2,\"output_index\":1,\"content_index\":0,\"delta\":\"paid partial\"}\n\n"
        val source = prefix + if (accumulated) buildString {
            val fragment = "x".repeat(32_768)
            repeat((payloadChars + fragment.length - 1) / fragment.length) { index ->
                append("data: ")
                append(JSONObject().put("type", "response.output_text.delta").put("sequence_number", index + 3)
                    .put("output_index", 1).put("content_index", 0).put("delta", fragment))
                append("\n\n")
            }
            append("data: {\"type\":\"response.completed\",\"sequence_number\":")
            append(3 + (payloadChars + fragment.length - 1) / fragment.length)
            append(",\"response\":{\"id\":\"resp_memory_fixture\",\"status\":\"completed\"}}\n\n")
        } else "data: {\"type\":\"response.completed\",\"sequence_number\":3,\"response\":{\"id\":\"resp_memory_fixture\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"" +
            "x".repeat(payloadChars) + "\"}]}]}}\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(source))
        var chatId = ""
        var requestId = ""
        var sourceFile: File? = null
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    activity.newChat()
                    chatId = activity.prefs.getString("current_chat", "")!!
                    prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
                        .putString("reasoning", "high").putString("text_format", "text")
                        .putString("system_prompt", "").putString("instructions", "")
                        .putBoolean("background_responses", true).putBoolean("file_analysis_tools", false)
                        .putBoolean("web", false).putBoolean("file_search", false)
                        .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit()
                    SecureStore(context).setApiKey("sk-only-local-memory-test")
                    field("client").set(activity, http)
                    // Reproduce the exact optional-metric exception from the CI
                    // crash; paid output must still commit and clear busy UI.
                    field("openAiEffortMetricHook").set(activity, { throw android.database.sqlite.SQLiteDatabaseLockedException("Synthetic effort telemetry lock") })
                    (field("input").get(activity) as EditText).setText("Memory source fixture")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                assertEquals("POST", server.takeRequest(20, TimeUnit.SECONDS)!!.method)
                requestId = ChatDatabase(context).use { it.listRequestAccounting(chatId).single().getString("local_request_id") }
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                var finished = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    finished = ChatDatabase(context).use { db ->
                        !db.isActiveRequest(chatId, requestId)
                    }
                    if (finished) break
                    SystemClock.sleep(20)
                }
                assertTrue("Actual Send must finalize a memory failure, not crash/retry", finished)
                instrumentation.waitForIdleSync()
                ChatDatabase(context).use { db ->
                    val assistant = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(1)
                    requestId = assistant.getString("request_id")
                    val receivedText = assistant.getString("text")
                    if (accumulated) {
                        assertTrue(receivedText.startsWith("paid partial"))
                        val retained = receivedText.removePrefix("paid partial")
                        assertTrue(retained.isNotEmpty())
                        assertEquals(0, retained.length % 32_768)
                        assertTrue(retained.all { it == 'x' })
                        assertTrue(retained.length < payloadChars)
                    } else assertEquals("paid partial", receivedText)
                    assertEquals("failed", assistant.getString("status"))
                    assertTrue(assistant.getBoolean("incomplete"))
                    assertTrue(assistant.getString("error").contains("памяти"))
                    if (accumulated) assertTrue(assistant.getString("error").contains("Накопленный ответ"))
                    assertEquals("exact-signed-memory-bytes", assistant.getJSONArray("openai_protocol_output")
                        .getJSONObject(0).getString("encrypted_content"))
                    sourceFile = File(assistant.getJSONArray("annotations").getJSONObject(0).getString("local_path"))
                    assertArrayEquals(source.toByteArray(), sourceFile!!.readBytes())
                    val accounting = db.listRequestAccounting(chatId).single()
                    assertEquals("failed", accounting.getString("local_status"))
                    assertEquals("unknown", accounting.getString("cost_kind"))
                    assertFalse(accounting.has("cost_usd"))
                }
                scenario.onActivity { activity -> assertTrue(activity.send.isEnabled) }
                assertEquals("One original Create; no automatic paid regeneration", 1, server.requestCount)
            }
        } finally {
            SecureStore(context).setApiKey(previousKey)
            prefs.edit().apply {
                previous.forEach { (key, value) -> when (value) {
                    null -> remove(key); is String -> putString(key, value); is Boolean -> putBoolean(key, value)
                } }
                if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat)
            }.commit()
            if (chatId.isNotBlank()) {
                ChatDatabase(context).use { it.deleteChat(chatId) }
                if (requestId.isNotBlank()) OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
            }
            sourceFile?.delete()
            server.shutdown()
        }
    }

    @Test fun committedOldCheckpointFailureCannotClearNewSameChatUi() =
        assertRecoveryFailureUi(expired = false, replaceBeforeUi = true)

    @Test fun expiredRecoveryPreservesSignedPartialAndRawSourceBeforeClearingUi() =
        assertRecoveryFailureUi(expired = true, replaceBeforeUi = false)

    @Test fun committedOldTimeoutCannotClearNewSameChatUi() =
        assertRecoveryFailureUi(expired = true, replaceBeforeUi = true)

    private fun assertRecoveryFailureUi(expired: Boolean, replaceBeforeUi: Boolean) {
        val chatId = "raw-ui-owner-" + UUID.randomUUID()
        val oldRequest = "req_checkpoint_old"
        val newRequest = "req_checkpoint_new"
        val prefs = context.getSharedPreferences("app", 0)
        val previousChat = prefs.getString("current_chat", null)
        val newerCall = OkHttpClient().newCall(Request.Builder().url("https://example.invalid/").build())
        val sourceBytes = "data: exact received source before timeout\n\n".toByteArray()
        var archive: OpenAiRawSseArchive? = null
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Recovery UI owner fixture")
                db.upsertCurrentChat(chatId, "Recovery UI owner fixture", null, "high", listOf(
                    JSONObject().put("role", "user").put("text", "Synthetic prompt")
                        .put("provider", "openai").put("request_id", oldRequest)))
            }
            archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(sourceBytes), context.filesDir, chatId, oldRequest)
            archive.use { it.receiveRemaining() }
            assertTrue(prefs.edit().putString("current_chat", chatId).commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                var newerGeneration = -1L
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    val state = field("activeResponseState").get(activity) as ActiveResponseStateStore
                    val oldGeneration = state.nextGeneration()
                    state.setAssistantItemId("old-assistant")
                    val captured = BackgroundResponseUiSnapshot(chatId, oldGeneration, "old-assistant")
                    ChatDatabase(context).use { db ->
                        assertTrue(db.startRequestAccounting(chatId, oldRequest, "openai", "fixture-model", "openai_api_key"))
                        val started = System.currentTimeMillis() -
                            if (expired) ResponseDeadlinePolicy.MAX_REQUEST_MS + 1L else 0L
                        db.setActiveResponse(chatId, "resp_old", oldRequest, "paid partial", "in_progress",
                            started, "high", "high", false, 16, 0, expired)
                        val signed = JSONObject().put("type", "reasoning").put("id", "signed_timeout")
                            .put("encrypted_content", "signed-paid-exact-bytes")
                        var recovered = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
                            .put("type", "response.output_item.done").put("output_index", 0).put("item", signed)).state
                        recovered = ResponseEventReducer.reduce(recovered, JSONObject()
                            .put("type", "response.output_text.delta").put("output_index", 1)
                            .put("content_index", 0).put("delta", "paid partial")).state
                        db.updateActiveResponse(chatId, "resp_old", "paid partial", "in_progress", 9,
                            oldRequest, ResponseIndexedCheckpoint.serialize(recovered))
                    }
                    val callbackFinished = CompletableFuture<Unit>()
                    val callback = Thread {
                        try {
                            if (expired) {
                                val active = ChatDatabase(context).use { it.getActiveResponse(chatId)!! }
                                MainActivity::class.java.getDeclaredMethod("streamBackgroundResponse",
                                    JSONObject::class.java, BackgroundResponseUiSnapshot::class.java)
                                    .apply { isAccessible = true }.invoke(activity, active, captured)
                            } else {
                                MainActivity::class.java.getDeclaredMethod("failUnrecoverableBackgroundCheckpoint",
                                    String::class.java, String::class.java, String::class.java,
                                    UnrecoverableResponsesCheckpointException::class.java,
                                    BackgroundResponseUiSnapshot::class.java).apply { isAccessible = true }
                                    .invoke(activity, chatId, "resp_old", oldRequest,
                                        UnrecoverableResponsesCheckpointException("Synthetic checkpoint failure"), captured)
                            }
                            callbackFinished.complete(Unit)
                        } catch (failure: Throwable) {
                            callbackFinished.completeExceptionally(failure)
                        }
                    }.apply { isDaemon = true }
                    callback.start()
                    // The main thread stays here until the old DB claim has
                    // completed and its UI runnable is queued, then starts a
                    // newer request before that runnable can execute.
                    callbackFinished.get(10, TimeUnit.SECONDS)
                    ChatDatabase(context).use { db ->
                        assertNull(db.getActiveResponse(chatId))
                        val assistant = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(1)
                        assertEquals("paid partial", assistant.getString("text"))
                        assertTrue(assistant.getBoolean("incomplete"))
                        assertEquals("signed-paid-exact-bytes", assistant.getJSONArray("openai_protocol_output")
                            .getJSONObject(0).getString("encrypted_content"))
                        assertArrayEquals(sourceBytes, File(assistant.getJSONArray("annotations")
                            .getJSONObject(0).getString("local_path")).readBytes())
                        val accounting = db.listRequestAccounting(chatId).single()
                        assertEquals("unknown", accounting.getString("cost_kind"))
                        assertFalse(accounting.has("cost_usd"))
                        if (replaceBeforeUi) db.setActiveResponse(chatId, "resp_new", newRequest, "", "starting",
                            System.currentTimeMillis(), "high", "high", false, 16, 0, false)
                    }
                    if (replaceBeforeUi) {
                        newerGeneration = state.nextGeneration()
                        state.setAssistantItemId("new-assistant")
                        state.setResponseId("resp_new")
                    }
                    field("currentCall").set(activity, newerCall)
                    (field("status").get(activity) as TextView).text = "New request remains busy"
                    activity.send.isEnabled = false
                }
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                    val state = (field("activeResponseState").get(activity) as ActiveResponseStateStore).state
                    if (replaceBeforeUi) {
                        assertEquals(newerGeneration, state.generation)
                        assertEquals("new-assistant", state.assistantItemId)
                        assertEquals("resp_new", state.responseId)
                        assertSame(newerCall, field("currentCall").get(activity))
                        assertFalse(activity.send.isEnabled)
                        assertEquals("New request remains busy", (field("status").get(activity) as TextView).text.toString())
                    } else {
                        assertNull(state.assistantItemId)
                        assertNull(field("currentCall").get(activity))
                        assertTrue(activity.send.isEnabled)
                        assertTrue((field("status").get(activity) as TextView).text.toString().contains("5 часов"))
                    }
                }
                ChatDatabase(context).use { db ->
                    if (replaceBeforeUi) assertEquals(newRequest, db.getActiveResponse(chatId)!!.getString("request_id"))
                    else assertNull(db.getActiveResponse(chatId))
                    assertEquals(2, db.getChat(chatId)!!.getJSONArray("messages").length())
                }
            }
        } finally {
            newerCall.cancel()
            prefs.edit().apply { if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat) }.commit()
            ChatDatabase(context).use { it.deleteChat(chatId) }
            archive?.file?.delete()
        }
    }

    @Test fun undecodedPaidSourceSurvivesSQLiteReopenAndHasAnActualSaveCard() {
        val chatId = "raw-export-" + UUID.randomUUID()
        val requestId = "req_raw_only"
        val bytes = "data: exact received bytes, not a fabricated completed response\n\n".toByteArray()
        val prefs = context.getSharedPreferences("app", 0)
        val previousChat = prefs.getString("current_chat", null)
        var archive: OpenAiRawSseArchive? = null
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Raw source fixture")
                db.upsertCurrentChat(chatId, "Raw source fixture", null, "high", listOf(
                    JSONObject().put("role", "user").put("provider", "openai")
                        .put("text", "Synthetic prompt").put("request_id", requestId)))
                db.setActiveResponse(chatId, "resp_raw_only", requestId, "", "in_progress",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
            }
            archive = OpenAiRawSseArchive.begin(ByteArrayInputStream(bytes), context.filesDir, chatId, requestId)
            archive.use { it.receiveRemaining() }
            ChatDatabase(context).use { db ->
                assertTrue(BackgroundRequestFailureRecorder.persist(db, chatId, requestId,
                    "resp_raw_only", "failed", "explicit decoder limit", context.filesDir))
                assertNull(db.getActiveResponse(chatId))
            }
            var filename = ""
            ChatDatabase(context).use { db ->
                val messages = db.getChat(chatId)!!.getJSONArray("messages")
                assertEquals("No visible text is required to retain a paid source", 2, messages.length())
                val assistant = messages.getJSONObject(1)
                assertTrue(assistant.getBoolean("incomplete"))
                assertFalse("Do not synthesize provider output", assistant.has("openai_protocol_output"))
                val card = assistant.getJSONArray("annotations").getJSONObject(0)
                // Android /data/user/0 and /data/data can alias the same file.
                assertEquals(archive.file.canonicalFile, File(card.getString("local_path")).canonicalFile)
                assertArrayEquals(bytes, File(card.getString("local_path")).readBytes())
                filename = card.getString("filename")
                assertFalse(BackgroundRequestFailureRecorder.persist(db, chatId, requestId,
                    "resp_raw_only", "failed", "duplicate", context.filesDir))
            }
            assertTrue(prefs.edit().putString("current_chat", chatId).commit())
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                instrumentation.waitForIdleSync()
                scenario.onActivity { activity ->
                    val labels = mutableListOf<String>()
                    fun collect(view: View) {
                        if (view is TextView) labels += view.text.toString()
                        if (view is ViewGroup) for (i in 0 until view.childCount) collect(view.getChildAt(i))
                    }
                    collect(activity.window.decorView)
                    assertTrue("Received source must be visible in the real answer bubble",
                        labels.any { it.contains(filename) })
                    assertTrue("Use the existing user-controlled file Save flow", labels.any { it == "Сохранить" })
                    assertTrue(labels.any { it.contains("Полученные исходные данные ответа") })
                }
            }
        } finally {
            prefs.edit().apply { if (previousChat == null) remove("current_chat") else putString("current_chat", previousChat) }.commit()
            ChatDatabase(context).use { it.deleteChat(chatId) }
            archive?.file?.delete()
        }
    }

    @Test fun stoppedOrReplacedOwnerCannotAttachOldSourceToAnotherResponse() {
        val chatId = "raw-owner-" + UUID.randomUUID()
        val oldRequest = "req_raw_old"
        val newRequest = "req_raw_new"
        var archive: OpenAiRawSseArchive? = null
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Raw owner fixture")
                db.setActiveResponse(chatId, null, oldRequest, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
                assertTrue(db.stopActiveRequestOnce(chatId, oldRequest))
                db.setActiveResponse(chatId, "resp_new", newRequest, "", "starting",
                    System.currentTimeMillis(), "high", "high", false, 16, 0, true)
            }
            archive = OpenAiRawSseArchive.begin(ByteArrayInputStream("old source".toByteArray()),
                context.filesDir, chatId, oldRequest)
            archive.use { it.receiveRemaining() }
            ChatDatabase(context).use { db ->
                assertFalse(BackgroundRequestFailureRecorder.persist(db, chatId, oldRequest,
                    "resp_old", "failed", "late failure", context.filesDir))
                assertEquals(newRequest, db.getActiveResponse(chatId)!!.getString("request_id"))
                assertEquals(0, db.getChat(chatId)!!.getJSONArray("messages").length())
            }
            val newMessage = JSONObject()
            OpenAiRawSseArchive.attachToMessage(newMessage, context.filesDir, chatId, newRequest)
            assertFalse(newMessage.has("annotations"))
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
            archive?.file?.delete()
        }
    }
}
