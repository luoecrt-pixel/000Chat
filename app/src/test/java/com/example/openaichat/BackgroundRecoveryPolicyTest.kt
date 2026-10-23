package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundRecoveryPolicyTest {
    @Test fun inferenceProvidersNeverBecomeResponsesRecoveryEvenWithAResponseId() {
        for (provider in listOf("deepseek", "openrouter", "unknown")) {
            val active = JSONObject().put("provider", provider).put("chat_id", "chat")
                .put("response_id", "stale-response-id").put("request_id", "request")
                .put("background_enabled", true)
            assertFalse(provider, BackgroundRecoveryPolicy.isEligibleForResume(active))
        }
        assertTrue(BackgroundRecoveryPolicy.supportsResponsesRecovery("openai"))
        assertTrue(BackgroundRecoveryPolicy.supportsResponsesRecovery(null))
    }

    @Test
    fun resumeRequiresCompleteEligibleActiveResponseState() {
        fun active(chatId: String, responseId: String, requestId: String, enabled: Boolean = true) =
            JSONObject()
                .put("chat_id", chatId)
                .put("response_id", responseId)
                .put("request_id", requestId)
                .put("background_enabled", enabled)

        assertTrue(BackgroundRecoveryPolicy.isEligibleForResume(active("chat-a", "resp-a", "req-a")))
        assertFalse(BackgroundRecoveryPolicy.isEligibleForResume(active("", "resp-a", "req-a")))
        assertFalse(BackgroundRecoveryPolicy.isEligibleForResume(active("chat-a", "", "req-a")))
        assertFalse(BackgroundRecoveryPolicy.isEligibleForResume(active("chat-a", "resp-a", "")))
        assertFalse(BackgroundRecoveryPolicy.isEligibleForResume(active("chat-a", "resp-a", "req-a", enabled = false)))
    }

    @Test
    fun recoveryWindowIsFiveHoursPerResponse() {
        val started = 10_000L
        val fiveHours = 5L * 60L * 60L * 1000L
        assertFalse(BackgroundRecoveryPolicy.isExpired(started, started + fiveHours - 1L))
        assertTrue(BackgroundRecoveryPolicy.isExpired(started, started + fiveHours))
        assertEquals(fiveHours, BackgroundRecoveryPolicy.MAX_RECOVERY_MS)
    }

    @Test
    fun classifiesPermanentAndRetryableHttpStatuses() {
        assertEquals(BackgroundRecoveryAction.AUTH_FAILURE, BackgroundRecoveryPolicy.actionForHttpStatus(401))
        assertEquals(BackgroundRecoveryAction.AUTH_FAILURE, BackgroundRecoveryPolicy.actionForHttpStatus(403))
        assertEquals(BackgroundRecoveryAction.RESPONSE_GONE, BackgroundRecoveryPolicy.actionForHttpStatus(404))
        assertEquals(BackgroundRecoveryAction.RESPONSE_GONE, BackgroundRecoveryPolicy.actionForHttpStatus(410))
        assertEquals(BackgroundRecoveryAction.RETRY, BackgroundRecoveryPolicy.actionForHttpStatus(429))
        assertEquals(BackgroundRecoveryAction.RETRY, BackgroundRecoveryPolicy.actionForHttpStatus(503))
        assertEquals(BackgroundRecoveryAction.RETRY, BackgroundRecoveryPolicy.actionForHttpStatus(599))
        assertEquals(BackgroundRecoveryAction.FATAL, BackgroundRecoveryPolicy.actionForHttpStatus(400))
    }

    @Test
    fun retryAfterTakesPrecedenceAndIsClamped() {
        assertEquals(15_000L, BackgroundRecoveryPolicy.retryDelayMs(1_500L, 15_000L))
        assertEquals(1_000L, BackgroundRecoveryPolicy.retryDelayMs(1_500L, 100L))
        assertEquals(300_000L, BackgroundRecoveryPolicy.retryDelayMs(1_500L, 900_000L))
    }

    @Test
    fun backoffDoublesUntilThirtySeconds() {
        assertEquals(3_000L, BackgroundRecoveryPolicy.nextDelayMs(1_500L))
        assertEquals(30_000L, BackgroundRecoveryPolicy.nextDelayMs(20_000L))
        assertEquals(30_000L, BackgroundRecoveryPolicy.nextDelayMs(30_000L))
    }

    @Test
    fun jitterStaysWithinTwentyPercentAndNeverBelowOneSecond() {
        repeat(100) {
            val actual = BackgroundRecoveryPolicy.jitteredDelayMs(10_000L)
            assertTrue(actual in 8_000L..12_000L)
        }
        assertTrue(BackgroundRecoveryPolicy.jitteredDelayMs(1_000L) >= 1_000L)
    }
}
