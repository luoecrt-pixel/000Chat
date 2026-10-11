package com.example.openaichat

import android.content.SharedPreferences
import org.json.JSONObject
import java.io.IOException

/** At-least-once posting, using the same Android notification tag/id on every retry. */
internal object AnswerNotificationOutbox {
    private val lock = Any()
    private const val PENDING = "pending:"
    private const val SENT = "sent:"
    private const val SENT_AT = "sent_at:"
    private const val RETAIN_MS = 30L * 24 * 60 * 60 * 1000
    fun prune(store: SharedPreferences, now: Long = System.currentTimeMillis()) = synchronized(lock) {
        val all = store.all
        val editor = store.edit()
        all.keys.filter { it.startsWith(SENT) }.forEach { key ->
            val id = key.removePrefix(SENT)
            val at = all[SENT_AT + id] as? Long
            if (at == null) editor.putLong(SENT_AT + id, now) // Timestamp legacy acknowledgments once.
            else if (now - at > RETAIN_MS && !all.containsKey(PENDING + id)) {
                editor.remove(key).remove(SENT_AT + id)
            }
        }
        editor.commit()
    }

    fun deliver(store: SharedPreferences, requestId: String, record: JSONObject,
                post: (JSONObject) -> Unit) = synchronized(lock) {
        prune(store)
        if (store.getBoolean(SENT + requestId, false)) return@synchronized
        // Keep the record until both posting and durable acknowledgment succeed.
        if (!store.edit().putString(PENDING + requestId, record.toString()).commit()) {
            throw IOException("Could not persist pending answer notification")
        }
        post(record)
        if (!store.edit().putBoolean(SENT + requestId, true).putLong(SENT_AT + requestId, System.currentTimeMillis()).remove(PENDING + requestId).commit()) {
            throw IOException("Could not acknowledge answer notification")
        }
    }

    fun pending(store: SharedPreferences): List<Pair<String, JSONObject>> = synchronized(lock) {
        prune(store)
        store.all.entries.mapNotNull { (key, value) ->
            if (!key.startsWith(PENDING) || value !is String) return@mapNotNull null
            try { key.removePrefix(PENDING) to JSONObject(value) } catch (_: Exception) { null }
        }
    }
    fun discard(store: SharedPreferences, requestId: String) = synchronized(lock) {
        prune(store)
        store.edit().putBoolean(SENT + requestId, true).putLong(SENT_AT + requestId, System.currentTimeMillis()).remove(PENDING + requestId).commit()
    }
}
