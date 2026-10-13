package com.example.openaichat

import java.io.Closeable

/**
 * Conservative allocation reservation for one SSE frame, including framing
 * copies, JSON objects and callback/persistence copies. This is a local
 * processing budget, never a generation limit. The received-source reader
 * drains the original body after a budget failure.
 *
 * All decoders share reservations. Already retained response state also reduces
 * the measured heap headroom; a new frame cannot assume an otherwise empty heap.
 * Reservations stay held through the event callback and are always released.
 * This does not bound unrelated allocations in UI/history/SQLite code.
 */
internal class ResponsesSseMemoryBudget(
    private val limitBytes: Long,
    private val sharedLimitBytes: Long = Long.MAX_VALUE
) : Closeable {
    private val frameCap = minOf(limitBytes, Runtime.getRuntime().maxMemory() / 3L)
    private var reserved = 0L

    fun framingCharacters(count: Long) {
        val bytes = count * BYTES_PER_CHARACTER
        if (bytes > frameCap) fail()
        // Check at most once per 1024 characters, reserving ahead of StringBuilder
        // growth. A small explicit test budget still retains its exact boundary.
        reserve(minOf(frameCap, ((count + 1023L) / 1024L) * 1024L * BYTES_PER_CHARACTER))
    }

    fun json(data: String) {
        var bytes = data.length.toLong() * BYTES_PER_CHARACTER
        var depth = 0
        var quoted = false
        var escaped = false
        for (ch in data) {
            if (quoted) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') quoted = false
                continue
            }
            when (ch) {
                '"' -> { quoted = true; bytes += 128L }
                '{', '[' -> {
                    depth++
                    if (depth > MAX_JSON_DEPTH) throw ResponsesSseLimitException(
                        "Ответ превысил безопасную глубину вложенности JSON.")
                    bytes += 512L
                }
                '}', ']' -> depth--
                ':', ',' -> bytes += 128L
            }
            if (bytes > frameCap) fail()
            reserve(minOf(frameCap, ((bytes + RESERVATION_STEP - 1L) / RESERVATION_STEP) * RESERVATION_STEP))
        }
        if (bytes > frameCap) fail()
        reserve(bytes)
    }

    private fun reserve(bytes: Long) {
        if (bytes <= reserved) return
        synchronized(lock) {
            val runtime = Runtime.getRuntime()
            val headroom = runtime.maxMemory() - runtime.totalMemory() + runtime.freeMemory()
            val increase = bytes - reserved
            // Leave half of actual headroom for non-decoder allocations and the
            // failure recorder/drain. Counting allocated + reserved memory is
            // deliberately conservative rather than relying on a forced GC.
            if (increase > headroom / 2L - allReserved || increase > sharedLimitBytes - allReserved) fail()
            allReserved += increase
            reserved = bytes
        }
    }

    override fun close() {
        synchronized(lock) {
            allReserved -= reserved
            reserved = 0L
        }
    }

    private fun fail(): Nothing = throw ResponsesSseLimitException(
        "Ответ превысил безопасный лимит памяти для обработки.")

    private companion object {
        const val BYTES_PER_CHARACTER = 32L
        const val RESERVATION_STEP = 32L * 1024L
        const val MAX_JSON_DEPTH = 128
        val lock = Any()
        var allReserved = 0L
    }
}
