package com.example.openaichat

/**
 * Pure ownership contract for persisted response finalization.
 *
 * A worker may commit only while the chat still points at the same active request
 * that the worker started for. Blank expected IDs are never valid ownership.
 */
object ActiveResponseWritePolicy {
    fun shouldCommit(activeRequestId: String?, expectedRequestId: String): Boolean =
        expectedRequestId.isNotBlank() && activeRequestId == expectedRequestId
}
