package com.example.openaichat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Runtime never retains an Activity or a view; a recreated Activity can subscribe again. */
object OpenRouterUiBridge {
    private const val REASONING_THROTTLE_MS = 120L

    interface Host {
        fun responseChanged(chatId: String, requestId: String)
        fun reasoningChanged(chatId: String, requestId: String, reasoning: String)
        fun protocolWarning(chatId: String, requestId: String, warning: String)
        fun confirmTool(chatId: String, requestId: String, name: String, arguments: JSONObject): Boolean
        fun isViewing(chatId: String): Boolean
        fun ownsClient(client: ChatCompletionTransport): Boolean
    }

    private data class RequestKey(val chatId: String, val requestId: String)
    private class ReasoningState {
        var latest = ""
        var delivered: String? = null
        var pending = false
        var finished = false
    }

    @Volatile private var host = WeakReference<Host>(null)
    private val handler = Handler(Looper.getMainLooper())
    private val reasoningLock = Any()
    private val reasoningStates = linkedMapOf<RequestKey, ReasoningState>()
    private var reasoningFlushScheduled = false
    private var lastReasoningFlushUptimeMs = -REASONING_THROTTLE_MS

    private val flushReasoning = object : Runnable {
        override fun run() {
            synchronized(reasoningLock) {
                reasoningFlushScheduled = false
                val currentHost = host.get() ?: return
                val remaining = REASONING_THROTTLE_MS -
                    (SystemClock.uptimeMillis() - lastReasoningFlushUptimeMs)
                if (remaining > 0L) {
                    scheduleReasoningLocked(remaining)
                    return
                }
                val pending = reasoningStates.filterValues { it.pending }.toList()
                if (pending.isEmpty()) return
                lastReasoningFlushUptimeMs = SystemClock.uptimeMillis()
                for ((key, state) in pending) {
                    // Cancellation and a replacement request invalidate even a previously queued flush.
                    if (reasoningStates[key] !== state) continue
                    state.pending = false
                    if (state.latest != state.delivered) {
                        state.delivered = state.latest
                        currentHost.reasoningChanged(key.chatId, key.requestId, state.latest)
                    }
                    if (state.finished && reasoningStates[key] === state) {
                        reasoningStates.remove(key)
                        currentHost.responseChanged(key.chatId, key.requestId)
                    }
                }
                if (reasoningStates.values.any { it.pending }) scheduleReasoningLocked()
            }
        }
    }

    private fun scheduleReasoningLocked(delayMs: Long = 0L) {
        if (reasoningFlushScheduled) return
        reasoningFlushScheduled = true
        handler.postDelayed(flushReasoning, delayMs)
    }

    fun attach(value: Host) {
        synchronized(reasoningLock) {
            if (host.get() !== value) {
                // A replacement view gets its first snapshot immediately. Requeue the
                // callback for that subscriber rather than carrying the former UI's
                // scheduled/throttle state across a lifecycle transition.
                handler.removeCallbacks(flushReasoning)
                reasoningFlushScheduled = false
                lastReasoningFlushUptimeMs = -REASONING_THROTTLE_MS
            }
            host = WeakReference(value)
            if (reasoningStates.values.any { it.pending }) scheduleReasoningLocked()
        }
    }

    fun detach(value: Host) { if (host.get() === value) host.clear() }

    fun changed(chatId: String, requestId: String) {
        // Text and tool notifications do not consume a queued reasoning snapshot.
        handler.post { host.get()?.responseChanged(chatId, requestId) }
    }

    /** Register the request before streaming; absent entries reject late, cancelled deltas. */
    fun beginReasoning(chatId: String, requestId: String) {
        synchronized(reasoningLock) {
            reasoningStates[RequestKey(chatId, requestId)] = ReasoningState()
        }
    }

    fun reasoning(chatId: String, requestId: String, reasoning: String) {
        synchronized(reasoningLock) {
            val state = reasoningStates[RequestKey(chatId, requestId)] ?: return
            if (state.finished) return
            state.latest = reasoning
            state.pending = true
            scheduleReasoningLocked()
        }
    }

    /** Deliver the last value before the terminal card reload, then retire the request. */
    fun finishReasoning(chatId: String, requestId: String, latest: String) {
        synchronized(reasoningLock) {
            val state = reasoningStates[RequestKey(chatId, requestId)]
            if (state == null) {
                changed(chatId, requestId)
                return
            }
            state.latest = latest
            state.pending = true
            state.finished = true
            scheduleReasoningLocked()
        }
    }

    fun cancelReasoning(chatId: String, requestId: String) {
        synchronized(reasoningLock) { reasoningStates.remove(RequestKey(chatId, requestId)) }
    }

    fun clearReasoning(chatId: String, requestId: String) = cancelReasoning(chatId, requestId)
    fun warning(chatId: String, requestId: String, warning: String) {
        handler.post { host.get()?.protocolWarning(chatId, requestId, warning) }
    }
    fun confirm(chatId: String, requestId: String, name: String, arguments: JSONObject) =
        host.get()?.confirmTool(chatId, requestId, name, arguments) == true
    fun viewing(chatId: String) = host.get()?.isViewing(chatId) == true
    fun ownsClient(client: ChatCompletionTransport) = host.get()?.ownsClient(client) == true
}
