package com.example.openaichat

enum class ResponseCheckpointReason {
    NONE,
    INTERVAL,
    TERMINAL
}

data class ResponseCheckpointDecision(
    val reason: ResponseCheckpointReason,
    val responseId: String?,
    val text: String,
    val status: String,
    val lastSequenceNumber: Long
) {
    val shouldPersist: Boolean
        get() = reason != ResponseCheckpointReason.NONE
}

object ResponseCheckpointPolicy {
    fun decide(
        state: ResponseEventState,
        responseId: String?,
        lastSequenceNumber: Long,
        nowElapsedMs: Long,
        lastCheckpointElapsedMs: Long,
        checkpointIntervalMs: Long
    ): ResponseCheckpointDecision {
        require(checkpointIntervalMs >= 0L) { "checkpointIntervalMs must be non-negative" }

        val terminal = state.terminalState
        val reason = when {
            terminal != null -> ResponseCheckpointReason.TERMINAL
            nowElapsedMs - lastCheckpointElapsedMs >= checkpointIntervalMs -> ResponseCheckpointReason.INTERVAL
            else -> ResponseCheckpointReason.NONE
        }

        return ResponseCheckpointDecision(
            reason = reason,
            responseId = responseId,
            text = state.text,
            status = terminal?.wireStatus ?: "in_progress",
            lastSequenceNumber = lastSequenceNumber
        )
    }
}
