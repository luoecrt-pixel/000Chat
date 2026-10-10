package com.example.openaichat

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class ResponseDeadlinePolicyTest {
    private val max = ResponseDeadlinePolicy.MAX_REQUEST_MS

    @Test fun fakeClockDoesNotResetAfterActivityAndWorkerRestarts() {
        val persistedStartedAt = 1_700_000_000_000L
        assertEquals(max, ResponseDeadlinePolicy.remainingMillis(persistedStartedAt, persistedStartedAt))
        assertEquals(90_000L, ResponseDeadlinePolicy.remainingMillis(persistedStartedAt,
            persistedStartedAt + max - 90_000L))
        // Reconstructed Worker uses identical persisted start, not this entry time.
        assertEquals(90_000L, ResponseDeadlinePolicy.remainingMillis(persistedStartedAt,
            persistedStartedAt + max - 90_000L))
        assertEquals(0L, ResponseDeadlinePolicy.remainingMillis(persistedStartedAt, persistedStartedAt + max))
        assertEquals(0L, ResponseDeadlinePolicy.remainingMillis(0L, persistedStartedAt))
        assertEquals(0L, ResponseDeadlinePolicy.remainingMillis(persistedStartedAt, persistedStartedAt - 1L))
    }

    @Test fun silentSseSocketIsCancelledByRemainingOriginalBudget() {
        val server = MockWebServer()
        server.start()
        val client = OkHttpClient.Builder().readTimeout(0, TimeUnit.MILLISECONDS)
            .callTimeout(0, TimeUnit.MILLISECONDS).build()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val call = client.newCall(Request.Builder().url(server.url("/never-ends")).build())
            val started = 1_700_000_000_000L
            ResponseDeadlinePolicy.boundCall(call, started, started + max - 100L)
            val before = System.nanoTime()
            assertThrows(IOException::class.java) {
                call.execute().use { response -> response.body?.string() }
            }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - before)
            assertTrue("Blocked socket must time out promptly, got " + elapsedMs, elapsedMs < 4000L)
        } finally {
            client.dispatcher.executorService.shutdown()
            server.shutdown()
        }
    }

    @Test fun expiredDeadlineCancelsBeforeAnyNetwork() {
        val server = MockWebServer()
        server.start()
        try {
            val call = OkHttpClient().newCall(Request.Builder().url(server.url("/")).build())
            assertThrows(IOException::class.java) {
                ResponseDeadlinePolicy.boundCall(call, 1000L, 1000L + max)
            }
            assertTrue(call.isCanceled())
            assertEquals(0, server.requestCount)
        } finally {
            server.shutdown()
        }
    }
}
