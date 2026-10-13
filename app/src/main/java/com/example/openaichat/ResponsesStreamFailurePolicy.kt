package com.example.openaichat

import java.util.Collections
import java.util.IdentityHashMap

object ResponsesStreamFailurePolicy {
    /** use{} can suppress a failed archive fsync under an earlier socket error. */
    fun isUnrecoverable(failure: Throwable): Boolean {
        val seen = Collections.newSetFromMap(IdentityHashMap<Throwable, Boolean>())
        val pending = java.util.ArrayDeque<Throwable>()
        pending.add(failure)
        while (pending.isNotEmpty()) {
            val current = pending.removeFirst()
            if (!seen.add(current)) continue
            if (current is UnrecoverableResponsesStreamException) return true
            current.cause?.let { pending.add(it) }
            current.suppressed.forEach { pending.add(it) }
        }
        return false
    }
}
