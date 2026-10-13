package com.example.openaichat

import android.os.SystemClock
import android.widget.EditText
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class OpenAiPreIdLifecycleTest {
    @Test fun liveCreateOlderThanTwoMinutesKeepsOwnerAcrossStopStartThenCommitsTerminal() =
        assertDelayedCreated(stopBeforeCreated = false)

    @Test fun receivedCreatedAfterStopCancelsOriginalAccountWithoutRevivingUiOrCreatingAgain() =
        assertDelayedCreated(stopBeforeCreated = true)

    private fun assertDelayedCreated(stopBeforeCreated: Boolean) {
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
        val responseId = "resp_preid_fixture"
        val terminal = JSONObject().put("id", responseId).put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Canonical result")))))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
                .put("response", JSONObject().put("id", responseId)) + "\n\n" +
            "data: " + JSONObject().put("type", "response.completed").put("sequence_number", 1)
                .put("response", terminal) + "\n\n"))
        if (stopBeforeCreated) server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody(JSONObject().put("id", responseId).put("status", "cancelled")
                .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31)).toString()))
        val createClient = trusted.newBuilder().addInterceptor { chain ->
            val response = chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build())
            val body = response.body!!
            val gated = object : ForwardingSource(body.source()) {
                private var first = true
                override fun read(sink: Buffer, byteCount: Long): Long {
                    val received = Buffer()
                    val count = super.read(received, byteCount)
                    if (first && count > 0L) {
                        first = false
                        entered.countDown()
                        if (!release.await(20, TimeUnit.SECONDS)) throw IOException("Fixture gate was not released")
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
            chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
        }.build()
        var chatId = ""
        var requestId = ""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = activity.prefs.getString("current_chat", "")!!
                    prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
                        .putString("reasoning", "high").putString("text_format", "text")
                        .putString("system_prompt", "").putString("instructions", "")
                        .putBoolean("background_responses", true).putBoolean("file_analysis_tools", false)
                        .putBoolean("web", false).putBoolean("file_search", false)
                        .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit()
                    SecureStore(context).setApiKey("sk-only-local-pre-id")
                    field("client").set(activity, createClient)
                    field("pollingClient").set(activity, cancelClient)
                    (field("input").get(activity) as EditText).setText("Pre-ID prompt")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                val create = server.takeRequest(20, TimeUnit.SECONDS)!!
                assertEquals("POST", create.method)
                assertTrue(JSONObject(create.body.readUtf8()).getBoolean("background"))
                assertTrue(entered.await(20, TimeUnit.SECONDS))
                ChatDatabase(context).use { db ->
                    val active = db.getActiveResponse(chatId)!!
                    requestId = active.getString("request_id")
                    assertTrue(JsonProtocol.string(active, "response_id").isBlank())
                    assertTrue(OpenAiCreateCallOwners.hasLiveCall(chatId, requestId))
                    db.setActiveResponse(chatId, null, requestId, "", "starting",
                        System.currentTimeMillis() - 180_000L, "high", "high", false, 13, 0, true)
                }
                if (stopBeforeCreated) {
                    scenario.onActivity { activity ->
                        MainActivity::class.java.getDeclaredMethod("stopCurrentResponse")
                            .apply { isAccessible = true }.invoke(activity)
                        SecureStore(context).setApiKey("sk-only-local-new-account")
                    }
                    ChatDatabase(context).use { assertNull(it.getActiveResponse(chatId)) }
                } else {
                    scenario.moveToState(Lifecycle.State.CREATED)
                    scenario.moveToState(Lifecycle.State.RESUMED)
                    ChatDatabase(context).use { db ->
                        assertEquals("A live pre-ID call is not an orphan after 120s", requestId,
                            db.getActiveResponse(chatId)!!.getString("request_id"))
                    }
                }
                release.countDown()
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                while (OpenAiCreateCallOwners.isRegistered(chatId, requestId) && SystemClock.elapsedRealtime() < deadline)
                    SystemClock.sleep(20)
                assertFalse("The original callback must release its registry owner",
                    OpenAiCreateCallOwners.isRegistered(chatId, requestId))
                if (stopBeforeCreated) {
                    val cancel = server.takeRequest(20, TimeUnit.SECONDS)!!
                    assertEquals("POST", cancel.method)
                    assertEquals("/v1/responses/$responseId/cancel", cancel.path)
                    assertEquals("Bearer sk-only-local-pre-id", cancel.getHeader("Authorization"))
                }
                // Wait for the final owned transaction/reconciliation, not just HTTP.
                var finished = false
                while (SystemClock.elapsedRealtime() < deadline) {
                    finished = ChatDatabase(context).use { db ->
                        if (stopBeforeCreated) db.listRequestAccounting(chatId).single().optString("response_id") == responseId
                        else db.getChat(chatId)!!.getJSONArray("messages").length() == 2
                    }
                    if (finished) break
                    SystemClock.sleep(20)
                }
                assertTrue("Original request did not finish its owned transaction", finished)
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                ChatDatabase(context).use { db ->
                    assertNull(db.getActiveResponse(chatId))
                    val messages = db.getChat(chatId)!!.getJSONArray("messages")
                    if (stopBeforeCreated) {
                        assertEquals(0, messages.length())
                        val accounting = db.listRequestAccounting(chatId).single()
                        assertEquals("stopped", accounting.getString("local_status"))
                        assertEquals(responseId, accounting.getString("response_id"))
                        assertEquals("cancelled", accounting.getString("provider_status"))
                        assertEquals(17L, accounting.getLong("input_tokens"))
                        assertEquals(31L, accounting.getLong("output_tokens"))
                        assertEquals("unknown", accounting.getString("cost_kind"))
                        assertFalse(accounting.has("cost_usd"))
                    } else assertEquals("Canonical result", messages.getJSONObject(1).getString("text"))
                }
                scenario.onActivity { activity ->
                    assertTrue(activity.send.isEnabled)
                    if (stopBeforeCreated) assertEquals("Pre-ID prompt", (field("input").get(activity) as EditText).text.toString())
                }
                assertEquals("Never create a second paid request", if (stopBeforeCreated) 2 else 1, server.requestCount)
            }
        } finally {
            release.countDown()
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
            server.shutdown()
        }
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
}
