package com.example.openaichat

import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual Send, onStart and Worker finalization must extract paid typed output before parsing. */
@RunWith(AndroidJUnit4::class)
class OpenAiEffectiveResponseIntegrationTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private val changedSettings = listOf("current_chat", "provider", "model", "reasoning", "text_format",
        "system_prompt", "instructions", "background_responses", "response_notifications", "file_analysis_tools",
        "web", "file_search", "code_interpreter", "image_generation")
    private lateinit var previous: Map<String, Any?>
    private var previousKey = ""
    private val ownedChats = mutableMapOf<String, String>()
    private val artifactFiles = mutableListOf<File>()
    private val image = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMfkAAAAASUVORK5CYII="
    private val rawBytes = "data: original paid source before terminal\n\n".toByteArray()

    @Before fun isolateSettings() {
        previous = changedSettings.associateWith { prefs.all[it] }
        previousKey = SecureStore(context).getApiKey()
        assertTrue(prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("reasoning", "high").putString("text_format", "text")
            .putString("system_prompt", "").putString("instructions", "")
            .putBoolean("background_responses", false).putBoolean("response_notifications", false)
            .putBoolean("file_analysis_tools", false).putBoolean("web", false).putBoolean("file_search", false)
            .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit())
        SecureStore(context).setApiKey("sk-effective-output-local-fixture")
    }

    @After fun restoreSettingsAndFixtures() {
        SecureStore(context).setApiKey(previousKey)
        assertTrue(prefs.edit().apply {
            previous.forEach { (key, value) -> when (value) {
                null -> remove(key)
                is String -> putString(key, value)
                is Boolean -> putBoolean(key, value)
            } }
        }.commit())
        ownedChats.forEach { (chatId, requestId) ->
            ChatDatabase(context).use { it.deleteChat(chatId) }
            OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
        }
        artifactFiles.forEach { it.delete() }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun signed() = JSONObject().put("type", "reasoning").put("id", "reasoning_paid")
        .put("encrypted_content", "exact-signed-paid-bytes")
    private fun tool() = JSONObject().put("type", "web_search_call").put("id", "tool_paid").put("status", "completed")
    private fun generated() = JSONObject().put("type", "image_generation_call").put("id", "image_paid")
        .put("status", "completed").put("result", image)
    private fun event(type: String, sequence: Int) = JSONObject().put("type", type).put("sequence_number", sequence)
    private fun frame(event: JSONObject) = "data: $event\n\n"

    @Test fun actualForegroundCancelledTerminalKeepsPaidImageToolsSummaryAndArchive() = actualSend(damagedFrame = false)
    @Test fun actualForegroundDecoderFailureKeepsPaidArtifactsAndOneDurableAssistantBubble() = actualSend(damagedFrame = true)

    private fun actualSend(damagedFrame: Boolean) {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build()) }.build()
        val responseId = "resp_effective_send"
        val source = frame(event("response.created", 0).put("response", JSONObject().put("id", responseId))) +
            frame(event("response.output_item.done", 1).put("output_index", 0).put("item", signed())) +
            frame(event("response.output_item.done", 2).put("output_index", 1).put("item", tool())) +
            frame(event("response.output_item.done", 3).put("output_index", 2).put("item", generated())) +
            frame(event("response.reasoning_summary_text.delta", 4).put("delta", "stream-only summary")) +
            frame(event("response.output_text.delta", 5).put("output_index", 3).put("content_index", 0).put("delta", "paid partial")) +
            if (damagedFrame) "data: { definitely damaged JSON }\n\n" else
                frame(event("response.cancelled", 6).put("response", JSONObject().put("id", responseId).put("status", "cancelled")))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(source))
        var chatId = ""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = prefs.getString("current_chat", "")!!
                    field("client").set(activity, http)
                    (field("input").get(activity) as EditText).setText("Paid typed fallback fixture")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                assertEquals("POST", server.takeRequest(20, TimeUnit.SECONDS)!!.method)
                val requestId = ChatDatabase(context).use { it.listRequestAccounting(chatId).single().getString("local_request_id") }
                ownedChats[chatId] = requestId
                waitForFinalization(chatId, requestId)
                instrumentation.waitForIdleSync()
                assertArtifacts(chatId, requestId, if (damagedFrame) "failed" else "cancelled", source.toByteArray())
                scenario.onActivity { activity ->
                    val adapter = field("messageAdapter").get(activity) as ChatMessageAdapter
                    val items = ChatMessageAdapter::class.java.getDeclaredField("items").apply { isAccessible = true }
                        .get(adapter) as List<*>
                    assertEquals("One user and one saved assistant; no second transient error bubble", 2, items.size)
                    assertTrue(activity.send.isEnabled)
                }
                assertEquals("No paid retry or parallel recovery", 1, server.requestCount)
            }
        } finally {
            server.shutdown()
        }
    }

    private fun seedCancelledTerminal(): Triple<String, String, String> {
        val chatId = "effective-terminal-" + UUID.randomUUID()
        val requestId = "req_cancelled_checkpoint"
        val responseId = "resp_cancelled_checkpoint"
        ownedChats[chatId] = requestId
        var state = ResponseEventState(responseId = responseId)
        listOf(signed(), tool(), generated()).forEachIndexed { index, item ->
            state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_item.done")
                .put("output_index", index).put("item", item)).state
        }
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_text.delta")
            .put("output_index", 3).put("content_index", 0).put("delta", "paid partial")).state
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.reasoning_summary_text.delta")
            .put("delta", "stream-only summary")).state
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.cancelled")
            .put("response", JSONObject().put("id", responseId).put("status", "cancelled"))).state
        ChatDatabase(context).use { db ->
            db.ensureChat(chatId, "Cancelled typed checkpoint")
            db.upsertCurrentChat(chatId, "Cancelled typed checkpoint", "resp_previous_completed", "high", listOf(
                JSONObject().put("role", "user").put("text", "Prompt").put("provider", "openai")
                    .put("request_id", requestId).put("requested_model", "gpt-6-astra")
                    .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD,
                        OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("", "", ""), SecureStore(context).getApiKey(), "gpt-6-astra"))))
            db.setActiveResponse(chatId, responseId, requestId, "", "starting", System.currentTimeMillis(),
                "high", "high", false, 6, 0, true)
            db.updateActiveResponse(chatId, responseId, state.text, "cancelled", 6, requestId,
                ResponseIndexedCheckpoint.serialize(state))
            assertEquals("cancelled", db.getActiveResponse(chatId)!!.getString("status"))
        }
        OpenAiRawSseArchive.begin(ByteArrayInputStream(rawBytes), context.filesDir, chatId, requestId)
            .use { it.receiveRemaining() }
        return Triple(chatId, requestId, responseId)
    }

    @Test fun actualOnStartFinalizesReopenedCancelledCheckpointWithPaidTypedArtifactsAndNoKey() {
        val (chatId, requestId, _) = seedCancelledTerminal()
        SecureStore(context).setApiKey("")
        assertTrue(prefs.edit().putString("current_chat", chatId).commit())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            waitForFinalization(chatId, requestId)
            instrumentation.waitForIdleSync()
            assertArtifacts(chatId, requestId, "cancelled", rawBytes)
            scenario.onActivity { activity -> assertTrue(activity.send.isEnabled) }
        }
    }

    @Test fun realWorkerFinalizesReopenedCancelledCheckpointWithPaidTypedArtifactsAndNoKey() {
        val (chatId, requestId, responseId) = seedCancelledTerminal()
        SecureStore(context).setApiKey("")
        val worker = TestListenableWorkerBuilder<BackgroundResponseWorker>(context).setInputData(Data.Builder()
            .putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId)
            .putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId)
            .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, responseId).build()).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertArtifacts(chatId, requestId, "cancelled", rawBytes)
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        ChatDatabase(context).use { assertEquals(2, it.getChat(chatId)!!.getJSONArray("messages").length()) }
    }

    private fun waitForFinalization(chatId: String, requestId: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        var done = false
        while (SystemClock.elapsedRealtime() < deadline) {
            done = ChatDatabase(context).use { !it.isActiveRequest(chatId, requestId) }
            if (done) break
            SystemClock.sleep(20)
        }
        assertTrue("The original request must finalize without a paid restart", done)
    }

    private fun assertArtifacts(chatId: String, requestId: String, status: String, expectedRaw: ByteArray) {
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            val chat = db.getChat(chatId)!!
            assertTrue("Only completed responses may establish a server chain", JsonProtocol.nonBlank(chat, "response_id") == null)
            val messages = chat.getJSONArray("messages")
            assertEquals("Exactly one durable assistant", 2, messages.length())
            val assistant = messages.getJSONObject(1)
            assertEquals(requestId, assistant.getString("request_id"))
            assertEquals(status, assistant.getString("status"))
            assertEquals("paid partial", assistant.getString("text"))
            assertEquals("stream-only summary", assistant.getString("reasoning_summary"))
            val output = assistant.getJSONArray("openai_protocol_output")
            assertEquals(3, output.length())
            assertEquals("exact-signed-paid-bytes", output.getJSONObject(0).getString("encrypted_content"))
            assertEquals("tool_paid", output.getJSONObject(1).getString("id"))
            assertEquals(image, output.getJSONObject(2).getString("result"))
            assertTrue(assistant.getJSONArray("tools").toString().contains("Web Search"))
            assertTrue(assistant.getJSONArray("tools").toString().contains("Image Generation"))
            val generated = assistant.getJSONArray("generated_images").getJSONObject(0)
            assertEquals("READY", generated.getString("state"))
            val file = File(generated.getString("local_path"))
            artifactFiles += file
            assertArrayEquals(android.util.Base64.decode(image, android.util.Base64.DEFAULT), file.readBytes())
            val raw = assistant.getJSONArray("annotations").let { annotations ->
                (0 until annotations.length()).map { annotations.getJSONObject(it) }
                    .single { it.optString("filename").endsWith(".sse") }
            }
            assertArrayEquals(expectedRaw, File(raw.getString("local_path")).readBytes())
            assertEquals(status, db.listRequestAccounting(chatId).single().getString("local_status"))
        }
    }
}
