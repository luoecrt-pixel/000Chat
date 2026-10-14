package com.example.openaichat

/**
 * shutdownNow interrupts the obsolete Create socket, but received bytes still
 * need an owned SQLite transaction or a compensating cancel. Clear that flag
 * only for an already-destroyed owner; a live request keeps normal interruption.
 */
internal class DestroyedActivityCallbackCleanup(private val ownerDestroyed: () -> Boolean) : AutoCloseable {
    private var restoreInterrupt = false

    fun enter() {
        if (ownerDestroyed()) restoreInterrupt = Thread.interrupted() || restoreInterrupt
    }

    override fun close() {
        if (restoreInterrupt) Thread.currentThread().interrupt()
    }
}
