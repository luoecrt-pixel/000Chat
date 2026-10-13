package com.example.openaichat

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regresses the observed emulator SQLITE_BUSY / PRAGMA journal_mode crash:
 * multiple independent Activity/Worker-style SQLiteOpenHelper instances open
 * the same persistent database concurrently while the file is being used.
 * No isolated per-thread database: every thread accesses the SAME app DB.
 */
@RunWith(AndroidJUnit4::class)
class ChatDatabaseConcurrentOpenStressTest {
    @Test fun distinctHelpersUseSameWalModeAcrossReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val chatId = "db-wal-open-" + UUID.randomUUID()
        try {
            ChatDatabase(context).use { first ->
                first.ensureChat(chatId, "WAL persists")
                first.writableDatabase.rawQuery("PRAGMA journal_mode", null).use {
                    assertTrue(it.moveToFirst())
                    assertEquals("wal", it.getString(0).lowercase(java.util.Locale.ROOT))
                }
                ChatDatabase(context).use { second ->
                    second.writableDatabase.rawQuery("PRAGMA journal_mode", null).use {
                        assertTrue(it.moveToFirst())
                        assertEquals("wal", it.getString(0).lowercase(java.util.Locale.ROOT))
                    }
                    assertEquals("WAL persists", second.getChatTitle(chatId))
                }
            }
            ChatDatabase(context).use { reopened ->
                assertEquals("WAL persists", reopened.getChatTitle(chatId))
            }
        } finally {
            ChatDatabase(context).use { it.deleteChat(chatId) }
        }
    }

    @Test fun simultaneousColdHelperOpensUnderReadWriteLoadKeepHistory() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "db-concurrency-" + UUID.randomUUID()
        val threads = 10
        val rounds = 18
        val executor = Executors.newFixedThreadPool(threads)
        val ready = CountDownLatch(threads)
        val go = CountDownLatch(1)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        val attempts = AtomicInteger()
        try {
            ChatDatabase(context).use { db -> db.ensureChat(id, "Concurrent DB open") }
            val futures = (0 until threads).map { worker ->
                executor.submit {
                    ready.countDown()
                    if (!go.await(10, TimeUnit.SECONDS)) error("Start gate timed out")
                    for (round in 0 until rounds) {
                        try {
                            ChatDatabase(context).use { db ->
                                val raw = db.readableDatabase.rawQuery(
                                    "SELECT title FROM chats WHERE id=?", arrayOf(id))
                                raw.use {
                                    check(it.moveToFirst())
                                    check(it.getString(0) == "Concurrent DB open")
                                }
                                if (worker == 0 && round % 4 == 0) {
                                    db.updateActiveResponse(id, status = "starting")
                                }
                            }
                            attempts.incrementAndGet()
                        } catch (failure: Throwable) {
                            failures += failure
                            break
                        }
                    }
                }
            }
            assertTrue("All parallel helpers must be ready", ready.await(15, TimeUnit.SECONDS))
            go.countDown()
            for (future in futures) future.get(60, TimeUnit.SECONDS)
            assertTrue("All $threads * $rounds opens must complete; errors: " +
                failures.joinToString { it.toString() }, failures.isEmpty())
            assertEquals(threads * rounds, attempts.get())
            ChatDatabase(context).use { db ->
                assertEquals("Concurrent DB open", db.getChatTitle(id))
            }
        } finally {
            go.countDown()
            executor.shutdownNow()
            ChatDatabase(context).use { it.deleteChat(id) }
        }
    }

    @Test fun opensDuringHeldWriteTransactionDoNotFailJournalModeOrLoseRows() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = "db-held-write-" + UUID.randomUUID()
        val holder = ChatDatabase(context)
        val readers = Executors.newFixedThreadPool(6)
        val holdStarted = CountDownLatch(1)
        val releaseHold = CountDownLatch(1)
        try {
            holder.ensureChat(id, "Before held write")
            val writer = Thread {
                val db = holder.writableDatabase
                db.beginTransaction()
                try {
                    db.execSQL("UPDATE chats SET title='After held write' WHERE id=?",
                        arrayOf(id))
                    holdStarted.countDown()
                    releaseHold.await(2, TimeUnit.SECONDS)
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
            }
            writer.start()
            assertTrue(holdStarted.await(5, TimeUnit.SECONDS))
            val attempts = (0 until 6).map {
                readers.submit<String> {
                    ChatDatabase(context).use { db ->
                        db.readableDatabase.rawQuery("SELECT title FROM chats WHERE id=?",
                            arrayOf(id)).use { cursor ->
                            check(cursor.moveToFirst())
                            cursor.getString(0)
                        }
                    }
                }
            }
            Thread.sleep(400)
            releaseHold.countDown()
            writer.join(8_000)
            assertFalse(writer.isAlive)
            val texts = attempts.map { it.get(20, TimeUnit.SECONDS) }
            assertTrue(texts.toString(), texts.all { it == "After held write" || it == "Before held write" })
            ChatDatabase(context).use {
                assertEquals("After held write", it.getChatTitle(id))
            }
        } finally {
            releaseHold.countDown()
            readers.shutdownNow()
            holder.close()
            ChatDatabase(context).use { it.deleteChat(id) }
        }
    }
}
