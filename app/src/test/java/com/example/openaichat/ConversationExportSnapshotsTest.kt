package com.example.openaichat

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.UUID

class ConversationExportSnapshotsTest {
    @get:Rule val temp = TemporaryFolder()
    private val now = 1_800_000_000_000L
    private fun old(directory: File, prefix: String = "dialog"): File =
        File(directory, "${prefix}_${UUID.randomUUID()}.txt").apply {
            writeText("private partial conversation")
            assertTrue(setLastModified(now - ConversationExportSnapshots.RETENTION_MS - 1))
        }

    @Test fun coldProcessRemovesInterruptedCopiesButPreservesRestoredPendingPicker() {
        val directory = temp.newFolder("exports")
        val previousProcess = ConversationExportSnapshots(directory, mutableSetOf())
        val pending = previousProcess.create(DialogConversationExporter.Format.MARKDOWN).apply {
            writeText("pending conversation")
            setLastModified(now - ConversationExportSnapshots.RETENTION_MS - 1)
        }
        val interrupted = previousProcess.create(DialogConversationExporter.Format.TEXT).apply {
            writeText("partially written conversation")
            setLastModified(now - ConversationExportSnapshots.RETENTION_MS - 1)
        }
        val legacy = old(directory, "message")
        val coldProcess = ConversationExportSnapshots(directory, mutableSetOf())
        assertEquals(pending.path, coldProcess.retain(pending.path))
        assertEquals(2, coldProcess.cleanup(now))
        assertTrue(pending.exists())
        assertFalse(interrupted.exists())
        assertFalse(legacy.exists())
        coldProcess.delete(pending.path)
        assertFalse(pending.exists())
    }

    @Test fun liveWriterAndCopyAreProtectedAcrossStoreInstances() {
        val directory = temp.newFolder("exports")
        val writer = ConversationExportSnapshots(directory)
        val file = writer.create(DialogConversationExporter.Format.TEXT).apply {
            writeText("still copying")
            setLastModified(now - ConversationExportSnapshots.RETENTION_MS - 1)
        }
        val recreatedActivity = ConversationExportSnapshots(directory)
        assertEquals(0, recreatedActivity.cleanup(now))
        assertEquals(file, recreatedActivity.requireFile(file.path))
        writer.delete(file.path)
        assertFalse(file.exists())
    }

    @Test fun recentUnknownOutsideAndSymlinkTargetsAreNotRemoved() {
        val directory = temp.newFolder("exports")
        val store = ConversationExportSnapshots(directory, mutableSetOf())
        val recent = old(directory).apply { setLastModified(now) }
        val unknown = File(directory, "original.txt").apply { writeText("original"); setLastModified(1) }
        val outside = old(temp.root)
        val link = File(directory, "dialog_${UUID.randomUUID()}.txt")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertNull(store.retain(outside.path))
        assertNull(store.retain(link.path))
        assertNull(store.retain("invalid\u0000path"))
        store.delete(outside.path)
        assertEquals(0, store.cleanup(now))
        assertTrue(recent.exists())
        assertTrue(unknown.exists())
        assertTrue(outside.exists())
        assertTrue(Files.isSymbolicLink(link.toPath()))
        try { store.requireFile(link.path); fail("Must reject symlink") } catch (_: IOException) {}
    }

    @Test fun cleanupHasDeletionBudgetAndCanContinueOnNextLaunch() {
        val directory = temp.newFolder("exports")
        repeat(ConversationExportSnapshots.MAX_DELETED + 9) { old(directory) }
        val store = ConversationExportSnapshots(directory, mutableSetOf())
        assertEquals(ConversationExportSnapshots.MAX_DELETED, store.cleanup(now))
        assertEquals(9, store.cleanup(now))
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
