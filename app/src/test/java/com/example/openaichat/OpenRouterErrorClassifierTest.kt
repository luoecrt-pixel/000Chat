package com.example.openaichat

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test
import java.util.concurrent.TimeUnit

class OpenRouterErrorClassifierTest {
    private fun complete(client: OpenRouterChatClient) = client.complete(
        "mock", "anthropic/claude-sonnet-5.5",
        JSONArray().put(JSONObject().put("role", "user").put("content", "Test request")),
        tools = null, maxTokens = null, reasoningEffort = "max", verbosity = null,
        parallelToolCalls = false, executeTool = { _, _ -> throw AssertionError("Unexpected tool") }
    )

    private fun stream(events: String) = MockResponse().setHeader("Content-Type", "text/event-stream")
        .setBody(events + "data: [DONE]\n\n")

    @Test fun immutableThinkingHttp400ExplainsSafeOptionsWithoutRetryOrSecretDisclosure() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        val rejection = "messages.19.content.1: `thinking` or `redacted_thinking` blocks in the latest assistant " +
            "message cannot be modified. These blocks must remain as they were in the original response."
        val secret = "sk-or-v1-SyntheticSecretForRedaction12345"
        try {
            // OpenRouter can forward the explanation in its outer message or in metadata.raw.
            for (location in listOf("outer", "raw_object", "raw_string")) {
                val providerError = JSONObject().put("error", JSONObject().put("type", "invalid_request_error")
                    .put("code", "signed_context_rejected").put("message", "$secret $rejection"))
                val metadata = JSONObject().put("provider_name", "Anthropic").put("flagged_input", "PRIVATE_INPUT")
                if (location != "outer") metadata.put("raw", if (location == "raw_string") providerError.toString() else providerError)
                val error = JSONObject().put("code", 400)
                    .put("message", if (location == "outer") "$secret $rejection" else "Provider returned error")
                    .put("metadata", metadata)
                server.enqueue(MockResponse().setResponseCode(400).setBody(JSONObject().put("error", error).toString()))
                val failure = assertThrows(OpenRouterUpstreamException::class.java) { complete(client) }
                assertEquals("HTTP", failure.stage)
                assertEquals(400, failure.httpStatus)
                assertEquals("Anthropic", failure.providerName)
                assertEquals(if (location == "outer") "400" else "invalid_request_error", failure.errorType)
                if (location != "outer") assertEquals("signed_context_rejected", failure.providerCode)
                assertFalse(failure.retryEligible)
                assertTrue(failure.message.orEmpty().contains("исходные модель и маршрут"))
                assertTrue(failure.message.orEmpty().contains("начните новый чат"))
                assertTrue(failure.message.orEmpty().contains("История сохранена"))
                assertTrue(failure.message.orEmpty().contains("автоматический повтор не выполнялся"))
                assertTrue(failure.message.orEmpty().contains("messages.19.content.1"))
                assertFalse(failure.message.orEmpty().contains(secret))
                assertFalse(failure.safeMetadata.orEmpty().contains(secret))
                assertFalse(failure.safeMetadata.orEmpty().contains("PRIVATE_INPUT"))
                assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS)!!.method)
                assertEquals("Unexpected retry after $location", null, server.takeRequest(100, TimeUnit.MILLISECONDS))
            }
            assertEquals(3, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }

    @Test fun unrelatedThinkingValidationAndServerErrorsDoNotSuggestSignedContextReset() {
        for ((status, message) in listOf(400 to "Unsupported thinking effort", 500 to
            "thinking blocks cannot be modified")) {
            val failure = OpenRouterErrorClassifier.classify(JSONObject().put("code", status).put("message", message),
                "HTTP", 0, status)
            assertEquals(status, failure.httpStatus)
            assertFalse(failure.message.orEmpty().contains("исходные модель и маршрут"))
        }
    }

    @Test
    fun httpRateLimitPreservesRetryAfterDisablesResponseCacheAndNeverRetries() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "45")
                .setBody("""{"error":{"code":429,"message":"Rate limited","metadata":{"limit_source":"provider"}}}"""))
            server.enqueue(stream("data: {\"choices\":[{\"delta\":{\"content\":\"Unexpected retry\"},\"finish_reason\":\"stop\"}]}\n\n"))
            val error = assertThrows(OpenRouterUpstreamException::class.java) { complete(client) }
            assertEquals("HTTP", error.stage)
            assertEquals(429, error.httpStatus)
            assertEquals(45L, error.retryAfterSeconds)
            assertEquals("provider", error.limitSource)
            assertTrue(error.retryEligible)
            assertEquals(1, server.requestCount)
            assertEquals("false", server.takeRequest(2, TimeUnit.SECONDS)!!.getHeader("X-OpenRouter-Cache"))
        } finally { client.close(); server.shutdown() }
    }

    @Test
    fun sseServerErrorKeepsPartialTextWithoutRawProviderPayloadOrPaidRetry() {
        val server = MockWebServer(); server.start()
        val client = OpenRouterChatClient(server.url("/api/v1").toString(), reconcileGenerations = false)
        try {
            val error = JSONObject().put("code", 503).put("message", "No available providers")
                .put("metadata", JSONObject().put("provider_name", "Anthropic")
                    .put("flagged_input", "PRIVATE_INPUT")
                    .put("raw", JSONObject().put("error", JSONObject().put("message", "PRIVATE_PROVIDER_STACK"))))
            server.enqueue(stream("data: {\"choices\":[{\"delta\":{\"content\":\"Partial answer\"}}]}\n\n" +
                "data: " + JSONObject().put("error", error) + "\n\n"))
            server.enqueue(stream("data: {\"choices\":[{\"delta\":{\"content\":\"Unexpected retry\"},\"finish_reason\":\"stop\"}]}\n\n"))
            val failure = assertThrows(OpenRouterCompletionException::class.java) { complete(client) }
            assertEquals("Partial answer", failure.partialText)
            val upstream = failure.cause as OpenRouterUpstreamException
            assertEquals("SSE", upstream.stage)
            assertEquals(503, upstream.httpStatus)
            assertTrue(upstream.retryEligible)
            assertFalse(upstream.message.orEmpty().contains("PRIVATE_"))
            assertFalse(upstream.safeMetadata.orEmpty().contains("PRIVATE_"))
            assertEquals(1, server.requestCount)
        } finally { client.close(); server.shutdown() }
    }

    @Test
    fun classifies400ImageErrorsAnd402BudgetVariantsAnd403GuardrailAnd429RetryAfterAnd503() {
        val imgErr = JSONObject()
            .put("code", 400)
            .put("message", "Provider rejected image")
            .put("metadata", JSONObject().put("error_type", "image_too_large").put("provider_name", "Anthropic"))
        val classified400 = OpenRouterErrorClassifier.classify(imgErr, "HTTP", 0, 400)
        assertEquals(400, classified400.httpStatus)
        assertEquals("image_too_large", classified400.errorType)
        assertFalse(classified400.retryEligible)
        assertTrue(classified400.message.orEmpty().contains("превышает допустимый размер"))

        val keyLimit402 = JSONObject()
            .put("code", 402)
            .put("message", "Key quota exceeded")
            .put("metadata", JSONObject().put("limit_source", "key").put("resets_at", "2026-10-16T00:00:00Z"))
        val classified402 = OpenRouterErrorClassifier.classify(keyLimit402, "HTTP", 0, 402, retryAfterHeader = "120")
        assertEquals(402, classified402.httpStatus)
        assertEquals("key", classified402.limitSource)
        assertEquals(120L, classified402.retryAfterSeconds)
        assertFalse(classified402.retryEligible)
        assertTrue(classified402.message.orEmpty().contains("лимит расходов API-ключа"))
        assertTrue(classified402.message.orEmpty().contains("Retry-After: 120 с"))

        val guardrail403 = JSONObject()
            .put("code", 403)
            .put("message", "Moderation blocked input")
            .put("metadata", JSONObject().put("provider_name", "Anthropic").put("flagged_input", "PRIVATE_USER_TEXT_MUST_NOT_LEAK"))
        val classified403 = OpenRouterErrorClassifier.classify(guardrail403, "HTTP", 0, 403)
        assertEquals(403, classified403.httpStatus)
        assertTrue(classified403.message.orEmpty().contains("Guardrail"))
        assertFalse(classified403.message.orEmpty().contains("PRIVATE_USER_TEXT_MUST_NOT_LEAK"))
        assertFalse(classified403.safeMetadata.orEmpty().contains("PRIVATE_USER_TEXT_MUST_NOT_LEAK"))

        val rate429 = JSONObject()
            .put("code", 429)
            .put("message", "Rate limit exceeded for sk-or-v1-SecretKeyValue123456789")
            .put("metadata", JSONObject().put("limit_source", "provider").put("resets_at", "60s"))
        val classified429 = OpenRouterErrorClassifier.classify(rate429, "HTTP", 1, 429, retryAfterHeader = "45")
        assertEquals(429, classified429.httpStatus)
        assertTrue(classified429.retryEligible)
        assertEquals(45L, classified429.retryAfterSeconds)
        assertFalse(classified429.message.orEmpty().contains("sk-or-v1-SecretKeyValue123456789"))
        assertTrue(classified429.message.orEmpty().contains("[REDACTED]"))

        val route503 = JSONObject()
            .put("code", 503)
            .put("message", "No available providers")
            .put("metadata", JSONObject().put("provider_name", "Anthropic"))
        val classified503 = OpenRouterErrorClassifier.classify(route503, "HTTP", 0, 503)
        assertEquals(503, classified503.httpStatus)
        assertTrue(classified503.retryEligible)
        assertTrue(classified503.message.orEmpty().contains("Нет доступного маршрута"))

        val server500 = JSONObject()
            .put("code", 500)
            .put("message", "Internal error")
            .put("metadata", JSONObject().put("raw", "{\"error\":{\"message\":\"unredacted_internal_stack\"}}"))
        val classified500 = OpenRouterErrorClassifier.classify(server500, "HTTP", 0, 500)
        assertFalse(classified500.message.orEmpty().contains("unredacted_internal_stack"))
        assertFalse(classified500.safeMetadata.orEmpty().contains("unredacted_internal_stack"))
    }
}
