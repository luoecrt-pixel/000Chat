package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterArtifactRuntimeTest {
    private lateinit var context: Context
    private lateinit var metadataClient: OkHttpClient
    private lateinit var originals: File
    private val chat = "artifact-runtime"
    private val requestId = "request-artifact-runtime"
    private val derivative = AtomicReference<File?>()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putBoolean("response_notifications", false).commit()
        metadataClient = OkHttpClient()
        originals = File(context.filesDir, "generated/artifact-runtime").apply { mkdirs() }
    }

    @After fun teardown() {
        try {
            OpenRouterResponseRuntime.cancel(requestId)
            awaitRuntimeAndCleanup()
        } finally {
            metadataClient.dispatcher.executorService.shutdownNow()
            metadataClient.connectionPool.evictAll()
            metadataClient.cache?.close()
            originals.deleteRecursively()
            SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        }
    }

    private fun runtimeRequest() = OpenRouterRuntimeRequest(
        chatId = chat, requestId = requestId, model = "test-model",
        messages = JSONArray().put(JSONObject().put("role", "user").put("content", "Read the report")),
        attachments = emptyList(), effort = "high", verbosity = "", maxTokens = null,
        github = false, githubRepo = "", githubBranches = emptySet(), webSearch = false,
        webFetch = false, parallel = false, cacheMode = "off", background = false,
        startedAt = SystemClock.elapsedRealtime(), taskClass = "general", effortPref = "high",
        autoSelected = false, promptChars = 15, apiKey = "mock-key",
        metadataClient = metadataClient, pdfEngine = "auto"
    )

    private fun sse(frame: JSONObject) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: $frame\n\ndata: [DONE]\n\n")

    private fun toolCall(index: Int, id: String, name: String, args: JSONObject) = JSONObject()
        .put("index", index).put("id", id).put("type", "function")
        .put("function", JSONObject().put("name", name).put("arguments", args.toString()))

    private fun toolRound(id: String, vararg calls: JSONObject) = sse(JSONObject().put("id", id)
        .put("choices", JSONArray().put(JSONObject()
            .put("delta", JSONObject().put("tool_calls", JSONArray(calls.toList())))
            .put("finish_reason", "tool_calls"))))

    private fun finalRound() = sse(JSONObject().put("id", "gen-artifact-final").put("model", "test-model")
        .put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "done"))
            .put("finish_reason", "stop"))))

    private fun toolResult(body: String, callId: String): JSONObject {
        val messages = JSONObject(body).getJSONArray("messages")
        val result = (0 until messages.length()).map { messages.getJSONObject(it) }
            .single { it.optString("role") == "tool" && it.optString("tool_call_id") == callId }
        return JSONObject(result.getString("content"))
    }

    private fun awaitRuntimeAndCleanup() {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            // work.remove happens before lease.release; registry removal alone is insufficient.
            val finished = !OpenRouterResponseRuntime.contains(requestId)
            val terminal = ChatDatabase(context).use { it.getActiveResponse(chat) == null }
            if (finished && terminal) return
            Thread.sleep(10)
        }
        assertFalse("Runtime did not finish", OpenRouterResponseRuntime.contains(requestId))
        ChatDatabase(context).use { assertNull("Request did not reach DB terminal state", it.getActiveResponse(chat)) }
    }

    @Test fun actualRuntimeNormalizesChatArtifactsAndRetainsDurableManifestForTheNextRequest() {
        val text = "Отчёт по эксперименту №1\nРезультат: успешно"
        val sourceBytes = text.toByteArray(Charset.forName("windows-1251"))
        val original = File(originals, "report-cp1251.txt").apply { writeBytes(sourceBytes) }
        val foreignText = "This belongs to a different chat"
        val foreign = File(originals, "foreign-secret.txt").apply { writeText(foreignText) }
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.ensureChat("foreign-artifact-chat")
            for ((owner, file) in listOf(chat to original, "foreign-artifact-chat" to foreign)) {
                assertTrue(db.registerLocalArtifact(owner, "previous-request", JSONObject()
                    .put("filename", file.name).put("path", file.path)
                    .put("mime_type", "text/plain").put("size_bytes", file.length())))
            }
            db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, requestId, "openrouter", "test-model", "openrouter_api_key"))
        }
        val dispatchFailure = AtomicReference<Throwable?>()
        val rounds = AtomicInteger()
        val activePaths = AtomicReference<Set<String>>()
        val listedId = AtomicReference<String>()
        val actualRead = AtomicReference<JSONObject>()
        val foreignRead = AtomicReference<JSONObject>()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    when (rounds.getAndIncrement()) {
                        0 -> toolRound("gen-artifact-list", toolCall(0, "call-artifact-list", "file_list", JSONObject()))
                        1 -> {
                            val listed = toolResult(request.body.readUtf8(), "call-artifact-list")
                            assertTrue(listed.getBoolean("ok"))
                            val files = listed.getJSONArray("files")
                            assertEquals("Foreign-chat artifact must not enter this runtime", 1, files.length())
                            val item = files.getJSONObject(0)
                            assertEquals(original.name, item.getString("filename"))
                            assertEquals("text", item.getString("kind"))
                            assertEquals("windows-1251", item.getString("source_encoding"))
                            listedId.set(item.getString("file_id"))
                            assertTrue(OpenRouterResponseRuntime.contains(requestId))
                            activePaths.set(OpenRouterResponseRuntime.localFilePaths())
                            assertTrue(original.path in activePaths.get())
                            assertFalse(foreign.path in activePaths.get())
                            val prepared = activePaths.get().filter { it != original.path }.single()
                            derivative.set(File(prepared))
                            assertEquals(File(context.filesDir, "attachments").canonicalFile, derivative.get()!!.parentFile!!.canonicalFile)
                            assertTrue(derivative.get()!!.name.startsWith("derived_"))
                            assertEquals(text, derivative.get()!!.readText(Charsets.UTF_8))
                            // Even an ownership scan with no durable/active references must respect the live lease.
                            PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
                            assertTrue("Live runtime lease must protect its derivative", derivative.get()!!.isFile)
                            toolRound("gen-artifact-read",
                                toolCall(0, "call-artifact-read", "file_read_range", JSONObject().put("file_id", listedId.get())),
                                toolCall(1, "call-foreign-read", "file_read_range", JSONObject().put("file_id", "local_${foreign.name}")))
                        }
                        2 -> {
                            // The next upstream request contains the actual runtime tool results.
                            val body = request.body.readUtf8()
                            actualRead.set(toolResult(body, "call-artifact-read"))
                            foreignRead.set(toolResult(body, "call-foreign-read"))
                            assertTrue(actualRead.get().getBoolean("ok"))
                            assertEquals(listedId.get(), actualRead.get().getString("file_id"))
                            assertEquals(text, actualRead.get().getString("content"))
                            assertFalse(foreignRead.get().getBoolean("ok"))
                            assertFalse(foreignRead.get().has("content"))
                            assertTrue(derivative.get()!!.isFile)
                            assertTrue(derivative.get()!!.path in OpenRouterResponseRuntime.localFilePaths())
                            finalRound()
                        }
                        else -> throw AssertionError("Unexpected additional tool round")
                    }
                } catch (failure: Throwable) {
                    dispatchFailure.compareAndSet(null, failure)
                    finalRound()
                }
            }
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
                awaitRuntimeAndCleanup()
            } finally {
                OpenRouterResponseRuntime.cancel(requestId)
                try {
                    awaitRuntimeAndCleanup()
                } finally {
                    if (!OpenRouterResponseRuntime.uses(client)) client.close()
                }
            }
            dispatchFailure.get()?.let { throw AssertionError("Runtime artifact exchange failed", it) }
            assertEquals(3, server.requestCount)
            assertNotNull("Runtime never exposed its normalized artifact", derivative.get())
            assertNotNull("Runtime never executed file_read_range", actualRead.get())
            assertTrue("The artifact manifest owns its reusable derivative", derivative.get()!!.exists())
            assertArrayEquals("Original CP1251 bytes must stay intact", sourceBytes, original.readBytes())
            assertEquals(foreignText, foreign.readText())
            ChatDatabase(context).use { db ->
                val assistant = db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals("completed", assistant.getString("status"))
                assertEquals("done", assistant.getString("text"))
                assertEquals(setOf(original.path, foreign.path), db.listLocalFiles().map { it.getString("path") }.toSet())
                assertTrue(derivative.get()!!.path in db.listAllLocalFilePaths())
                val saved = db.listLocalFiles(chat).single()
                val reused = LocalArtifactAttachmentResolver.resolve(context, db, chat, saved)
                assertEquals(derivative.get()!!.path, reused.textPath)
                db.deleteChat(chat)
                assertFalse(derivative.get()!!.path in db.listAllLocalFilePaths())
            }
        }
    }
}
