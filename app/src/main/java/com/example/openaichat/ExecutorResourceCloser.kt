package com.example.openaichat

import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/** A destroyed Activity must not close SQLite underneath its final callbacks. */
internal object ExecutorResourceCloser {
    fun closeAfterTermination(executor: ExecutorService, close: () -> Unit): Thread =
        Thread({
            var interrupted = false
            try {
                while (!executor.isTerminated) {
                    try { executor.awaitTermination(30L, TimeUnit.SECONDS) }
                    catch (_: InterruptedException) { interrupted = true }
                }
                close()
            } finally {
                if (interrupted) Thread.currentThread().interrupt()
            }
        }, "activity-database-close").apply { isDaemon = true; start() }
}
