package com.example.openaichat

import android.app.Application
import android.content.Context
import android.os.Looper
import android.widget.LinearLayout
import android.widget.TextView
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** The production Activity HTTP completion races the real attachment mutation and SQLite writers. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class HistoricalAttachmentDownloadTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val chatId = "download-race"
    private var controller: ActivityController<MainActivity>? = null
    private val gates = mutableListOf<Gate>()
    private lateinit var original: File

    @Before fun before() {
        try { androidx.work.WorkManager.getInstance(context) }
        catch (_: IllegalStateException) { androidx.work.WorkManager.initialize(context, androidx.work.Configuration.Builder().build()) }
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().clear()
            .putString("current_chat", chatId).putString("provider", "openai").putString("model", "gpt-6-astra")
            .putBoolean("response_notifications", false).commit()
        context.getSharedPreferences("secure", Context.MODE_PRIVATE).edit().clear().commit()
        context.deleteDatabase("astra_chats.db")
        File(context.filesDir, "attachments").deleteRecursively()
        original = File(context.filesDir, "keep-original.txt").apply { writeText("KEEP ORIGINAL") }
    }

    @After fun after() {
        gates.forEach { it.release.countDown() }
        controller?.pause()?.stop()?.destroy()
        shadowOf(Looper.getMainLooper()).idle()
        original.delete()
    }

    @Test fun normalFileCompletionPersistsAcrossReopenAndLeavesOtherOriginalUntouched() {
        val activity = launch(files())
        val gate = download(activity)
        gate.release.countDown(); awaitIdle(activity)
        val path = persisted().getJSONArray("file_paths").getString(0)
        assertEquals("DOWNLOADED ORIGINAL", File(path).readText())
        assertEquals(original.path, persisted().getJSONArray("file_paths").getString(1))
        assertEquals("KEEP ORIGINAL", original.readText())
        assertEquals("", gate.message.getJSONArray("file_paths").getString(0))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("Файл доступен: missing.txt", status(activity).text.toString())
    }

    @Test fun fileCompletionAfterActualRemoveCannotOverwriteShiftedFileOrKeepFreshGarbage() {
        val activity = launch(files())
        val gate = download(activity)
        removeUsingActivity(activity, gate.message, image = false)
        awaitCondition { persisted().getJSONArray("file_ids").length() == 1 }
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("file_keep", persisted().getJSONArray("file_ids").getString(0))
        assertEquals(original.path, persisted().getJSONArray("file_paths").getString(0))
        assertTrue(downloadedFiles().isEmpty())
        assertEquals("KEEP ORIGINAL", original.readText())
    }

    @Test fun fileCompletionAfterReorderRejectsOriginalIndex() {
        val activity = launch(files())
        val gate = download(activity)
        val replacement = JsonProtocol.objectCopy(gate.message)
        AttachmentMessageState.arrays.filter { it != "attachments" }.forEach { key ->
            val values = replacement.optJSONArray(key) ?: return@forEach
            replacement.put(key, JSONArray().put(values.opt(1)).put(values.opt(0)))
        }
        change(gate.message, replacement)
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("file_keep", persisted().getJSONArray("file_ids").getString(0))
        assertEquals(original.path, persisted().getJSONArray("file_paths").getString(0))
        assertEquals("", persisted().getJSONArray("file_paths").getString(1))
        assertTrue(downloadedFiles().isEmpty())
    }

    @Test fun fileCompletionAfterReplacementPreservesReplacementAndSharedOriginal() {
        val activity = launch(files())
        val gate = download(activity)
        val replacement = AttachmentMessageState.remove(gate.message, fileIndex = 0)
            .put("attachments", JSONArray().put(JSONObject().put("path", original.path).put("name", "replacement.txt").put("kind", "text")))
        change(gate.message, replacement)
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals(original.path, persisted().getJSONArray("attachments").getJSONObject(0).getString("path"))
        assertEquals(original.path, persisted().getJSONArray("file_paths").getString(0))
        assertTrue(downloadedFiles().isEmpty()); assertEquals("KEEP ORIGINAL", original.readText())
    }

    @Test fun imageCompletionAfterActualRemoveCannotRestoreRemovedImage() {
        val activity = launch(image())
        val gate = download(activity, image = true)
        removeUsingActivity(activity, gate.message, image = true)
        awaitCondition { !persisted().has("image_file_id") }
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertFalse(persisted().has("image_path")); assertFalse(persisted().has("image_file_id"))
        assertTrue(downloadedFiles().isEmpty())
    }

    @Test fun imageCompletionAfterReplacementCannotReplaceNewImage() {
        val activity = launch(image())
        val gate = download(activity, image = true)
        change(gate.message, JsonProtocol.objectCopy(gate.message)
            .put("image_file_id", "file_replacement").put("image_path", original.path))
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("file_replacement", persisted().getString("image_file_id"))
        assertEquals(original.path, persisted().getString("image_path"))
        assertTrue(downloadedFiles().isEmpty()); assertEquals("KEEP ORIGINAL", original.readText())
    }

    @Test fun normalImageCompletionPersistsAcrossReopen() {
        val activity = launch(image())
        val gate = download(activity, image = true)
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("DOWNLOADED ORIGINAL", File(persisted().getString("image_path")).readText())
        assertEquals("file_missing_image", persisted().getString("image_file_id"))
        assertFalse(gate.message.has("image_path"))
        assertEquals("Изображение доступно: missing.png", status(activity).text.toString())
    }

    @Test fun completionAfterEditOrDeleteCannotRestoreRemovedMessage() {
        for (delete in listOf(false, true)) {
            val activity = launch(files())
            val gate = download(activity)
            if (delete) activity.deleteChat(chatId)
            else invoke(activity, "editMessage", 0)
            gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
            ChatDatabase(context).use { db ->
                val rows = db.getChat(chatId)?.getJSONArray("messages")
                assertTrue(rows == null || rows.length() == 0)
            }
            assertTrue(downloadedFiles().isEmpty()); assertEquals("KEEP ORIGINAL", original.readText())
            controller!!.pause().stop().destroy(); controller = null
        }
    }

    @Test fun switchedChatKeepsValidDurableDownloadWithoutApplyingItsUiCallback() {
        val activity = launch(files())
        val gate = download(activity)
        ChatDatabase(context).use { it.ensureChat("other", "Other") }
        invoke(activity, "switchChat", "other")
        status(activity).text = "OTHER CHAT"
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("DOWNLOADED ORIGINAL", File(persisted().getJSONArray("file_paths").getString(0)).readText())
        assertEquals("other", activity.currentChatIdForTesting())
        assertEquals("OTHER CHAT", status(activity).text.toString())
    }

    @Test fun repairBetweenSqliteCommitAndPostedUiCallbackPreventsStaleSuccessAndPreview() {
        val activity = launch(files())
        val gate = download(activity)
        gate.release.countDown(); awaitIdle(activity)
        val committed = persisted()
        change(committed, AttachmentMessageState.remove(committed, fileIndex = 0))
        status(activity).text = "REPAIRED"
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("REPAIRED", status(activity).text.toString())
        assertEquals(original.path, persisted().getJSONArray("file_paths").getString(0))
        assertEquals("KEEP ORIGINAL", original.readText())
    }

    @Test fun destroyedActivityCannotApplyLateUiWhileValidDownloadPersists() {
        val activity = launch(files())
        val gate = download(activity)
        controller!!.pause().stop().destroy(); controller = null
        status(activity).text = "DESTROYED"
        gate.release.countDown(); awaitIdle(activity); shadowOf(Looper.getMainLooper()).idle()
        assertEquals("DESTROYED", status(activity).text.toString())
        assertEquals("DOWNLOADED ORIGINAL", File(persisted().getJSONArray("file_paths").getString(0)).readText())
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        assertEquals("DOWNLOADED ORIGINAL", File(persisted().getJSONArray("file_paths").getString(0)).readText())
    }

    private fun files() = JSONObject().put("role", "user").put("text", "Question").put("request_id", "request")
        .put("file_ids", JSONArray().put("file_missing").put("file_keep"))
        .put("file_names", JSONArray().put("missing.txt").put("keep.txt"))
        .put("file_paths", JSONArray().put("").put(original.path))
        .put("file_mimes", JSONArray().put("text/plain").put("text/plain"))
        .put("file_sizes", JSONArray().put(0L).put(original.length()))

    private fun image() = JSONObject().put("role", "user").put("text", "Photo").put("request_id", "request")
        .put("image_file_id", "file_missing_image").put("image_name", "missing.png").put("image_mime", "image/png")

    private fun launch(message: JSONObject): MainActivity {
        ChatDatabase(context).use { it.upsertCurrentChat(chatId, "Downloads", null, null, listOf(message)) }
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putString("current_chat", chatId).commit()
        controller = Robolectric.buildActivity(MainActivity::class.java).create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idle()
        return controller!!.get()
    }

    private class Gate(val message: JSONObject) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
    }

    private fun download(activity: MainActivity, image: Boolean = false): Gate {
        val gate = Gate(persisted()).also(gates::add)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertTrue(chain.request().url.toString().startsWith("https://api.openai.com/v1/files/file_"))
            gate.entered.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (gate.release.count > 0L) {
                check(System.nanoTime() < deadline) { "Download test gate timed out" }
                try { gate.release.await(100, TimeUnit.MILLISECONDS) } catch (_: InterruptedException) { /* Simulate an HTTP result arriving during teardown. */ }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("DOWNLOADED ORIGINAL".toResponseBody()).build()
        }.build()
        MainActivity::class.java.getDeclaredField("client").apply { isAccessible = true }.set(activity, client)
        if (image) invoke(activity, "downloadUserImageCopy", gate.message, LinearLayout(activity), false)
        else invoke(activity, "downloadUserFileCopy", gate.message, 0, LinearLayout(activity), false)
        assertTrue("The production download must reach HTTP", gate.entered.await(5, TimeUnit.SECONDS))
        return gate
    }

    private fun removeUsingActivity(activity: MainActivity, expected: JSONObject, image: Boolean) {
        val target = invoke(activity, "historyAttachmentTarget", expected, null, if (image) null else 0, image)
        val removed = AttachmentMessageState.remove(expected, fileIndex = if (image) null else 0, image = image)
        invoke(activity, "applyHistoricalAttachmentChange", target, removed, null)
    }

    private fun change(expected: JSONObject, replacement: JSONObject) = ChatDatabase(context).use {
        assertTrue(it.updateMessageAttachments(chatId, expected, replacement, true))
    }

    private fun persisted(): JSONObject = ChatDatabase(context).use { it.getChat(chatId)!!.getJSONArray("messages").getJSONObject(0) }
    private fun downloadedFiles() = File(context.filesDir, "attachments").listFiles().orEmpty().toList()
    private fun status(activity: MainActivity) = field<TextView>(activity, "status")
    private fun awaitIdle(activity: MainActivity) = awaitCondition(pumpUi = false) { field<ThreadPoolExecutor>(activity, "executor").activeCount == 0 }
    private fun awaitCondition(pumpUi: Boolean = true, condition: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!condition()) {
            check(System.nanoTime() < deadline) { "Attachment completion did not reach the expected state" }
            if (pumpUi) shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
    @Suppress("UNCHECKED_CAST") private fun <T> field(activity: MainActivity, name: String): T =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }.get(activity) as T
    private fun invoke(activity: MainActivity, name: String, vararg args: Any?): Any? =
        MainActivity::class.java.declaredMethods.single { it.name == name && it.parameterCount == args.size }
            .apply { isAccessible = true }.invoke(activity, *args)
}
