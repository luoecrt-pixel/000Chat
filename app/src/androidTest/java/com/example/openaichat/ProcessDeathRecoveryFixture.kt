package com.example.openaichat

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
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
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Driven only by tools/test_android_process_recovery.py on a disposable emulator.
 * The seed invocation stays alive until the host actually kills its process.
 * Verification uses the ordinary WorkManager singleton/schedulers, not a test
 * Worker factory, TestDriver, or direct doWork() invocation. No live provider
 * key: background_created uses a synthetic key and trusted local HTTPS only.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val scenario get() = arguments.getString("recoveryScenario").orEmpty().also {
        require(it in setOf("terminal", "expired_partial", "stopped_pre_id", "replaced", "background_created"))
    }
    private val chatId get() = if (scenario == "background_created")
        prefs.getString("actual_chat_id", "process-recovery-fixture-$scenario")!!
        else "process-recovery-fixture-$scenario"
    private val requestId get() = if (scenario == "background_created")
        prefs.getString("actual_request_id", "req_process_fixture")!! else "req_process_fixture"
    private val responseId = "resp_process_fixture"
    private val replacementId = "req_replacement_fixture"
    private val sourceBytes = "data: received paid source before process death\n\n".toByteArray()
    private val prefs get() = context.getSharedPreferences("process_recovery_fixture", 0)

    private fun signedItem() = JSONObject().put("type", "reasoning")
        .put("id", "reasoning_fixture").put("encrypted_content", "signed-fixture-exact-bytes")

    @Test fun seedAndWaitForHostProcessKill() {
        assumeTrue(arguments.getString("recoveryStage") == "seed")
        assertTrue("Never run with a real provider credential", SecureStore(context).getApiKey().isBlank())
        if (scenario == "background_created") {
            seedBackgroundCreatedAndWaitForKill()
            return
        }
        BackgroundResponseWorkScheduler.cancel(context, chatId, requestId)
        ChatDatabase(context).use { db ->
            db.deleteChat(chatId)
            db.ensureChat(chatId, "Process recovery fixture")
            db.upsertCurrentChat(chatId, "Process recovery fixture", null, "high", listOf(
                JSONObject().put("role", "user").put("text", "Synthetic prompt")
                    .put("provider", "openai").put("request_id", requestId)
                    .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, OpenAiInstructionChainPolicy.fingerprint(
                        GenerationInstructions(null, null, null), "sk-synthetic-process-fixture", "gpt-6-astra"))))
            val started = if (scenario == "expired_partial")
                System.currentTimeMillis() - ResponseDeadlinePolicy.MAX_REQUEST_MS - 1L
                else System.currentTimeMillis()
            db.setActiveResponse(chatId, if (scenario == "stopped_pre_id") null else responseId,
                requestId, "", "starting", started, "high", "high", false, 16, 0, true)
            if (scenario == "stopped_pre_id" || scenario == "replaced") {
                assertTrue(db.stopActiveRequestOnce(chatId, requestId))
                if (scenario == "replaced") {
                    db.setActiveResponse(chatId, "resp_replacement_fixture", replacementId, "",
                        "starting", System.currentTimeMillis(), "high", "high", false, 16, 0, false)
                }
            } else {
                var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
                    .put("type", "response.output_item.done").put("output_index", 0)
                    .put("item", signedItem())).state
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.output_text.delta")
                    .put("output_index", 1).put("content_index", 0).put("delta", "received fragment")).state
                if (scenario == "terminal") {
                    val terminal = JSONObject().put("id", responseId).put("status", "completed")
                        .put("output", JSONArray().put(signedItem()).put(JSONObject()
                            .put("type", "message").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                                .put("text", "canonical paid result")))))
                        .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31))
                    state = ResponseEventReducer.reduce(state, JSONObject()
                        .put("type", "response.completed").put("response", terminal)).state
                }
                db.updateActiveResponse(chatId, responseId, state.text,
                    if (scenario == "terminal") "completed" else "in_progress", 9,
                    requestId, ResponseIndexedCheckpoint.serialize(state))
                val stored = db.getActiveResponse(chatId)!!
                assertEquals(9L, stored.getLong("last_sequence_number"))
                assertEquals(state.text, ResponseIndexedCheckpoint.restore(stored).text)
            }
        }
        OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
        OpenAiRawSseArchive.begin(ByteArrayInputStream(sourceBytes), context.filesDir, chatId, requestId)
            .use { it.receiveRemaining() }
        assertTrue(prefs.edit().putInt("seed_pid", Process.myPid()).putString("scenario", scenario).commit())
        // Production scheduler, persisted work, deliberately not ready before
        // the host kills us. A fresh process will reschedule this same owner.
        BackgroundResponseWorkScheduler.enqueue(context, chatId, responseId, requestId, 120_000L)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
            BackgroundResponseWorkScheduler.workName(chatId, requestId)).get(15, TimeUnit.SECONDS)
        assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED })
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "PROCESS_RECOVERY_SEEDED scenario=$scenario pid=${Process.myPid()}\n")
        })
        // Returning would let the runner end normally and fake process death.
        CountDownLatch(1).await(180, TimeUnit.SECONDS)
        fail("Host did not terminate the live seeded process")
    }

    @Test fun verifyProductionScheduledRecoveryInNewProcess() {
        assumeTrue(arguments.getString("recoveryStage") == "verify")
        assertEquals(scenario, prefs.getString("scenario", ""))
        assertNotEquals("A DB reopen in the same process is insufficient", prefs.getInt("seed_pid", -1), Process.myPid())
        assertEquals(if (scenario == "background_created") SYNTHETIC_KEY else "", SecureStore(context).getApiKey())
        if (scenario == "background_created") assertOriginalArchive("before Worker recovery")
        val recoveryTransport = if (scenario == "background_created") trustedRecoveryTransport() else null
        // Same production rescheduling entry point used on Android onStop.
        val manager = WorkManager.getInstance(context)
        val name = BackgroundResponseWorkScheduler.workName(chatId, requestId)
        val beforeIds = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        BackgroundResponseWorkScheduler.enqueueImmediate(context, chatId, responseId, requestId)
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        try {
            var work: List<WorkInfo>
            var firstExecution: WorkInfo? = null
            do {
                work = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                firstExecution = work.firstOrNull { it.id !in beforeIds }
                if (firstExecution?.state == WorkInfo.State.SUCCEEDED) break
                if (firstExecution?.state == WorkInfo.State.FAILED) fail("Production scheduled Worker failed: $work")
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals("Actual new WorkManager execution must finish", WorkInfo.State.SUCCEEDED, firstExecution?.state)
            ChatDatabase(context).use { db ->
                when (scenario) {
                    "terminal", "expired_partial", "background_created" -> {
                        assertNull(db.getActiveResponse(chatId))
                        val messages = db.getChat(chatId)!!.getJSONArray("messages")
                        assertEquals("One terminal owner, no duplicate assistant", 2, messages.length())
                        val assistant = messages.getJSONObject(1)
                        assertEquals(when (scenario) {
                            "terminal" -> "canonical paid result"
                            "background_created" -> "Recovered canonical paid result"
                            else -> "received fragment"
                        },
                            assistant.getString("text"))
                        assertEquals("signed-fixture-exact-bytes", assistant.getJSONArray("openai_protocol_output")
                            .getJSONObject(0).getString("encrypted_content"))
                        if (scenario == "expired_partial") {
                            assertTrue(assistant.getBoolean("incomplete"))
                            val source = assistant.getJSONArray("annotations").getJSONObject(0)
                            assertArrayEquals(sourceBytes, java.io.File(source.getString("local_path")).readBytes())
                        }
                        if (scenario == "background_created") {
                            assertEquals("completed", assistant.getString("status"))
                            assertEquals(prefs.getString("original_fingerprint", ""),
                                assistant.getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
                            assertOriginalArchive("after Worker recovery")
                        }
                    }
                    "stopped_pre_id" -> {
                        assertNull(db.getActiveResponse(chatId))
                        assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
                        assertEquals("stopped", db.listRequestAccounting(chatId).single()
                            .getString("local_status"))
                    }
                    "replaced" -> {
                        val active = db.getActiveResponse(chatId)!!
                        assertEquals(replacementId, active.getString("request_id"))
                        assertEquals("resp_replacement_fixture", active.getString("response_id"))
                        assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
                    }
                }
            }
            // Another real scheduled attempt cannot claim this old terminal.
            val previousIds = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            BackgroundResponseWorkScheduler.enqueueImmediate(context, chatId, responseId, requestId)
            val repeatDeadline = SystemClock.elapsedRealtime() + 30_000L
            var repeated: WorkInfo? = null
            do {
                work = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                repeated = work.firstOrNull { it.id !in previousIds }
                if (repeated?.state?.isFinished == true) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < repeatDeadline)
            assertEquals("A second actual scheduled execution must finish", WorkInfo.State.SUCCEEDED, repeated?.state)
            ChatDatabase(context).use { db ->
                assertEquals(if (scenario in setOf("terminal", "expired_partial", "background_created")) 2 else 1,
                    db.getChat(chatId)!!.getJSONArray("messages").length())
            }
            if (recoveryTransport != null) {
                val request = recoveryTransport.first.takeRequest(10, TimeUnit.SECONDS)!!
                assertEquals("No second paid Create", "GET", request.method)
                assertEquals("/v1/responses/$responseId?stream=true&starting_after=2", request.path)
                assertEquals("Bearer $SYNTHETIC_KEY", request.getHeader("Authorization"))
                assertEquals("Repeated scheduled recovery cannot issue another request", 1,
                    recoveryTransport.first.requestCount)
                assertEquals(1, prefs.getInt("seed_create_count", -1))
            }
        } finally {
            try {
                ResumableResponseStreamClient.transportForTests = null
                recoveryTransport?.second?.let { client ->
                    client.dispatcher.executorService.shutdownNow()
                    client.connectionPool.evictAll()
                }
                recoveryTransport?.first?.shutdown()
            } finally {
                cleanupFixture()
            }
        }
    }

    /** Called by the host even when seed/verification was killed or failed. */
    @Test fun cleanupProcessRecoveryFixture() {
        assumeTrue(arguments.getString("recoveryStage") == "cleanup")
        cleanupFixture()
    }

    private fun assertOriginalArchive(stage: String) {
        val expected = prefs.getString("seed_source", "")!!.toByteArray(Charsets.UTF_8)
        fun digest(bytes: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it) }
        val files = OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId)
        val actual = files.map { it.readBytes() }
        assertTrue("Original paid source survives process death ($stage); expectedBytes=${expected.size} " +
            "expectedSha=${digest(expected)} archives=" + actual.map { "${it.size}:${digest(it)}" },
            expected.isNotEmpty() && actual.any { it.contentEquals(expected) })
    }

    private fun seedBackgroundCreatedAndWaitForKill() {
        saveSettingsForProcessDeath()
        val appPrefs = context.getSharedPreferences("app", 0)
        appPrefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("reasoning", "high").putString("text_format", "text")
            .putString("system_prompt", "").putString("instructions", "")
            .putBoolean("background_responses", true).putBoolean("response_notifications", false)
            .putBoolean("file_analysis_tools", false).putBoolean("web", false)
            .putBoolean("file_search", false).putBoolean("code_interpreter", false)
            .putBoolean("image_generation", false).commit()
        SecureStore(context).setApiKey(SYNTHETIC_KEY)
        val (server, trusted) = trustedServer()
        val parsedCreated = CountDownLatch(1)
        val releaseCreated = CountDownLatch(1)
        val waitingAtEof = CountDownLatch(1)
        val releaseEof = CountDownLatch(1)
        fun wire(event: JSONObject) = "data: $event\n\n"
        val prefix = wire(JSONObject().put("type", "response.created").put("sequence_number", 0)
            .put("response", JSONObject().put("id", responseId))) +
            wire(JSONObject().put("type", "response.output_text.delta").put("sequence_number", 1)
                .put("output_index", 1).put("content_index", 0).put("delta", "received fragment")) +
            wire(JSONObject().put("type", "response.output_item.done").put("sequence_number", 2)
                .put("output_index", 0).put("item", signedItem()))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(prefix))
        val createClient = trusted.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build())
            val body = response.body!!
            val gated = object : ForwardingSource(body.source()) {
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val count = super.read(sink, byteCount)
                    if (count < 0L) {
                        waitingAtEof.countDown()
                        if (!releaseEof.await(180, TimeUnit.SECONDS)) throw IOException("Host process-kill gate timed out")
                    }
                    return count
                }
            }.buffer()
            response.newBuilder().body(object : ResponseBody() {
                override fun contentType(): MediaType? = body.contentType()
                override fun contentLength(): Long = -1L
                override fun source(): BufferedSource = gated
            }).build()
        }.build()
        // If ownership regresses, a duplicate Worker stream must be observable
        // on this local server, never sent to the live provider with a fake key.
        ResumableResponseStreamClient.transportForTests = trusted to server.url("/")
        val activity = ActivityScenario.launch(MainActivity::class.java)
        try {
            activity.onActivity { current ->
                current.newChat()
                val actualChatId = current.prefs.getString("current_chat", "")!!
                prefs.edit().putString("actual_chat_id", actualChatId).commit()
                field("client").set(current, createClient)
                val hook: (JSONObject) -> Unit = { event ->
                    if (event.optString("type") == "response.created") {
                        parsedCreated.countDown()
                        if (!releaseCreated.await(30, TimeUnit.SECONDS)) throw IOException("Before-ID fixture gate timed out")
                    }
                }
                field("openAiReceivedEventHook").set(current, hook)
                (field("input").get(current) as EditText).setText("Synthetic prompt")
                MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                    .apply { isAccessible = true }.invoke(current, false)
            }
            val create = server.takeRequest(20, TimeUnit.SECONDS)!!
            assertEquals("POST", create.method)
            assertTrue(JSONObject(create.body.readUtf8()).getBoolean("background"))
            assertTrue(parsedCreated.await(20, TimeUnit.SECONDS))
            val originalStartedAt = ChatDatabase(context).use { db ->
                val active = db.getActiveResponse(chatId)!!
                assertTrue(JsonProtocol.string(active, "response_id").isBlank())
                val localRequest = active.getString("request_id")
                val fingerprint = db.getChat(chatId)!!.getJSONArray("messages")
                    .getJSONObject(0).getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD)
                assertTrue(prefs.edit().putString("actual_request_id", localRequest)
                    .putLong("original_started_at", active.getLong("started_at"))
                    .putString("original_fingerprint", fingerprint).commit())
                assertTrue(OpenAiCreateCallOwners.hasLiveCall(chatId, requestId))
                active.getLong("started_at")
            }
            activity.moveToState(Lifecycle.State.CREATED)
            assertEquals(Lifecycle.State.CREATED, activity.state)
            releaseCreated.countDown()
            assertTrue(waitingAtEof.await(20, TimeUnit.SECONDS))
            ChatDatabase(context).use { db ->
                val active = db.getActiveResponse(chatId)!!
                assertEquals(requestId, active.getString("request_id"))
                assertEquals(responseId, active.getString("response_id"))
                assertEquals("Send's five-hour clock must survive late ID", originalStartedAt, active.getLong("started_at"))
                assertEquals(2L, active.getLong("last_sequence_number"))
                val recovered = ResponseIndexedCheckpoint.restore(active)
                assertEquals("received fragment", recovered.text)
                assertEquals("signed-fixture-exact-bytes", recovered.typedOutputItems[0]!!.getString("encrypted_content"))
            }
            val manager = WorkManager.getInstance(context)
            val name = BackgroundResponseWorkScheduler.workName(chatId, requestId)
            val deadline = SystemClock.elapsedRealtime() + 20_000L
            var work: List<WorkInfo>
            do {
                work = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                if (work.any { it.state == WorkInfo.State.RUNNING }) break
                SystemClock.sleep(50)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertTrue("Late background response.created must run durable production recovery",
                work.any { it.state == WorkInfo.State.RUNNING })
            assertTrue("Primary Create remains the live reader", OpenAiCreateCallOwners.hasLiveCall(chatId, requestId))
            assertNull("Scheduled Worker must not open a parallel stream", server.takeRequest(1, TimeUnit.SECONDS))
            assertEquals(1, server.requestCount)
            assertTrue(OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId)
                .any { it.readText(Charsets.UTF_8) == prefix })
            // Keep a persisted production job pending until verification installs
            // its new-process trusted HTTPS server. No test Worker factory.
            BackgroundResponseWorkScheduler.enqueue(context, chatId, responseId, requestId, 120_000L)
            val pending = manager.getWorkInfosForUniqueWork(name).get(15, TimeUnit.SECONDS)
            assertTrue(pending.any { it.state == WorkInfo.State.ENQUEUED })
            assertTrue(prefs.edit().putInt("seed_pid", Process.myPid()).putString("scenario", scenario)
                .putString("seed_source", prefix).putInt("seed_create_count", server.requestCount).commit())
            instrumentation.sendStatus(2, Bundle().apply {
                putString("stream", "PROCESS_RECOVERY_SEEDED scenario=$scenario pid=${Process.myPid()}\n")
            })
            CountDownLatch(1).await(180, TimeUnit.SECONDS)
            fail("Host did not terminate the live background-created process")
        } finally {
            try {
                releaseCreated.countDown()
                releaseEof.countDown()
                ResumableResponseStreamClient.transportForTests = null
                activity.close()
                createClient.dispatcher.executorService.shutdownNow()
                createClient.connectionPool.evictAll()
                server.shutdown()
            } finally {
                cleanupFixture()
            }
        }
    }

    private fun trustedRecoveryTransport(): Pair<MockWebServer, OkHttpClient> = trustedServer().also { (server, client) ->
        val terminal = JSONObject().put("id", responseId).put("status", "completed")
            .put("output", JSONArray().put(signedItem()).put(JSONObject().put("type", "message")
                .put("id", "msg_recovered").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                    .put("text", "Recovered canonical paid result")))))
            .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31))
        val event = JSONObject().put("type", "response.completed").put("sequence_number", 3).put("response", terminal)
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\n"))
        ResumableResponseStreamClient.transportForTests = client to server.url("/")
        ChatDatabase(context).use { db ->
            val active = db.getActiveResponse(chatId)!!
            assertEquals(prefs.getLong("original_started_at", -1L), active.getLong("started_at"))
            assertEquals(2L, active.getLong("last_sequence_number"))
            assertFalse("A killed process cannot retain its Create registry", OpenAiCreateCallOwners.isRegistered(chatId, requestId))
        }
    }

    private fun trustedServer(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        val client = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager).build()
        return server to client
    }

    private fun saveSettingsForProcessDeath() {
        val appPrefs = context.getSharedPreferences("app", 0)
        val snapshot = JSONObject()
        SETTINGS.forEach { key ->
            val value = appPrefs.all[key]
            snapshot.put(key, JSONObject().put("present", value != null).apply {
                if (value != null) put("value", value).put("type", when (value) {
                    is Boolean -> "boolean"
                    is Int -> "int"
                    is Long -> "long"
                    is Float -> "float"
                    else -> "string"
                })
            })
        }
        assertTrue(prefs.edit().putString("saved_settings", snapshot.toString())
            .putBoolean("synthetic_key_owned", true).commit())
    }

    private fun cleanupFixture() {
        BackgroundResponseWorkScheduler.cancel(context, chatId, requestId)
        ChatDatabase(context).use { it.deleteChat(chatId) }
        OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
        if (prefs.getBoolean("synthetic_key_owned", false)) {
            val currentKey = SecureStore(context).getApiKey()
            check(currentKey.isBlank() || currentKey == SYNTHETIC_KEY) { "Fixture cannot overwrite a different credential" }
            SecureStore(context).setApiKey("")
        }
        prefs.getString("saved_settings", null)?.let { raw ->
            val snapshot = JSONObject(raw)
            val edit = context.getSharedPreferences("app", 0).edit()
            snapshot.keys().forEach { key ->
                val entry = snapshot.getJSONObject(key)
                if (!entry.getBoolean("present")) edit.remove(key)
                else when (entry.getString("type")) {
                    "boolean" -> edit.putBoolean(key, entry.getBoolean("value"))
                    "int" -> edit.putInt(key, entry.getInt("value"))
                    "long" -> edit.putLong(key, entry.getLong("value"))
                    "float" -> edit.putFloat(key, entry.getDouble("value").toFloat())
                    else -> edit.putString(key, entry.getString("value"))
                }
            }
            assertTrue(edit.commit())
        }
        prefs.edit().clear().commit()
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    companion object {
        private const val SYNTHETIC_KEY = "sk-synthetic-process-fixture"
        private val SETTINGS = listOf("current_chat", "provider", "model", "reasoning", "text_format",
            "system_prompt", "instructions", "background_responses", "response_notifications",
            "file_analysis_tools", "web", "file_search", "code_interpreter", "image_generation")
    }
}
