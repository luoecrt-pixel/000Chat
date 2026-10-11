package com.example.openaichat

import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

internal object KeyedFileLocks {
    private class Entry(val lock: ReentrantLock = ReentrantLock(), var users: Int = 0)
    private val entries = mutableMapOf<String, Entry>()
    fun busy(key: String) = synchronized(entries) { entries.containsKey(key) }
    fun ifIdle(key: String, action: () -> Unit): Boolean {
        val entry = synchronized(entries) {
            if (entries.containsKey(key)) null else Entry(users = 1).also { it.lock.lock(); entries[key] = it }
        } ?: return false
        try { action(); return true }
        finally {
            entry.lock.unlock()
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
    fun <T> with(key: String, active: () -> Boolean, action: () -> T): T {
        val entry = synchronized(entries) { entries.getOrPut(key) { Entry() }.also { it.users++ } }
        var acquired = false
        try {
            while (!acquired) {
                if (!active()) throw IOException("File read cancelled while waiting")
                acquired = entry.lock.tryLock(100, TimeUnit.MILLISECONDS)
            }
            if (!active()) throw IOException("File read cancelled")
            return action()
        } finally {
            if (acquired) entry.lock.unlock()
            synchronized(entries) { if (--entry.users == 0) entries.remove(key) }
        }
    }
}
