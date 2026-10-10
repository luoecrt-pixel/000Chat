package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterZipCleanupTest {
    @Test fun oversizedSecondEntryRemovesOnlyItsOperationFiles() {
        val context = RuntimeEnvironment.getApplication()
        val ownedDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val existing = File(ownedDir, "existing-ready-" + System.nanoTime() + ".txt")
            .apply { writeText("must persist") }
        val initial = ownedDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val zip = File(context.cacheDir, "failure-" + System.nanoTime() + ".zip")
        try {
            ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(ZipEntry("good.txt"))
                stream.write("Previously successful entry".toByteArray())
                stream.closeEntry()
                stream.putNextEntry(ZipEntry("too-large.txt"))
                val chunk = ByteArray(64 * 1024) { 65 }
                repeat(401) { stream.write(chunk) } // >25 MiB after inflation
                stream.closeEntry()
            }
            assertThrows(IOException::class.java) {
                OpenRouterAttachmentStore.extractZipFile(context, zip, "failure.zip")
            }
            val after = ownedDir.listFiles()?.map { it.name }?.toSet().orEmpty()
            assertEquals("Partial zip_ and txt_ files must be removed", initial, after)
            assertTrue(existing.isFile)
            assertEquals("must persist", existing.readText())
        } finally {
            zip.delete()
            existing.delete()
        }
    }
}
