package com.example.openaichat

import android.content.SharedPreferences
import android.content.Intent
import android.os.SystemClock
import android.widget.LinearLayout
import android.widget.Button
import android.app.Activity
import android.app.AlertDialog
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okhttp3.OkHttpClient
import java.lang.reflect.Proxy
import java.util.concurrent.CompletableFuture
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class MainActivityLifecycleTest {
    @Test fun largeAnswersAndSignedProtocolSurviveHistoryAndCheckpointReopen() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "large-protocol-" + java.util.UUID.randomUUID()
        val text = "Длинный ответ 😀\n".repeat(150000)
        val opaque = "signed-data-".repeat(260000)
        val protocol = JSONObject().put("version", 1).put("tail", JSONArray().put(JSONObject()
            .put("role", "assistant").put("content", "visible")
            .put("reasoning_details", JSONArray().put(JSONObject().put("type", "reasoning.encrypted").put("data", opaque)))))
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat(chatId, "Large regression")
                db.upsertCurrentChat(chatId, "Large regression", null, null, listOf(JSONObject()
                    .put("role", "assistant").put("text", text).put("request_id", "r").put("openrouter_protocol", protocol)))
                db.setActiveResponse(chatId, null, "active", text, "in_progress", 1, "max", "max", false, 0, 0, true)
                db.saveOpenRouterCheckpoint(chatId, "active", protocol)
            }
            ChatDatabase(context).use { db ->
                val restored = db.getChat(chatId)!!.getJSONArray("messages").getJSONObject(0)
                assertEquals(text, restored.getString("text"))
                assertEquals(opaque, restored.getJSONObject("openrouter_protocol").getJSONArray("tail")
                    .getJSONObject(0).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
                assertEquals(text, db.getActiveResponse(chatId)!!.getString("text"))
                assertTrue(db.isActiveRequest(chatId, "active")); assertFalse(db.isActiveRequest(chatId, "old"))
                assertEquals(opaque, db.getOpenRouterCheckpoint(chatId, "active")!!.getJSONArray("tail")
                    .getJSONObject(0).getJSONArray("reasoning_details").getJSONObject(0).getString("data"))
                assertEquals(text, db.listActiveResponses().first { it.getString("chat_id") == chatId }.getString("text"))
            }
        } finally { ChatDatabase(context).use { it.deleteChat(chatId) } }
    }

    // Verify the *real Activity Send* JSON rather than a helper-only fixture.
    // The interceptor redirects a synthetic request to a locally trusted HTTPS
    // MockWebServer; no live provider endpoint or billable credentials are used.
    private fun actualOpenAiWireWithOwnedLocalManifest(replayAttachment: Boolean): JSONObject {
        val (server, testHttpClient) = createHttpsMockServer()
        val response = JSONObject().put("id", "resp_mock_new").put("status", "completed")
            .put("output", JSONArray().put(JSONObject().put("type", "message")
                .put("role", "assistant").put("content", JSONArray().put(
                    JSONObject().put("type", "output_text").put("text", "ok")))))
        val sse = "data: " + JSONObject().put("type", "response.created")
            .put("sequence_number", 0).put("response", JSONObject().put("id", "resp_mock_new")) +
            "\n\ndata: " + JSONObject().put("type", "response.completed")
                .put("sequence_number", 1).put("response", response) + "\n\n"
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse))

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val prefs = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
            .targetContext.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val modified = listOf("provider", "model", "reasoning", "text_format",
            "system_prompt", "instructions", "file_analysis_tools", "background_responses",
            "openai_auto_compaction", "web", "file_search", "code_interpreter", "image_generation")
        val saved = modified.associateWith { key -> prefs.all[key] }
        var originalApiKey: String? = null
        try {
            scenario.onActivity { activity ->
                activity.newChat()
                activity.prefs.edit().putString("provider", "openai")
                    .putString("model", "gpt-6-astra").putString("reasoning", "high")
                    .putString("text_format", "text")
                    .putString("system_prompt", "System fixture")
                    .putString("instructions", "Developer fixture")
                    .putBoolean("file_analysis_tools", false)
                    .putBoolean("background_responses", false)
                    .putBoolean("openai_auto_compaction", false)
                    .putBoolean("web", false).putBoolean("file_search", false)
                    .putBoolean("code_interpreter", false).putBoolean("image_generation", false)
                    .commit()
                originalApiKey = SecureStore(activity).getApiKey()
                SecureStore(activity).setApiKey("sk-synthetic-only")
                val secureClient = testHttpClient.newBuilder().addInterceptor { chain ->
                    val redirected = chain.request().newBuilder()
                        .url(server.url("/v1/responses")).build()
                    chain.proceed(redirected)
                }.build()
                setPrivateField(activity, "client", secureClient)
                val presentation = invoke(activity, "presentationInstructionsForRequest") as String?
                val fingerprint = OpenAiInstructionChainPolicy.fingerprint(
                    GenerationInstructions("System fixture", "Developer fixture", presentation))
                @Suppress("UNCHECKED_CAST")
                val history = privateField(activity, "chat") as MutableList<JSONObject>
                history.clear()
                history.add(JSONObject().put("role", "user").put("text", "Earlier question")
                    .put("provider", "openai"))
                history.add(JSONObject().put("role", "assistant").put("text", "Earlier answer")
                    .put("provider", "openai").put("response_id", "resp_previous")
                    .put("status", "completed")
                    .put(OpenAiInstructionChainPolicy.FINGERPRINT_FIELD, fingerprint)
                    .put("openai_protocol_output", JSONArray().put(
                        JSONObject().put("type", "message").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject()
                                .put("type", "output_text").put("text", "Earlier answer"))))))
                setPrivateField(activity, "currentResponseId", "resp_previous")
                if (replayAttachment) {
                    val file = java.io.File(activity.filesDir, "fixture-openai-owned-note.txt")
                        .apply { writeText("ORIGINAL-LOCAL-BYTES-123") }
                    val manifest = OpenRouterAttachmentStore.classifyLocalFile(
                        file, file.name, "text/plain")
                    @Suppress("UNCHECKED_CAST")
                    val attachments = privateField(activity, "attachedOpenRouterAttachments")
                        as MutableList<OpenRouterLocalAttachment>
                    attachments.add(manifest)
                }
                (privateField(activity, "input") as android.widget.EditText)
                    .setText("Next question")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            val wire = server.takeRequest(20, TimeUnit.SECONDS)
                ?: throw AssertionError("Actual OpenAI Activity Send did not reach HTTPS mock")
            return JSONObject(wire.body.readUtf8())
        } finally {
            scenario.onActivity { activity ->
                originalApiKey?.let { SecureStore(activity).setApiKey(it) }
            }
            prefs.edit().apply {
                for (key in modified) {
                    when (val value = saved[key]) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        else -> remove(key)
                    }
                }
            }.commit()
            scenario.close()
            server.shutdown()
        }
    }

    @Test fun actualOpenAiSendKeepsInheritedChainWithoutRepeatingRoles() {
        val body = actualOpenAiWireWithOwnedLocalManifest(false)
        assertEquals("resp_previous", body.getString("previous_response_id"))
        assertFalse(body.has("instructions"))
        val input = body.getJSONArray("input")
        assertEquals(1, input.length())
        assertEquals("user", input.getJSONObject(0).getString("role"))
    }

    @Test fun actualOpenAiSendSwitchesToFullReplayAndRestoresRolesForManifest() {
        val body = actualOpenAiWireWithOwnedLocalManifest(true)
        assertFalse(body.has("previous_response_id"))
        assertFalse(body.has("instructions"))
        val input = body.getJSONArray("input")
        assertEquals("system", input.getJSONObject(0).getString("role"))
        assertEquals("developer", input.getJSONObject(1).getString("role"))
        assertTrue(input.getJSONObject(0).getString("content").contains("System fixture"))
        assertEquals("Developer fixture", input.getJSONObject(1).getString("content"))
        assertTrue("Historical assistant output must be replayed",
            input.toString().contains("Earlier answer"))
        assertTrue("Original local file bytes must reach OpenAI wire request",
            input.toString().contains("ORIGINAL-LOCAL-BYTES-123"))
        assertTrue(input.toString().contains("Next question"))
    }

    private var savedNotificationPreference: Boolean? = null
    @Before fun suppressUnrelatedNotificationPermissionDialog() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        savedNotificationPreference = if (preferences.contains("response_notifications")) preferences.getBoolean("response_notifications", true) else null
        preferences.edit().putBoolean("response_notifications", false).commit()
    }
    @After fun restoreNotificationPreference() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val edit = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE).edit()
        savedNotificationPreference?.let { edit.putBoolean("response_notifications", it) } ?: edit.remove("response_notifications")
        edit.commit()
    }
    // Production blocks cleartext HTTP. Exercise real Send over an isolated local HTTPS
    // server with a certificate trusted only by this test client's OkHttp instance.
    private fun createHttpsMockServer(): Pair<MockWebServer, OkHttpClient> {
        val localhost = HeldCertificate.Builder()
            .commonName("localhost")
            .addSubjectAlternativeName("localhost")
            .build()
        val serverCertificates = HandshakeCertificates.Builder()
            .heldCertificate(localhost)
            .build()
        val clientCertificates = HandshakeCertificates.Builder()
            .addTrustedCertificate(localhost.certificate)
            .build()
        val server = MockWebServer()
        server.useHttps(serverCertificates.sslSocketFactory(), false)
        server.start()
        val client = OkHttpClient.Builder()
            .sslSocketFactory(clientCertificates.sslSocketFactory(), clientCertificates.trustManager)
            .build()
        return server to client
    }
    @Test
    fun plusOpensThreeAttachmentActionsAndClosesWithoutLaunchingPicker() {
        fun byDescription(v: android.view.View, description: String): android.view.View? {
            if (v.contentDescription?.toString() == description) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    byDescription(v.getChildAt(i), description)?.let { return it }
                }
            }
            return null
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val plus = byDescription(root, "Добавить в чат")
                ?: throw AssertionError("Missing plus button")
            plus.performClick()
            val sheet = activity.attachmentSheetDialog
            assertTrue("Plus sheet must be visible", sheet?.isShowing == true)
            val sheetRoot = sheet!!.window!!.decorView
            for (action in AttachmentMenuPolicy.actions) {
                assertTrue("Missing attachment action: ${action.title}",
                    byDescription(sheetRoot, action.title) != null)
            }
            val close = byDescription(sheetRoot, "Закрыть меню вложений")
                ?: throw AssertionError("Missing sheet close action")
            close.performClick()
            assertFalse("Sheet must dismiss without launching external activity", sheet.isShowing)
        }
        scenario.close()
    }

    @Test
    fun compactHeaderKeepsModelPickerAndComposerEffortAccessible() {
        fun find(v: android.view.View, term: String): android.view.View? {
            if (v.contentDescription?.toString()?.contains(term) == true) return v
            if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) {
                    find(v.getChildAt(i), term)?.let { return it }
                }
            }
            return null
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val root = activity.window.decorView
            val model = find(root, "Выбрать модель")
                ?: throw AssertionError("Bottom model selector missing")
            val effort = find(root, "Усилие мышления")
                ?: throw AssertionError("Composer effort control missing")
            val advanced = find(root, "Дополнительные настройки мышления и ответа")
                ?: throw AssertionError("Advanced panel button missing")
            val header = find(root, "Открыть чаты") ?: error("Header missing")
            val modelLocation = IntArray(2)
            val headerLocation = IntArray(2)
            model.getLocationOnScreen(modelLocation)
            header.getLocationOnScreen(headerLocation)
            assertTrue("Model must be below header", modelLocation[1] > headerLocation[1] + header.height)
            assertTrue("Bottom controls must share composer options row",
                model.parent === effort.parent && model.parent === advanced.parent)
            assertTrue((effort as android.widget.TextView).text.toString().startsWith("⚡"))
            assertTrue("Model picker should be clickable", model.hasOnClickListeners())
            assertTrue("Quick effort picker should be clickable", effort.hasOnClickListeners())
            assertTrue("Advanced settings should be clickable", advanced.hasOnClickListeners())
            assertTrue(find(root, "Добавить в чат") != null)
            assertTrue(find(root, "Настройки") != null)
        }
        scenario.close()
    }

    @Test
    fun cameraResultAfterChatSwitchAndRecreateKeepsOpenAiSourceDraftWithoutUploading() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalChatId = ""
        var otherChatId = ""
        var photoPath = ""
        scenario.onActivity { activity ->
            activity.newChat()
            originalChatId = activity.currentChatIdForTesting()
            photoPath = createCameraFixture(activity)
            activity.setPendingCameraCaptureForTesting(photoPath, originalChatId, "openai")
            activity.newChat()
            otherChatId = activity.currentChatIdForTesting()
            activity.prefs.edit().putString(
                "composer_state_$originalChatId", JSONObject().put("text", "Исходный текст").toString()
            ).apply()
        }
        scenario.recreate()
        scenario.onActivity { activity ->
            activity.completePendingCameraCapture(Activity.RESULT_OK)
            val source = JSONObject(activity.prefs.getString("composer_state_$originalChatId", "{}")!!)
            val other = JSONObject(activity.prefs.getString("composer_state_$otherChatId", "{}")!!)
            assertEquals("Исходный текст", source.getString("text"))
            assertEquals(photoPath, source.getString("image_path"))
            assertEquals("image/jpeg", source.getString("image_mime"))
            assertFalse(source.has("image_file_id")) // Upload is deferred: no API calls in the test.
            assertEquals(1, source.getJSONArray("pending_uploads").length())
            assertEquals("image", source.getJSONArray("pending_uploads").getJSONObject(0).getString("kind"))
            assertFalse(other.has("image_path"))
            assertTrue(java.io.File(photoPath).isFile)
        }
        scenario.close()
    }

    @Test
    fun cameraResultAfterChatSwitchAndRecreateKeepsOpenRouterSourceDraft() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalChatId = ""
        var otherChatId = ""
        scenario.onActivity { activity ->
            activity.newChat()
            originalChatId = activity.currentChatIdForTesting()
            val photoPath = createCameraFixture(activity)
            activity.setPendingCameraCaptureForTesting(photoPath, originalChatId, "openrouter")
            activity.newChat()
            otherChatId = activity.currentChatIdForTesting()
            activity.prefs.edit().putString(
                "composer_state_$originalChatId", JSONObject().put("text", "Старый черновик").toString()
            ).apply()
        }
        scenario.recreate()
        scenario.onActivity { it.completePendingCameraCapture(Activity.RESULT_OK) }
        assertTrue(
            "Camera result was not saved to original OpenRouter draft",
            waitForSourceAttachmentCount(scenario, originalChatId, 1)
        )
        scenario.onActivity { activity ->
            val source = JSONObject(activity.prefs.getString("composer_state_$originalChatId", "{}")!!)
            val entry = source.getJSONArray("openrouter_attachments").getJSONObject(0)
            assertEquals("Старый черновик", source.getString("text"))
            assertEquals("image", entry.getString("kind"))
            assertTrue(java.io.File(entry.getString("path")).isFile)
            val other = JSONObject(activity.prefs.getString("composer_state_$otherChatId", "{}")!!)
            assertEquals(0, other.optJSONArray("openrouter_attachments")?.length() ?: 0)
        }
        scenario.close()
    }

    @Test
    fun cancelledCameraAfterNavigationDeletesOnlyItsTempPhoto() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.newChat()
            val sourceChatId = activity.currentChatIdForTesting()
            val photoPath = createCameraFixture(activity)
            activity.setPendingCameraCaptureForTesting(photoPath, sourceChatId, "openai")
            activity.newChat()
            // newChat() saves the source composer; Cancellation must not change that saved state.
            val savedAfterNavigation = activity.prefs.getString("composer_state_$sourceChatId", null)
            activity.completePendingCameraCapture(Activity.RESULT_CANCELED)
            assertFalse(java.io.File(photoPath).exists())
            assertEquals(savedAfterNavigation, activity.prefs.getString("composer_state_$sourceChatId", null))
        }
        scenario.close()
    }

    private fun createCameraFixture(activity: MainActivity): String {
        val dir = java.io.File(activity.filesDir, "attachments").apply { mkdirs() }
        val image = java.io.File(dir, "camera_test_${System.nanoTime()}.jpg")
        val bitmap = android.graphics.Bitmap.createBitmap(8, 8, android.graphics.Bitmap.Config.ARGB_8888)
        try {
            image.outputStream().use { output ->
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, output))
            }
        } finally {
            bitmap.recycle()
        }
        return image.absolutePath
    }

    @Test
    fun newChatKeepsOldDraftAndStartsWithEmptyComposer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.newChat()
            val source = activity.currentChatIdForTesting()
            val input = activity.window.decorView
                .let { root ->
                    fun findEdit(v: android.view.View): android.widget.EditText? {
                        if (v is android.widget.EditText && v.hint?.toString() == "Сообщение…") return v
                        if (v is android.view.ViewGroup) for (i in 0 until v.childCount)
                            findEdit(v.getChildAt(i))?.let { return it }
                        return null
                    }
                    findEdit(root) ?: throw AssertionError("No composer")
                }
            input.setText("черновик исходного чата")
            activity.newChat()
            assertEquals("", input.text.toString())
            val sourceState = JSONObject(activity.prefs.getString("composer_state_$source", "{}")!!)
            assertEquals("черновик исходного чата", sourceState.getString("text"))
        }
        scenario.close()
    }

    @Test
    fun concurrentSourceCompletionsKeepBothAttachmentsAndOriginalText() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.newChat()
            val source = activity.currentChatIdForTesting()
            activity.prefs.edit().putString("composer_state_$source", JSONObject().put("text","newer draft").toString()).commit()
            val a = OpenRouterLocalAttachment("a","a.txt","text","/tmp/a","text/plain",2L,"sha-a")
            val b = OpenRouterLocalAttachment("b","b.txt","text","/tmp/b","text/plain",2L,"sha-b")
            val gate = CountDownLatch(1)
            val done = CountDownLatch(2)
            listOf(a,b).forEach { attachment ->
                Thread {
                    gate.await()
                    activity.saveOpenRouterAttachmentsToComposer(source, listOf(attachment))
                    done.countDown()
                }.start()
            }
            gate.countDown()
            assertTrue("concurrent writes timed out", done.await(4, TimeUnit.SECONDS))
            val result = JSONObject(activity.prefs.getString("composer_state_$source", "{}")!!)
            assertEquals(2, result.getJSONArray("openrouter_attachments").length())
            assertEquals("newer draft", result.getString("text"))
        }
        scenario.close()
    }

    @Test
    fun callbackMutationPassesWhileResumed() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val generation = activeGeneration(activity)
            val itemId = addAssistantItem(activity)

            invokeCallback(activity, chatId, generation, itemId, "updated")

            assertEquals("updated", adapterSnapshot(activity).first { it.id == itemId }.text)
        }
        scenario.close()
    }

    @Test
    fun lateCallbackIsRejectedAfterStopWithoutChangingGeneration() {
        var chatId = ""
        var generation = -1L
        var itemId = ""

        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            chatId = currentChatId(activity)
            generation = activeGeneration(activity)
            assertTrue(isRequestCurrent(activity, chatId, generation))
            itemId = addAssistantItem(activity)
        }

        scenario.moveToState(Lifecycle.State.CREATED)

        scenario.onActivity { activity ->
            assertEquals(generation, activeGeneration(activity))
            assertFalse(isRequestCurrent(activity, chatId, generation))

            invokeCallback(activity, chatId, generation, itemId, "late")

            assertEquals("initial", adapterSnapshot(activity).first { it.id == itemId }.text)
        }
        scenario.close()
    }

    @Test
    fun stopCancelsOpenRouterCallAndPendingGitHubConfirmation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val callCancelled = AtomicReference(false)
        val confirmationDone = CountDownLatch(1)
        val confirmationResult = AtomicReference<Boolean?>(null)

        scenario.onActivity { activity ->
            val fakeCall = Proxy.newProxyInstance(
                Call::class.java.classLoader,
                arrayOf(Call::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        callCancelled.set(true)
                        null
                    }
                    "isCanceled" -> callCancelled.get()
                    "isExecuted" -> true
                    else -> null
                }
            } as Call
            @Suppress("UNCHECKED_CAST")
            val activeCall = privateField(activity, "openRouterActiveCall") as AtomicReference<Call?>
            activeCall.set(fakeCall)

            val owner = currentChatId(activity) to activeGeneration(activity)
            @Suppress("UNCHECKED_CAST")
            val ownerSlot = privateField(activity, "currentGitHubToolOwner")
                as ThreadLocal<Pair<String, Long>>
            Thread {
                ownerSlot.set(owner)
                try {
                    confirmationResult.set(
                        invoke(
                            activity,
                            "confirmGitHubWrite",
                            String::class.java,
                            JSONObject::class.java,
                            "create_issue_comment",
                            JSONObject().put("issue_number", 1)
                        ) as Boolean
                    )
                } finally {
                    ownerSlot.remove()
                    confirmationDone.countDown()
                }
            }.start()
        }

        var dialogSeen = false
        repeat(100) {
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val dialogRef = privateField(activity, "pendingGitHubConfirmationDialog") as AtomicReference<android.app.AlertDialog?>
                dialogSeen = dialogRef.get()?.isShowing == true
            }
            if (!dialogSeen) SystemClock.sleep(10L)
        }
        assertTrue("GitHub confirmation dialog was not shown", dialogSeen)

        scenario.onActivity { activity ->
            invoke(activity, "stopCurrentResponse")
        }

        assertTrue("confirmation thread did not finish", confirmationDone.await(2, TimeUnit.SECONDS))
        assertEquals(false, confirmationResult.get())
        assertTrue("OpenRouter active Call was not cancelled", callCancelled.get())
        scenario.close()
    }

    @Test
    fun newOpenRouterMessageCancelsPreviousActiveCall() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val callCancelled = AtomicReference(false)

        scenario.onActivity { activity ->
            val fakeCall = Proxy.newProxyInstance(
                Call::class.java.classLoader,
                arrayOf(Call::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        callCancelled.set(true)
                        null
                    }
                    "isCanceled" -> callCancelled.get()
                    "isExecuted" -> true
                    else -> null
                }
            } as Call
            val prefs = activity.prefs
            prefs.edit().putString("provider", "openrouter").apply()
            @Suppress("UNCHECKED_CAST")
            val activeCall = privateField(activity, "openRouterActiveCall") as AtomicReference<Call?>
            activeCall.set(fakeCall)

            invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
        }

        assertTrue("new OpenRouter message did not cancel previous active Call", callCancelled.get())
        scenario.close()
    }

    @Test
    fun newOpenRouterMessageCancelsPreviousActiveCallAndAdvancesGeneration() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val callCancelled = AtomicReference(false)
        var generationBefore = -1L
        var generationAfter = -1L

        scenario.onActivity { activity ->
            val fakeCall = Proxy.newProxyInstance(
                Call::class.java.classLoader,
                arrayOf(Call::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        callCancelled.set(true)
                        null
                    }
                    "isCanceled" -> callCancelled.get()
                    "isExecuted" -> true
                    else -> null
                }
            } as Call
            val prefs = activity.prefs
            prefs.edit().putString("provider", "openrouter").apply()
            val secureStore = SecureStore(activity)
            secureStore.setOpenRouterApiKey("test-key")
            val input = privateField(activity, "input") as android.widget.EditText
            input.setText("hello")
            generationBefore = activeGeneration(activity)

            @Suppress("UNCHECKED_CAST")
            val activeCall = privateField(activity, "openRouterActiveCall") as AtomicReference<Call?>
            activeCall.set(fakeCall)

            invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            generationAfter = activeGeneration(activity)

            assertTrue("sendMessage did not reach nextGeneration()", generationAfter > generationBefore)
            assertTrue("new OpenRouter message did not cancel previous active Call", callCancelled.get())

            invoke(activity, "stopCurrentResponse")
            secureStore.setOpenRouterApiKey("")
        }

        scenario.close()
    }

    @Test
    fun staleOpenRouterCallCreatedAfterGenerationChangeIsCancelledAndNotStored() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val staleCancelled = AtomicReference(false)

        scenario.onActivity { activity ->
            val secureStore = SecureStore(activity)
            secureStore.setOpenRouterApiKey("test-key")
            val prefs = activity.prefs
            prefs.edit().putString("provider", "openrouter").apply()
            val input = privateField(activity, "input") as android.widget.EditText
            input.setText("hello")
            val oldGeneration = activeGeneration(activity)

            invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            assertTrue("sendMessage did not advance generation", activeGeneration(activity) > oldGeneration)

            val staleCall = Proxy.newProxyInstance(
                Call::class.java.classLoader,
                arrayOf(Call::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        staleCancelled.set(true)
                        null
                    }
                    "isCanceled" -> staleCancelled.get()
                    "isExecuted" -> true
                    else -> null
                }
            } as Call

            val accepted = invoke(
                activity,
                "registerOpenRouterCallIfOwned",
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                Call::class.java,
                "stale-token",
                currentChatId(activity),
                oldGeneration,
                staleCall
            ) as Boolean

            assertFalse("stale Call was incorrectly claimed by OpenRouter owner", accepted)
            assertTrue("stale Call was not cancelled", staleCancelled.get())

            invoke(activity, "stopCurrentResponse")
            secureStore.setOpenRouterApiKey("")
        }
        scenario.close()
    }


    @Test
    fun stopCancelsOpenRouterModelPreflightCall() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val callCancelled = AtomicReference(false)
        scenario.onActivity { activity ->
            val fakeCall = Proxy.newProxyInstance(
                Call::class.java.classLoader,
                arrayOf(Call::class.java)
            ) { _, method, _ ->
                when (method.name) {
                    "cancel" -> {
                        callCancelled.set(true)
                        null
                    }
                    "isCanceled" -> callCancelled.get()
                    "isExecuted" -> true
                    else -> null
                }
            } as Call
            @Suppress("UNCHECKED_CAST")
            val preflight = privateField(activity, "openRouterModelPreflightCall") as AtomicReference<Call?>
            preflight.set(fakeCall)
        }

        scenario.moveToState(Lifecycle.State.CREATED)

        assertTrue("OpenRouter model preflight Call was not cancelled on Stop", callCancelled.get())
        scenario.close()
    }

    @Test
    fun staleOpenRouterAttachmentCanPersistToSourceChatAfterNavigation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val sourceChatId = currentChatId(activity)
            invoke(activity, "newChat")
            val currentChatId = currentChatId(activity)
            assertFalse(sourceChatId == currentChatId)

            val attachment = OpenRouterLocalAttachment(
                id = "test-attachment",
                name = "notes.txt",
                kind = "text",
                path = "/tmp/notes.txt",
                mime = "text/plain",
                size = 12L,
                sha256 = "sha-source"
            )
            invokeMethod(
                activity,
                "saveOpenRouterAttachmentsToComposer",
                arrayOf(String::class.java, List::class.java, List::class.java),
                arrayOf(sourceChatId, listOf(attachment), emptyList<Pair<String, String>>())
            )

            val prefs = activity.prefs
            val sourceRaw = prefs.getString("composer_state_$sourceChatId", null)
            val currentRaw = prefs.getString("composer_state_$currentChatId", null)
            val sourceState = if (sourceRaw.isNullOrBlank()) JSONObject() else JSONObject(sourceRaw)
            val currentState = if (currentRaw.isNullOrBlank()) JSONObject() else JSONObject(currentRaw)
            assertEquals(1, sourceState.optJSONArray("openrouter_attachments")?.length() ?: 0)
            assertEquals(0, currentState.optJSONArray("openrouter_attachments")?.length() ?: 0)
        }
        scenario.close()
    }

    @Test
    fun staleOpenRouterPickerCopyPersistsSourceChatThroughExecutorAfterNavigation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val source = BlockingAttachmentInputStreamSource()
        var sourceChatId = ""
        var sourceUri: Uri? = null

        scenario.onActivity { launched ->
            launched.openRouterAttachmentInputStreamSource = source
            sourceChatId = currentChatId(launched)
            sourceUri = Uri.parse("content://blocking.test/notes.txt")
            launched.prefs.edit().putString("provider", "openrouter").apply()
            launched.handleActivityResult(
                launched.pickAny,
                Activity.RESULT_OK,
                Intent().setData(sourceUri)
            )
        }

        val uri = sourceUri!!
        assertTrue("copyAndClassify did not reach the blocking source", source.waitUntilOpened(uri))

        scenario.onActivity { launched -> launched.newChat() }
        source.release(uri)

        assertTrue("stale copy completion did not persist to the source chat", waitForSourceAttachmentCount(scenario, sourceChatId, 1))

        scenario.onActivity { launched ->
            val currentId = currentChatId(launched)
            assertEquals(0, composerAttachmentCount(launched, currentId))
        }
        scenario.close()
    }

    @Test
    fun staleOpenRouterZipExtractionPersistsSourceChatThroughExecutorAfterNavigation() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val source = BlockingAttachmentInputStreamSource()
        var sourceChatId = ""
        var sourceUri: Uri? = null

        scenario.onActivity { launched ->
            // A previous test can leave a composer attachment in the restored chat.
            // Do not treat that old attachment as proof the ZIP executor finished.
            launched.newChat()
            launched.openRouterAttachmentInputStreamSource = source
            sourceChatId = currentChatId(launched)
            assertEquals(0, composerAttachmentCount(launched, sourceChatId))
            sourceUri = Uri.parse("content://blocking.test/archive.zip")
            launched.pendingUploadUris.add(sourceUri.toString() to "zip")
            launched.preparePendingOpenRouterZipsThenSend(false)
        }

        val uri = sourceUri!!
        assertTrue("extractZip did not reach the blocking source", source.waitUntilOpened(uri))

        scenario.onActivity { launched -> launched.newChat() }
        source.release(uri)

        assertTrue("stale ZIP completion did not persist to the source chat", waitForSourceAttachmentCount(scenario, sourceChatId, 1))

        // Completion is asynchronous; assert cleanup explicitly after the source
        // manifest is persisted instead of racing the UI callback.
        var preparationFinished = false
        for (attempt in 0 until 100) {
            scenario.onActivity { launched ->
                preparationFinished = !launched.preparingZipAttachments
            }
            if (preparationFinished) break
            SystemClock.sleep(20L)
        }
        scenario.onActivity { launched ->
            val currentId = currentChatId(launched)
            assertEquals(0, composerAttachmentCount(launched, currentId))
            assertTrue("ZIP operation never cleared the preparation state", preparationFinished)
        }
        scenario.close()
    }

    @Test
    fun staleEndUploadDoesNotMutateStoppedActivityUi() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        var chatId = ""
        var generation = -1L

        scenario.onActivity { launched ->
            activity = launched
            chatId = currentChatId(launched)
            generation = activeGeneration(launched)
            launched.pendingUploads.set(1)
            launched.send.isEnabled = false
        }

        // The lifecycle transition itself is covered by the neighboring stop callback tests.
        // Here we directly set the same ownership gate used by onStop() so this regression
        // remains deterministic on emulators where ActivityScenario can stay RESUMED.
        scenario.onActivity { launched ->
            setPrivateField(launched, "activityVisible", false)
        }

        activity!!.endUpload(chatId, generation)
        SystemClock.sleep(100L)

        scenario.onActivity { launched ->
            assertFalse("stale endUpload re-enabled a stopped Activity send button", launched.send.isEnabled)
        }
        scenario.close()
    }

    @Test
    fun uploadCompletionWhileStoppedReconcilesSendOnForegroundResume() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var activity: MainActivity? = null
        var chatId = ""
        var generation = -1L

        scenario.onActivity { launched ->
            activity = launched
            chatId = currentChatId(launched)
            generation = activeGeneration(launched)
            launched.pendingUploadUris.clear()
            launched.pendingUploads.set(1)
            launched.send.isEnabled = false
        }

        scenario.moveToState(Lifecycle.State.CREATED)

        activity!!.endUpload(chatId, generation)
        SystemClock.sleep(100L)

        scenario.onActivity { launched ->
            assertEquals(0, launched.pendingUploads.get())
            assertFalse("Send should stay disabled while Activity is stopped", launched.send.isEnabled)
        }

        scenario.moveToState(Lifecycle.State.RESUMED)

        scenario.onActivity { launched ->
            assertTrue(
                "Send was not reconciled after returning to foreground with no pending uploads",
                launched.send.isEnabled
            )
        }
        scenario.close()
    }

    @Test
    fun recreateCancelsOpenGitHubConfirmationDecision() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val confirmationDone = CountDownLatch(1)
        val confirmationResult = AtomicReference<Boolean?>(null)
        val oldDialog = AtomicReference<AlertDialog?>(null)

        scenario.onActivity { activity ->
            val owner = currentChatId(activity) to activeGeneration(activity)
            @Suppress("UNCHECKED_CAST")
            val ownerSlot = privateField(activity, "currentGitHubToolOwner")
                as ThreadLocal<Pair<String, Long>>
            Thread {
                ownerSlot.set(owner)
                try {
                    confirmationResult.set(
                        invoke(
                            activity,
                            "confirmGitHubWrite",
                            String::class.java,
                            JSONObject::class.java,
                            "create_issue_comment",
                            JSONObject().put("issue_number", 2)
                        ) as Boolean
                    )
                } finally {
                    ownerSlot.remove()
                    confirmationDone.countDown()
                }
            }.start()
        }

        var dialogSeen = false
        repeat(100) {
            scenario.onActivity { activity ->
                @Suppress("UNCHECKED_CAST")
                val dialogRef = privateField(activity, "pendingGitHubConfirmationDialog") as AtomicReference<AlertDialog?>
                oldDialog.set(dialogRef.get())
                dialogSeen = dialogRef.get()?.isShowing == true
            }
            if (!dialogSeen) SystemClock.sleep(10L)
        }
        assertTrue("GitHub confirmation dialog was not shown", dialogSeen)

        scenario.recreate()

        assertTrue("confirmation did not finish after recreation", confirmationDone.await(2, TimeUnit.SECONDS))
        assertEquals(false, confirmationResult.get())
        assertFalse("old confirmation dialog remained visible", oldDialog.get()?.isShowing == true)

        scenario.onActivity { activity ->
            @Suppress("UNCHECKED_CAST")
            val pending = privateField(activity, "pendingGitHubConfirmation") as AtomicReference<java.util.concurrent.CompletableFuture<Boolean>?>
            assertEquals(null, pending.get())
        }
        scenario.close()
    }

    @Test
    fun realOpenRouterSendKeepsNewDraftAndSendsOriginalPngBytes() {
        val original = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8yyfNwMDAxMDAwMDAAAAO1AEqT6J0VgAAAABJRU5ErkJggg=="
        )
        val (server, testHttpClient) = createHttpsMockServer()
        // Resolve MockWebServer DNS outside of Activity/UI callbacks.
        val endpoint = server.url("/api/v1").toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"picture received\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val model = "anthropic/claude-sonnet-5.5"
        try {
            scenario.onActivity { activity ->
                activity.prefs.edit().putString("provider", "openrouter")
                    .putString("model", model).putString("reasoning", "high").apply()
                SecureStore(activity).setOpenRouterApiKey("test-key")
                val file = java.io.File(activity.filesDir, "photo-with-wrong-jpg-name.jpeg")
                    .apply { writeBytes(original) }
                @Suppress("UNCHECKED_CAST")
                val attachments = privateField(activity, "attachedOpenRouterAttachments")
                    as MutableList<OpenRouterLocalAttachment>
                // Keep intentionally misleading filename/MIME while verifying the true
                // digest; stale or forged digests must be rejected by production.
                val sha = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(original).joinToString("") { "%02x".format(it) }
                attachments.add(OpenRouterLocalAttachment(
                    "photo", "photo-with-wrong-jpg-name.jpeg", "image", file.absolutePath,
                    "image/jpeg", file.length(), sha
                ))
                @Suppress("UNCHECKED_CAST")
                val modalities = privateField(activity, "openRouterModelModalities")
                    as ConcurrentHashMap<String, Set<String>>
                modalities[model] = setOf("text", "image")
                setPrivateField(activity, "openRouterClientFactory",
                    { OpenRouterChatClient(endpoint, testHttpClient, reconcileGenerations = false) })
                setPrivateField(activity, "openRouterPreparationHook", {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    Unit
                })
                val composer = privateField(activity, "input") as android.widget.EditText
                composer.setText("What is in this picture?")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            assertTrue("Send never entered the background preparation", entered.await(3, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val composer = privateField(activity, "input") as android.widget.EditText
                composer.setText("New draft typed while the file is being prepared")
            }
            release.countDown()

            val request = server.takeRequest(20, TimeUnit.SECONDS)
                ?: run {
                    var diagnostic = ""
                    scenario.onActivity { activity ->
                        val status = privateField(activity, "status") as android.widget.TextView
                        @Suppress("UNCHECKED_CAST")
                        val pending = privateField(activity, "pendingOpenRouterPreparation")
                            as AtomicReference<String?>
                        @Suppress("UNCHECKED_CAST")
                        val callOwner = privateField(activity, "openRouterCallOwnerToken")
                            as AtomicReference<String?>
                        diagnostic = "status=" + status.text + "; pending=" + pending.get() +
                            "; callOwner=" + callOwner.get() +
                            "; visible=" + (privateField(activity, "activityVisible") as Boolean)
                    }
                    throw AssertionError("Production Send never reached local MockWebServer: $diagnostic")
                }
            val body = JSONObject(request.body.readUtf8())
            val messages = body.getJSONArray("messages")
            val user = (0 until messages.length()).map { messages.getJSONObject(it) }
                .first { it.optString("role") == "user" }
            val content = user.getJSONArray("content")
            val image = (0 until content.length()).map { content.getJSONObject(it) }
                .first { it.optString("type") == "image_url" }
            val url = image.getJSONObject("image_url").getString("url")
            assertTrue("Wrong image MIME in real HTTP body", url.startsWith("data:image/png;base64,"))
            assertTrue("Source photo was transcoded", java.util.Base64.getDecoder()
                .decode(url.substringAfter(",")).contentEquals(original))

            var completed = false
            for (attempt in 0 until 100) {
                scenario.onActivity { activity ->
                    val status = privateField(activity, "status") as android.widget.TextView
                    completed = status.text.toString().startsWith("Готово")
                    val composer = privateField(activity, "input") as android.widget.EditText
                    assertEquals("New draft typed while the file is being prepared",
                        composer.text.toString())
                }
                if (completed) break
                SystemClock.sleep(25L)
            }
            assertTrue("Mock HTTP success was not reflected by Activity", completed)
        } finally {
            release.countDown()
            scenario.onActivity { activity ->
                setPrivateField(activity, "openRouterPreparationHook", null)
                SecureStore(activity).setOpenRouterApiKey("")
            }
            scenario.close()
            server.shutdown()
        }
    }

    @Test
    fun claudeRouteAndFormatAreCapturedBeforePreparationPreferencesChange() {
        val context = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val saved = listOf("openrouter_claude_route", "openrouter_text_format", "openrouter_json_schema")
            .associateWith { preferences.getString(it, null) }
        val oldKey = SecureStore(context).getOpenRouterApiKey()
        val (server, testHttpClient) = createHttpsMockServer()
        // Resolve MockWebServer host before the factory is called by MainActivity UI.
        val endpoint = server.url("/api/v1").toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"{\\\"answer\\\":\\\"ok\\\"}\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var createdChat: String? = null
        try {
            scenario.onActivity { activity ->
                activity.newChat(); createdChat = activity.currentChatIdForTesting()
                activity.prefs.edit().putString("provider", "openrouter")
                    .putString("model", "anthropic/claude-sonnet-5.5")
                    .putString("openrouter_claude_route", "anthropic")
                    .putString("openrouter_text_format", "json_schema")
                    .putString("openrouter_json_schema", "{\"type\":\"object\",\"properties\":{\"answer\":{\"type\":\"string\"}},\"required\":[\"answer\"],\"additionalProperties\":false}").apply()
                SecureStore(activity).setOpenRouterApiKey("test-key")
                setPrivateField(activity, "openRouterClientFactory",
                    { OpenRouterChatClient(endpoint, testHttpClient, reconcileGenerations = false) })
                setPrivateField(activity, "openRouterPreparationHook", {
                    entered.countDown(); release.await(5, TimeUnit.SECONDS); Unit
                })
                (privateField(activity, "input") as android.widget.EditText).setText("Return JSON")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            assertTrue("Production Send did not reach preparation", entered.await(3, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                activity.prefs.edit().putString("openrouter_claude_route", "auto")
                    .putString("openrouter_text_format", "text").apply()
            }
            release.countDown()
            val body = JSONObject((server.takeRequest(20, TimeUnit.SECONDS) ?: error("Captured Send did not reach local HTTPS")).body.readUtf8())
            val provider = body.getJSONObject("provider")
            assertEquals("anthropic", provider.getJSONArray("order").getString(0))
            assertFalse(provider.getBoolean("allow_fallbacks"))
            assertTrue(provider.getBoolean("require_parameters"))
            assertEquals("json_schema", body.getJSONObject("response_format").getString("type"))
        } finally {
            release.countDown()
            scenario.onActivity { activity ->
                setPrivateField(activity, "openRouterPreparationHook", null)
                invoke(activity, "stopCurrentResponse")
                val edit = activity.prefs.edit()
                saved.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
                edit.apply(); SecureStore(activity).setOpenRouterApiKey(oldKey)
            }
            scenario.close()
            createdChat?.let { chatId -> ChatDatabase(context).use { it.deleteChat(chatId) } }
            server.shutdown()
        }
    }

    @Test
    fun stopDuringBlockedRealOpenRouterPreparationNeverStartsHttp() {
        val (server, testHttpClient) = createHttpsMockServer()
        // Resolve MockWebServer DNS outside of Activity/UI callbacks.
        val endpoint = server.url("/api/v1").toString()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        try {
            scenario.onActivity { activity ->
                activity.prefs.edit().putString("provider", "openrouter")
                    .putString("model", "anthropic/claude-sonnet-5.5").apply()
                SecureStore(activity).setOpenRouterApiKey("test-key")
                setPrivateField(activity, "openRouterClientFactory",
                    { OpenRouterChatClient(endpoint, testHttpClient, reconcileGenerations = false) })
                setPrivateField(activity, "openRouterPreparationHook", {
                    entered.countDown()
                    release.await(5, TimeUnit.SECONDS)
                    Unit
                })
                val composer = privateField(activity, "input") as android.widget.EditText
                composer.setText("Do not send after Stop")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            assertTrue("Preparation hook did not run", entered.await(3, TimeUnit.SECONDS))
            scenario.onActivity { activity ->
                val composer = privateField(activity, "input") as android.widget.EditText
                composer.setText("Unsent draft after Stop")
                invoke(activity, "stopCurrentResponse")
                assertEquals("Unsent draft after Stop", composer.text.toString())
            }
            release.countDown()
            // The only network destination is the local mock; a cancelled snapshot
            // must never create a Call against it.
            assertEquals(null, server.takeRequest(300, TimeUnit.MILLISECONDS))
            scenario.onActivity { activity ->
                val composer = privateField(activity, "input") as android.widget.EditText
                assertEquals("Unsent draft after Stop", composer.text.toString())
                assertTrue(activity.send.isEnabled)
            }
        } finally {
            release.countDown()
            scenario.onActivity { activity ->
                setPrivateField(activity, "openRouterPreparationHook", null)
                SecureStore(activity).setOpenRouterApiKey("")
            }
            scenario.close()
            server.shutdown()
        }
    }

    @Test
    fun missingHistoricalOpenRouterFileKeepsUnsentComposerAndNeverStartsHttp() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var beforeSize = 0
        scenario.onActivity { activity ->
            activity.prefs.edit().putString("provider", "openrouter").apply()
            SecureStore(activity).setOpenRouterApiKey("test-key")
            @Suppress("UNCHECKED_CAST")
            val history = privateField(activity, "chat") as MutableList<JSONObject>
            beforeSize = history.size
            val missing = OpenRouterLocalAttachment(
                "deleted-file", "old.png", "image",
                java.io.File(activity.filesDir, "no-such-attachment.png").absolutePath,
                "image/png", 128L, "removed"
            )
            history.add(
                JSONObject().put("role", "user").put("text", "previous image")
                    .put("provider", "openrouter")
                    .put("attachments", JSONArray().put(missing.toJson()))
            )
            val composer = privateField(activity, "input") as android.widget.EditText
            composer.setText("New message that must stay a draft")
            invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
        }

        var failed = false
        for (attempt in 0 until 100) {
            scenario.onActivity { activity ->
                val status = privateField(activity, "status") as android.widget.TextView
                failed = status.text.toString().contains("Ошибка подготовки вложений")
                if (failed) {
                    val composer = privateField(activity, "input") as android.widget.EditText
                    assertEquals("New message that must stay a draft", composer.text.toString())
                    assertTrue("Send stayed locked after failed preparation", activity.send.isEnabled)
                    @Suppress("UNCHECKED_CAST")
                    val history = privateField(activity, "chat") as MutableList<JSONObject>
                    assertEquals("Failed send was committed to history", beforeSize + 1, history.size)
                    @Suppress("UNCHECKED_CAST")
                    val call = privateField(activity, "openRouterActiveCall") as AtomicReference<Call?>
                    assertEquals("HTTP call started despite missing file", null, call.get())
                    history.removeAt(history.lastIndex)
                    SecureStore(activity).setOpenRouterApiKey("")
                }
            }
            if (failed) break
            SystemClock.sleep(25L)
        }
        assertTrue("Missing historical file did not reach controlled failure path", failed)
        scenario.close()
    }

    @Test
    fun stopDuringOpenRouterPreparationPreservesDraftAndPreviousHistory() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            @Suppress("UNCHECKED_CAST")
            val token = privateField(activity, "pendingOpenRouterPreparation")
                as AtomicReference<String?>
            val composer = privateField(activity, "input") as android.widget.EditText
            composer.setText("Keep this during Stop")
            @Suppress("UNCHECKED_CAST")
            val history = privateField(activity, "chat") as MutableList<JSONObject>
            val size = history.size
            token.set("in-flight-test")
            invoke(activity, "setBusy", Boolean::class.javaPrimitiveType!!, true)
            invoke(activity, "stopCurrentResponse")
            assertEquals(null, token.get())
            assertEquals("Keep this during Stop", composer.text.toString())
            assertEquals(size, history.size)
            assertTrue(activity.send.isEnabled)
        }
        scenario.close()
    }

    @Test
    fun openAiHistoryWithoutResponseChainNeverSilentlyDropsFileInputs() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val httpAttempts = AtomicInteger(0)
        var originalApiKey: String? = null
        var originalHistory = emptyList<JSONObject>()
        var expectedHistorySize = 0
        var preparationGeneration = 0L

        fun assertFailedPreparation() {
            // Local history encoding now runs off-main. Wait for its owned failure
            // before inspecting the draft, rather than assuming a synchronous guard.
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var failed = false
            while (!failed && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val pending = privateField(activity, "pendingOpenAiPreparation")
                        as AtomicReference<*>
                    val status = privateField(activity, "status") as android.widget.TextView
                    failed = pending.get() == null &&
                        status.text.toString().startsWith("Не отправлено:")
                }
                if (!failed) SystemClock.sleep(20L)
            }
            scenario.onActivity { activity ->
                val status = privateField(activity, "status") as android.widget.TextView
                assertTrue("Local context failure did not settle: ${status.text}", failed)
                assertTrue("Missing explanation for unavailable bytes: ${status.text}",
                    status.text.toString().contains("локальных байтов"))
                assertEquals("Failed preparation must not commit another generation",
                    preparationGeneration, activeGeneration(activity))
                assertEquals("Next question",
                    (privateField(activity, "input") as android.widget.EditText).text.toString())
                @Suppress("UNCHECKED_CAST")
                val history = privateField(activity, "chat") as MutableList<JSONObject>
                assertEquals("Unsafe fallback appended to chat", expectedHistorySize, history.size)
                assertTrue("Remote-only file must remain in history",
                    history.any { it.optString("image_file_id") == "file-example" })
                assertEquals("Preflight failure must not start HTTP", 0, httpAttempts.get())
                assertTrue("Failure must release Send", activity.send.isEnabled)
                assertEquals("Failure must release Stop", android.view.View.GONE,
                    (privateField(activity, "stop") as android.view.View).visibility)
            }
        }

        try {
            scenario.onActivity { activity ->
                activity.newChat()
                activity.prefs.edit().putString("provider", "openai")
                    .putString("model", "gpt-6-astra").putString("reasoning", "high")
                    .putString("text_format", "text").apply()
                val secrets = SecureStore(activity)
                originalApiKey = secrets.getApiKey()
                secrets.setApiKey("test-key")
                // Reject any accidental real call, and prove both fallback cases
                // finish before HTTP rather than merely failing authentication.
                setPrivateField(activity, "client", OkHttpClient.Builder()
                    .addInterceptor {
                        httpAttempts.incrementAndGet()
                        throw java.io.IOException("Unexpected HTTP during local context preflight")
                    }.build())
                @Suppress("UNCHECKED_CAST")
                val history = privateField(activity, "chat") as MutableList<JSONObject>
                originalHistory = history.map { JSONObject(it.toString()) }
                history.add(
                    JSONObject().put("role", "user").put("text", "Old photo")
                        .put("provider", "openai").put("image_file_id", "file-example")
                )
                expectedHistorySize = history.size
                (privateField(activity, "activeResponseState") as ActiveResponseStateStore)
                    .setResponseId(null)
                (privateField(activity, "input") as android.widget.EditText).setText("Next question")
                val previousGeneration = activeGeneration(activity)
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
                preparationGeneration = activeGeneration(activity)
                assertTrue("Test must enter owned local preparation",
                    preparationGeneration > previousGeneration)
            }
            assertFailedPreparation()

            scenario.onActivity { activity ->
                // A stored ID is not usable after another provider answered.
                @Suppress("UNCHECKED_CAST")
                val history = privateField(activity, "chat") as MutableList<JSONObject>
                history.add(JSONObject().put("role", "assistant")
                    .put("provider", "openrouter").put("text", "other provider"))
                expectedHistorySize = history.size
                (privateField(activity, "activeResponseState") as ActiveResponseStateStore)
                    .setResponseId("resp_stale")
                val previousGeneration = activeGeneration(activity)
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
                preparationGeneration = activeGeneration(activity)
                assertTrue("Stale chain must enter local preparation",
                    preparationGeneration > previousGeneration)
            }
            assertFailedPreparation()
        } finally {
            scenario.onActivity { activity ->
                originalApiKey?.let { SecureStore(activity).setApiKey(it) }
                @Suppress("UNCHECKED_CAST")
                val history = privateField(activity, "chat") as MutableList<JSONObject>
                history.clear()
                history.addAll(originalHistory)
                (privateField(activity, "activeResponseState") as ActiveResponseStateStore)
                    .setResponseId(null)
            }
            scenario.close()
        }
    }

    @Test
    fun githubConfirmationRejectsMissingAndStaleOwnerWithoutOpeningDialog() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val owner = currentChatId(activity) to activeGeneration(activity)
            @Suppress("UNCHECKED_CAST")
            val ownerSlot = privateField(activity, "currentGitHubToolOwner")
                as ThreadLocal<Pair<String, Long>>
            val deniedWithoutOwner = invoke(
                activity,
                "confirmGitHubWrite",
                String::class.java,
                JSONObject::class.java,
                "create_issue_comment",
                JSONObject().put("issue_number", 3)
            ) as Boolean
            assertFalse("GitHub confirmation without a request owner must be denied", deniedWithoutOwner)

            // A previously valid generation must not be allowed to open a new decision.
            ownerSlot.set(owner.first to (owner.second - 1L))
            try {
                assertFalse(isRequestCurrent(activity, owner.first, owner.second - 1L))
                val deniedStale = invoke(
                    activity,
                    "confirmGitHubWrite",
                    String::class.java,
                    JSONObject::class.java,
                    "create_issue_comment",
                    JSONObject().put("issue_number", 3)
                ) as Boolean
                assertFalse("Stale GitHub confirmation owner must be denied", deniedStale)
            } finally {
                ownerSlot.remove()
            }
            @Suppress("UNCHECKED_CAST")
            val dialogRef = privateField(activity, "pendingGitHubConfirmationDialog")
                as AtomicReference<AlertDialog?>
            assertEquals(null, dialogRef.get())
        }
        scenario.close()
    }

    @Test
    fun vectorStoreRenderCanBeInvokedOffMainWithoutUiMutationException() {
        var activity: MainActivity? = null
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { launched ->
            activity = launched
            val attachedStatus = privateField(launched, "status") as android.widget.TextView
            setPrivateField(launched, "vsStatusLine", attachedStatus)
            setPrivateField(launched, "vsListArea", LinearLayout(launched))
        }

        val thrown = AtomicReference<Throwable?>()
        val done = CountDownLatch(1)
        Thread {
            try {
                invoke(
                    activity!!,
                    "renderVectorStoreList",
                    String::class.java,
                    "vs-test-background-thread"
                )
            } catch (t: Throwable) {
                thrown.set(t)
            } finally {
                done.countDown()
            }
        }.start()

        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertTrue(
            "off-main renderVectorStoreList invocation threw " + thrown.get(),
            thrown.get() == null
        )
        scenario.close()
    }

    @Test
    fun zipSourceChatCompletionConsumesOnlyCapturedPersistedZip() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val pendingZipUri = "content://example/archive.zip"
            val pendingFileUri = "content://example/notes.txt"
            val state = JSONObject()
                .put(
                    "pending_uploads",
                    JSONArray()
                        .put(JSONObject().put("uri", pendingZipUri).put("kind", "zip"))
                        .put(JSONObject().put("uri", pendingFileUri).put("kind", "file"))
                )

            val prefs = activity.prefs
            prefs.edit().putString("composer_state_$chatId", state.toString()).commit()

            invokeSaveUploadedFileToComposer(
                activity = activity,
                chatId = chatId,
                fileId = "file-from-zip",
                name = "extracted.txt",
                size = 12L,
                rawUri = pendingZipUri,
                localPath = "/tmp/extracted.txt",
                mime = "text/plain",
                pendingUploadKind = "zip"
            )

            val saved = JSONObject(prefs.getString("composer_state_$chatId", "") ?: "")
            val pending = saved.getJSONArray("pending_uploads")
            assertEquals(1, pending.length())
            assertEquals(pendingFileUri, pending.getJSONObject(0).getString("uri"))
            assertEquals("file", pending.getJSONObject(0).getString("kind"))
        }
        scenario.close()
    }

    @Test
    fun zipPreparationFailureClearsPreparationState() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val brokenZip = java.io.File(activity.cacheDir, "broken.zip").apply {
                writeText("not a zip archive")
            }
            @Suppress("UNCHECKED_CAST")
            val pending = privateField(activity, "pendingUploadUris") as MutableList<Pair<String, String>>
            pending.add(android.net.Uri.fromFile(brokenZip).toString() to "zip")

            invoke(
                activity,
                "preparePendingZipsThenSend",
                Boolean::class.javaPrimitiveType!!,
                false
            )
        }

        for (attempt in 0 until 40) {
            var complete = false
            scenario.onActivity { activity ->
                complete = !(privateField(activity, "preparingZipAttachments") as Boolean)
            }
            if (complete) break
            SystemClock.sleep(25L)
        }

        scenario.onActivity { activity ->
            assertFalse(privateField(activity, "preparingZipAttachments") as Boolean)
            val status = privateField(activity, "status") as android.widget.TextView
            assertTrue(status.text.toString().contains("Не удалось подготовить ZIP"))
        }
        scenario.close()
    }

    @Test
    fun lateCallbackIsRejectedAfterDeleteAndDeletedChatStaysGone() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            val chatId = currentChatId(activity)
            val generation = activeGeneration(activity)
            assertTrue(isRequestCurrent(activity, chatId, generation))
            val itemId = addAssistantItem(activity)

            activity.deleteChat(chatId)

            val database = privateField(activity, "chatDb") as ChatDatabase
            assertTrue(database.getChat(chatId) == null)
            assertFalse(isRequestCurrent(activity, chatId, generation))
            assertEquals(-1, adapterIndexOf(activity, itemId))

            invokeCallback(activity, chatId, generation, itemId, "late")

            assertEquals(-1, adapterIndexOf(activity, itemId))
        }
        scenario.close()
    }

    private fun addAssistantItem(activity: MainActivity): String =
        activity.addBubble("Astra", "initial", false)

    private fun invokeCallback(
        activity: MainActivity,
        chatId: String,
        generation: Long,
        itemId: String,
        snapshot: String
    ) {
        activity.postAssistantTextUpdate(chatId, generation, itemId, snapshot)
    }

    private fun activeGeneration(activity: MainActivity): Long = activity.activeGenerationForTesting()

    private fun currentChatId(activity: MainActivity): String = activity.currentChatIdForTesting()

    private fun isRequestCurrent(activity: MainActivity, chatId: String, generation: Long): Boolean =
        invoke(
            activity,
            "isRequestCurrent",
            String::class.java,
            Long::class.javaPrimitiveType!!,
            chatId,
            generation
        ) as Boolean

    private fun adapterSnapshot(activity: MainActivity): List<ChatBubbleItem> =
        (privateField(activity, "messageAdapter") as ChatMessageAdapter).snapshot()

    private fun adapterIndexOf(activity: MainActivity, itemId: String): Int =
        (privateField(activity, "messageAdapter") as ChatMessageAdapter).indexOfId(itemId)

    private fun invokeSaveUploadedFileToComposer(
        activity: MainActivity,
        chatId: String,
        fileId: String,
        name: String,
        size: Long,
        rawUri: String,
        localPath: String,
        mime: String,
        pendingUploadKind: String
    ) {
        invokeMethod(
            activity,
            "saveUploadedFileToComposer",
            arrayOf(
                String::class.java,
                String::class.java,
                String::class.java,
                Long::class.javaPrimitiveType!!,
                String::class.java,
                String::class.java,
                String::class.java,
                String::class.java
            ),
            arrayOf(chatId, fileId, name, size, rawUri, localPath, mime, pendingUploadKind)
        )
    }

    private fun waitForSourceAttachmentCount(
        scenario: ActivityScenario<MainActivity>,
        chatId: String,
        expected: Int
    ): Boolean {
        repeat(100) {
            var count = 0
            scenario.onActivity { activity ->
                count = composerAttachmentCount(activity, chatId)
            }
            if (count >= expected) return true
            SystemClock.sleep(25L)
        }
        return false
    }

    private fun composerAttachmentCount(activity: MainActivity, chatId: String): Int {
        val prefs = activity.prefs
        val raw = prefs.getString("composer_state_$chatId", null)
        if (raw.isNullOrBlank()) return 0
        return JSONObject(raw).optJSONArray("openrouter_attachments")?.length() ?: 0
    }

    private fun privateField(activity: MainActivity, name: String): Any {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        return field.get(activity)
    }

    private fun setPrivateField(activity: MainActivity, name: String, value: Any?) {
        val field = MainActivity::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(activity, value)
    }

    private fun invoke(activity: MainActivity, name: String, vararg args: Any?): Any? =
        invokeMethod(activity, name, args.map { it?.javaClass ?: Any::class.java }.toTypedArray(), args)

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        arg1: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1), arrayOf(arg1))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        arg1: Any?,
        arg2: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2), arrayOf(arg1, arg2))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        argType3: Class<*>,
        arg1: Any?,
        arg2: Any?,
        arg3: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2, argType3), arrayOf(arg1, arg2, arg3))

    private fun invoke(
        activity: MainActivity,
        name: String,
        argType1: Class<*>,
        argType2: Class<*>,
        argType3: Class<*>,
        argType4: Class<*>,
        arg1: Any?,
        arg2: Any?,
        arg3: Any?,
        arg4: Any?
    ): Any? = invokeMethod(activity, name, arrayOf(argType1, argType2, argType3, argType4), arrayOf(arg1, arg2, arg3, arg4))

    private fun invokeMethod(
        activity: MainActivity,
        name: String,
        parameterTypes: Array<Class<*>>,
        args: Array<out Any?>
    ): Any? {
        val method = MainActivity::class.java.getDeclaredMethod(name, *parameterTypes)
        method.isAccessible = true
        return method.invoke(activity, *args)
    }

    @Test
    fun externalImagePickerResultAfterChatSwitchAndRecreateBelongsToSourceChat() {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8yyfNwMDAxMDAwMDAAAAO1AEqT6J0VgAAAABJRU5ErkJggg=="
        )
        val uri = Uri.parse("content://test-photo-picker/synthetic.png")
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var originalChatId = ""
        var otherChatId = ""
        try {
            scenario.onActivity { activity ->
                activity.newChat()
                originalChatId = activity.currentChatIdForTesting()
                setPrivateField(activity, "pendingPickerChatId", originalChatId)
                setPrivateField(activity, "pendingPickerProvider", "openrouter")
                activity.newChat()
                otherChatId = activity.currentChatIdForTesting()
                (privateField(activity, "input") as android.widget.EditText)
                    .setText("D2 in another chat")
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                activity.openRouterAttachmentInputStreamSource =
                    AttachmentInputStreamSource { _, _ -> ByteArrayInputStream(png) }
                assertEquals(originalChatId, privateField(activity, "pendingPickerChatId"))
                activity.handleActivityResult(5101, Activity.RESULT_OK, Intent().setData(uri))
            }
            assertTrue("External picker result did not reach source draft",
                waitForSourceAttachmentCount(scenario, originalChatId, 1))
            scenario.onActivity { activity ->
                assertEquals(otherChatId, activity.currentChatIdForTesting())
                assertEquals("D2 in another chat",
                    (privateField(activity, "input") as android.widget.EditText).text.toString())
                assertEquals(0, composerAttachmentCount(activity, otherChatId))
                val source = JSONObject(activity.prefs.getString("composer_state_" + originalChatId, "{}")!!)
                val item = source.getJSONArray("openrouter_attachments").getJSONObject(0)
                assertEquals("image", item.getString("kind"))
                assertTrue(java.io.File(item.getString("path")).isFile)
            }
        } finally {
            scenario.close()
        }
    }

    @Test
    fun deliberateHorizontalGestureOpensAndClosesChatDrawer() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()

        fun visibleWindowBounds(view: android.view.View): android.graphics.Rect {
            val bounds = android.graphics.Rect()
            assertTrue("Gesture surface must be visible", view.getLocalVisibleRect(bounds))
            val location = IntArray(2)
            view.getLocationInWindow(location)
            bounds.offset(location[0], location[1])
            return bounds
        }

        fun swipe(x0: Float, y0: Float, x1: Float, y1: Float) {
            // Exercise the Activity -> Window -> View touch path on main using
            // window coordinates. System-wide sendPointerSync can hit the IME
            // or another UID's window; that does not test this Activity's drawer.
            // The first MOVE may capture the drag; later MOVEs must move it.
            val down = SystemClock.uptimeMillis()
            for (step in 0..12) {
                if (step > 0) SystemClock.sleep(25L)
                val fraction = step / 12f
                val action = when (step) {
                    0 -> android.view.MotionEvent.ACTION_DOWN
                    12 -> android.view.MotionEvent.ACTION_UP
                    else -> android.view.MotionEvent.ACTION_MOVE
                }
                val event = android.view.MotionEvent.obtain(
                    down, SystemClock.uptimeMillis(), action,
                    x0 + (x1 - x0) * fraction, y0 + (y1 - y0) * fraction, 0
                ).apply { source = android.view.InputDevice.SOURCE_TOUCHSCREEN }
                scenario.onActivity { activity ->
                    try {
                        activity.dispatchTouchEvent(event)
                    } finally {
                        event.recycle()
                    }
                }
            }
            instrumentation.waitForIdleSync()
        }

        fun awaitDrawer(open: Boolean) {
            val deadline = SystemClock.uptimeMillis() + 5_000L
            var settled = false
            while (!settled && SystemClock.uptimeMillis() < deadline) {
                scenario.onActivity { activity ->
                    val drawer = privateField(activity, "drawerLayout")
                        as FingerDrawerLayout
                    settled = if (open) drawer.isDrawerOpen(androidx.core.view.GravityCompat.START)
                        else !drawer.isDrawerVisible(androidx.core.view.GravityCompat.START)
                }
                if (!settled) SystemClock.sleep(20L)
            }
            assertTrue(if (open) "Swipe right must open drawer"
                else "Swipe left must fully close drawer", settled)
        }

        try {
            scenario.onActivity { activity ->
                activity.newChat()
            }
            instrumentation.waitForIdleSync()
            var chatBounds = android.graphics.Rect()
            scenario.onActivity { activity ->
                val scroll = privateField(activity, "scroll") as android.view.View
                chatBounds = visibleWindowBounds(scroll)
                assertTrue("Chat surface must be laid out", chatBounds.width() > 200)
            }
            // A vertical gesture must keep the drawer closed.
            swipe(chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * 0.25f,
                chatBounds.exactCenterX(), chatBounds.top + chatBounds.height() * 0.75f)
            scenario.onActivity { activity ->
                val drawer = privateField(activity, "drawerLayout")
                    as FingerDrawerLayout
                assertFalse("Vertical scroll must not open drawer",
                    drawer.isDrawerVisible(androidx.core.view.GravityCompat.START))
            }
            swipe(chatBounds.left + chatBounds.width() * 0.2f, chatBounds.exactCenterY(),
                chatBounds.left + chatBounds.width() * 0.9f, chatBounds.exactCenterY())
            awaitDrawer(true)

            var panelBounds = android.graphics.Rect()
            scenario.onActivity { activity ->
                val drawer = privateField(activity, "drawerLayout")
                    as FingerDrawerLayout
                val panel = (0 until drawer.childCount).map { drawer.getChildAt(it) }
                    .first { (it.layoutParams as android.widget.FrameLayout.LayoutParams)
                        .gravity == androidx.core.view.GravityCompat.START }
                panelBounds = visibleWindowBounds(panel)
            }
            // Stay inside the actual drawer panel, independent of screen width.
            swipe(panelBounds.left + panelBounds.width() * 0.85f, panelBounds.exactCenterY(),
                panelBounds.left + panelBounds.width() * 0.1f, panelBounds.exactCenterY())
            awaitDrawer(false)
        } finally {
            scenario.close()
        }
    }

    @Test
    fun realLargeJpegIsUnchangedAcrossHttpsSendAndFollowingTextHistory() {
        val (server, testHttpClient) = createHttpsMockServer()
        // Resolve MockWebServer DNS outside of Activity/UI callbacks.
        val endpoint = server.url("/api/v1").toString()
        repeat(2) {
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"accepted\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"))
        }
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        var jpegBytes = ByteArray(0)
        // 10 MB encoded image is supported on direct Anthropic, not on auto partner routes.
        val prefs = androidx.test.platform.app.InstrumentationRegistry
            .getInstrumentation().targetContext.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
        val previousRoute = prefs.getString("openrouter_claude_route", null)
        val model = "anthropic/claude-sonnet-5.5"
        try {
            scenario.onActivity { activity ->
                activity.newChat()
                activity.prefs.edit().putString("provider", "openrouter")
                    .putString("model", model).putString("reasoning", "high")
                    .putString("openrouter_claude_route", "anthropic").apply()
                SecureStore(activity).setOpenRouterApiKey("test-key")
                val width = 2600
                val height = 2300
                val pixels = IntArray(width * height)
                val random = java.util.Random(55555L)
                for (i in pixels.indices) pixels[i] = 0xff000000.toInt() or random.nextInt(0x1000000)
                val bitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
                val output = ByteArrayOutputStream()
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 92, output))
                bitmap.recycle()
                jpegBytes = output.toByteArray()
                assertTrue("JPEG must be a large decodable image within Claude's encoded limit: " + jpegBytes.size,
                    jpegBytes.size >= 4_500_000 && OpenRouterAttachmentPreflight.encodedBytes(jpegBytes.size.toLong()) <= 10_000_000L)
                val file = java.io.File(activity.filesDir, "test-large-photo.jpg").apply { writeBytes(jpegBytes) }
                val attachment = OpenRouterAttachmentStore.classifyLocalFile(file, file.name, "image/png")
                @Suppress("UNCHECKED_CAST")
                val attachments = privateField(activity, "attachedOpenRouterAttachments")
                    as MutableList<OpenRouterLocalAttachment>
                attachments.add(attachment)
                @Suppress("UNCHECKED_CAST")
                val modalities = privateField(activity, "openRouterModelModalities")
                    as ConcurrentHashMap<String, Set<String>>
                modalities[model] = setOf("text", "image")
                setPrivateField(activity, "openRouterClientFactory",
                    { OpenRouterChatClient(endpoint, testHttpClient, reconcileGenerations = false) })
                (privateField(activity, "input") as android.widget.EditText).setText("Read the image")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            fun assertJpeg(request: okhttp3.mockwebserver.RecordedRequest) {
                val body = JSONObject(request.body.readUtf8())
                val messages = body.getJSONArray("messages")
                val content = (0 until messages.length()).map { messages.getJSONObject(it) }
                    .first { it.optString("role") == "user" }.getJSONArray("content")
                val image = (0 until content.length()).map { content.getJSONObject(it) }
                    .first { it.optString("type") == "image_url" }
                val url = image.getJSONObject("image_url").getString("url")
                assertTrue("Wrong actual data URL MIME", url.startsWith("data:image/jpeg;base64,"))
                assertTrue("Original JPEG bytes changed during HTTP serialization",
                    java.util.Base64.getDecoder().decode(url.substringAfter(","))
                        .contentEquals(jpegBytes))
            }
            val firstRequest = server.takeRequest(30, TimeUnit.SECONDS) ?: run {
                var diagnostic = ""
                scenario.onActivity { activity ->
                    diagnostic = (privateField(activity, "status") as android.widget.TextView).text.toString()
                }
                error("First HTTPS Send missing; status=$diagnostic")
            }
            assertJpeg(firstRequest)
            var completed = false
            repeat(120) {
                scenario.onActivity { activity ->
                    completed = (privateField(activity, "status") as android.widget.TextView)
                        .text.toString().startsWith("Готово")
                }
                if (completed) return@repeat
                SystemClock.sleep(50L)
            }
            assertTrue("First Send must commit before next message", completed)
            scenario.onActivity { activity ->
                val composer = privateField(activity, "input") as android.widget.EditText
                composer.setText("And one more text request")
                invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)
            }
            val second = server.takeRequest(30, TimeUnit.SECONDS) ?: error("Second HTTPS Send missing")
            val secondBody = JSONObject(second.body.readUtf8())
            assertTrue(secondBody.getJSONArray("messages").toString().contains("And one more text request"))
            val secondContent = secondBody.getJSONArray("messages").getJSONObject(0).optJSONArray("content")
                ?: secondBody.getJSONArray("messages").getJSONObject(1).optJSONArray("content")
                ?: error("Image context missing")
            val imageBlock = (0 until secondContent.length()).map { secondContent.optJSONObject(it) }
                .firstOrNull { it?.optString("type") == "image_url" }
                ?: error("Historical image missing from second HTTP request")
            val historical = imageBlock.getJSONObject("image_url").getString("url")
            assertTrue(historical.startsWith("data:image/jpeg;base64,"))
            assertTrue(java.util.Base64.getDecoder().decode(historical.substringAfter(",")).contentEquals(jpegBytes))
        } finally {
            scenario.onActivity { SecureStore(it).setOpenRouterApiKey("") }
            prefs.edit().apply {
                if (previousRoute == null) remove("openrouter_claude_route")
                else putString("openrouter_claude_route", previousRoute)
            }.commit()
            scenario.close()
            server.shutdown()
        }
    }

    private class BlockingAttachmentInputStreamSource : AttachmentInputStreamSource {
        private val opened = ConcurrentHashMap.newKeySet<String>()
        private val releases = ConcurrentHashMap<String, CountDownLatch>()

        override fun openInputStream(context: android.content.Context, uri: Uri): InputStream {
            val key = uri.toString()
            opened += key
            val gate = CountDownLatch(1)
            releases[key] = gate
            check(gate.await(10L, TimeUnit.SECONDS)) { "blocking attachment source timed out: $uri" }
            return ByteArrayInputStream(
                if (uri.lastPathSegment.orEmpty().endsWith(".zip", ignoreCase = true)) zipBytes()
                else "attachment from blocking source".toByteArray(Charsets.UTF_8)
            )
        }

        fun waitUntilOpened(uri: Uri): Boolean {
            repeat(100) {
                if (opened.contains(uri.toString())) return true
                SystemClock.sleep(20L)
            }
            return false
        }

        fun release(uri: Uri) {
            releases[uri.toString()]?.countDown()
        }

        private fun zipBytes(): ByteArray {
            val buffer = ByteArrayOutputStream()
            ZipOutputStream(buffer).use { zip ->
                zip.putNextEntry(ZipEntry("notes.txt"))
                zip.write("attachment extracted from blocking source".toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            return buffer.toByteArray()
        }
    }

}

