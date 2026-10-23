package com.example.openaichat

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.Looper
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class ArtifactIntentReconcilerTest {
    @Test fun oneForegroundEnqueueDrainsMoreThan256IntentsAcrossPersistedBackoffRetries() {
        val context: Context = RuntimeEnvironment.getApplication()
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        val clock = AtomicLong(System.currentTimeMillis())
        WorkManagerTestInitHelper.initializeTestWorkManager(context, Configuration.Builder()
            .setExecutor(SynchronousExecutor()).setClock { clock.get() }.build())
        val workManager = WorkManager.getInstance(context)
        val driver = WorkManagerTestInitHelper.getTestDriver(context)!!
        val executor = LocalArtifactToolExecutor(context)
        val count = 300
        val paths = mutableListOf<File>()
        try {
            ChatDatabase(context).use { db ->
                db.ensureChat("bulk")
                assertTrue(db.startRequestAccounting("bulk", "request", "deepseek", "test", "deepseek_api_key"))
                db.setActiveResponse("bulk", null, "request", "", "starting", 1, "none", "none", false, 1, 0, false)
                repeat(count) { index ->
                    val args = JSONObject().put("filename", "result-$index.txt").put("content", "bytes-$index")
                    val callId = "call-$index"
                    val planned = executor.prepareCreateTextFile(args, "bulk\u0000request\u0000$callId")
                        .put("tool_name", "create_local_text_file").put("tool_call_id", callId)
                        .put("arguments_sha256", StreamingJsonRequestBody.sha256(args))
                    assertTrue(db.saveLocalArtifactIntentIfActive("bulk", "request", callId, planned))
                    // Simulate publication followed by process death before result registration.
                    File(planned.getString("path")).also { it.parentFile!!.mkdirs(); it.writeText("bytes-$index"); paths += it }
                }
            }
            // The only enqueue is the same durable entry point called by MainActivity.onStart.
            ArtifactIntentReconciler.enqueue(context)
            var info = workManager.getWorkInfosForUniqueWork(ArtifactIntentReconciler.WORK_NAME).get().single()
            assertFalse("One bounded pass must leave work for continuation", info.state.isFinished)
            repeat(24) {
                if (!info.state.isFinished) {
                    clock.addAndGet(60L * 60L * 1000L)
                    driver.setInitialDelayMet(info.id)
                    shadowOf(Looper.getMainLooper()).idle()
                    info = workManager.getWorkInfoById(info.id).get()!!
                }
            }
            assertEquals(WorkInfo.State.SUCCEEDED, info.state)
            assertTrue("The initial foreground trigger must persist follow-up attempts", info.runAttemptCount >= 2)
            ChatDatabase(context).use { db ->
                assertEquals(count, db.listLocalFiles("bulk").size)
                db.readableDatabase.rawQuery("SELECT COUNT(*) FROM local_artifact_intents WHERE recovery_state='pending'", null).use {
                    assertTrue(it.moveToFirst()); assertEquals(0, it.getInt(0))
                }
            }
            assertTrue(paths.all(File::isFile))
        } finally {
            workManager.cancelAllWork().result.get()
            WorkManagerTestInitHelper.closeWorkDatabase()
            paths.forEach(File::delete)
            SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        }
    }
}
