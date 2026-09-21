package com.example.openaichat

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

object BackgroundResponseWorkScheduler {
    private const val TAG = "astra_response_recovery"
    private const val NAME_PREFIX = "astra_response_recovery_"

    fun workName(chatId: String, requestId: String): String =
        NAME_PREFIX + chatId + "_" + requestId

    fun enqueue(
        context: Context,
        chatId: String,
        responseId: String,
        requestId: String,
        initialDelayMs: Long = 15_000L
    ) {
        if (chatId.isBlank() || responseId.isBlank() || requestId.isBlank()) return
        val request = OneTimeWorkRequestBuilder<BackgroundResponseWorker>()
            .setInputData(
                androidx.work.Data.Builder()
                    .putString(BackgroundResponseWorker.KEY_CHAT_ID, chatId)
                    .putString(BackgroundResponseWorker.KEY_RESPONSE_ID, responseId)
                    .putString(BackgroundResponseWorker.KEY_REQUEST_ID, requestId)
                    .build()
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .setInitialDelay(initialDelayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()

        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(
                workName(chatId, requestId),
                ExistingWorkPolicy.REPLACE,
                request
            )
    }

    fun enqueueImmediate(
        context: Context,
        chatId: String,
        responseId: String,
        requestId: String
    ) = enqueue(context, chatId, responseId, requestId, 0L)

    fun cancel(context: Context, chatId: String, requestId: String?) {
        if (chatId.isBlank() || requestId.isNullOrBlank()) return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(workName(chatId, requestId))
    }

    fun cancelAll(context: Context) {
        WorkManager.getInstance(context.applicationContext).cancelAllWorkByTag(TAG)
    }

    fun rescheduleAll(context: Context, activeResponses: List<org.json.JSONObject>) {
        activeResponses.forEach { active ->
            val chatId = active.optString("chat_id")
            val responseId = active.optString("response_id")
            val requestId = active.optString("request_id")
            if (chatId.isNotBlank() && responseId.isNotBlank() && requestId.isNotBlank()) {
                enqueueImmediate(context, chatId, responseId, requestId)
            }
        }
    }
}
