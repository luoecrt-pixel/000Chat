package com.example.openaichat

import android.app.Application

class AstraApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // WorkManager/service process starts also replay pending notifications, never API requests.
        Thread({ try { ResponseNotifications.restorePending(applicationContext) } catch (_: Exception) { /* Retry on next Activity start. */ } }, "answer-notification-replay").start()
    }
}
