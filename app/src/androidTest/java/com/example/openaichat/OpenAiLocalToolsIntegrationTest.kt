package com.example.openaichat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector
import android.widget.Button
import android.widget.CheckBox
import android.widget.TextView
import android.widget.EditText
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.work.Data
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.Dispatcher
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
import java.io.File
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32

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
        "file_search", "web", "image_generation", "openai_auto_compaction", "openai_auto_compaction_opt_in_migrated_v25", "app_theme")

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
    private fun awaitFinal(chatId: String, expectTools: Boolean = true) {
        val deadline = SystemClock.elapsedRealtime() + 20_000
        while (SystemClock.elapsedRealtime() < deadline) {
            val message = ChatDatabase(context).use { it.getChat(chatId)?.optJSONArray("messages")?.let { all ->
                (0 until all.length()).map { all.getJSONObject(it) }.lastOrNull { it.optString("role") == "assistant" }
            } }
            if (message?.optString("response_id") == "resp_h_final") {
                assertEquals("Found needle", message.getString("text"))
                if (expectTools) assertTrue(message.getJSONArray("openai_protocol_output").toString().contains("function_call_output"))
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

    @Test fun actualPhotoPickerPersistsTwoImagesAcrossRecreateAndSendsBothOnWire() =
        assertPickerImagesOnWire(pickAny = false, fileAnalysis = false, recreate = true, holdFirstCopy = true)

    @Test fun photoPickerWithFileAnalysisEnabledSendsImagesWithoutContainerOrUploads() =
        assertPickerImagesOnWire(pickAny = false, fileAnalysis = true)

    @Test fun pickAnyWithFileAnalysisEnabledSendsTwoImagesAsInputImageInNewChat() =
        assertPickerImagesOnWire(pickAny = true, fileAnalysis = true)

    @Test fun pickAnyWithoutFileAnalysisSendsTwoImagesInExactOrder() =
        assertPickerImagesOnWire(pickAny = true, fileAnalysis = false)

    @Test fun pickAnyMixedImagesAndTextUsesLocalToolsWithoutUploadingOrReplacingImages() =
        assertPickerImagesOnWire(pickAny = true, fileAnalysis = false, includeText = true)

    @Test fun pickAnyMixedImagesAndTextWithAnalysisUploadsOnlyTextAndKeepsBothVisionImages() =
        assertPickerImagesOnWire(pickAny = true, fileAnalysis = true, includeText = true)

    /** Real picker result -> persisted composer -> Send -> HTTPS request, including optional recreate. */
    private fun assertPickerImagesOnWire(pickAny: Boolean, fileAnalysis: Boolean,
                                        recreate: Boolean = false, includeText: Boolean = false,
                                        holdFirstCopy: Boolean = false) {
        val firstBytes = encodedPng(3, 2, 0xffd52c36.toInt())
        val secondBytes = encodedPng(4, 3, 0xff225fc7.toInt())
        assertFalse("Multi-photo fixtures must have different bytes", firstBytes.contentEquals(secondBytes))
        val payloads = listOf(firstBytes, secondBytes)
        val images = payloads.mapIndexed { index, bytes ->
            File(context.filesDir, "attachments/h-photo-${UUID.randomUUID()}-$index.png").apply {
                parentFile!!.mkdirs(); writeBytes(bytes); files.add(this)
            }
        }
        val textFile = if (includeText) File(context.filesDir, "attachments/h-mixed-${UUID.randomUUID()}.txt").apply {
            writeText("PRIVATE_MIXED_SOURCE_ONLY_READ_ON_REQUEST"); files.add(this)
        } else null
        val selected = images + listOfNotNull(textFile)
        prefs.edit().putBoolean("file_analysis_tools", fileAnalysis).putBoolean("code_interpreter", false).commit()
        val uploadIds = AtomicInteger()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = when {
                request.method == "POST" && request.path == "/v1/responses" -> sse(finalResponse())
                includeText && fileAnalysis && request.method == "POST" && request.path == "/v1/files" ->
                    MockResponse().setBody(JSONObject().put("id", "file_mixed_text_${uploadIds.incrementAndGet()}").toString())
                else -> MockResponse().setResponseCode(400).setBody("Unexpected request: photo originals must stay inline")
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            lateinit var chatId: String
            val firstCopyStarted = CountDownLatch(1)
            val releaseFirstCopy = CountDownLatch(1)
            val secondCopyStarted = CountDownLatch(1)
            scenario.onActivity { activity ->
                activity.newChat(); chatId = activity.currentChatIdForTesting(); chats.add(chatId)
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
                val uris = selected.map { FileProvider.getUriForFile(context, context.packageName + ".fileprovider", it) }
                val clip = ClipData.newUri(context.contentResolver, "photos", uris[0]).apply {
                    uris.drop(1).forEach { addItem(ClipData.Item(it)) }
                }
                if (holdFirstCopy) activity.openRouterAttachmentInputStreamSource = AttachmentInputStreamSource { ctx, uri ->
                    if (uri == uris[0]) {
                        firstCopyStarted.countDown()
                        check(releaseFirstCopy.await(10, TimeUnit.SECONDS)) { "First image copy was not released" }
                    } else if (uri == uris[1]) secondCopyStarted.countDown()
                    DefaultAttachmentInputStreamSource.openInputStream(ctx, uri)
                }
                val picker = if (pickAny) activity.pickAny else field(activity, "pickImage") as Int
                activity.handleActivityResult(picker, Activity.RESULT_OK, Intent().apply { clipData = clip })
            }
            if (holdFirstCopy) {
                try {
                    assertTrue("First selected image must start preparing", firstCopyStarted.await(10, TimeUnit.SECONDS))
                    assertFalse("The second image must not overtake a blocked first image",
                        secondCopyStarted.await(500, TimeUnit.MILLISECONDS))
                } finally { releaseFirstCopy.countDown() }
            }
            val deadline = SystemClock.elapsedRealtime() + 20_000
            var attached = false
            val expectedLocalAttachments = selected.size
            while (!attached && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity {
                    val localReady = (field(it, "attachedOpenRouterAttachments") as List<*>).size == expectedLocalAttachments
                    attached = localReady && it.pendingUploads.get() == 0
                }
                if (!attached) SystemClock.sleep(50)
            }
            assertTrue("All picker sources must be prepared", attached)
            assertEquals("Selecting attachments must not upload any file before Send", 0, server.requestCount)
            if (recreate) scenario.recreate()
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val attachments = field(activity, "attachedOpenRouterAttachments") as List<OpenRouterLocalAttachment>
                assertEquals(expectedLocalAttachments, attachments.size)
                assertEquals(2, attachments.count { it.kind == "image" })
                attachments.forEach { attachment ->
                    files.add(File(attachment.path))
                    attachment.textPath?.let { files.add(File(it)) }
                }
                (field(activity, "attachedFilePaths") as List<*>).filterIsInstance<String>().forEach { files.add(File(it)) }
                MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, http)
                send(activity)
            }
            val textUploads = mutableListOf<RecordedRequest>()
            var recorded = server.takeRequest(20, TimeUnit.SECONDS)
                ?: throw AssertionError("Picker image Send did not reach the HTTPS server")
            while (recorded.path == "/v1/files") {
                textUploads.add(recorded)
                recorded = server.takeRequest(20, TimeUnit.SECONDS)
                    ?: throw AssertionError("Prepared mixed picker sources did not reach Responses")
            }
            assertEquals("POST", recorded.method)
            assertEquals("/v1/responses", recorded.path)
            val body = JSONObject(recorded.body.readUtf8())
            val inputs = body.getJSONArray("input")
            val content = (0 until inputs.length()).map { inputs.getJSONObject(it) }
                .last { it.optString("role") == "user" }.getJSONArray("content")
            val blocks = (0 until content.length()).map { content.getJSONObject(it) }
            val imageBlocks = blocks.filter { it.optString("type") == "input_image" }
            assertEquals("Both images must stay input_image (pickAny=$pickAny, analysis=$fileAnalysis)", 2, imageBlocks.size)
            assertFalse("Images must not become input_file", blocks.any { it.optString("type") == "input_file" })
            if (!includeText || !fileAnalysis) assertFalse("Images must not become container text descriptors",
                content.toString().contains("Complete file in the Code Interpreter container"))
            imageBlocks.forEachIndexed { index, block ->
                assertTrue(block.getString("image_url").startsWith("data:image/png;base64,"))
                assertFalse("Inline originals do not need uploaded file IDs", block.has("file_id"))
                assertArrayEquals("Original image bytes/order at index $index", payloads[index],
                    java.util.Base64.getDecoder().decode(block.getString("image_url").substringAfter(",")))
                assertArrayEquals(payloads[index], images[index].readBytes())
            }
            if (!includeText || !fileAnalysis) assertFalse("Photos alone must not enable a paid container",
                body.optJSONArray("tools")?.toString().orEmpty().contains("code_interpreter"))
            if (includeText) {
                if (fileAnalysis) {
                    val tools = body.getJSONArray("tools")
                    val interpreter = (0 until tools.length()).map { tools.getJSONObject(it) }
                        .single { it.optString("type") == "code_interpreter" }
                    val containerFiles = interpreter.getJSONObject("container").getJSONArray("file_ids")
                    assertEquals("The container gets the document, never the two vision photos", 1, containerFiles.length())
                    assertTrue(containerFiles.getString(0).startsWith("file_mixed_text_"))
                    assertEquals("The complete text source must be uploaded exactly once", 1, textUploads.size)
                    textUploads.forEach { upload ->
                        assertEquals("POST", upload.method)
                        val raw = String(upload.body.clone().readByteArray(), Charsets.ISO_8859_1)
                        assertTrue("Preserve the original text filename", raw.contains("filename=\"${textFile!!.name}\""))
                        assertFalse("Do not append a second text extension", raw.contains("${textFile.name}.txt"))
                        val boundary = upload.getHeader("Content-Type")!!.substringAfter("boundary=").trim('"')
                        assertTrue("The uploaded file must contain the complete text document",
                            raw.contains("\r\n\r\n${textFile!!.readText()}\r\n--$boundary"))
                        assertTrue(raw.contains("Content-Type: text/plain"))
                        assertFalse("Vision MIME must not be uploaded", raw.contains("Content-Type: image/"))
                        payloads.forEach { bytes ->
                            assertFalse("Original PNG bytes must not be uploaded", raw.contains(String(bytes, Charsets.ISO_8859_1)))
                        }
                    }
                } else assertTrue(body.getJSONArray("tools").toString().contains("search_attached_file"))
                assertFalse("Selective tools must not inject full file contents", content.toString().contains("PRIVATE_MIXED_SOURCE"))
                assertTrue(textFile!!.isFile)
            }
            awaitFinal(chatId, expectTools = false)
            assertEquals("Exactly one Responses POST plus the counted text uploads", 1 + textUploads.size, server.requestCount)
            if (!includeText || !fileAnalysis) assertTrue("No image upload is needed", textUploads.isEmpty())
        }
    }

    /** Android's standard encoder, independently checked for decode/dimensions and all PNG CRCs. */
    private fun encodedPng(width: Int, height: Int, color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val bytes = try {
            bitmap.eraseColor(color)
            ByteArrayOutputStream().use { output ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
                output.toByteArray()
            }
        } finally { bitmap.recycle() }
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertArrayEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10), ByteArray(8).also(input::readFully))
            val chunks = mutableListOf<String>()
            while (input.available() > 0) {
                val size = input.readInt()
                assertTrue("Complete PNG chunk", size >= 0 && size <= input.available() - 8)
                val type = ByteArray(4).also(input::readFully)
                val data = ByteArray(size).also(input::readFully)
                assertEquals("PNG CRC for ${String(type, Charsets.US_ASCII)}",
                    CRC32().apply { update(type); update(data) }.value, input.readInt().toLong() and 0xffffffffL)
                chunks.add(String(type, Charsets.US_ASCII))
                if (chunks.last() == "IEND") {
                    assertEquals(0, size)
                    assertEquals("No trailing PNG bytes", 0, input.available())
                }
            }
            assertEquals("IHDR", chunks.first())
            assertTrue(chunks.contains("IDAT"))
            assertEquals("IEND", chunks.last())
        }
        val decoded = requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))
        try {
            assertEquals(width, decoded.width)
            assertEquals(height, decoded.height)
            assertEquals(color, decoded.getPixel(width - 1, height - 1))
        } finally { decoded.recycle() }
        return bytes
    }

    @SdkSuppress(minSdkVersion = 29)
    @Test fun settingsAndResponseSettingsControlsUseReadableThemeColorsInLightAndGrayThemes() {
        fun collectViews(root: View): List<View> {
            val out = mutableListOf(root)
            if (root is ViewGroup) {
                for (i in 0 until root.childCount) out.addAll(collectViews(root.getChildAt(i)))
            }
            return out
        }
        fun assertDialogColors(title: String, theme: ThemeColors, minimumEdits: Int, minimumChecks: Int) {
            // Public API: inspect the real shown dialog, never a detached test-only view tree.
            val root = WindowInspector.getGlobalWindowViews().single { decor ->
                collectViews(decor).filterIsInstance<TextView>().any { it.text.toString() == title }
            }
            try {
                val all = collectViews(root)
                val edits = all.filterIsInstance<EditText>()
                val checks = all.filterIsInstance<CheckBox>()
                assertTrue("$title must contain its real EditTexts", edits.size >= minimumEdits)
                assertTrue("$title must contain its real CheckBoxes", checks.size >= minimumChecks)
                edits.forEach { edit ->
                    assertEquals("$title EditText text in ${theme.id}", theme.textPrimary, edit.currentTextColor)
                    assertEquals("$title EditText hint in ${theme.id}", theme.hintText, edit.currentHintTextColor)
                    assertEquals("$title EditText background in ${theme.id}", theme.inputBg,
                        (edit.background as GradientDrawable).color!!.defaultColor)
                }
                checks.forEach { check ->
                    assertEquals("$title CheckBox text in ${theme.id}", theme.textPrimary, check.currentTextColor)
                }
            } finally {
                root.findViewById<Button>(android.R.id.button2).performClick()
            }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (themeId in listOf("light", "gray")) {
                val theme = AppThemePalette.byId(themeId)
                scenario.onActivity { activity ->
                    prefs.edit().putString("app_theme", themeId).commit()
                    activity.showSettings()
                }
                scenario.onActivity { assertDialogColors("Настройки приложения", theme, 3, 2) }
                scenario.onActivity { activity -> activity.showResponseSettings() }
                scenario.onActivity { assertDialogColors("Настройки модели и ответа", theme, 4, 4) }
            }
        }
    }
}
