package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DestroyedActivityCallbackCleanupTest {
    @Test fun cancelledCreateBlocksResumeUntilReleaseAndReplacementKeepsOwnLease() {
        val http = okhttp3.OkHttpClient()
        val call = http.newCall(okhttp3.Request.Builder().url("https://example.invalid/").build())
        val chat = "lease-cancelled-create"
        OpenAiCreateCallOwners.register(chat, "old", call)
        call.cancel()
        val replacement = OpenAiCreateCallOwners.acquireResume(chat, "new")!!
        try {
            assertNull(OpenAiCreateCallOwners.acquireResume(chat, "old"))
            OpenAiCreateCallOwners.release(chat, "old", call)
            val old = OpenAiCreateCallOwners.acquireResume(chat, "old")!!
            OpenAiCreateCallOwners.releaseResume(chat, "old", old)
            assertNull(OpenAiCreateCallOwners.acquireResume(chat, "new"))
            OpenAiCreateCallOwners.releaseResume(chat, "new", Any())
            assertNull("Foreign release must not clear replacement", OpenAiCreateCallOwners.acquireResume(chat, "new"))
        } finally {
            OpenAiCreateCallOwners.release(chat, "old", call)
            OpenAiCreateCallOwners.releaseResume(chat, "new", replacement)
            http.dispatcher.executorService.shutdown()
        }
    }

    @Test fun workerAndActivityCannotAcquireSameResumeConcurrently() {
        val start = CountDownLatch(1)
        val acquired = CountDownLatch(2)
        val executor = Executors.newFixedThreadPool(2)
        val winners = java.util.concurrent.ConcurrentLinkedQueue<Any>()
        try {
            repeat(2) { executor.execute {
                start.await()
                OpenAiCreateCallOwners.acquireResume("lease-race", "request")?.let(winners::add)
                acquired.countDown()
            } }
            start.countDown()
            assertTrue(acquired.await(5L, TimeUnit.SECONDS))
            assertEquals(1, winners.size)
        } finally {
            winners.forEach { OpenAiCreateCallOwners.releaseResume("lease-race", "request", it) }
            executor.shutdownNow()
        }
        val next = OpenAiCreateCallOwners.acquireResume("lease-race", "request")!!
        OpenAiCreateCallOwners.releaseResume("lease-race", "request", next)
    }

    @Test fun destroyedOwnerCanRetrySqliteAndInterruptIsRestoredAfterFailure() {
        val cleanup = DestroyedActivityCallbackCleanup { true }
        try {
            Thread.currentThread().interrupt()
            try {
                cleanup.enter()
                assertFalse(Thread.currentThread().isInterrupted)
                Thread.sleep(1L)
                throw IllegalStateException("Synthetic failing finalization")
            } catch (_: IllegalStateException) {
                // finally must restore even when the owned transaction fails.
            } finally { cleanup.close() }
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun liveOwnerRetainsNormalCancellationInterrupt() {
        val cleanup = DestroyedActivityCallbackCleanup { false }
        try {
            Thread.currentThread().interrupt()
            cleanup.enter()
            assertTrue(Thread.currentThread().isInterrupted)
            cleanup.close()
            assertTrue(Thread.currentThread().isInterrupted)
        } finally { Thread.interrupted() }
    }

    @Test fun databaseCloseWaitsForInterruptedCallbackAndNeverBlocksDestroyThread() {
        val executor = Executors.newSingleThreadExecutor()
        val callbackEntered = CountDownLatch(1)
        val destroyInterruptObserved = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val resourceClosed = CountDownLatch(1)
        val closedWhileRunning = AtomicBoolean(false)
        executor.execute {
            callbackEntered.countDown()
            while (releaseCallback.count > 0L) {
                try { releaseCallback.await() }
                catch (_: InterruptedException) { destroyInterruptObserved.countDown() }
            }
            closedWhileRunning.set(resourceClosed.count == 0L)
        }
        assertTrue(callbackEntered.await(5L, TimeUnit.SECONDS))
        executor.shutdownNow()
        val closer = ExecutorResourceCloser.closeAfterTermination(executor) { resourceClosed.countDown() }
        try {
            assertTrue(destroyInterruptObserved.await(5L, TimeUnit.SECONDS))
            assertFalse(resourceClosed.await(50L, TimeUnit.MILLISECONDS))
            closer.interrupt()
            assertFalse(resourceClosed.await(50L, TimeUnit.MILLISECONDS))
            releaseCallback.countDown()
            assertTrue(resourceClosed.await(5L, TimeUnit.SECONDS))
            assertFalse(closedWhileRunning.get())
        } finally {
            releaseCallback.countDown()
            closer.join(5_000L)
        }
    }
}
