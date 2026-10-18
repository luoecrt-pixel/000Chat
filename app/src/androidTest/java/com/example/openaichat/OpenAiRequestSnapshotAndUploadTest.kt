package com.example.openaichat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Color
import android.os.SystemClock
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Real Activity picker/preparation/Send paths; all HTTP is redirected to a private HTTPS mock. */
@RunWith(AndroidJUnit4::class)
class OpenAiRequestSnapshotAndUploadTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val prefs get() = context.getSharedPreferences("app", 0)
    private val cachePrefs get() = context.getSharedPreferences("openai_file_upload_cache", 0)
    private lateinit var server: MockWebServer
    private lateinit var http: OkHttpClient
    private lateinit var savedSettings: Map<String, Any?>
    private lateinit var savedCache: Map<String, *>
    private var oldKey = ""
    private val syntheticKey = "sk-synthetic-k-upload-${UUID.randomUUID()}"
    private val chats = mutableListOf<String>()
    private val files = linkedSetOf<File>()
    private val responseCounter = AtomicInteger()
    private val uploadCounter = AtomicInteger()
    private val sendCounter = AtomicInteger()
    private val requestCounter = AtomicInteger()
    private val attemptedPaths = java.util.concurrent.CopyOnWriteArrayList<String>()
    @Volatile private var observedActivity: MainActivity? = null
    private val settings = listOf(
        "provider", "model", "current_chat", "background_responses", "background_responses_migrated_v25",
        "response_notifications", "reasoning", "reasoning_mode", "text_format", "json_schema", "system_prompt",
        "instructions", "file_analysis_tools", "code_interpreter", "file_search", "vector_store", "web",
        "image_generation", "parallel_tools", "cache_key", "openai_auto_compaction", "openai_container_memory",
        "openai_auto_compaction_opt_in_migrated_v25"
    )

    @Before fun before() {
        savedSettings = settings.associateWith { prefs.all[it] }
        savedCache = cachePrefs.all.toMap()
        oldKey = SecureStore(context).getApiKey()
        assertTrue(SecureStore(context).setApiKey(syntheticKey))
        prefs.edit().putString("provider", "openai").putString("model", "gpt-6-astra")
            .putString("reasoning", "high").putString("reasoning_mode", "standard")
            .putString("text_format", "text").putString("json_schema", "")
            .putString("system_prompt", "").putString("instructions", "")
            .putBoolean("background_responses", false).putBoolean("background_responses_migrated_v25", true)
            .putBoolean("response_notifications", false).putBoolean("file_analysis_tools", false)
            .putBoolean("code_interpreter", false).putBoolean("file_search", false)
            .putBoolean("web", false).putBoolean("image_generation", false).putBoolean("parallel_tools", true)
            .putBoolean("openai_auto_compaction", false).putBoolean("openai_auto_compaction_opt_in_migrated_v25", true)
            .putString("openai_container_memory", "1g").putString("vector_store", "")
            .putString("cache_key", "").commit()
        val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
        val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
        val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
        server = MockWebServer().apply { useHttps(serverTls.sslSocketFactory(), false); start() }
        http = OkHttpClient.Builder().sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
            .addInterceptor { chain ->
                attemptedPaths.add(chain.request().url.encodedPath.takeIf { it in setOf("/v1/files", "/v1/responses") } ?: "other")
                chain.proceed(chain.request().newBuilder().url(server.url(chain.request().url.encodedPath)).build())
            }.build()
        ResumableResponseStreamClient.transportForTests = http to server.url("/")
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/files" -> MockResponse().setBody(
                    JSONObject().put("id", "file_k_${uploadCounter.incrementAndGet()}").toString())
                request.method == "POST" && request.path == "/v1/responses" -> completedResponse(responseCounter.incrementAndGet())
                else -> MockResponse().setResponseCode(400).setBody("Unexpected test request")
            }
        }
    }

    @After fun after() {
        ResumableResponseStreamClient.transportForTests = null
        SecureStore(context).setApiKey(oldKey)
        ChatDatabase(context).use { db -> chats.forEach {
            db.deleteChat(it); prefs.edit().remove("composer_state_$it").commit()
        } }
        restore(prefs, savedSettings, clear = false)
        restore(cachePrefs, savedCache, clear = true)
        files.forEach { it.delete() }
        server.shutdown(); http.dispatcher.executorService.shutdown(); http.connectionPool.evictAll()
    }

    private fun restore(store: SharedPreferences, values: Map<String, *>, clear: Boolean) {
        store.edit().apply {
            if (clear) clear()
            values.forEach { (name, value) -> when (value) {
                is String -> putString(name, value)
                is Boolean -> putBoolean(name, value)
                is Long -> putLong(name, value)
                is Int -> putInt(name, value)
                is Float -> putFloat(name, value)
                else -> remove(name)
            } }
        }.commit()
    }

    private fun completedResponse(index: Int): MockResponse {
        val response = JSONObject().put("id", "resp_k_$index").put("status", "completed").put("model", "gpt-6-astra")
            .put("usage", JSONObject().put("input_tokens", 10).put("output_tokens", 2))
            .put("output", JSONArray().put(JSONObject().put("type", "message").put("id", "msg_k_$index")
                .put("role", "assistant").put("content", JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "Prepared answer $index")))))
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: " + JSONObject().put("type", "response.created").put("sequence_number", 0)
                .put("response", JSONObject().put("id", response.getString("id"))) + "\n\ndata: " +
                JSONObject().put("type", "response.completed").put("sequence_number", 1).put("response", response) + "\n\n")
    }

    private fun field(activity: MainActivity, name: String): Any? = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun setField(activity: MainActivity, name: String, value: Any?) = MainActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun invoke(activity: MainActivity, method: String) = MainActivity::class.java.getDeclaredMethod(method)
        .apply { isAccessible = true }.invoke(activity)
    private fun send(activity: MainActivity, text: String = "Inspect the complete attached files") {
        observedActivity = activity
        sendCounter.incrementAndGet()
        (field(activity, "input") as EditText).setText(text)
        MainActivity::class.java.getDeclaredMethod("sendMessage", Boolean::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(activity, false)
    }
    private fun initialize(activity: MainActivity): String {
        activity.newChat(); setField(activity, "client", http)
        return activity.currentChatIdForTesting().also(chats::add)
    }
    private fun source(name: String, bytes: ByteArray): File = File(context.filesDir, "attachments/k-${UUID.randomUUID()}/$name")
        .apply { parentFile!!.mkdirs(); writeBytes(bytes); files.add(this) }
    private fun textSource(name: String = "note.txt") = source(name, "Original UTF-8 source ${UUID.randomUUID()} 😀".toByteArray())
    private fun attach(activity: MainActivity, file: File, mime: String = "text/plain") {
        val item = OpenRouterAttachmentStore.classifyLocalFile(context, file, file.name, mime)
        item.textPath?.let { files.add(File(it)) }
        @Suppress("UNCHECKED_CAST")
        (field(activity, "attachedOpenRouterAttachments") as MutableList<OpenRouterLocalAttachment>).add(item)
    }
    private fun awaitCondition(message: String, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(25)
        }
        fail(message)
    }
    private fun awaitCompleted(chatId: String, index: Int) = awaitCondition("Response $index was not committed") {
        ChatDatabase(context).use { db ->
            db.getActiveResponse(chatId) == null && db.getChat(chatId)?.optJSONArray("messages")?.let { messages ->
                (0 until messages.length()).any { messages.optJSONObject(it)?.optString("response_id") == "resp_k_$index" }
            } == true
        }
    }
    private fun request(): RecordedRequest {
        val ordinal = requestCounter.incrementAndGet()
        return server.takeRequest(20, TimeUnit.SECONDS) ?: run {
            var ui = "unavailable"
            var chatId: String? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                observedActivity?.let { activity ->
                    chatId = activity.currentChatIdForTesting()
                    val status = (field(activity, "status") as? TextView)?.text?.toString().orEmpty()
                    // Categories only: status can contain provider errors or paths; never print raw UI text.
                    val category = when {
                        status.contains("ошиб", true) -> "error"
                        status.contains("подготов", true) -> "preparing"
                        status.contains("загруз", true) -> "uploading"
                        status.contains("останов", true) -> "stopped"
                        else -> "other"
                    }
                    val sendEnabled = (field(activity, "send") as? android.view.View)?.isEnabled
                    val stopVisible = (field(activity, "stop") as? android.view.View)?.visibility == android.view.View.VISIBLE
                    ui = "statusCategory=$category,statusLength=${status.length},sendEnabled=$sendEnabled,stopVisible=$stopVisible"
                }
            }
            val activeStatus = chatId?.let { id -> ChatDatabase(context).use { it.getActiveResponse(id)?.optString("status") } }
                ?.takeIf { it in setOf("queued", "in_progress", "completed", "failed", "cancelled", "incomplete") } ?: "none-or-other"
            error("No expected HTTPS request at send=${sendCounter.get()},request=$ordinal: serverRequestCount=${server.requestCount},attemptedPaths=$attemptedPaths,$ui,activeResponse=$activeStatus")
        }
    }
    private fun tools(body: JSONObject): List<JSONObject> = body.optJSONArray("tools")?.let { arr ->
        (0 until arr.length()).map { arr.getJSONObject(it) }
    }.orEmpty()

    @Test fun disabledPaidSettingsCannotBeEnabledByPreferenceChangesDuringActualOpenAiPreparation() =
        assertSnapshot(initiallyEnabled = false)

    @Test fun enabledSettingsAndContainerMemoryRemainTheSendSnapshotDuringActualOpenAiPreparation() =
        assertSnapshot(initiallyEnabled = true)

    private fun assertSnapshot(initiallyEnabled: Boolean) {
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val note = textSource()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                var chatId = ""
                scenario.onActivity { activity ->
                    chatId = initialize(activity)
                    prefs.edit().putBoolean("web", initiallyEnabled).putBoolean("file_search", initiallyEnabled)
                        .putBoolean("file_analysis_tools", initiallyEnabled)
                        .putString("vector_store", "vs_send_snapshot").putBoolean("code_interpreter", initiallyEnabled)
                        .putBoolean("image_generation", initiallyEnabled).putBoolean("parallel_tools", !initiallyEnabled)
                        .putBoolean("openai_auto_compaction", initiallyEnabled)
                        .putString("text_format", if (initiallyEnabled) "json_object" else "text")
                        .putString("cache_key", "send-snapshot").commit()
                    attach(activity, note)
                    setField(activity, "openAiPreparationHook", {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); Unit
                    })
                    send(activity)
                }
                assertTrue("Production Send must enter async OpenAI preparation", entered.await(10, TimeUnit.SECONDS))
                assertEquals("Preparation may not send HTTP before the captured hook is released", 0, server.requestCount)
                scenario.onActivity { activity ->
                    prefs.edit().putBoolean("web", !initiallyEnabled).putBoolean("file_search", !initiallyEnabled)
                        .putBoolean("file_analysis_tools", !initiallyEnabled)
                        .putString("vector_store", "vs_changed_during_prepare")
                        .putBoolean("code_interpreter", !initiallyEnabled).putBoolean("image_generation", !initiallyEnabled)
                        .putBoolean("parallel_tools", initiallyEnabled).putBoolean("openai_auto_compaction", !initiallyEnabled)
                        .putString("text_format", if (initiallyEnabled) "text" else "json_object")
                        .putString("openai_container_memory", "64g").putString("cache_key", "changed-during-prepare").commit()
                    (field(activity, "input") as EditText).setText("A new unsent draft")
                }
                release.countDown()
                val (uploads, body) = takeUploadsAndResponse()
                val requestedTools = tools(body)
                assertEquals("File analysis must use the Send snapshot", if (initiallyEnabled) 1 else 0, uploads.size)
                uploads.forEach { upload ->
                    assertEquals(note.name, multipartFile(upload).first)
                    assertArrayEquals(note.readBytes(), multipartFile(upload).second)
                }
                for (type in listOf("web_search", "file_search", "code_interpreter", "image_generation")) {
                    assertEquals("$type must use the Send snapshot", initiallyEnabled, requestedTools.any { it.optString("type") == type })
                }
                assertEquals(!initiallyEnabled, body.getBoolean("parallel_tool_calls"))
                assertEquals("send-snapshot", body.getString("prompt_cache_key"))
                assertEquals(if (initiallyEnabled) "json_object" else "text",
                    body.getJSONObject("text").optJSONObject("format")?.optString("type") ?: "text")
                assertEquals("Compaction must use the Send snapshot", initiallyEnabled, body.has("context_management"))
                if (initiallyEnabled) {
                    assertEquals("1g", requestedTools.single { it.optString("type") == "code_interpreter" }
                        .getJSONObject("container").getString("memory_limit"))
                    assertEquals("vs_send_snapshot", requestedTools.single { it.optString("type") == "file_search" }
                        .getJSONArray("vector_store_ids").getString(0))
                }
                assertEquals("Mid-flight file-analysis changes must not change uploads", if (initiallyEnabled) 1 else 0,
                    uploadCounter.get())
                awaitCompleted(chatId, 1)
                scenario.onActivity { assertEquals("A new unsent draft", (field(it, "input") as EditText).text.toString()) }
                assertNull(server.takeRequest(300, TimeUnit.MILLISECONDS))
            } finally {
                release.countDown()
                scenario.onActivity { setField(it, "openAiPreparationHook", null) }
            }
        }
    }

    @Test fun realMixedPickerUploadsEachTextAndWorkbookOnceOnSendThenReusesTheExactCachedIds() {
        val note = textSource(); val workbook = workbookSource(); val image = pngSource()
        val originalImage = image.readBytes()
        prefs.edit().putBoolean("file_analysis_tools", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = initialize(it); pick(it, listOf(note, workbook, image)) }
            awaitPrepared(scenario, 3)
            assertEquals("Picker preparation is local, before Send", 0, server.requestCount)
            scenario.onActivity { send(it) }
            val (uploads, body) = takeUploadsAndResponse()
            assertEquals(2, uploads.size)
            val expected = mapOf(note.name to note.readBytes(), workbook.name to workbook.readBytes())
            uploads.forEach { upload ->
                val multipart = multipartFile(upload)
                assertTrue("Only text and workbook must be uploaded: ${multipart.first}", expected.containsKey(multipart.first))
                assertArrayEquals("Original uploaded bytes for ${multipart.first}", expected.getValue(multipart.first), multipart.second)
                assertEquals("Bearer $syntheticKey", upload.getHeader("Authorization"))
            }
            assertEquals(expected.keys, uploads.map { multipartFile(it).first }.toSet())
            val ids = containerFileIds(body)
            assertEquals(setOf("file_k_1", "file_k_2"), ids)
            val imageBlocks = inputBlocks(body).filter { it.optString("type") == "input_image" }
            assertEquals(1, imageBlocks.size)
            assertArrayEquals(originalImage, java.util.Base64.getDecoder().decode(imageBlocks.single().getString("image_url").substringAfter(',')))
            assertFalse(inputBlocks(body).any { it.optString("type") == "input_file" && it.optString("file_id").startsWith("file_k_") })
            awaitCompleted(chatId, 1)
            scenario.onActivity { send(it, "Continue using the same two complete documents") }
            val (repeatUploads, repeatBody) = takeUploadsAndResponse()
            assertTrue("The next turn reuses document file IDs", repeatUploads.isEmpty())
            assertEquals(ids, containerFileIds(repeatBody))
            awaitCompleted(chatId, 2)
            assertEquals(2, uploadCounter.get()); assertEquals(4, server.requestCount)
            assertTrue(note.isFile); assertTrue(workbook.isFile); assertArrayEquals(originalImage, image.readBytes())
        }
    }

    @Test fun switchingCredentialReuploadsHistoricalDocumentInsteadOfReusingTheOtherAccountsFileId() {
        val note = textSource()
        prefs.edit().putBoolean("file_analysis_tools", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = initialize(it); pick(it, listOf(note)) }
            awaitPrepared(scenario, 1); assertEquals(0, server.requestCount)
            scenario.onActivity { send(it) }
            val (firstUploads, firstBody) = takeUploadsAndResponse()
            assertEquals(1, firstUploads.size); assertEquals(setOf("file_k_1"), containerFileIds(firstBody))
            awaitCompleted(chatId, 1)
            val secondKey = "sk-synthetic-k-second-account-${UUID.randomUUID()}"
            scenario.onActivity { assertTrue(SecureStore(context).setApiKey(secondKey)); send(it, "Analyze under the new account") }
            val (secondUploads, secondBody) = takeUploadsAndResponse()
            assertEquals(1, secondUploads.size)
            assertEquals("Bearer $secondKey", secondUploads.single().getHeader("Authorization"))
            assertArrayEquals(note.readBytes(), multipartFile(secondUploads.single()).second)
            assertEquals(setOf("file_k_2"), containerFileIds(secondBody))
            awaitCompleted(chatId, 2)
        }
    }

    @Test fun identicalBytesWithDifferentPickerFilenamesKeepDistinctUploadMetadataAndIds() {
        val bytes = "Same bytes, independently named ${UUID.randomUUID()}".toByteArray()
        val first = source("a.txt", bytes); val second = source("b.txt", bytes)
        prefs.edit().putBoolean("file_analysis_tools", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var chatId = ""
            scenario.onActivity { chatId = initialize(it); pick(it, listOf(first, second)) }
            awaitPrepared(scenario, 2); assertEquals(0, server.requestCount)
            scenario.onActivity { send(it) }
            val (uploads, body) = takeUploadsAndResponse()
            assertEquals(2, uploads.size)
            assertEquals(setOf("a.txt", "b.txt"), uploads.map { multipartFile(it).first }.toSet())
            uploads.forEach { assertArrayEquals(bytes, multipartFile(it).second) }
            assertEquals(setOf("file_k_1", "file_k_2"), containerFileIds(body))
            awaitCompleted(chatId, 1)
        }
    }

    @Test fun stopWhileActualOpenAiPreparationIsBlockedPreservesDraftAndNeverUploadsOrPosts() =
        assertPreparationAbort(removeSource = false)

    @Test fun missingOriginalAfterActualPickerPreparationFailsClosedWithoutUploadingDerivative() =
        assertPreparationAbort(removeSource = true)

    @Test fun openAiPickerCompletionAfterNavigationStaysInItsSourceDraftWithoutUploading() {
        val note = textSource()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        prefs.edit().putBoolean("file_analysis_tools", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                var sourceChat = ""; var currentChat = ""
                scenario.onActivity { activity ->
                    sourceChat = initialize(activity)
                    (field(activity, "input") as EditText).setText("Source draft")
                    activity.openRouterAttachmentInputStreamSource = AttachmentInputStreamSource { ctx, uri ->
                        entered.countDown()
                        check(release.await(15, TimeUnit.SECONDS)) { "Source picker copy was not released" }
                        DefaultAttachmentInputStreamSource.openInputStream(ctx, uri)
                    }
                    pick(activity, listOf(note))
                }
                assertTrue("The actual picker copy must be pending", entered.await(10, TimeUnit.SECONDS))
                scenario.onActivity { activity ->
                    currentChat = initialize(activity)
                    (field(activity, "input") as EditText).setText("Unrelated current draft")
                }
                release.countDown()
                awaitCondition("Local picker result was not persisted in the source draft") {
                    val state = prefs.getString("composer_state_$sourceChat", null)?.let(::JSONObject)
                    (state?.optJSONArray("openrouter_attachments")?.length() ?: 0) == 1
                }
                val sourceState = JSONObject(prefs.getString("composer_state_$sourceChat", "{}")!!)
                assertEquals("Source draft", sourceState.getString("text"))
                val local = sourceState.getJSONArray("openrouter_attachments").getJSONObject(0)
                assertEquals(note.name, local.getString("name")); assertEquals("text", local.getString("kind"))
                assertArrayEquals(note.readBytes(), File(local.getString("path")).readBytes())
                files.add(File(local.getString("path")))
                local.optString("text_path").takeIf { it.isNotBlank() }?.let { files.add(File(it)) }
                scenario.onActivity { activity ->
                    assertEquals(currentChat, activity.currentChatIdForTesting())
                    assertEquals("Unrelated current draft", (field(activity, "input") as EditText).text.toString())
                    assertTrue((field(activity, "attachedOpenRouterAttachments") as List<*>).isEmpty())
                    assertTrue((field(activity, "attachedFileIds") as List<*>).isEmpty())
                }
                assertEquals("A stale local picker copy must not cause any HTTP", 0, server.requestCount)
            } finally { release.countDown() }
        }
    }

    private fun assertPreparationAbort(removeSource: Boolean) {
        val note = textSource(); val entered = CountDownLatch(1); val release = CountDownLatch(1)
        prefs.edit().putBoolean("file_analysis_tools", true).commit()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            try {
                var chatId = ""
                scenario.onActivity { chatId = initialize(it); pick(it, listOf(note)) }
                awaitPrepared(scenario, 1)
                lateinit var selectedOriginal: File
                scenario.onActivity { activity ->
                    val selected = (field(activity, "attachedOpenRouterAttachments") as List<*>).single() as OpenRouterLocalAttachment
                    selectedOriginal = File(selected.path)
                    setField(activity, "openAiPreparationHook", {
                        entered.countDown(); check(release.await(15, TimeUnit.SECONDS)); Unit
                    })
                    send(activity, "Keep this unsent request")
                }
                assertTrue(entered.await(10, TimeUnit.SECONDS))
                if (removeSource) assertTrue(selectedOriginal.delete()) else scenario.onActivity { activity ->
                    (field(activity, "input") as EditText).setText("New draft while preparing")
                    invoke(activity, "stopCurrentResponse")
                }
                release.countDown()
                awaitCondition("The aborted preparation retained its pending owner") {
                    var finished = false
                    scenario.onActivity { activity ->
                        @Suppress("UNCHECKED_CAST")
                        val pending = field(activity, "pendingOpenAiPreparation") as AtomicReference<String?>
                        finished = pending.get() == null && activity.send.isEnabled
                    }
                    finished
                }
                if (removeSource) awaitCondition("Missing source did not produce a preflight error") {
                    var failed = false
                    scenario.onActivity { failed = (field(it, "status") as TextView).text.toString().startsWith("Не отправлено") }
                    failed
                }
                assertNull(server.takeRequest(1, TimeUnit.SECONDS)); assertEquals(0, server.requestCount)
                scenario.onActivity { activity ->
                    assertEquals(if (removeSource) "Keep this unsent request" else "New draft while preparing",
                        (field(activity, "input") as EditText).text.toString())
                    assertEquals(1, (field(activity, "attachedOpenRouterAttachments") as List<*>).size)
                }
                assertNull(ChatDatabase(context).use { it.getActiveResponse(chatId) })
                assertTrue("The picker source remains intact", note.isFile)
            } finally {
                release.countDown(); scenario.onActivity { setField(it, "openAiPreparationHook", null) }
            }
        }
    }

    private fun pick(activity: MainActivity, selected: List<File>) {
        val uris = selected.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
        val clip = ClipData.newUri(context.contentResolver, "local sources", uris.first()).apply {
            uris.drop(1).forEach { addItem(ClipData.Item(it)) }
        }
        activity.handleActivityResult(activity.pickAny, Activity.RESULT_OK, Intent().apply { clipData = clip })
    }
    private fun awaitPrepared(scenario: ActivityScenario<MainActivity>, count: Int) = awaitCondition("Picker did not finish local preparation") {
        var prepared = false
        scenario.onActivity { activity ->
            val items = (field(activity, "attachedOpenRouterAttachments") as List<*>).filterIsInstance<OpenRouterLocalAttachment>()
            prepared = items.size == count && activity.pendingUploads.get() == 0
            if (prepared) items.forEach { item -> files.add(File(item.path)); item.textPath?.let { files.add(File(it)) } }
        }
        prepared
    }
    private fun takeUploadsAndResponse(): Pair<List<RecordedRequest>, JSONObject> {
        val uploads = mutableListOf<RecordedRequest>()
        var next = request()
        while (next.path == "/v1/files") { uploads.add(next); next = request() }
        assertEquals("POST", next.method); assertEquals("/v1/responses", next.path)
        return uploads to JSONObject(next.body.readUtf8())
    }
    private fun containerFileIds(body: JSONObject): Set<String> = tools(body).single { it.optString("type") == "code_interpreter" }
        .getJSONObject("container").getJSONArray("file_ids").let { ids ->
            (0 until ids.length()).map { ids.getString(it) }.toSet()
        }
    private fun inputBlocks(body: JSONObject): List<JSONObject> = body.getJSONArray("input").let { input ->
        (0 until input.length()).mapNotNull { input.optJSONObject(it)?.optJSONArray("content") }
            .flatMap { content -> (0 until content.length()).mapNotNull { content.optJSONObject(it) } }
    }
    private fun multipartFile(request: RecordedRequest): Pair<String, ByteArray> {
        val raw = String(request.body.clone().readByteArray(), Charsets.ISO_8859_1)
        val boundary = request.getHeader("Content-Type")!!.substringAfter("boundary=").trim('"')
        val fileHeader = raw.indexOf("name=\"file\"; filename=\"")
        assertTrue("A complete file multipart part is required", fileHeader >= 0)
        val nameStart = raw.indexOf("filename=\"", fileHeader) + "filename=\"".length
        val filename = raw.substring(nameStart, raw.indexOf('"', nameStart))
        val payloadStart = raw.indexOf("\r\n\r\n", fileHeader) + 4
        val payloadEnd = raw.indexOf("\r\n--$boundary", payloadStart)
        assertTrue("Complete multipart bytes", payloadStart >= 4 && payloadEnd >= payloadStart)
        return filename to raw.substring(payloadStart, payloadEnd).toByteArray(Charsets.ISO_8859_1)
    }
    private fun pngSource(): File {
        val bitmap = Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888)
        val bytes = try { bitmap.eraseColor(Color.BLUE); ByteArrayOutputStream().use {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)); it.toByteArray()
        } } finally { bitmap.recycle() }
        return source("photo.png", bytes)
    }
    private fun workbookSource(): File {
        val entries = linkedMapOf(
            "[Content_Types].xml" to """<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/><Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/></Types>""",
            "_rels/.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r0" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/></Relationships>""",
            "xl/workbook.xml" to """<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Data" sheetId="1" r:id="r1"/></sheets></workbook>""",
            "xl/_rels/workbook.xml.rels" to """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="r1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/></Relationships>""",
            "xl/worksheets/sheet1.xml" to """<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData><row r="1"><c r="A1"><v>42</v></c><c r="B1" t="inlineStr"><is><t>${UUID.randomUUID()}</t></is></c></row></sheetData></worksheet>"""
        )
        val file = source("data.xlsx", byteArrayOf())
        ZipOutputStream(file.outputStream()).use { zip -> entries.forEach { (path, xml) ->
            zip.putNextEntry(ZipEntry(path)); zip.write(xml.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
        return file
    }
}
