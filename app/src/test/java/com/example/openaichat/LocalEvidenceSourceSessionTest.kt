package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class LocalEvidenceSourceSessionTest {
    private fun attachment(id: String) = OpenRouterLocalAttachment(id, "$id.txt", "text", "/synthetic/$id", "text/plain", 1, "")

    @Test fun threeConcurrentPanelsShareOneDiscoveryAndImmutableMetadataSnapshot() {
        val owner = Executors.newSingleThreadExecutor(); val consumers = Executors.newFixedThreadPool(3)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val original = mutableListOf(attachment("source"))
        val session = LocalEvidenceSourceSession("chat", 1, owner) { chat ->
            assertEquals("chat", chat); calls.incrementAndGet(); entered.countDown()
            assertTrue(release.await(5, TimeUnit.SECONDS)); original
        }
        try {
            val readers = (1..3).map { consumers.submit(Callable { session.sources() }) }
            assertTrue(entered.await(5, TimeUnit.SECONDS)); release.countDown()
            val results = readers.map { it.get(5, TimeUnit.SECONDS) }
            assertEquals(1, calls.get()); results.forEach { assertSame(results.first(), it) }
            original.clear(); assertEquals("source", session.sources().single().id)
            assertThrows(UnsupportedOperationException::class.java) { (results.first() as MutableList<*>).clear() }
        } finally { release.countDown(); session.invalidate(); consumers.shutdownNow(); owner.shutdownNow() }
    }

    @Test fun cancellingFirstConsumerDoesNotCancelDiscoveryForAnotherPanel() {
        val owner = Executors.newSingleThreadExecutor(); val consumers = Executors.newFixedThreadPool(2)
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val calls = AtomicInteger()
        val session = LocalEvidenceSourceSession("chat", 1, owner) {
            calls.incrementAndGet(); entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)); listOf(attachment("shared"))
        }
        try {
            val first = consumers.submit(Callable { session.sources() })
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val second = consumers.submit(Callable { session.sources() })
            assertTrue(first.cancel(true)); release.countDown()
            assertEquals("shared", second.get(5, TimeUnit.SECONDS).single().id)
            assertEquals(1, calls.get()); assertEquals("shared", session.sources().single().id)
        } finally { release.countDown(); session.invalidate(); consumers.shutdownNow(); owner.shutdownNow() }
    }

    @Test fun invalidatedOldChatCannotPublishIntoNewChatOrRevision() {
        val owner = Executors.newFixedThreadPool(2); val consumer = Executors.newSingleThreadExecutor()
        val entered = CountDownLatch(1); val release = CountDownLatch(1)
        val old = LocalEvidenceSourceSession("old-chat", 1, owner) {
            entered.countDown()
            while (release.count > 0) try { release.await() } catch (_: InterruptedException) { }
            listOf(attachment("stale"))
        }
        val fresh = LocalEvidenceSourceSession("new-chat", 2, owner) { listOf(attachment("new")) }
        try {
            val stale = consumer.submit(Callable { runCatching { old.sources() }.exceptionOrNull() })
            assertTrue(entered.await(5, TimeUnit.SECONDS)); old.invalidate()
            assertEquals("new", fresh.sources().single().id)
            assertTrue(stale.get(5, TimeUnit.SECONDS) is IOException)
            release.countDown()
            assertThrows(IOException::class.java) { old.sources() }
            assertEquals("new", fresh.sources().single().id)
        } finally { release.countDown(); old.invalidate(); fresh.invalidate(); consumer.shutdownNow(); owner.shutdownNow() }
    }

    @Test fun changedRevisionReloadsMetadataAndFailureDoesNotPoisonTheNextAttempt() {
        val owner = Executors.newSingleThreadExecutor(); val calls = AtomicInteger()
        val name = AtomicReference("before")
        val first = LocalEvidenceSourceSession("same-chat", 1, owner) { listOf(attachment(name.get())) }
        try {
            assertEquals("before", first.sources().single().id); name.set("after")
            first.invalidate()
            val next = LocalEvidenceSourceSession("same-chat", 2, owner) {
                if (calls.incrementAndGet() == 1) throw IOException("synthetic read failure")
                listOf(attachment(name.get()))
            }
            try {
                assertThrows(IOException::class.java) { next.sources() }
                assertEquals("after", next.sources().single().id)
                assertEquals(2, calls.get())
            } finally { next.invalidate() }
        } finally { first.invalidate(); owner.shutdownNow() }
    }
}
