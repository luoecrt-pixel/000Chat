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
import org.junit.runner.RunWith
import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
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
            assertTrue(find(root, "Выбрать модель") != null)
            val effort = find(root, "Усилие мышления")
                ?: throw AssertionError("Composer effort control missing")
            assertTrue((effort as android.widget.TextView).text.toString().startsWith("⚡"))
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
            launched.openRouterAttachmentInputStreamSource = source
            sourceChatId = currentChatId(launched)
            sourceUri = Uri.parse("content://blocking.test/archive.zip")
            launched.pendingUploadUris.add(sourceUri.toString() to "zip")
            launched.preparePendingOpenRouterZipsThenSend(false)
        }

        val uri = sourceUri!!
        assertTrue("extractZip did not reach the blocking source", source.waitUntilOpened(uri))

        scenario.onActivity { launched -> launched.newChat() }
        source.release(uri)

        assertTrue("stale ZIP completion did not persist to the source chat", waitForSourceAttachmentCount(scenario, sourceChatId, 1))

        scenario.onActivity { launched ->
            val currentId = currentChatId(launched)
            assertEquals(0, composerAttachmentCount(launched, currentId))
            assertFalse(launched.preparingZipAttachments)
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
    fun openAiHistoryWithoutResponseChainNeverSilentlyDropsFileInputs() {
        val scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity ->
            activity.prefs.edit().putString("provider", "openai").apply()
            @Suppress("UNCHECKED_CAST")
            val history = privateField(activity, "chat") as MutableList<JSONObject>
            val oldSize = history.size
            history.add(
                JSONObject().put("role", "user").put("text", "Old photo")
                    .put("provider", "openai")
                    .put("image_file_id", "file-example")
            )
            setPrivateField(activity, "currentResponseId", null)
            val composer = privateField(activity, "input") as android.widget.EditText
            composer.setText("Next question")
            val generation = activeGeneration(activity)

            invoke(activity, "sendMessage", Boolean::class.javaPrimitiveType!!, false)

            assertEquals("Unsafe fallback advanced generation", generation, activeGeneration(activity))
            assertEquals("Next question", composer.text.toString())
            assertEquals("Unsafe fallback appended to chat", oldSize + 1, history.size)
            val status = privateField(activity, "status") as android.widget.TextView
            assertTrue("Missing explanation for unavailable file history",
                status.text.toString().contains("вложения OpenAI"))
            history.removeAt(history.lastIndex)
        }
        scenario.close()
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
