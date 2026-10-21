package com.example.openaichat

import android.net.Uri
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
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenRouterZipCleanupTest {
    @Test fun pickerAndFileExtractionShareSkippedNamesAndOriginalBytes() {
        val context = RuntimeEnvironment.getApplication()
        val bytes = ByteArrayOutputStream().also { sink ->
            ZipOutputStream(sink).use { zip ->
                for ((name, text) in listOf("nested/kept.txt" to "полный текст", "inner.zip" to "nested", "ignored.bin" to "binary")) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(text.toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }
        }.toByteArray()
        val zip = File(context.cacheDir, "parity-" + System.nanoTime() + ".zip").apply { writeBytes(bytes) }
        var sourceClosed = false
        val source = object : ByteArrayInputStream(bytes) {
            override fun close() { sourceClosed = true; super.close() }
        }
        val uriReports = mutableListOf<String>()
        val fileReports = mutableListOf<String>()
        val prepared = mutableListOf<OpenRouterLocalAttachment>()
        try {
            val uriItems = OpenRouterAttachmentStore.extractZip(context, Uri.parse("content://test/parity.zip"), "parity.zip",
                uriReports::add, AttachmentInputStreamSource { _, _ -> source }).also { prepared.addAll(it) }
            val fileItems = OpenRouterAttachmentStore.extractZipFile(context, zip, "parity.zip", fileReports::add)
                .also { prepared.addAll(it) }
            assertTrue(sourceClosed)
            assertEquals(fileReports, uriReports)
            assertTrue(uriReports.contains("Пропущен вложенный архив/бинарник: inner.zip"))
            assertTrue(uriReports.contains("Неподдерживаемый файл пропущен: ignored.bin"))
            assertEquals(1, uriItems.size)
            assertEquals("kept.txt", uriItems.single().name)
            assertEquals(fileItems.single().sha256, uriItems.single().sha256)
            assertEquals("полный текст", File(uriItems.single().path).readText(Charsets.UTF_8))
        } finally {
            prepared.forEach { item ->
                File(item.path).delete()
                item.textPath?.let { File(it).delete() }
            }
            zip.delete()
        }
    }

    @Test fun skippedEntryStopsAtAggregateLimitBeforeReportingItAndCleansEarlierArtifacts() {
        val context = RuntimeEnvironment.getApplication()
        val ownedDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val initial = ownedDir.listFiles()?.map { it.name }?.toSet().orEmpty()
        val zip = File(context.cacheDir, "aggregate-" + System.nanoTime() + ".zip")
        val reports = mutableListOf<String>()
        try {
            ZipOutputStream(zip.outputStream()).use { stream ->
                stream.putNextEntry(ZipEntry("good.txt"))
                stream.write('a'.code)
                stream.closeEntry()
                // Small compressed input exercises the real 200 MiB aggregate
                // bound without ever allocating the inflated archive in memory.
                val chunk = ByteArray(64 * 1024) { 65 }
                repeat(8) { index ->
                    stream.putNextEntry(ZipEntry("ignored-$index.zip"))
                    repeat((OpenRouterAttachmentStore.MAX_ZIP_ENTRY_BYTES / chunk.size).toInt()) { stream.write(chunk) }
                    stream.closeEntry()
                }
            }
            val error = assertThrows(IOException::class.java) {
                OpenRouterAttachmentStore.extractZipFile(context, zip, "aggregate.zip", reports::add)
            }
            assertTrue(error.message.orEmpty().contains("200 МБ"))
            assertEquals(7, reports.count { it.startsWith("Пропущен вложенный архив/бинарник:") })
            assertTrue(reports.none { it.contains("ignored-7.zip") })
            assertEquals(initial, ownedDir.listFiles()?.map { it.name }?.toSet().orEmpty())
        } finally { zip.delete() }
    }

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
