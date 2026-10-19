package com.example.openaichat

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

object ResponseNotifications {
    const val EXTRA_CHAT_ID = "notification_chat_id"
    private const val COMPLETE_CHANNEL = "astra_finished_answers"
    private const val RUNNING_CHANNEL = "integral_background_connection_v2"
    private fun channels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(COMPLETE_CHANNEL, "Готовые ответы", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(RUNNING_CHANNEL, "Фоновое соединение", NotificationManager.IMPORTANCE_MIN).apply {
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        })
    }
    private fun intent(context: Context, chatId: String, id: Int): PendingIntent = PendingIntent.getActivity(
        context, id, Intent(context, MainActivity::class.java).putExtra(EXTRA_CHAT_ID, chatId)
            .setData(android.net.Uri.parse("astra-answer://chat/" + android.net.Uri.encode(chatId)))
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    fun running(context: Context, chatId: String): Notification {
        channels(context)
        return NotificationCompat.Builder(context, RUNNING_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Integral LLM · фоновое соединение")
            // Android requires this indicator for a long-running foreground service.
            // It carries no thinking/progress alerts or private chat text.
            .setContentIntent(intent(context, chatId, 41063)).setOngoing(true).setOnlyAlertOnce(true)
            .setSilent(true).setShowWhen(false).setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_DEFERRED)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET).build()
    }
    /** Only a winning SQLite finalizer calls this; never include answer text on the lock screen. */
    fun finished(context: Context, chatId: String, requestId: String, completed: Boolean) {
        val store = context.getSharedPreferences("answer_notification_outbox", Context.MODE_PRIVATE)
        val permissionDenied = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val manager = NotificationManagerCompat.from(context)
        if (OpenRouterUiBridge.viewing(chatId) ||
            !context.getSharedPreferences("app", Context.MODE_PRIVATE).getBoolean("response_notifications", true) ||
            permissionDenied || !manager.areNotificationsEnabled()) {
            try {
                if (AnswerNotificationOutbox.discard(store, requestId)) {
                    ChatDatabase(context).use { it.acknowledgeAnswerNotification(chatId, requestId) }
                }
            } catch (_: Exception) { /* Notification state must never turn a saved answer into an error. */ }
            return
        }
        val record = JSONObject().put("chat_id", chatId).put("completed", completed)
        try {
            AnswerNotificationOutbox.deliver(store, requestId, record) { pending ->
                channels(context)
                val id = requestId.hashCode() and 0x7fffffff
                manager.notify(requestId, id, NotificationCompat.Builder(context, COMPLETE_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(if (pending.getBoolean("completed")) "Ответ готов" else "Ответ остановлен — требуется внимание")
                .setContentText("Нажмите, чтобы открыть чат")
                .setContentIntent(intent(context, pending.getString("chat_id"), id)).setAutoCancel(true).setOnlyAlertOnce(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE).build())
            }
            ChatDatabase(context).use { it.acknowledgeAnswerNotification(chatId, requestId) }
        } catch (_: Exception) { /* Pending record survives permission races and process interruption. */ }
    }

    /** Called on next Activity start; it posts notifications, never repeats provider requests. */
    fun restorePending(context: Context) {
        val store = context.getSharedPreferences("answer_notification_outbox", Context.MODE_PRIVATE)
        val pending = linkedMapOf<String, JSONObject>()
        ChatDatabase(context).use { db ->
            db.pendingAnswerNotifications().forEach { pending[it.getString("request_id")] = it }
            AnswerNotificationOutbox.pending(store).forEach { (id, record) ->
                if (db.getChatTitle(record.getString("chat_id")) == null) AnswerNotificationOutbox.discard(store, id)
                else pending.putIfAbsent(id, record)
            }
        }
        pending.forEach { (id, record) ->
            finished(context, record.getString("chat_id"), id, record.getBoolean("completed"))
        }
    }
}
