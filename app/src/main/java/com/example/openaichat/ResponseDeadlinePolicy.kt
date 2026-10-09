package com.example.openaichat

import okhttp3.Call
import java.io.IOException
import java.util.concurrent.TimeUnit

/** The origin is the persisted epoch started_at from ChatDatabase, never a fresh resume time. */
object ResponseDeadlinePolicy {
    const val MAX_REQUEST_MS = 5L * 60 * 60 * 1000L

    fun remainingMillis(startedAtEpochMs: Long, nowEpochMs: Long): Long {
        if (startedAtEpochMs <= 0L) return 0L
        // Subtraction avoids overflow when a corrupt future timestamp is encountered.
        val elapsed = nowEpochMs - startedAtEpochMs
        return if (elapsed < 0L) 0L else (MAX_REQUEST_MS - elapsed).coerceAtLeast(0L)
    }

    /** Apply the same budget to a fully silent SSE socket, not only to retry loops. */
    fun boundCall(call: Call, startedAtEpochMs: Long, nowEpochMs: Long) {
        val remaining = remainingMillis(startedAtEpochMs, nowEpochMs)
        if (remaining <= 0L) {
            call.cancel()
            throw IOException("Responses request reached its five-hour deadline")
        }
        call.timeout().timeout(remaining, TimeUnit.MILLISECONDS)
    }
}
