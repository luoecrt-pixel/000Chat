package com.example.openaichat

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.os.SystemClock
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.IOException
import java.lang.reflect.Proxy
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** Stop admits no further work while it freezes the transport and still owns SQLite. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekStopOwnershipTest {
    @Test fun freezePrecedesOwnerRevocationAndRuntimeCannotStealStopTerminal() = runStop(false)
    @Test fun checkpointFailureReturnsNoticeAndArchivesOnlyLastAcknowledgedPrefix() = runStop(true)

    @Test fun generalCancellationFinalizesDisconnectedInsteadOfLeavingActiveOwner() = runStop(false, reserveStop = false)

    private fun runStop(failCheckpoint: Boolean, reserveStop: Boolean = true) {
        val context: Context = RuntimeEnvironment.getApplication()
        val chat = "stop-freeze"
        val requestId = "stop-freeze-request"
        SQLiteDatabase.deleteDatabase(context.getDatabasePath("astra_chats.db"))
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit().putBoolean("response_notifications", false).commit()
        fun checkpoint(prefix: String) = JSONObject().put("version", 1).put("replayable", false)
            .put("tail", JSONArray()).put("uncommitted_assistant", JSONObject().put("role", "assistant")
                .put("content", prefix))
        ChatDatabase(context).use { db ->
            db.ensureChat(chat)
            db.setActiveResponse(chat, null, requestId, "", "in_progress", System.currentTimeMillis(),
                "none", "none", false, 1, 0, false)
            assertTrue(db.startRequestAccounting(chat, requestId, "deepseek", "deepseek-chat", "deepseek_api_key"))
            assertTrue(db.saveDeepSeekCheckpoint(chat, requestId, checkpoint("acknowledged")))
        }
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val freezes = AtomicInteger()
        val closes = AtomicInteger()
        val client = Proxy.newProxyInstance(ChatCompletionTransport::class.java.classLoader,
            arrayOf(ChatCompletionTransport::class.java)) { _, method, _ -> when (method.name) {
                "getProvider" -> "deepseek"
                "complete" -> {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    throw CancellationException("Stopped test transport")
                }
                "freezeAndFlushCheckpoint" -> {
                    freezes.incrementAndGet()
                    ChatDatabase(context).use { db ->
                        assertTrue("Freeze must happen before SQLite Stop", db.isActiveRequest(chat, requestId))
                        if (failCheckpoint) throw IOException("Checkpoint exceeds 8 MiB")
                        assertTrue(db.saveDeepSeekCheckpoint(chat, requestId, checkpoint("latest")))
                    }
                    null
                }
                "flushCheckpoint" -> error("Stop must freeze rather than only flush")
                "close" -> { closes.incrementAndGet(); null }
                else -> null
            }
        } as ChatCompletionTransport
        val metadata = OkHttpClient()
        try {
            OpenRouterResponseRuntime.start(context, OpenRouterRuntimeRequest(chat, requestId, "deepseek-chat",
                JSONArray(), emptyList(), "none", "", null, false, "", emptySet(), false, false, false,
                "off", false, SystemClock.elapsedRealtime(), "general", "none", false, 1,
                "inert-key", metadata, "auto", provider = "deepseek"), client)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            if (!reserveStop) {
                OpenRouterResponseRuntime.cancel(requestId, "Соединение прервано")
                release.countDown()
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (OpenRouterResponseRuntime.contains(requestId) && System.nanoTime() < deadline) Thread.sleep(5)
                assertFalse(OpenRouterResponseRuntime.contains(requestId))
                ChatDatabase(context).use { db ->
                    assertFalse(db.isActiveRequest(chat, requestId))
                    assertEquals("disconnected", db.getChat(chat)!!.getJSONArray("messages").getJSONObject(0).getString("status"))
                }
                return
            }
            val warning = OpenRouterResponseRuntime.prepareStop(requestId)
            if (failCheckpoint) assertTrue(warning!!.contains("последний подтверждённый checkpoint")) else assertNull(warning)
            ChatDatabase(context).use { db ->
                assertTrue(db.isActiveRequest(chat, requestId))
                assertTrue(db.stopActiveRequestOnce(chat, requestId))
            }
            OpenRouterResponseRuntime.cancel(requestId)
            assertEquals("Cancel must reuse the prepared immutable cursor", 1, freezes.get())
            release.countDown()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (OpenRouterResponseRuntime.contains(requestId) && System.nanoTime() < deadline) Thread.sleep(5)
            assertFalse(OpenRouterResponseRuntime.contains(requestId))
            ChatDatabase(context).use { db ->
                val protocol = db.getRequestRecoveryArchive(chat, requestId)!!.getJSONObject("deepseek_protocol")
                assertEquals(if (failCheckpoint) "acknowledged" else "latest",
                    protocol.getJSONObject("uncommitted_assistant").getString("content"))
                assertEquals(0, db.getChat(chat)!!.getJSONArray("messages").length())
            }
            while (closes.get() == 0 && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, closes.get())
        } finally {
            release.countDown()
            OpenRouterResponseRuntime.cancel(requestId)
            metadata.dispatcher.executorService.shutdownNow()
            metadata.connectionPool.evictAll()
        }
    }
}
