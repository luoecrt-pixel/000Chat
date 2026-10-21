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

    private fun awaitRuntimeAndCleanup(ownerRequestId: String = requestId) {
        val deadline = System.nanoTime() + 10_000_000_000L
        while (System.nanoTime() < deadline) {
            // work.remove happens before lease.release; registry removal alone is insufficient.
            val finished = !OpenRouterResponseRuntime.contains(ownerRequestId)
            val terminal = ChatDatabase(context).use { it.getActiveResponse(chat) == null }
            if (finished && terminal) return
            Thread.sleep(10)
        }
        assertFalse("Runtime did not finish", OpenRouterResponseRuntime.contains(ownerRequestId))
        ChatDatabase(context).use { assertNull("Request did not reach DB terminal state", it.getActiveResponse(chat)) }
    }

    @Test fun actualRuntimeCanPatchRevertedAndNoOpVersionsByTheirReturnedExactIds() {
        val original = LocalArtifactToolExecutor(context).createTextFile(JSONObject()
            .put("filename", "reverted-runtime.txt").put("content", "a"))
        ChatDatabase(context).use { db ->
            db.ensureChat(chat, "Version identities")
            assertTrue(db.registerLocalArtifact(chat, requestId, original))
            db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, requestId, "openrouter", "test-model", "openrouter_api_key"))
        }
        val failure = AtomicReference<Throwable?>()
        val phase = AtomicInteger()
        val ids = mutableListOf(original.getString("file_id"))
        val replacements = listOf("a" to "b", "b" to "a", "a" to "a", "a" to "c")
        try {
            MockWebServer().use { server ->
                server.dispatcher = object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse = try {
                        val step = phase.getAndIncrement()
                        if (step in 1..4) {
                            val descriptor = toolResult(request.body.readUtf8(), "version-${step - 1}")
                            assertTrue(descriptor.toString(), descriptor.getBoolean("ok"))
                            val id = descriptor.getString("file_id")
                            assertFalse("Every retained version has its own exact ID", id in ids)
                            ids.add(id)
                        }
                        if (step < replacements.size) {
                            val (search, replacement) = replacements[step]
                            toolRound("gen-version-$step", toolCall(0, "version-$step", "patch_local_file",
                                JSONObject().put("file_id", ids.last()).put("patches", JSONArray()
                                    .put(JSONObject().put("search", search).put("replace", replacement)))))
                        } else if (step == 4) {
                            toolRound("gen-version-read", toolCall(0, "read-final-version", "file_read_range",
                                JSONObject().put("file_id", ids.last())))
                        } else {
                            val result = toolResult(request.body.readUtf8(), "read-final-version")
                            assertTrue(result.getBoolean("ok")); assertEquals("c", result.getString("content"))
                            finalRound()
                        }
                    } catch (error: Throwable) { failure.compareAndSet(null, error); finalRound() }
                }
                val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
                OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
                awaitRuntimeAndCleanup()
                failure.get()?.let { throw AssertionError("Version identity runtime failed", it) }
                assertEquals(6, server.requestCount)
                ChatDatabase(context).use { db ->
                    val versions = db.listLocalFiles(chat).associateBy { it.getString("file_id") }
                    assertEquals(5, versions.size)
                    assertEquals(listOf("a", "b", "a", "a", "c"), ids.map { File(versions.getValue(it).getString("path")).readText() })
                }
            }
        } finally {
            ChatDatabase(context).use { db -> db.listLocalFiles(chat).forEach { value ->
                File(value.getString("path")).delete()
                JsonProtocol.nonBlank(value, "patch_diff_path")?.let { File(it).delete() }
            } }
        }
    }

    @Test fun actualRuntimeCreatesPatchesAndReadsNewVersionThenReopenedNextTurnReadsTheSameBytes() {
        val originalText = "alpha\nbefore\nomega\n"
        val updatedText = "alpha\nafter\nomega\n"
        val nextRequestId = "request-artifact-next-turn"
        ChatDatabase(context).use { db ->
            db.upsertCurrentChat(chat, "Patch runtime", null, null, listOf(JSONObject().put("role", "user")
                .put("text", "Create and patch").put("request_id", requestId).put("provider", "openrouter")))
            db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "high", "high", false, 0, 0, false)
            assertTrue(db.startRequestAccounting(chat, requestId, "openrouter", "test-model", "openrouter_api_key"))
        }
        val failure = AtomicReference<Throwable?>()
        val created = AtomicReference<JSONObject>()
        val patched = AtomicReference<JSONObject>()
        val phase = AtomicInteger()
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = try {
                    when (phase.getAndIncrement()) {
                        0 -> toolRound("gen-create", toolCall(0, "call-create", "create_local_text_file",
                            JSONObject().put("filename", "runtime-report.txt").put("content", originalText)))
                        1 -> {
                            val descriptor = toolResult(request.body.readUtf8(), "call-create").also(created::set)
                            assertTrue(descriptor.getBoolean("ok")); assertFalse(descriptor.has("path"))
                            val patch = JSONObject().put("file_id", descriptor.getString("file_id"))
                                .put("expected_sha256", descriptor.getString("sha256"))
                                .put("patches", JSONArray().put(JSONObject().put("search", "before").put("replace", "after")))
                            toolRound("gen-patch", toolCall(0, "call-patch", "patch_local_file", patch),
                                toolCall(1, "call-foreign", "patch_local_file", JsonProtocol.objectCopy(patch)
                                    .put("file_id", "file-not-in-this-chat")))
                        }
                        2 -> {
                            val body = request.body.readUtf8()
                            val descriptor = toolResult(body, "call-patch").also(patched::set)
                            assertTrue(descriptor.getBoolean("ok")); assertFalse(descriptor.has("path"))
                            assertFalse(descriptor.has("attachment")); assertFalse(descriptor.has("patch_diff_path"))
                            assertFalse(toolResult(body, "call-foreign").getBoolean("ok"))
                            assertNotEquals(created.get().getString("file_id"), descriptor.getString("file_id"))
                            toolRound("gen-read-patch", toolCall(0, "call-read-new", "file_read_range",
                                JSONObject().put("file_id", descriptor.getString("file_id"))))
                        }
                        3 -> {
                            val result = toolResult(request.body.readUtf8(), "call-read-new")
                            assertTrue(result.getBoolean("ok")); assertEquals(updatedText, result.getString("content"))
                            finalRound()
                        }
                        4 -> toolRound("gen-next-turn", toolCall(0, "call-read-reopened", "file_read_range",
                            JSONObject().put("file_id", patched.get().getString("file_id"))))
                        5 -> {
                            val result = toolResult(request.body.readUtf8(), "call-read-reopened")
                            assertTrue(result.getBoolean("ok")); assertEquals(updatedText, result.getString("content"))
                            finalRound()
                        }
                        else -> throw AssertionError("Unexpected request")
                    }
                } catch (error: Throwable) { failure.compareAndSet(null, error); finalRound() }
            }
            val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
            try {
                OpenRouterResponseRuntime.start(context, runtimeRequest(), client)
                awaitRuntimeAndCleanup()
                failure.get()?.let { throw AssertionError("Patch runtime failed", it) }
                assertEquals(4, server.requestCount)
                ChatDatabase(context).use { reopened ->
                    val records = reopened.listLocalFiles(chat)
                    assertEquals("The denied foreign patch must create no version", 2, records.size)
                    val old = records.single { it.getString("file_id") == created.get().getString("file_id") }
                    val version = records.single { it.getString("file_id") == patched.get().getString("file_id") }
                    assertEquals(originalText, File(old.getString("path")).readText())
                    assertEquals(updatedText, File(version.getString("path")).readText())
                    val assistant = reopened.getChat(chat)!!.getJSONArray("messages").getJSONObject(1)
                    val annotations = assistant.getJSONArray("annotations")
                    assertEquals(2, annotations.length())
                    val patchAnnotation = (0 until annotations.length()).map { annotations.getJSONObject(it) }
                        .single { it.has("patch_diff_path") }
                    assertEquals(version.getString("sha256"), patchAnnotation.getString("sha256"))
                    val preview = LocalArtifactDiffPanel.readPreview(context, patchAnnotation.getString("patch_diff_path"),
                        patchAnnotation.getString("sha256"))
                    assertTrue(preview.lines.any { it.text == "- before" })
                    assertTrue(preview.lines.any { it.text == "+ after" })
                    assertTrue(reopened.listAllLocalFilePaths().containsAll(listOf(old.getString("path"),
                        version.getString("path"), version.getString("patch_diff_path"))))
                    reopened.setActiveResponse(chat, null, nextRequestId, "", "in_progress", System.currentTimeMillis(),
                        "high", "high", false, 0, 0, false)
                    assertTrue(reopened.startRequestAccounting(chat, nextRequestId, "openrouter", "test-model", "openrouter_api_key"))
                }
                OpenRouterResponseRuntime.start(context, runtimeRequest().copy(requestId = nextRequestId), client)
                awaitRuntimeAndCleanup(nextRequestId)
                failure.get()?.let { throw AssertionError("Reopened patch runtime failed", it) }
                assertEquals(6, server.requestCount)
                ChatDatabase(context).use { assertEquals(2, it.listLocalFiles(chat).size) }
            } finally {
                OpenRouterResponseRuntime.cancel(nextRequestId)
                awaitRuntimeAndCleanup(nextRequestId)
                client.close()
                ChatDatabase(context).use { db ->
                    db.listLocalFiles(chat).forEach { artifact ->
                        File(artifact.getString("path")).delete()
                        JsonProtocol.nonBlank(artifact, "patch_diff_path")?.let { File(it).delete() }
                    }
                }
            }
        }
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
