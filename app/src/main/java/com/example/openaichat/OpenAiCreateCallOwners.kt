package com.example.openaichat

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/** A live pre-ID Create is an owner, even while its Activity is stopped. */
internal object OpenAiCreateCallOwners {
    private data class Owner(val chatId: String, val requestId: String)
    private val calls = ConcurrentHashMap<Owner, Call>()
    private val resumeLeases = mutableMapOf<Owner, Any>()

    @Synchronized fun register(chatId: String, requestId: String, call: Call) {
        check(Owner(chatId, requestId) !in resumeLeases)
        calls[Owner(chatId, requestId)] = call
    }

    /** Keep a stopped/recreated Activity able to cancel the current tool-round HTTP call. */
    @Synchronized fun replace(chatId: String, requestId: String, previous: Call, next: Call): Boolean =
        calls.replace(Owner(chatId, requestId), previous, next)

    @Synchronized fun release(chatId: String, requestId: String, call: Call) {
        calls.remove(Owner(chatId, requestId), call)
    }

    fun call(chatId: String, requestId: String): Call? = calls[Owner(chatId, requestId)]

    fun hasLiveCall(chatId: String, requestId: String): Boolean =
        calls[Owner(chatId, requestId)]?.isCanceled() == false

    fun isRegistered(chatId: String, requestId: String): Boolean =
        calls.containsKey(Owner(chatId, requestId))

    /** A cancelled reader owns its received bytes until its finally releases. */
    @Synchronized fun acquireResume(chatId: String, requestId: String): Any? {
        val owner = Owner(chatId, requestId)
        if (calls.containsKey(owner) || owner in resumeLeases) return null
        return Any().also { resumeLeases[owner] = it }
    }

    @Synchronized fun releaseResume(chatId: String, requestId: String, lease: Any) {
        val owner = Owner(chatId, requestId)
        if (resumeLeases[owner] === lease) resumeLeases.remove(owner)
    }
}
