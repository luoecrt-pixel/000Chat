package com.example.openaichat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.os.SystemClock
import android.widget.EditText
import androidx.core.content.FileProvider
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
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OpenAiLocalToolsIntegrationTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var previous: Map<String, Any?>
    private var oldKey = ""
    private val chats = mutableListOf<String>()
    private val files = mutableListOf<File>()
    private val key = "sk-synthetic-package-h"
    private val settings = listOf("provider", "model", "current_chat", "background_responses", "response_notifications",
        "reasoning", "text_format", "system_prompt", "instructions", "file_analysis_tools", "code_interpreter",
        "file_search", "web", "image_generation", "openai_auto_compaction", "openai_auto_compaction_opt_in_migrated_v25")

    @Before fun before() {
        previous = settings.associateWith { prefs.all[it] }
        oldKey = SecureStore(context).getApiKey()
        assertTrue(SecureStore(context).setApiKey(key))
        prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("reasoning", "high").putString("text_format", "text")
            .putString("system_prompt", "").putString("instructions", "")
            .putBoolean("background_responses", false).putBoolean("response_notifications", false)
            .putBoolean("file_analysis_tools", false).putBoolean("code_interpreter", false)
            .putBoolean("file_search", false).putBoolean("web", false).putBoolean("image_generation", false)
            .putBoolean("openai_auto_compaction", false).commit()
        val cert = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .url(server.url(chain.request().url.encodedPath)).build()) }.build()
        ResumableResponseStreamClient.transportForTests = http to server.url("/")
    }
    @After fun after() {
        ResumableResponseStreamClient.transportForTests = null
        SecureStore(context).setApiKey(oldKey)
        ChatDatabase(context).use { db -> chats.forEach { db.deleteChat(it); prefs.edit().remove("composer_state_$it").commit() } }
        prefs.edit().apply { previous.forEach { (k, v) -> when (v) {
            is String -> putString(k, v); is Boolean -> putBoolean(k, v); else -> remove(k)
        } } }.commit()
        files.forEach { it.delete() }
        server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }
    private fun source() = File(context.filesDir, "attachments/h-${UUID.randomUUID()}.txt").apply {
        parentFile!!.mkdirs(); writeText("Find needle in the complete local source 😀"); files.add(this)
    }.let { OpenRouterAttachmentStore.classifyLocalFile(context, it, it.name, "text/plain") }
    private fun toolResponse(fileId: String) = JSONObject().put("id", "resp_h_tools").put("status", "completed")
        .put("output", JSONArray().put(JSONObject().put("type", "reasoning").put("id", "rs_h").put("encrypted_content", "opaque-signed"))
            .put(JSONObject().put("type", "function_call").put("id", "fc_h").put("call_id", "call_h")
                .put("name", "search_attached_file").put("arguments", JSONObject().put("file_id", fileId).put("query", "needle").toString())))
    private fun finalResponse() = JSONObject().put("id", "resp_h_final").put("status", "completed").put("model", "gpt-6-astra")
        .put("output", JSONArray().put(JSONObject().put("type", "message").put("id", "msg_h").put("role", "assistant")
            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Found needle")))))
    private fun sse(response: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
            .put("response", JSONObject().put("id", response.getString("id"))) + "\n\ndata: " +
            JSONObject().put("type", "response.completed").put("sequence_number", 1).put("response", response) + "\n\n")
    private fun field(activity: MainActivity, name: String): Any = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun send(activity: MainActivity) {
        (field(activity, "input") as EditText).setText("Inspect attached sources")
        MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType).apply { isAccessible = true }.invoke(activity, false)
    }
    private fun assertContinuation() {
        val request = server.takeRequest(20, TimeUnit.SECONDS) ?: error("No tool continuation")
        val body = JSONObject(request.body.readUtf8())
        assertEquals("POST", request.method); assertEquals("resp_h_tools", body.getString("previous_response_id"))
        val output = body.getJSONArray("input").getJSONObject(0)
        assertEquals("function_call_output", output.getString("type")); assertEquals("call_h", output.getString("call_id"))
        assertTrue(JSONObject(output.getString("output")).getJSONArray("matches").getJSONObject(0).getString("snippet").contains("needle"))
    }
    private fun awaitFinal(chatId: String) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val message = ChatDatabase(context).use { it.getChat(chatId)?.optJSONArray("messages")?.let { all ->
                (0 until all.length()).map { all.getJSONObject(it) }.lastOrNull { it.optString("role") == "assistant" }
            } }
            if (message?.optString("response_id") == "resp_h_final") {
                assertEquals("Found needle", message.getString("text"))
                assertTrue(message.getJSONArray("openai_protocol_output").toString().contains("function_call_output"))
                return
            }
            SystemClock.sleep(50)
        }
        fail("Final tool response not committed")
    }

    @Test fun actualActivitySendExecutesLocalFileToolAndContinuesResponses() {
        val attachment = source()
        server.enqueue(sse(toolResponse(attachment.id))); server.enqueue(MockResponse().setBody(finalResponse().toString()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var chatId: String
            scenario.onActivity { activity ->
                activity.newChat(); chatId = activity.currentChatIdForTesting(); chats.add(chatId)
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
                @Suppress("UNCHECKED_CAST")
                (field(activity, "attachedOpenRouterAttachments") as MutableList<OpenRouterLocalAttachment>).add(attachment)
                send(activity)
            }
            val first = JSONObject(server.takeRequest(20, TimeUnit.SECONDS)!!.body.readUtf8())
            assertTrue(first.getJSONArray("tools").toString().contains("search_attached_file"))
            assertFalse(first.getJSONArray("input").toString().contains("Find needle in the complete"))
            assertContinuation(); awaitFinal(chatId)
        }
    }

    @Test fun workerReopensPaidFunctionCheckpointAndCommitsFinalToolAnswer() {
        val attachment = source()
        val chatId = "h-worker-${UUID.randomUUID()}"; val requestId = "request-h-worker"; chats.add(chatId)
        val fingerprint = OpenAiInstructionChainPolicy.fingerprint(GenerationInstructions("", "", null), key, "gpt-6-astra")
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chatId, "Worker tools", null, null, listOf(JSONObject().put("role", "user").put("text", "Find needle")
                .put("provider", "openai").put("request_id", requestId).put("model", "gpt-6-astra")
                .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint).put("attachments", JSONArray().put(attachment.toJson()))))
            db.setActiveResponse(chatId, "resp_h_tools", requestId, "", "in_progress", System.currentTimeMillis(), "high", "high", false, 11, 1, true)
            OpenAiLocalFileToolBridge.configure(db, chatId, requestId, JSONObject().put("model", "gpt-6-astra").put("store", true).put("background", true), listOf(attachment))
            val state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject().put("type", "response.completed").put("response", toolResponse(attachment.id))).state
            db.updateActiveResponse(chatId, "resp_h_tools", state.text, "completed", 1, requestId, ResponseIndexedCheckpoint.serialize(state))
        }
        server.enqueue(MockResponse().setBody(finalResponse().toString()))
        val worker = TestListenableWorkerBuilder<BackgroundResponseWorker>(context).setInputData(Data.Builder()
            .putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId).putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId)
            .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, "resp_h_tools").build()).build()
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker.doWork() })
        assertContinuation(); awaitFinal(chatId)
        assertEquals(1, server.requestCount)
    }

    @Test fun actualPhotoPickerPersistsTwoImagesAcrossRecreateAndSendsBothOnWire() {
        val bytes = java.util.Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMfkAAAAASUVORK5CYII=")
        val images = (1..2).map { n -> File(context.filesDir, "attachments/h-${UUID.randomUUID()}-$n.png").apply {
            parentFile!!.mkdirs(); writeBytes(bytes); files.add(this)
        } }
        server.enqueue(sse(finalResponse()))
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var chatId: String
            scenario.onActivity { activity ->
                activity.newChat(); chatId = activity.currentChatIdForTesting(); chats.add(chatId)
                val uris = images.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
                val clip = ClipData.newUri(context.contentResolver, "photos", uris[0]).apply { addItem(ClipData.Item(uris[1])) }
                activity.handleActivityResult(5101, Activity.RESULT_OK, Intent().apply { clipData = clip })
            }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var attached = false
            while (!attached && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { attached = (field(it, "attachedOpenRouterAttachments") as List<*>).size == 2 && it.pendingUploads.get() == 0 }
                if (!attached) SystemClock.sleep(50)
            }
            assertTrue("Both image sources must be prepared", attached)
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals(2, (field(activity, "attachedOpenRouterAttachments") as List<*>).size)
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
                send(activity)
            }
            val body = JSONObject(server.takeRequest(20, TimeUnit.SECONDS)!!.body.readUtf8())
            val inputs = body.getJSONArray("input")
            val content = (0 until inputs.length()).map { inputs.getJSONObject(it) }.last { it.optString("role") == "user" }.getJSONArray("content")
            val imageBlocks = (0 until content.length()).map { content.getJSONObject(it) }.filter { it.optString("type") == "input_image" }
            assertEquals(2, imageBlocks.size)
            imageBlocks.forEach { assertArrayEquals(bytes, java.util.Base64.getDecoder().decode(it.getString("image_url").substringAfter(","))) }
        }
    }
}
