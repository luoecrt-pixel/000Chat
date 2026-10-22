package com.example.openaichat

import org.json.JSONObject

enum class BackgroundRecoveryAction {
    RETRY,
    AUTH_FAILURE,
    RESPONSE_GONE,
    FATAL
}

object BackgroundRecoveryPolicy {
    // One-response recovery window; this is not a session-wide chat limit.
    const val MAX_RECOVERY_MS = 5L * 60L * 60L * 1000L

    fun isExpired(startedAtElapsedMs: Long, nowElapsedMs: Long): Boolean =
        nowElapsedMs - startedAtElapsedMs >= MAX_RECOVERY_MS

    fun isEligibleForResume(active: JSONObject): Boolean =
        supportsResponsesRecovery(JsonProtocol.nonBlank(active, "provider")) &&
            active.optBoolean("background_enabled", true) &&
            active.optString("chat_id").isNotBlank() &&
            active.optString("response_id").isNotBlank() &&
            active.optString("request_id").isNotBlank()

    /** Unlabelled legacy Responses requests remain recoverable. Inference providers never are. */
    fun supportsResponsesRecovery(provider: String?): Boolean =
        provider.isNullOrBlank() || provider == "openai"

    fun actionForHttpStatus(statusCode: Int): BackgroundRecoveryAction = when (statusCode) {
        401, 403 -> BackgroundRecoveryAction.AUTH_FAILURE
        404, 410 -> BackgroundRecoveryAction.RESPONSE_GONE
        429 -> BackgroundRecoveryAction.RETRY
        in 500..599 -> BackgroundRecoveryAction.RETRY
        else -> BackgroundRecoveryAction.FATAL
    }

    fun nextDelayMs(currentDelayMs: Long): Long =
        (currentDelayMs.coerceAtLeast(1_000L) * 2L).coerceAtMost(30_000L)

    fun retryDelayMs(baseDelayMs: Long, retryAfterMs: Long?): Long =
        (retryAfterMs ?: baseDelayMs.coerceAtMost(60_000L)).coerceIn(1_000L, 300_000L)

    fun jitteredDelayMs(baseDelayMs: Long): Long {
        val base = baseDelayMs.coerceIn(1_000L, 30_000L)
        val spread = (base / 5L).coerceAtLeast(1L)
        val delta = kotlin.random.Random.nextLong(-spread, spread + 1L)
        return (base + delta).coerceAtLeast(1_000L)
    }
}
