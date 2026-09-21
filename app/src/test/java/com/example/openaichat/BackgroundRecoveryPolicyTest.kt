package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Test

class BackgroundRecoveryPolicyTest {
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
}
