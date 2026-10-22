package com.example.openaichat

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executor
import java.util.concurrent.FutureTask
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** One Activity/chat revision's metadata snapshot. File bytes are verified separately at read time. */
class LocalEvidenceSourceSession(
    val chatId: String,
    val version: Long,
    private val ownerExecutor: Executor,
    private val discover: (String) -> List<OpenRouterLocalAttachment>
) {
    private val valid = AtomicBoolean(true)
    private val snapshot = AtomicReference<FutureTask<List<OpenRouterLocalAttachment>>?>()

    fun sources(): List<OpenRouterLocalAttachment> {
        checkActive()
        var task = snapshot.get()
        if (task == null) {
            val candidate = FutureTask {
                checkActive()
                val values = try { discover(chatId) } catch (error: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("Проверка файлов отменена", error)
                }
                checkValid()
                Collections.unmodifiableList(ArrayList(values)) as List<OpenRouterLocalAttachment>
            }
            if (snapshot.compareAndSet(null, candidate)) {
                task = candidate
                try { ownerExecutor.execute(candidate) } catch (error: RejectedExecutionException) {
                    snapshot.compareAndSet(candidate, null)
                    candidate.cancel(false)
                    throw IOException("Проверка файлов больше недоступна", error)
                }
            } else task = snapshot.get()
        }
        val current = task ?: throw IOException("Снимок файлов изменился")
        try {
            val values = current.get()
            checkActive()
            return values
        } catch (error: InterruptedException) {
            // Cancel this consumer only; another visible card can still use the shared discovery.
            Thread.currentThread().interrupt()
            throw IOException("Проверка файлов отменена", error)
        } catch (error: CancellationException) {
            throw IOException("Снимок файлов больше не действителен", error)
        } catch (error: ExecutionException) {
            snapshot.compareAndSet(current, null)
            val cause = error.cause
            if (cause is IOException) throw cause
            throw IOException("Не удалось получить список локальных файлов", cause ?: error)
        }
    }

    fun invalidate() {
        valid.set(false)
        snapshot.getAndSet(null)?.cancel(true)
    }

    private fun checkActive() {
        checkValid()
        if (Thread.currentThread().isInterrupted) throw IOException("Проверка файлов отменена")
    }

    private fun checkValid() {
        if (!valid.get()) throw IOException("Снимок файлов больше не действителен")
    }
}
