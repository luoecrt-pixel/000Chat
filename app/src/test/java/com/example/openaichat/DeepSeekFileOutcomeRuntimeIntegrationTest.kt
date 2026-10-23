package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DeepSeekFileOutcomeRuntimeIntegrationTest {
    private lateinit var context: Context
    private val chat = "file-outcome"
    private val requestId = "file-outcome-request"
    private val metadataClient = OkHttpClient()

    @Before fun setup() {
        context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        File(context.filesDir, "generated").deleteRecursively()
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("response_notifications", false).commit()
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(), "none", "none", false, 1, 0, false)
            assertTrue(db.startRequestAccounting(chat, requestId, "deepseek", "deepseek-chat", "deepseek_api_key"))
        }
    }
    @After fun cleanup() {
        OpenRouterResponseRuntime.cancel(requestId)
        await { !OpenRouterResponseRuntime.contains(requestId) }
        metadataClient.dispatcher.executorService.shutdownNow()
        metadataClient.connectionPool.evictAll()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
    }
    private fun request() = OpenRouterRuntimeRequest(chat, requestId, "deepseek-chat",
        JSONArray().put(JSONObject().put("role", "user").put("content", "Create file")), emptyList(),
        "none", "", null, false, "", emptySet(), false, false, false, "off", false,
        SystemClock.elapsedRealtime(), "general", "none", false, 1, "inert-key", metadataClient, "auto", provider = "deepseek")
    private fun usage() = JSONObject().put("prompt_tokens", 17).put("completion_tokens", 5).put("total_tokens", 22)
    private fun call(index: Int, id: String, name: String, args: JSONObject) = JSONObject().put("index", index)
        .put("id", id).put("type", "function").put("function", JSONObject().put("name", name).put("arguments", args.toString()))
    private fun sse(delta: JSONObject, finish: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody("data: " + JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", delta)
            .put("finish_reason", finish))).put("usage", usage()).toString() + "\n\ndata: [DONE]\n\n")
    private fun await(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!predicate() && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue("Runtime did not settle", predicate())
    }

    @Test fun createPostMoveFailureStopsPaidChainAndRecoveryConfirmsExactFileWithoutExecution() = postMoveFailure(false)
    @Test fun patchPostMoveFailureStopsPaidChainAndRecoveryConfirmsExactVersionWithoutExecution() = postMoveFailure(true)

    @Test fun createLateSqliteFailureStopsPaidChainAndRecoveryConfirmsSameFile() = postMoveFailure(false, lateSqlite = true)

    private fun postMoveFailure(patch: Boolean, lateSqlite: Boolean = false) {
        val toolName = if (patch) "patch_local_file" else "create_local_text_file"
        val args = if (patch) {
            val source = LocalArtifactToolExecutor(context).createTextFile(JSONObject().put("filename", "source.txt").put("content", "before"))
            ChatDatabase(context).use { assertTrue(it.registerLocalArtifact(chat, "prior", source)) }
            JSONObject().put("file_id", source.getString("file_id")).put("expected_sha256", source.getString("sha256"))
                .put("patches", JSONArray().put(JSONObject().put("search", "before").put("replace", "after")))
        } else JSONObject().put("filename", "result.txt").put("content", "confirmed bytes")
        val rawCalls = JSONArray().put(call(0, "wire-effect", toolName, args))
            .put(call(1, "wire-never", "create_local_text_file", JSONObject().put("filename", "never.txt").put("content", "must not execute")))
        val publications = AtomicInteger()
        val published = mutableListOf<File>()
        MockWebServer().use { server ->
            server.enqueue(sse(JSONObject().put("content", "Visible prefix").put("tool_calls", rawCalls), "tool_calls"))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()),
                artifactToolsFactory = { app -> LocalArtifactToolExecutor(app) { destination, staged ->
                    Files.move(staged.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    publications.incrementAndGet(); published.add(destination)
                    if (lateSqlite) ChatDatabase(context).use { db -> db.writableDatabase.execSQL(
                        "CREATE TRIGGER fail_artifact_registration BEFORE INSERT ON local_artifacts BEGIN SELECT RAISE(ABORT, 'synthetic late registration failure'); END") }
                    else throw IOException("Synthetic I/O failure after publication")
                } })
            await { !OpenRouterResponseRuntime.contains(requestId) }
            assertEquals("No continuation POST may ask the model to retry the file effect", 1, server.requestCount)
            assertEquals(1, publications.get())
            assertEquals(if (patch) "after" else "confirmed bytes", published.single().readText())
            ChatDatabase(context).use { db ->
                val archive = db.getRequestRecoveryArchive(chat, requestId)!!
                assertEquals("failed", archive.getString("status"))
                assertEquals("Visible prefix", archive.getString("text"))
                assertEquals(17, archive.getJSONObject("usage").getInt("prompt_tokens"))
                val protocol = archive.getJSONObject("deepseek_protocol")
                assertFalse(protocol.getBoolean("replayable"))
                val tail = protocol.getJSONArray("tail")
                assertEquals(1, tail.length())
                assertEquals("assistant", tail.getJSONObject(0).getString("role"))
                val originalCalls = tail.getJSONObject(0).getJSONArray("tool_calls")
                assertEquals("wire-effect", originalCalls.getJSONObject(0).getString("id"))
                assertEquals(args.toString(), originalCalls.getJSONObject(0).getJSONObject("function").getString("arguments"))
                assertEquals("wire-never", originalCalls.getJSONObject(1).getString("id"))
                val execution = protocol.getJSONArray("tool_execution")
                assertEquals("outcome_unknown", execution.getJSONObject(0).getString("state"))
                assertEquals("not_started", execution.getJSONObject(1).getString("state"))
                assertFalse(protocol.toString().contains("cancelled"))
            }
            // Reopen and run only the verification recovery path, never a tool executor.
            ChatDatabase(context).use { db ->
                if (lateSqlite) db.writableDatabase.execSQL("DROP TRIGGER fail_artifact_registration")
                db.reconcilePublishedArtifactIntents(maxFileBytes = 32L * 1024 * 1024, maxWorkMillis = 500)
                val confirmed = db.getLocalArtifactToolResult(chat, requestId, "local_artifact_0")!!
                assertEquals(StreamingJsonRequestBody.sha256(args), confirmed.getString("arguments_sha256"))
                assertEquals(LocalUtf8FilePatch.sha256(published.single()), confirmed.getString("sha256"))
                assertEquals(published.single().path, confirmed.getString("path"))
                assertEquals(1, db.getRequestRecoveryArchive(chat, requestId)!!.getJSONArray("confirmed_artifacts").length())
            }
            assertEquals(1, publications.get())
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun prevalidationFailureProducesOrdinaryToolErrorAndAllowsNormalContinuation() {
        val args = JSONObject().put("filename", "invalid.png").put("content", "not binary")
        val publications = AtomicInteger()
        MockWebServer().use { server ->
            server.enqueue(sse(JSONObject().put("tool_calls", JSONArray().put(call(0, "wire-invalid", "create_local_text_file", args))), "tool_calls"))
            server.enqueue(sse(JSONObject().put("content", "Choose a text filename"), "stop"))
            OpenRouterResponseRuntime.start(context, request(), DeepSeekChatClient(server.url("/").toString()),
                artifactToolsFactory = { app -> LocalArtifactToolExecutor(app) { _, _ -> publications.incrementAndGet(); error("Prevalidation must not publish") } })
            await { !OpenRouterResponseRuntime.contains(requestId) }
            assertEquals(2, server.requestCount)
            server.takeRequest()
            val messages = JSONObject(server.takeRequest().body.readUtf8()).getJSONArray("messages")
            val tool = (0 until messages.length()).map { messages.getJSONObject(it) }.single { it.optString("role") == "tool" }
            assertFalse(JSONObject(tool.getString("content")).getBoolean("ok"))
            assertEquals(0, publications.get())
            ChatDatabase(context).use { db -> assertEquals("completed", db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0).getString("status")) }
        }
    }
}
