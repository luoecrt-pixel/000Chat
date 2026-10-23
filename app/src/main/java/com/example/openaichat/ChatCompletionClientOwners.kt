package com.example.openaichat

import java.lang.ref.WeakReference
import java.util.IdentityHashMap

/** Activity ownership and application-owned work share one transport lifetime. */
object ChatCompletionClientOwners {
    private val owners = IdentityHashMap<ChatCompletionTransport, Int>()
    // Reject resurrection without retaining every previously used transport for the app lifetime.
    private val closed = mutableListOf<WeakReference<ChatCompletionTransport>>()

    @Synchronized fun retain(client: ChatCompletionTransport) {
        closed.removeAll { it.get() == null }
        check(closed.none { it.get() === client }) { "Transport is already closed" }
        owners[client] = (owners[client] ?: 0) + 1
    }

    fun release(client: ChatCompletionTransport) {
        val close = synchronized(this) {
            val count = owners[client] ?: return
            if (count > 1) {
                owners[client] = count - 1
                false
            } else {
                owners.remove(client)
                closed.add(WeakReference(client))
                true
            }
        }
        if (close) client.close()
    }
}
