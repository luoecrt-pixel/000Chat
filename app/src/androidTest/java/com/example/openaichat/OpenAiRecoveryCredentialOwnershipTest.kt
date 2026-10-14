package com.example.openaichat

import android.os.SystemClock
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
import java.util.concurrent.atomic.AtomicInteger

/** Original account binding is checked by the actual recovery transport and owners. */
@RunWith(AndroidJUnit4::class)
class OpenAiRecoveryCredentialOwnershipTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private var oldKey = ""
    private lateinit var oldPrefs: Map<String, Any?>
    private val originalKey = "sk-recovery-original-synthetic"
    private val responseId = "resp_owned_recovery"
    private val requestId = "req_owned_recovery"
    private var chatId = ""
    private val sourceBytes = "data: paid original raw source before credential failure\n\n".toByteArray()
    private val fingerprint get() = OpenAiInstructionChainPolicy.fingerprint(
        GenerationInstructions("Original system", "Original developer", null), originalKey, "gpt-6-astra")

    @Before fun configureSyntheticOwner() {
        oldKey = SecureStore(context).getApiKey()
        oldPrefs = listOf("response_notifications", "background_responses", "current_chat", "provider").associateWith { prefs.all[it] }
        SecureStore(context).setApiKey(originalKey)
        prefs.edit().putBoolean("response_notifications", false).putBoolean("background_responses", false)
            .putString("provider", "openai").commit()
        chatId = "credential-recovery-" + UUID.randomUUID()
    }

    @After fun cleanUpAndRestore() {
        BackgroundResponseWorkScheduler.cancel(context, chatId, requestId)
        ChatDatabase(context).use { it.deleteChat(chatId) }
        OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
        SecureStore(context).setApiKey(oldKey)
        prefs.edit().apply { oldPrefs.forEach { (key, value) -> when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            else -> remove(key)
        } } }.commit()
    }

    private fun seed(bound: Boolean = true) {
        val user = JSONObject().put("role", "user").put("text", "Original paid request")
            .put("provider", "openai").put("request_id", requestId).put("requested_model", "gpt-6-astra")
        if (bound) user.put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint)
        var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject().put("type", "response.created")
            .put("response", JSONObject().put("id", responseId))).state
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_item.done")
            .put("output_index", 0).put("item", JSONObject().put("type", "reasoning")
                .put("id", "signed_partial").put("encrypted_content", "SIGNED-PAID-ORIGINAL"))).state
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.reasoning_summary_text.delta")
            .put("output_index", 0).put("summary_index", 0).put("delta", "Original readable summary")).state
        state = ResponseEventReducer.reduce(state, JSONObject().put("type", "response.output_text.delta")
            .put("output_index", 1).put("content_index", 0).put("delta", "Original received partial")).state
        ChatDatabase(context).use { db ->
            db.ensureChat(chatId, "Original account recovery")
            db.upsertCurrentChat(chatId, "Original account recovery", null, "high", listOf(user))
            db.startRequestAccounting(chatId, requestId, "openai", "gpt-6-astra", "openai_api_key")
            db.setActiveResponse(chatId, responseId, requestId, "", "starting", System.currentTimeMillis(),
                "high", "high", false, 21, 0, true)
            db.updateActiveResponse(chatId, responseId, state.text, "in_progress", 3,
                requestId, ResponseIndexedCheckpoint.serialize(state))
        }
        OpenAiRawSseArchive.begin(ByteArrayInputStream(sourceBytes), context.filesDir, chatId, requestId)
            .use { it.receiveRemaining() }
    }

    private fun worker() = TestListenableWorkerBuilder<BackgroundResponseWorker>(context)
        .setInputData(Data.Builder().putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId)
            .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, responseId)
            .putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId).build()).build()

    private fun setTransport(worker: BackgroundResponseWorker, client: OkHttpClient) {
        val stream = BackgroundResponseWorker::class.java.getDeclaredMethod("getStreamClient")
            .apply { isAccessible = true }.invoke(worker)
        ResumableResponseStreamClient::class.java.getDeclaredField("httpClient")
            .apply { isAccessible = true }.set(stream, client)
    }

    private fun assertPreserved(bound: Boolean = true) {
        ChatDatabase(context).use { db ->
            assertNull(db.getActiveResponse(chatId))
            val messages = db.getChat(chatId)!!.getJSONArray("messages")
            assertEquals(2, messages.length())
            val partial = messages.getJSONObject(1)
            assertEquals("Original received partial", partial.getString("text"))
            assertTrue(partial.getBoolean("incomplete"))
            assertEquals("SIGNED-PAID-ORIGINAL", partial.getJSONArray("openai_protocol_output")
                .getJSONObject(0).getString("encrypted_content"))
            assertEquals("Original readable summary", partial.getString("reasoning_summary"))
            assertArrayEquals(sourceBytes, File(partial.getJSONArray("annotations").getJSONObject(0)
                .getString("local_path")).readBytes())
            if (bound) assertEquals(fingerprint, partial.getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
            assertEquals("failed", db.listRequestAccounting(chatId).single().getString("local_status"))
            assertEquals("unknown", db.listRequestAccounting(chatId).single().getString("cost_kind"))
            assertFalse(messages.toString().contains(originalKey))
            assertFalse(messages.toString().contains("sk-recovery-foreign-synthetic"))
            assertFalse(OpenAiInstructionChainPolicy.canReuse(responseId,
                (0 until messages.length()).map { messages.getJSONObject(it) }, fingerprint))
        }
    }

    @Test fun changedCredentialStopsRealWorkerBeforeNetworkAndPreservesOriginalPaidPartial() = assertBlockedWorker(bound = true, changed = true)
    @Test fun legacyUnboundRecoveryStopsRealWorkerBeforeNetworkAndPreservesPartial() = assertBlockedWorker(bound = false, changed = false)

    private fun assertBlockedWorker(bound: Boolean, changed: Boolean) {
        seed(bound)
        if (changed) SecureStore(context).setApiKey("sk-recovery-foreign-synthetic")
        val calls = AtomicInteger()
        val fixture = worker()
        setTransport(fixture, OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw AssertionError("Recovery must not disclose response ID to an unbound account")
        }.build())
        assertEquals(ListenableWorker.Result.failure(), runBlocking { fixture.doWork() })
        assertEquals(0, calls.get())
        assertPreserved(bound)
        assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
        assertEquals(0, calls.get())
    }

    @Test fun originalCredentialHttp401PreservesPaidPartialAndDoesNotRetryOrRegenerate() = assertAuthFailure(401)
    @Test fun originalCredentialHttp403PreservesPaidPartialAndDoesNotRetryOrRegenerate() = assertAuthFailure(403)

    private fun assertAuthFailure(code: Int) {
        seed()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        MockWebServer().use { server ->
            server.useHttps(serverTrust.sslSocketFactory(), false); server.start()
            server.enqueue(MockResponse().setResponseCode(code).setBody("{\"error\":{\"message\":\"synthetic auth rejection\"}}"))
            val fixture = worker()
            val client = OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
                .addInterceptor { chain ->
                    chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath + "?" + chain.request().url.encodedQuery)).build())
                }.build()
            setTransport(fixture, client)
            assertEquals(ListenableWorker.Result.failure(), runBlocking { fixture.doWork() })
            val request = server.takeRequest(10, TimeUnit.SECONDS) ?: throw AssertionError("Owned recovery did not reach HTTPS mock")
            assertEquals("GET", request.method)
            assertTrue(request.path!!.startsWith("/v1/responses/$responseId?"))
            assertEquals("Bearer $originalKey", request.getHeader("Authorization"))
            assertPreserved()
            assertEquals(ListenableWorker.Result.success(), runBlocking { worker().doWork() })
            assertEquals("No retry and no paid Create after auth failure", 1, server.requestCount)
        }
    }

    @Test fun changedCredentialStopsRealActivityResumeBeforeNetworkAndPreservesOriginalPartial() {
        val calls = AtomicInteger()
        val noNetwork = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw AssertionError("Activity recovery must not use the new account")
        }.build()
        // Launch before seeding to invoke precisely this Activity-owned recovery.
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.newChat()
                chatId = prefs.getString("current_chat", "")!!
                seed()
                SecureStore(context).setApiKey("sk-recovery-foreign-synthetic")
                val stream = MainActivity::class.java.getDeclaredMethod("getResumableResponseClient")
                    .apply { isAccessible = true }.invoke(activity)
                ResumableResponseStreamClient::class.java.getDeclaredField("httpClient")
                    .apply { isAccessible = true }.set(stream, noNetwork)
                val active = ChatDatabase(context).use { it.getActiveResponse(chatId)!! }
                MainActivity::class.java.getDeclaredMethod("streamBackgroundResponse", JSONObject::class.java,
                    BackgroundResponseUiSnapshot::class.java).apply { isAccessible = true }.invoke(activity, active, null)
            }
            val deadline = SystemClock.elapsedRealtime() + 10_000L
            while (ChatDatabase(context).use { it.getActiveResponse(chatId) != null } && SystemClock.elapsedRealtime() < deadline)
                SystemClock.sleep(20)
            assertEquals(0, calls.get())
            assertPreserved()
        }
    }

    @Test fun changedCredentialDeleteDoesNotCancelWithForeignAccount() = assertSafeCancel("delete")
    @Test fun changedCredentialStopDoesNotCancelWithForeignAccount() = assertSafeCancel("stop")
    @Test fun changedCredentialEditDoesNotCancelWithForeignAccount() = assertSafeCancel("edit")

    private fun assertSafeCancel(action: String) {
        val calls = AtomicInteger()
        val noNetwork = OkHttpClient.Builder().addInterceptor {
            calls.incrementAndGet(); throw AssertionError("Cancel must not use a foreign credential")
        }.build()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.newChat()
                chatId = prefs.getString("current_chat", "")!!
                seed()
                fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
                field("pollingClient").set(activity, noNetwork)
                MainActivity::class.java.getDeclaredMethod("restoreCurrentChat").apply { isAccessible = true }.invoke(activity)
                val active = ChatDatabase(context).use { it.getActiveResponse(chatId)!! }
                val capture = MainActivity::class.java.getDeclaredMethod("captureOpenAiRequestCredential",
                    String::class.java, JSONObject::class.java).apply { isAccessible = true }
                assertEquals("Same-key reopen may cancel", originalKey, capture.invoke(activity, chatId, active))
                SecureStore(context).setApiKey("sk-recovery-foreign-synthetic")
                assertNull(capture.invoke(activity, chatId, active))
                when (action) {
                    "delete" -> activity.deleteChat(chatId)
                    "stop" -> MainActivity::class.java.getDeclaredMethod("stopCurrentResponse")
                        .apply { isAccessible = true }.invoke(activity)
                    "edit" -> MainActivity::class.java.getDeclaredMethod("editMessage", Int::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, 0)
                }
                // Drain previously queued cancellation tasks before asserting.
                (field("executor").get(activity) as java.util.concurrent.ExecutorService)
                    .submit {}.get(5, TimeUnit.SECONDS)
                assertEquals(0, calls.get())
                ChatDatabase(context).use { assertNull(it.getActiveResponse(chatId)) }
            }
        }
    }
}
