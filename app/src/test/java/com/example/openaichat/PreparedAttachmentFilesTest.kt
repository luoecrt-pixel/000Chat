package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class PreparedAttachmentFilesTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private fun created(lease: PreparedAttachmentFiles.Lease, value: String = "derived"): File {
        val scope = lease.capture()
        return try { PreparedAttachmentFiles.derivative(File(context.filesDir, "attachments"), ".txt").apply { writeText(value) } }
        finally { scope.close() }
    }

    @Test fun failedPreparationDeletesOnlyUnownedDerivatives() {
        val directory = File(context.filesDir, "attachments").apply { mkdirs() }
        val original = File(directory, "original.jpg").apply { writeText("original bytes") }
        val lease = PreparedAttachmentFiles.lease(context)
        val orphan = created(lease)
        val saved = created(lease, "database or composer")
        lease.release(PreparedAttachmentFiles.Owners(setOf(saved.path)))
        assertFalse(orphan.exists())
        assertEquals("database or composer", saved.readText())
        assertEquals("original bytes", original.readText())
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertTrue(saved.exists()) // Promoted paths are no longer pending cleanup.
    }

    @Test fun runtimeOwnershipDefersCleanupUntilItsFinalizerReleasesTheFile() {
        val lease = PreparedAttachmentFiles.lease(context)
        val active = created(lease)
        lease.release(PreparedAttachmentFiles.Owners(emptySet(), setOf(active.path)))
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet(), setOf(active.path)) }
        assertTrue(active.exists())
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertFalse(active.exists())
    }

    @Test fun livePreparationAndFailedOwnershipLookupCannotDeleteAFile() {
        val lease = PreparedAttachmentFiles.lease(context)
        val file = created(lease)
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertTrue(file.exists())
        lease.defer()
        try {
            PreparedAttachmentFiles.cleanupPending(context) { throw IllegalStateException("database unavailable") }
            fail("A failed ownership snapshot must abort cleanup")
        } catch (_: IllegalStateException) { }
        assertTrue(file.exists())
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertFalse(file.exists())
    }

    @Test fun staleJournalCannotDeleteAnOriginalOrAPathOutsideAttachments() {
        val outside = File(context.filesDir, "derived_outside.txt").apply { writeText("outside") }
        val original = File(context.filesDir, "attachments/original.txt").apply { parentFile!!.mkdirs(); writeText("original") }
        context.getSharedPreferences("prepared_attachment_derivatives", Context.MODE_PRIVATE).edit()
            .putString("recovered", JSONArray(listOf(outside.path, original.path)).toString()).commit()
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertEquals("outside", outside.readText())
        assertEquals("original", original.readText())
    }

    @Test fun cleanupProtectsARequestThatFinishesDuringOwnershipRead() {
        val lease = PreparedAttachmentFiles.lease(context)
        val file = created(lease)
        PreparedAttachmentFiles.cleanupPending(context) {
            lease.defer()
            PreparedAttachmentFiles.Owners(emptySet())
        }
        assertTrue(file.exists())
        PreparedAttachmentFiles.cleanupPending(context) { PreparedAttachmentFiles.Owners(emptySet()) }
        assertFalse(file.exists())
    }
}
