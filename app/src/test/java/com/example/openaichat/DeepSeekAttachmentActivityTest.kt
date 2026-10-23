package com.example.openaichat

import android.app.Activity
import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.Looper
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class DeepSeekAttachmentActivityTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("app", Context.MODE_PRIVATE)
    private lateinit var controller: ActivityController<MainActivity>
    private lateinit var activity: MainActivity
    private val files = mutableListOf<File>()

    @Before fun before() {
        context.deleteDatabase("astra_chats.db")
        prefs.edit().clear().putString("provider", "deepseek").putString("model", "deepseek-flash")
            .putString("current_chat", "deepseek-attachments").putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        try { androidx.work.WorkManager.getInstance(context) } catch (_: IllegalStateException) {
            androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build())
        }
        controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        activity = controller.get()
    }

    @After fun after() {
        controller.pause().stop().destroy()
        shadowOf(Looper.getMainLooper()).idle()
        files.forEach { it.delete() }
        prefs.edit().clear().commit()
    }

    private fun field(name: String): Any? = MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity)
    private fun draft(chatId: String): JSONObject = JSONObject(prefs.getString("composer_state_$chatId", "{}")!!)

    @Test fun zipPickerUsesLocalDeepSeekManifestAndNeverQueuesOpenAiUpload() {
        val zip = File(context.cacheDir, "deepseek-source.zip").apply {
            ZipOutputStream(outputStream()).use { output ->
                output.putNextEntry(ZipEntry("complete.txt"))
                output.write("complete synthetic tool source".toByteArray())
                output.closeEntry()
            }
        }
        files += zip
        val prepare = MainActivity::class.java.getDeclaredMethod("prepareOpenRouterAttachment", Uri::class.java,
            String::class.java, String::class.java).apply { isAccessible = true }
        prepare.invoke(activity, Uri.fromFile(zip), "deepseek-attachments", "deepseek")
        (field("attachmentPreparationTail") as CompletableFuture<*>).get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val saved = draft("deepseek-attachments")
        val attachments = saved.getJSONArray("openrouter_attachments")
        assertEquals(1, attachments.length())
        val attachment = OpenRouterLocalAttachment.fromJson(attachments.getJSONObject(0))
        files += File(attachment.path)
        assertEquals("complete.txt", attachment.name)
        assertEquals("complete synthetic tool source", File(attachment.path).readText())
        assertEquals(0, saved.optJSONArray("pending_uploads")?.length() ?: 0)
        assertEquals(0, saved.optJSONArray("file_ids")?.length() ?: 0)
    }

    @Test fun cameraResultUsesCapturedDeepSeekProviderForOriginalChatAfterNavigation() {
        val source = "deepseek-camera-source"
        ChatDatabase(context).use { it.upsertCurrentChat(source, "source", null, null, emptyList()) }
        val photo = File(File(context.filesDir, "attachments").apply { mkdirs() }, "deepseek-camera.png").apply {
            val image = DeepSeekAttachmentActivityTest::class.java.classLoader!!
                .getResourceAsStream("images/solid-3x2-225fc7.png")!!
            image.use { input -> outputStream().use { input.copyTo(it) } }
        }
        files += photo
        val executorField = MainActivity::class.java.getDeclaredField("executor").apply { isAccessible = true }
        val originalExecutor = executorField.get(activity) as java.util.concurrent.ExecutorService
        val orderedExecutor = java.util.concurrent.Executors.newSingleThreadExecutor()
        executorField.set(activity, orderedExecutor)
        originalExecutor.shutdown()
        activity.setPendingCameraCaptureForTesting(photo.path, source, "deepseek")
        // The originating provider is captured with the camera operation.
        prefs.edit().putString("provider", "openai").commit()
        activity.completePendingCameraCapture(Activity.RESULT_OK)
        orderedExecutor.submit {}.get(10, TimeUnit.SECONDS)
        shadowOf(Looper.getMainLooper()).idle()
        val saved = draft(source)
        assertEquals(1, saved.getJSONArray("openrouter_attachments").length())
        assertEquals(0, saved.optJSONArray("pending_uploads")?.length() ?: 0)
        assertEquals(photo.path, saved.getJSONArray("openrouter_attachments").getJSONObject(0).getString("path"))
        assertEquals(0, draft("deepseek-attachments").optJSONArray("openrouter_attachments")?.length() ?: 0)
    }
}
