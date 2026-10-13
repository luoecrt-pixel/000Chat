package com.example.openaichat

import android.os.Bundle
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import androidx.work.WorkManager
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Driven only by tools/test_android_process_recovery.py on a disposable emulator.
 * The seed invocation stays alive until the host actually kills its process.
 * Verification uses the ordinary WorkManager singleton/schedulers, not a test
 * Worker factory, TestDriver, or direct doWork() invocation. No provider key.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathRecoveryFixture {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val arguments get() = InstrumentationRegistry.getArguments()
    private val scenario get() = arguments.getString("recoveryScenario").orEmpty().also {
        require(it in setOf("terminal", "expired_partial", "stopped_pre_id", "replaced"))
    }
    private val chatId get() = "process-recovery-fixture-$scenario"
    private val requestId = "req_process_fixture"
    private val responseId = "resp_process_fixture"
    private val replacementId = "req_replacement_fixture"
    private val sourceBytes = "data: received paid source before process death\n\n".toByteArray()
    private val prefs get() = context.getSharedPreferences("process_recovery_fixture", 0)

    private fun signedItem() = JSONObject().put("type", "reasoning")
        .put("id", "reasoning_fixture").put("encrypted_content", "signed-fixture-exact-bytes")

    @Test fun seedAndWaitForHostProcessKill() {
        assumeTrue(arguments.getString("recoveryStage") == "seed")
        assertTrue("Never run with a real provider credential", SecureStore(context).getApiKey().isBlank())
        BackgroundResponseWorkScheduler.cancel(context, chatId, requestId)
        ChatDatabase(context).use { db ->
            db.deleteChat(chatId)
            db.ensureChat(chatId, "Process recovery fixture")
            db.upsertCurrentChat(chatId, "Process recovery fixture", null, "high", listOf(
                JSONObject().put("role", "user").put("text", "Synthetic prompt")
                    .put("provider", "openai").put("request_id", requestId)))
            val started = if (scenario == "expired_partial")
                System.currentTimeMillis() - ResponseDeadlinePolicy.MAX_REQUEST_MS - 1L
                else System.currentTimeMillis()
            db.setActiveResponse(chatId, if (scenario == "stopped_pre_id") null else responseId,
                requestId, "", "starting", started, "high", "high", false, 16, 0, true)
            if (scenario == "stopped_pre_id" || scenario == "replaced") {
                assertTrue(db.stopActiveRequestOnce(chatId, requestId))
                if (scenario == "replaced") {
                    db.setActiveResponse(chatId, "resp_replacement_fixture", replacementId, "",
                        "starting", System.currentTimeMillis(), "high", "high", false, 16, 0, false)
                }
            } else {
                var state = ResponseEventReducer.reduce(ResponseEventState(), JSONObject()
                    .put("type", "response.output_item.done").put("output_index", 0)
                    .put("item", signedItem())).state
                state = ResponseEventReducer.reduce(state, JSONObject()
                    .put("type", "response.output_text.delta")
                    .put("output_index", 1).put("content_index", 0).put("delta", "received fragment")).state
                if (scenario == "terminal") {
                    val terminal = JSONObject().put("id", responseId).put("status", "completed")
                        .put("output", JSONArray().put(signedItem()).put(JSONObject()
                            .put("type", "message").put("role", "assistant")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text")
                                .put("text", "canonical paid result")))))
                        .put("usage", JSONObject().put("input_tokens", 17).put("output_tokens", 31))
                    state = ResponseEventReducer.reduce(state, JSONObject()
                        .put("type", "response.completed").put("response", terminal)).state
                }
                assertTrue(db.updateActiveResponse(chatId, responseId, state.text,
                    if (scenario == "terminal") "completed" else "in_progress", 9,
                    requestId, ResponseIndexedCheckpoint.serialize(state)))
            }
        }
        OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
        OpenAiRawSseArchive.begin(ByteArrayInputStream(sourceBytes), context.filesDir, chatId, requestId)
            .use { it.receiveRemaining() }
        assertTrue(prefs.edit().putInt("seed_pid", Process.myPid()).putString("scenario", scenario).commit())
        // Production scheduler, persisted work, deliberately not ready before
        // the host kills us. A fresh process will reschedule this same owner.
        BackgroundResponseWorkScheduler.enqueue(context, chatId, responseId, requestId, 120_000L)
        val work = WorkManager.getInstance(context).getWorkInfosForUniqueWork(
            BackgroundResponseWorkScheduler.workName(chatId, requestId)).get(15, TimeUnit.SECONDS)
        assertTrue(work.any { it.state == WorkInfo.State.ENQUEUED })
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "PROCESS_RECOVERY_SEEDED scenario=$scenario pid=${Process.myPid()}\n")
        })
        // Returning would let the runner end normally and fake process death.
        CountDownLatch(1).await(180, TimeUnit.SECONDS)
        fail("Host did not terminate the live seeded process")
    }

    @Test fun verifyProductionScheduledRecoveryInNewProcess() {
        assumeTrue(arguments.getString("recoveryStage") == "verify")
        assertEquals(scenario, prefs.getString("scenario", ""))
        assertNotEquals("A DB reopen in the same process is insufficient", prefs.getInt("seed_pid", -1), Process.myPid())
        assertTrue(SecureStore(context).getApiKey().isBlank())
        // Same production rescheduling entry point used on Android onStop.
        val manager = WorkManager.getInstance(context)
        val name = BackgroundResponseWorkScheduler.workName(chatId, requestId)
        val beforeIds = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
        BackgroundResponseWorkScheduler.enqueueImmediate(context, chatId, responseId, requestId)
        val deadline = SystemClock.elapsedRealtime() + 60_000L
        try {
            var work: List<WorkInfo>
            var firstExecution: WorkInfo? = null
            do {
                work = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                firstExecution = work.firstOrNull { it.id !in beforeIds }
                if (firstExecution?.state == WorkInfo.State.SUCCEEDED) break
                if (firstExecution?.state == WorkInfo.State.FAILED) fail("Production scheduled Worker failed: $work")
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < deadline)
            assertEquals("Actual new WorkManager execution must finish", WorkInfo.State.SUCCEEDED, firstExecution?.state)
            ChatDatabase(context).use { db ->
                when (scenario) {
                    "terminal", "expired_partial" -> {
                        assertNull(db.getActiveResponse(chatId))
                        val messages = db.getChat(chatId)!!.getJSONArray("messages")
                        assertEquals("One terminal owner, no duplicate assistant", 2, messages.length())
                        val assistant = messages.getJSONObject(1)
                        assertEquals(if (scenario == "terminal") "canonical paid result" else "received fragment",
                            assistant.getString("text"))
                        assertEquals("signed-fixture-exact-bytes", assistant.getJSONArray("openai_protocol_output")
                            .getJSONObject(0).getString("encrypted_content"))
                        if (scenario == "expired_partial") {
                            assertTrue(assistant.getBoolean("incomplete"))
                            val source = assistant.getJSONArray("annotations").getJSONObject(0)
                            assertArrayEquals(sourceBytes, java.io.File(source.getString("local_path")).readBytes())
                        }
                    }
                    "stopped_pre_id" -> {
                        assertNull(db.getActiveResponse(chatId))
                        assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
                        assertEquals("stopped", db.listRequestAccounting(chatId).single()
                            .getString("local_status"))
                    }
                    "replaced" -> {
                        val active = db.getActiveResponse(chatId)!!
                        assertEquals(replacementId, active.getString("request_id"))
                        assertEquals("resp_replacement_fixture", active.getString("response_id"))
                        assertEquals(1, db.getChat(chatId)!!.getJSONArray("messages").length())
                    }
                }
            }
            // Another real scheduled attempt cannot claim this old terminal.
            val previousIds = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).map { it.id }.toSet()
            BackgroundResponseWorkScheduler.enqueueImmediate(context, chatId, responseId, requestId)
            val repeatDeadline = SystemClock.elapsedRealtime() + 30_000L
            var repeated: WorkInfo? = null
            do {
                work = manager.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS)
                repeated = work.firstOrNull { it.id !in previousIds }
                if (repeated?.state?.isFinished == true) break
                SystemClock.sleep(100)
            } while (SystemClock.elapsedRealtime() < repeatDeadline)
            assertEquals("A second actual scheduled execution must finish", WorkInfo.State.SUCCEEDED, repeated?.state)
            ChatDatabase(context).use { db ->
                assertEquals(if (scenario in setOf("terminal", "expired_partial")) 2 else 1,
                    db.getChat(chatId)!!.getJSONArray("messages").length())
            }
        } finally {
            BackgroundResponseWorkScheduler.cancel(context, chatId, requestId)
            ChatDatabase(context).use { it.deleteChat(chatId) }
            OpenAiRawSseArchive.archivesFor(context.filesDir, chatId, requestId).forEach { it.delete() }
            prefs.edit().clear().commit()
        }
    }
}
