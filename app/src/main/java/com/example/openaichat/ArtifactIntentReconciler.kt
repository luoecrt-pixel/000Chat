package com.example.openaichat

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Durable, application-owned follow-up; it never runs tools or retains an Activity. */
object ArtifactIntentReconciler {
    const val WORK_NAME = "artifact_intent_reconciliation"

    fun enqueue(context: Context) {
        val app = context.applicationContext
        try {
            val request = OneTimeWorkRequestBuilder<ArtifactIntentReconciliationWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.SECONDS)
                .addTag(WORK_NAME)
                .build()
            // Appending also covers an intent committed while the preceding worker is
            // returning success: that worker cannot consume the newer durable wake-up.
            WorkManager.getInstance(app).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        } catch (error: RuntimeException) {
            // The durable intent remains available for startup scheduling. Optional
            // reconciliation must not turn a confirmed file operation into a retry.
            AstraDiagnostics(app).log("artifact_recovery_enqueue_deferred", mapOf("error_type" to error.javaClass.simpleName))
        }
    }
}

class ArtifactIntentReconciliationWorker(context: Context, parameters: WorkerParameters) : Worker(context, parameters) {
    override fun doWork(): Result {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        return try {
            ChatDatabase(applicationContext).use { db ->
                repeat(4) {
                    if (isStopped || System.nanoTime() >= deadline) return Result.retry()
                    val batch = db.reconcilePublishedArtifactIntents(
                        shouldContinue = { !isStopped && System.nanoTime() < deadline }, maxRows = 32,
                        maxFileBytes = 32L * 1024L * 1024L, maxWorkMillis = 500L)
                    if (!batch.hasMore) return Result.success()
                    if (batch.cancelled || batch.examined == 0) return Result.retry()
                }
            }
            // Pending rows are followed after WorkManager's persisted backoff, even
            // when the Activity has been destroyed or the application process restarted.
            Result.retry()
        } catch (error: Exception) {
            AstraDiagnostics(applicationContext).log("artifact_recovery_deferred", mapOf("error_type" to error.javaClass.simpleName))
            Result.retry()
        }
    }
}
