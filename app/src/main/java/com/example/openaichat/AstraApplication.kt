package com.example.openaichat

import android.app.Application

class AstraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A fresh installation has no durable notifications to restore. Opening
        // SQLite here would create an empty DB on a second thread, racing first-run
        // setup and legacy migrations. An existing DB or pending outbox is replayed.
        val outbox = getSharedPreferences("answer_notification_outbox", MODE_PRIVATE)
        if (getDatabasePath("astra_chats.db").isFile || outbox.all.keys.any { it.startsWith("pending:") }) {
            Thread({ try { ResponseNotifications.restorePending(applicationContext) } catch (_: Exception) { /* Retry on next Activity start. */ } }, "answer-notification-replay").start()
        }
    }
}
