package com.example.openaichat

import okhttp3.Call
import java.util.concurrent.ConcurrentHashMap

/** A live pre-ID Create is an owner, even while its Activity is stopped. */
internal object OpenAiCreateCallOwners {
    private data class Owner(val chatId: String, val requestId: String)
    private val calls = ConcurrentHashMap<Owner, Call>()

    fun register(chatId: String, requestId: String, call: Call) {
        calls[Owner(chatId, requestId)] = call
    }

    fun release(chatId: String, requestId: String, call: Call) {
        calls.remove(Owner(chatId, requestId), call)
    }

    fun hasLiveCall(chatId: String, requestId: String): Boolean =
        calls[Owner(chatId, requestId)]?.isCanceled() == false

    fun isRegistered(chatId: String, requestId: String): Boolean =
        calls.containsKey(Owner(chatId, requestId))
}
