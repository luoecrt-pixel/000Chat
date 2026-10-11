package com.example.openaichat

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat

/** Owns a visible foreground lifetime, separate from the OpenAI recovery Worker. */
class OpenRouterResponseService : Service() {
    companion object { const val EXTRA_REQUEST_ID = "request_id" }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val id = intent?.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val chat = OpenRouterResponseRuntime.chatFor(id).orEmpty()
        try {
            ServiceCompat.startForeground(this, 41063, ResponseNotifications.running(this, chat, OpenRouterResponseRuntime.count()),
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0)
        } catch (e: Exception) {
            OpenRouterResponseRuntime.cancel(id, "Android не разрешил запустить фоновую службу: ${e.javaClass.simpleName}")
            OpenRouterResponseRuntime.launch(id)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        // Even a stale/redelivered intent must satisfy the startForegroundService deadline.
        if (chat.isBlank()) { stopSelf(startId); return START_NOT_STICKY }
        OpenRouterResponseRuntime.launch(id)
        return START_NOT_STICKY // Chat Completions has no safe provider-side stream-resume ID.
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        OpenRouterResponseRuntime.cancelBackground("Android остановил фоновую службу по своему лимиту времени")
        stopSelf()
    }
}
