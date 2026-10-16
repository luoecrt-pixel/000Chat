package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** SQLite reopen + real CoroutineWorker + actual bridge/HTTP, without a live provider. */
@RunWith(AndroidJUnit4::class)
class OpenAiWorkerToolRecoveryTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private var previousKey = ""
    private var previousNotifications: Boolean? = null
    private val chats = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val key = "sk-synthetic-package-j-worker"
    private val parentId = "resp_j_worker_parent"
    private val childId = "resp_j_worker_child"

    @Before fun before() {
        previousKey = SecureStore(context).getApiKey()
        previousNotifications = prefs.all["response_notifications"] as? Boolean
        assertTrue(SecureStore(context).setApiKey(key))
        prefs.edit().putBoolean("response_notifications", false).commit()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        ResumableResponseStreamClient.transportForTests = http to server.url("/")
    }

    @After fun after() {
        ResumableResponseStreamClient.transportForTests = null
        SecureStore(context).setApiKey(previousKey)
        prefs.edit().apply {
            previousNotifications?.let { putBoolean("response_notifications", it) } ?: remove("response_notifications")
        }.commit()
        ChatDatabase(context).use { db -> chats.forEach(db::deleteChat) }
        files.forEach(File::delete)
        server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun source() = File(context.filesDir, "attachments/j-worker-${UUID.randomUUID()}.txt").apply {
        parentFile!!.mkdirs(); writeText("original worker needle source"); files.add(this)
    }.let { OpenRouterAttachmentStore.classifyLocalFile(context, it, it.name, "text/plain") }
    private fun toolResponse(fileId: String) = JSONObject().put("id", parentId).put("status", "completed")
        .put("usage", JSONObject().put("input_tokens", 13).put("output_tokens", 17))
        .put("output", JSONArray()
            .put(JSONObject().put("type", "reasoning").put("id", "rs_j_worker").put("encrypted_content", "opaque-j-worker-signed"))
            .put(JSONObject().put("type", "function_call").put("id", "fc_j_worker").put("call_id", "call_j_worker")
                .put("name", "search_attached_file").put("arguments", JSONObject().put("file_id", fileId).put("query", "needle").toString())))
    private fun finalResponse() = JSONObject().put("id", childId).put("status", "completed").put("model", "gpt-6-astra")
        .put("usage", JSONObject().put("input_tokens", 19).put("output_tokens", 23))
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("id", "msg_j_worker").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Worker recovered child")))))
    private fun sse(response: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
            .put("response", JSONObject().put("id", response.getString("id"))) + "\n\ndata: " +
            JSONObject().put("type", "response.completed").put("sequence_number", 1).put("response", response) + "\n\n")
    private fun request() = server.takeRequest(5, TimeUnit.SECONDS) ?: error("Expected worker Responses request")
    private fun worker(chatId: String, requestId: String) = TestListenableWorkerBuilder<BackgroundResponseWorker>(context)
        .setInputData(Data.Builder().putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId)
            .putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId)
            .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, childId).build()).build()

    /** Reach the waiting state through the production bridge, then close every DB helper. */
    private fun seedWaitingChild(): Pair<String, String> {
        val attachment = source()
        val chatId = "j-worker-${UUID.randomUUID()}".also(chats::add)
        val requestId = "j-worker-request-${UUID.randomUUID()}"
        val fingerprint = OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("", "", null), key, "gpt-6-astra")
        server.enqueue(MockResponse().setBody(JSONObject().put("id", childId).put("status", "queued").toString()))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", 1).setBody("temporary"))
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chatId, "Worker GET recovery", null, null, listOf(JSONObject().put("role", "user")
                .put("text", "Inspect original source").put("provider", "openai").put("model", "gpt-6-astra")
                .put("request_id", requestId).put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint)
                .put("attachments", JSONArray().put(attachment.toJson()))))
            db.setActiveResponse(chatId, parentId, requestId, "", "completed", System.currentTimeMillis(), "high", "high", false, 23, 1, true)
            OpenAiLocalFileToolBridge.configure(db, chatId, requestId,
                JSONObject().put("model", "gpt-6-astra").put("store", true).put("background", true), listOf(attachment))
            try {
                OpenAiLocalFileToolBridge.finish(context, db, chatId, requestId, toolResponse(attachment.id), http)
                fail("First child GET must fail and leave its waiting checkpoint")
            } catch (error: ApiHttpException) { assertEquals(503, error.statusCode) }
            val saved = db.getOpenAiToolCheckpoint(chatId, requestId)!!
            assertEquals("waiting", saved.getString("phase")); assertEquals(childId, saved.getString("response_id"))
            assertEquals(childId, db.getActiveResponse(chatId)!!.getString("response_id"))
            assertTelemetry(saved.getJSONObject("telemetry"))
        }
        val post = request()
        assertEquals("POST", post.method); assertEquals("/v1/responses", post.path)
        val body = JSONObject(post.body.readUtf8())
        assertEquals(parentId, body.getString("previous_response_id"))
        assertEquals("function_call_output", body.getJSONArray("input").getJSONObject(0).getString("type"))
        val get = request(); assertEquals("GET", get.method); assertEquals("/v1/responses/$childId", get.path)
        return chatId to requestId
    }

    private fun assertTelemetry(telemetry: JSONObject) {
        assertEquals(1, telemetry.getInt("rounds_completed")); assertEquals(1, telemetry.getInt("calls_executed"))
        assertEquals(1, telemetry.getJSONArray("executed_tools").length())
        assertEquals("search_attached_file", telemetry.getJSONArray("executed_tools").getString(0))
    }

    @Test fun reopenedWorkerRetriesWaitingChild429And503AsGetsWithoutDuplicatingPaidPostOrTelemetry() {
        val (chatId, requestId) = seedWaitingChild()
        val prior = ChatDatabase(context).use { it.getOpenAiToolCheckpoint(chatId, requestId)!!.getJSONArray("prior_output").toString() }
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", 1).setBody("rate limited"))
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After", 1).setBody("temporary"))
        server.enqueue(sse(finalResponse()))
        assertEquals(ListenableWorker.Result.success(), runBlocking { withTimeout(15_000L) { worker(chatId, requestId).doWork() } })
        repeat(3) {
            val get = request()
            assertEquals("GET", get.method); assertEquals("/v1/responses/$childId", get.path!!.substringBefore('?'))
            assertEquals("text/event-stream", get.getHeader("Accept"))
            assertEquals("Bearer $key", get.getHeader("Authorization"))
        }
        assertEquals("One continuation POST was paid before reopening; recovery must only GET", 5, server.requestCount)
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            val messages = db.getChat(chatId)!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            val assistant = messages.getJSONObject(1)
            assertEquals(childId, assistant.getString("response_id")); assertEquals("Worker recovered child", assistant.getString("text"))
            val output = assistant.getJSONArray("openai_protocol_output")
            assertEquals("opaque-j-worker-signed", output.getJSONObject(0).getString("encrypted_content"))
            assertEquals("call_j_worker", output.getJSONObject(1).getString("call_id"))
            assertEquals("function_call_output", output.getJSONObject(2).getString("type"))
            assertEquals("call_j_worker", output.getJSONObject(2).getString("call_id"))
            assertTrue(JSONObject(output.getJSONObject(2).getString("output")).getJSONArray("matches").length() > 0)
            assertEquals(32, assistant.getJSONObject("usage").getInt("input_tokens"))
            assertEquals(40, assistant.getJSONObject("usage").getInt("output_tokens"))
            assertNull(db.getOpenAiToolCheckpoint(chatId, requestId))
            assertTelemetry(assistant.getJSONObject("telemetry_summary"))
            assertEquals(32, OpenAiUsageLedger.total(assistant.getJSONObject(OpenAiUsageLedger.FIELD))!!.getInt("input_tokens"))
            assertEquals(32, db.listRequestAccounting(chatId).single().getInt("input_tokens"))
        }
        // A duplicate WorkManager execution must neither create nor GET anything or append another assistant.
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(chatId, requestId).doWork() })
        assertNull(server.takeRequest(1_000, TimeUnit.MILLISECONDS))
        ChatDatabase(context).use { assertEquals(2, it.getChat(chatId)!!.getJSONArray("messages").length()) }
    }

    @Test fun reopenedWorkerRejectsChangedCredentialBeforeReadingWaitingChildOrPostingAgain() {
        val (chatId, requestId) = seedWaitingChild()
        assertTrue(SecureStore(context).setApiKey("sk-synthetic-other-worker-account"))
        assertEquals(ListenableWorker.Result.failure(), runBlocking { withTimeout(10_000L) { worker(chatId, requestId).doWork() } })
        assertNull(server.takeRequest(1_000, TimeUnit.MILLISECONDS)); assertEquals(2, server.requestCount)
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            val assistant = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(1)
            assertEquals("failed", assistant.getString("status"))
            assertEquals("opaque-j-worker-signed", assistant.getJSONArray("openai_protocol_output")
                .getJSONObject(0).getString("encrypted_content"))
            assertNull(db.getOpenAiToolCheckpoint(chatId, requestId))
            assertTelemetry(assistant.getJSONObject("telemetry_summary"))
            assertEquals(13, assistant.getJSONObject("usage").getInt("input_tokens"))
        }
    }

    @Test fun finishedCheckpointCommitsAfterReopenWithoutNetworkAndRetainsSignedOutputTextImagesAndLedger() {
        val (chatId, requestId) = seedWaitingChild()
        fun png(color: Int): ByteArray {
            val bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888)
            return try {
                bitmap.eraseColor(color)
                java.io.ByteArrayOutputStream().use { output ->
                    assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output))
                    output.toByteArray()
                }
            } finally { bitmap.recycle() }
        }
        val imageBytes = listOf(png(android.graphics.Color.RED), png(android.graphics.Color.BLUE))
        ChatDatabase(context).use { db ->
            val terminal = finalResponse()
            val output = terminal.getJSONArray("output")
            output.put(JSONObject().put("type", "reasoning").put("id", "rs_paid_child")
                .put("encrypted_content", "opaque-paid-child-final"))
            imageBytes.forEachIndexed { index, bytes ->
                output.put(JSONObject().put("type", "image_generation_call").put("id", "ig_paid_$index")
                    .put("result", android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)))
            }
            val finished = OpenAiLocalFileToolBridge.finish(context, db, chatId, requestId, terminal, http)
            assertEquals("finished", db.getOpenAiToolCheckpoint(chatId, requestId)!!.getString("phase"))
            assertEquals(32, finished.getJSONObject("usage").getInt("input_tokens"))
        }
        assertEquals(2, server.requestCount)
        assertEquals(ListenableWorker.Result.success(), runBlocking { withTimeout(10_000L) { worker(chatId, requestId).doWork() } })
        assertNull(server.takeRequest(1_000L, TimeUnit.MILLISECONDS))
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId)); assertNull(db.getOpenAiToolCheckpoint(chatId, requestId))
            val messages = db.getChat(chatId)!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            val assistant = messages.getJSONObject(1)
            assertEquals("Worker recovered child", assistant.getString("text"))
            val output = assistant.getJSONArray("openai_protocol_output")
            assertEquals("opaque-j-worker-signed", output.getJSONObject(0).getString("encrypted_content"))
            assertTrue((0 until output.length()).mapNotNull { output.optJSONObject(it) }
                .any { it.optString("encrypted_content") == "opaque-paid-child-final" })
            val generated = assistant.getJSONArray("generated_images")
            assertEquals(imageBytes.size, generated.length())
            for (i in imageBytes.indices) {
                val image = generated.getJSONObject(i)
                assertEquals("READY", image.getString("state"))
                val saved = File(image.getString("local_path")).also(files::add)
                assertArrayEquals(imageBytes[i], saved.readBytes())
            }
            assertEquals(32, assistant.getJSONObject("usage").getInt("input_tokens"))
            assertEquals(40, assistant.getJSONObject("usage").getInt("output_tokens"))
            assertEquals(32, OpenAiUsageLedger.total(assistant.getJSONObject(OpenAiUsageLedger.FIELD))!!.getInt("input_tokens"))
            val accounting = db.listRequestAccounting(chatId).single()
            assertEquals(32, accounting.getInt("input_tokens")); assertEquals(40, accounting.getInt("output_tokens"))
            assertTelemetry(accounting.getJSONObject("telemetry_summary"))
            assertEquals(32, db.listRequestEvents(chatId).single().getInt("input_tokens"))
        }
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker(chatId, requestId).doWork() })
        assertEquals(2, server.requestCount)
    }
}
