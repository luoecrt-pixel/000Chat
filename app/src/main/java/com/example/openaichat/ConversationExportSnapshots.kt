package com.example.openaichat

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.util.UUID

/** Private, disposable export copies. Chat originals are never cleanup candidates. */
internal class ConversationExportSnapshots(
    private val directory: File,
    private val livePaths: MutableSet<String> = processLivePaths
) {
    companion object {
        const val RETENTION_MS = 24L * 60 * 60 * 1000
        const val MAX_SCANNED = 256
        const val MAX_DELETED = 32
        private val processLivePaths = mutableSetOf<String>()
        private val ownedName = Regex("(?:dialog|message)_[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.(?:md|txt)")
    }

    private fun owned(path: String): File? = try {
        val file = File(path).absoluteFile.toPath().normalize().toFile()
        file.takeIf { it.parentFile == directory.absoluteFile.toPath().normalize().toFile() && ownedName.matches(it.name) }
    } catch (_: InvalidPathException) { null }

    fun create(format: DialogConversationExporter.Format): File = synchronized(livePaths) {
        if (!directory.exists()) directory.mkdirs()
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) throw IOException("Export directory unavailable")
        val file = File(directory, "dialog_${UUID.randomUUID()}.${format.extension}").absoluteFile
        if (!file.createNewFile()) throw IOException("Export snapshot unavailable")
        livePaths.add(file.path)
        file
    }

    /** Restore the pending picker before scheduling cleanup, including after process death. */
    fun retain(path: String?): String? = synchronized(livePaths) {
        val file = path?.let(::owned) ?: return@synchronized null
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) return@synchronized null
        livePaths.add(file.path)
        file.path
    }

    fun requireFile(path: String): File = synchronized(livePaths) {
        val file = owned(path) ?: throw IOException("Invalid export snapshot")
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS) || !Files.isRegularFile(file.toPath(), NOFOLLOW_LINKS)) throw IOException("Export snapshot missing")
        file
    }

    fun delete(path: String?) = synchronized(livePaths) {
        val file = path?.let(::owned) ?: return@synchronized
        if (Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) file.delete()
        livePaths.remove(file.path)
    }

    /** Bounded background maintenance; active writers/pickers/copies are pinned in this process. */
    fun cleanup(now: Long = System.currentTimeMillis()): Int = synchronized(livePaths) {
        if (!Files.isDirectory(directory.toPath(), NOFOLLOW_LINKS)) return@synchronized 0
        var scanned = 0
        var deleted = 0
        try {
            Files.newDirectoryStream(directory.toPath()).use { entries ->
                for (entry in entries) {
                    if (scanned++ >= MAX_SCANNED || deleted >= MAX_DELETED) break
                    val file = owned(entry.toString()) ?: continue
                    if (file.path in livePaths || !Files.isRegularFile(entry, NOFOLLOW_LINKS)) continue
                    val modified = file.lastModified()
                    if (modified > 0 && modified <= now - RETENTION_MS && file.delete()) deleted++
                }
            }
        } catch (_: Exception) { /* Includes failed directory iteration; maintenance must not prevent opening a chat. */ }
        deleted
    }
}
