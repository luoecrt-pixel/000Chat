package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class ChatCompletionClientOwnersTest {
    private fun client(closes: AtomicInteger): ChatCompletionTransport = Proxy.newProxyInstance(
        ChatCompletionTransport::class.java.classLoader, arrayOf(ChatCompletionTransport::class.java)
    ) { _, method, _ ->
        when (method.name) {
            "getProvider" -> "deepseek"
            "close" -> { closes.incrementAndGet(); Unit }
            else -> throw AssertionError("Unexpected transport operation ${method.name}")
        }
    } as ChatCompletionTransport

    @Test fun activityRecreationCannotCloseTransportOwnedByAnActiveRequest() {
        val oldCloses = AtomicInteger()
        val oldClient = client(oldCloses)
        ChatCompletionClientOwners.retain(oldClient) // Original Activity.
        ChatCompletionClientOwners.retain(oldClient) // Application-owned running request.
        ChatCompletionClientOwners.release(oldClient) // Destroy original Activity.
        assertEquals(0, oldCloses.get())

        val newCloses = AtomicInteger()
        val newClient = client(newCloses)
        ChatCompletionClientOwners.retain(newClient) // Recreated Activity has its own transport.
        ChatCompletionClientOwners.release(oldClient) // Last request owner finishes.
        assertEquals(1, oldCloses.get())
        assertEquals(0, newCloses.get())
        ChatCompletionClientOwners.release(oldClient) // Cleanup is idempotent.
        assertEquals(1, oldCloses.get())
        try {
            ChatCompletionClientOwners.retain(oldClient)
            fail("A closed instance cannot be reused")
        } catch (_: IllegalStateException) {}
        ChatCompletionClientOwners.release(newClient)
        assertEquals(1, newCloses.get())
    }

    @Test fun concurrentActivityAndRuntimeReleaseCloseExactlyOnce() {
        val closes = AtomicInteger()
        val transport = client(closes)
        ChatCompletionClientOwners.retain(transport)
        ChatCompletionClientOwners.retain(transport)
        val ready = CountDownLatch(2)
        val release = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) {
            Thread {
                ready.countDown()
                release.await()
                try { ChatCompletionClientOwners.release(transport) } finally { done.countDown() }
            }.start()
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        release.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertEquals(1, closes.get())
    }
}
