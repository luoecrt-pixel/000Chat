package com.example.openaichat

import android.content.Context
import android.os.SystemClock
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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

/** Real MainActivity Send, HTTPS wire and SQLite reopen; only synthetic credentials. */
@RunWith(AndroidJUnit4::class)
class OpenAiCredentialChainLifecycleTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private val keys = listOf("provider", "model", "reasoning", "text_format", "system_prompt",
        "instructions", "file_analysis_tools", "background_responses", "response_notifications",
        "background_responses_migrated_v25", "openai_auto_compaction", "web", "file_search", "code_interpreter", "image_generation", "current_chat")
    private lateinit var saved: Map<String, Any?>
    private var savedKey = ""
    private val originalKey = "sk-chain-original-synthetic"
    private val originalModel = "gpt-6-astra"

    @Before fun captureAndSuppressPermissionDialog() {
        saved = keys.associateWith { prefs.all[it] }
        savedKey = SecureStore(context).getApiKey()
        SecureStore(context).setApiKey(originalKey)
        prefs.edit().putString("provider", "openai").putString("model", originalModel)
            .putString("reasoning", "high").putString("text_format", "text")
            .putString("system_prompt", "Chain system fixture").putString("instructions", "Chain developer fixture")
            .putBoolean("file_analysis_tools", false).putBoolean("background_responses", false).putBoolean("background_responses_migrated_v25", true)
            .putBoolean("response_notifications", false).putBoolean("openai_auto_compaction", false)
            .putBoolean("web", false).putBoolean("file_search", false)
            .putBoolean("code_interpreter", false).putBoolean("image_generation", false).commit()
    }

    @After fun restoreOriginalSettings() {
        SecureStore(context).setApiKey(savedKey)
        prefs.edit().apply { saved.forEach { (key, value) -> when (value) {
            is String -> putString(key, value)
            is Boolean -> putBoolean(key, value)
            is Int -> putInt(key, value)
            is Long -> putLong(key, value)
            else -> remove(key)
        } } }.commit()
    }

    private fun field(name: String) = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun invoke(activity: MainActivity, name: String) =
        MainActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(activity)

    private fun server(): Pair<MockWebServer, OkHttpClient> {
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTrust = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTrust = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        val server = MockWebServer().apply { useHttps(serverTrust.sslSocketFactory(), false); start() }
        return server to OkHttpClient.Builder().sslSocketFactory(clientTrust.sslSocketFactory(), clientTrust.trustManager)
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder().url(server.url("/v1/responses")).build()) }.build()
    }

    @Test fun unchangedCredentialAndModelReusePreviousIdWithoutRepeatingInstructions() = assertWire(changeKey = false, changeModel = false)
    @Test fun changedCredentialReplaysAllTypedRolesAndOriginalAttachments() = assertWire(changeKey = true, changeModel = false)
    @Test fun changedModelReplaysAllTypedRolesAndOriginalAttachments() = assertWire(changeKey = false, changeModel = true)
    @Test fun liveCreateFinishesWithOriginalCredentialBindingAfterSettingsKeyChanges() =
        assertWire(changeKey = false, changeModel = false, switchWhileLive = true)

    private fun assertWire(changeKey: Boolean, changeModel: Boolean, switchWhileLive: Boolean = false) {
        val (server, client) = server()
        val responseId = "resp_chain_next"
        val terminal = JSONObject().put("id", responseId).put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "message").put("role", "assistant")
                .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "New answer")))))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBodyDelay(if (switchWhileLive) 2L else 0L, TimeUnit.SECONDS).setBody(
            "data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
                .put("response", JSONObject().put("id", responseId)) + "\n\n" +
                "data: " + JSONObject().put("type", "response.completed").put("sequence_number", 1)
                .put("response", terminal) + "\n\n"))
        val note = File(context.filesDir, "chain-note-${UUID.randomUUID()}.txt").apply { writeText("EXACT-HISTORICAL-ATTACHMENT-BYTES") }
        val image = File(context.filesDir, "chain-image-${UUID.randomUUID()}.png").apply {
            writeBytes(android.util.Base64.decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aMfkAAAAASUVORK5CYII=", android.util.Base64.DEFAULT))
        }
        var chatId = ""
        var nextFingerprint = ""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity ->
                    activity.newChat()
                    chatId = prefs.getString("current_chat", "")!!
                    field("client").set(activity, client)
                    val instructions = GenerationInstructions("Chain system fixture", "Chain developer fixture",
                        invoke(activity, "presentationInstructionsForRequest") as String?)
                    val oldFingerprint = OpenAiInstructionChainPolicy.fingerprint(instructions, originalKey, originalModel)
                    val historyUser = JSONObject().put("role", "user").put("provider", "openai")
                        .put("text", "Historical user question").put("image_path", image.absolutePath)
                        .put("image_file_id", "file_old_account_image")
                        .put("file_ids", JSONArray().put("file_old_account_note"))
                        .put("image_mime", "image/png").put("file_paths", JSONArray().put(note.absolutePath))
                        .put("file_names", JSONArray().put(note.name)).put("file_mimes", JSONArray().put("text/plain"))
                    val output = JSONArray()
                        .put(JSONObject().put("type", "reasoning").put("id", "rs_exact").put("encrypted_content", "SIGNED-EXACT-REASONING"))
                        .put(JSONObject().put("type", "web_search_call").put("id", "tool_exact").put("status", "completed"))
                        .put(JSONObject().put("type", "message").put("id", "msg_exact").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "Historical answer"))))
                    val historyAssistant = JSONObject().put("role", "assistant").put("provider", "openai")
                        .put("text", "Historical answer").put("status", "completed").put("response_id", "resp_chain_previous")
                        .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, oldFingerprint).put("openai_protocol_output", output)
                    @Suppress("UNCHECKED_CAST")
                    val history = field("chat").get(activity) as MutableList<JSONObject>
                    history.clear(); history.add(historyUser); history.add(historyAssistant)
                    ChatDatabase(context).use { db -> db.upsertCurrentChat(chatId, "Credential chain", "resp_chain_previous", "high", history) }
                    (field("activeResponseState").get(activity) as ActiveResponseStateStore).setResponseId("resp_chain_previous")
                    val nextKey = if (changeKey) "sk-chain-new-account-synthetic" else originalKey
                    val nextModel = if (changeModel) "gpt-6-sol" else originalModel
                    SecureStore(context).setApiKey(nextKey)
                    prefs.edit().putString("model", nextModel).commit()
                    nextFingerprint = OpenAiInstructionChainPolicy.fingerprint(instructions, nextKey, nextModel)
                    (field("input").get(activity) as EditText).setText("Next user question")
                    MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType!!)
                        .apply { isAccessible = true }.invoke(activity, false)
                }
                val request = server.takeRequest(20, TimeUnit.SECONDS) ?: throw AssertionError("Send did not reach HTTPS mock")
                val wire = JSONObject(request.body.readUtf8())
                if (switchWhileLive) {
                    ChatDatabase(context).use { assertNotNull("Create must remain active while the key changes", it.getActiveResponse(chatId)) }
                    SecureStore(context).setApiKey("sk-key-changed-during-live-create")
                }
                assertEquals("Bearer " + if (changeKey) "sk-chain-new-account-synthetic" else originalKey, request.getHeader("Authorization"))
                assertEquals(if (changeModel) "gpt-6-sol" else originalModel, wire.getString("model"))
                assertFalse(wire.has("instructions"))
                val input = wire.getJSONArray("input")
                if (changeKey || changeModel) {
                    assertFalse(wire.has("previous_response_id"))
                    assertFalse("Replay must use owned bytes, not the previous account's image ID", wire.toString().contains("file_old_account_image"))
                    assertFalse("Replay must use owned bytes, not the previous account's file ID", wire.toString().contains("file_old_account_note"))
                    assertEquals("system", input.getJSONObject(0).getString("role"))
                    assertTrue(input.getJSONObject(0).getString("content").startsWith("Chain system fixture"))
                    assertEquals("developer", input.getJSONObject(1).getString("role"))
                    assertTrue(input.getJSONObject(1).getString("content").contains("Chain developer fixture"))
                    assertEquals("user", input.getJSONObject(2).getString("role"))
                    val parts = input.getJSONObject(2).getJSONArray("content")
                    assertEquals("Historical user question", parts.getJSONObject(0).getString("text"))
                    assertTrue((0 until parts.length()).any { parts.getJSONObject(it).optString("type") == "input_image" &&
                        parts.getJSONObject(it).optString("image_url").startsWith("data:image/png;base64,") })
                    assertFalse("Selective local tools must not inline the entire document", parts.toString().contains("EXACT-HISTORICAL-ATTACHMENT-BYTES"))
                    assertTrue(parts.toString().contains(note.name))
                    assertTrue(wire.getJSONArray("tools").toString().contains("read_attached_file"))
                    assertEquals("EXACT-HISTORICAL-ATTACHMENT-BYTES", note.readText())
                    assertEquals("SIGNED-EXACT-REASONING", input.getJSONObject(3).getString("encrypted_content"))
                    assertEquals("tool_exact", input.getJSONObject(4).getString("id"))
                    assertEquals("msg_exact", input.getJSONObject(5).getString("id"))
                    assertEquals("assistant", input.getJSONObject(5).getString("role"))
                    assertEquals("Next user question", input.getJSONObject(6).getJSONArray("content").getJSONObject(0).getString("text"))
                    assertEquals(7, input.length())
                } else {
                    assertEquals("resp_chain_previous", wire.getString("previous_response_id"))
                    assertEquals(1, input.length())
                    assertEquals("user", input.getJSONObject(0).getString("role"))
                    assertTrue(input.toString().contains(note.name))
                    assertFalse(input.toString().contains("EXACT-HISTORICAL-ATTACHMENT-BYTES"))
                    assertTrue(wire.getJSONArray("tools").toString().contains("read_attached_file"))
                }
                val deadline = SystemClock.elapsedRealtime() + 20_000L
                var finalized = false
                while (!finalized && SystemClock.elapsedRealtime() < deadline) {
                    finalized = ChatDatabase(context).use { db -> db.getActiveResponse(chatId) == null &&
                        db.getChat(chatId)?.getJSONArray("messages")?.length() == 4 }
                    if (!finalized) SystemClock.sleep(25)
                }
                assertTrue("Send must persist and finalize before Activity close", finalized)
                ChatDatabase(context).use { db ->
                    val restored = db.getChat(chatId)!!.getJSONArray("messages")
                    assertEquals(nextFingerprint, restored.getJSONObject(2).getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
                    assertEquals(nextFingerprint, restored.getJSONObject(3).getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD))
                    assertTrue(OpenAiInstructionChainPolicy.canReuse(responseId,
                        (0 until restored.length()).map { restored.getJSONObject(it) }, nextFingerprint))
                    if (switchWhileLive) assertFalse(OpenAiInstructionChainPolicy.matchesCredential(
                        restored.getJSONObject(3).getString(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD),
                        SecureStore(context).getApiKey()))
                    assertFalse(restored.toString().contains(originalKey))
                    assertFalse(restored.toString().contains("sk-chain-new-account-synthetic"))
                }
            }
        } finally {
            if (chatId.isNotBlank()) ChatDatabase(context).use { it.deleteChat(chatId) }
            note.delete(); image.delete(); server.shutdown()
        }
    }
}
