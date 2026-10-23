package com.example.openaichat

import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.lang.reflect.InvocationTargetException

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DeepSeekGitHubWriteSafetyTest {
    @Test fun disconnectAfterReceivedWriteIsUnknownAndNeverRetried() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setBody("{\"id\":2}"))
            server.start()
            val executor = GitHubToolExecutor(SecureStore(RuntimeEnvironment.getApplication()),
                { "owner/repo" }, { setOf("main") }, { _, _ -> true }, { _, _ -> },
                failOnUncertainWrite = true)
            try {
                val call = GitHubToolExecutor::class.java.getDeclaredMethod("call", Request::class.java)
                    .apply { isAccessible = true }
                val request = Request.Builder().url(server.url("/repos/owner/repo/issues/1/comments"))
                    .post("{\"body\":\"synthetic\"}".toRequestBody()).build()
                try { call.invoke(executor, request); fail("Ambiguous write must stop execution") }
                catch (failure: InvocationTargetException) {
                    assertTrue(failure.cause is GitHubWriteOutcomeUnknownException)
                }
                assertEquals(1, server.requestCount)
            } finally { executor.close() }
        }
    }

    @Test fun explicitHttpRefusalRemainsAConfirmedStatus() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(422).setBody("{\"message\":\"invalid\"}"))
            server.start()
            val executor = GitHubToolExecutor(SecureStore(RuntimeEnvironment.getApplication()),
                { "owner/repo" }, { setOf("main") }, { _, _ -> true }, { _, _ -> },
                failOnUncertainWrite = true)
            try {
                val call = GitHubToolExecutor::class.java.getDeclaredMethod("call", Request::class.java)
                    .apply { isAccessible = true }
                val result = call.invoke(executor, Request.Builder().url(server.url("/write"))
                    .post("{}".toRequestBody()).build()) as Pair<*, *>
                assertEquals(422, result.first)
                assertEquals(1, server.requestCount)
            } finally { executor.close() }
        }
    }
    @Test fun guardedRateLimitedWriteDoesNotConsumeQueuedSuccess() {
        assertGuardedRefusalIsSingleAttempt(429)
    }

    @Test fun guardedForbiddenWriteDoesNotConsumeQueuedSuccess() {
        assertGuardedRefusalIsSingleAttempt(403)
    }

    private fun assertGuardedRefusalIsSingleAttempt(status: Int) {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(status).setHeader("Retry-After", "0")
                .setBody("{\"message\":\"synthetic refusal\"}"))
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"id\":2}"))
            server.start()
            val executor = GitHubToolExecutor(SecureStore(RuntimeEnvironment.getApplication()),
                { "owner/repo" }, { setOf("main") }, { _, _ -> true }, { _, _ -> },
                failOnUncertainWrite = true)
            try {
                val call = GitHubToolExecutor::class.java.getDeclaredMethod("call", Request::class.java)
                    .apply { isAccessible = true }
                val result = call.invoke(executor, Request.Builder().url(server.url("/write"))
                    .post("{\"body\":\"synthetic\"}".toRequestBody()).build()) as Pair<*, *>
                assertEquals(status, result.first)
                assertEquals("{\"message\":\"synthetic refusal\"}", result.second)
                assertEquals(1, server.requestCount)
                assertEquals("POST", server.takeRequest().method)
            } finally { executor.close() }
        }
    }

}
