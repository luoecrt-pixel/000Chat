package com.example.openaichat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OpenAiFileAnalysisFormatsTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Test fun nativeOfficeUploadsOriginalArchiveWithOriginalNameAndMime() {
        val dir = File(context.cacheDir, "analysis-native-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (extension in listOf("docx", "pptx", "xlsx")) {
                val original = office(dir, extension)
                val originalBytes = original.readBytes()
                val mime = mimeForFilename(original.name)
                val attachment = OpenRouterAttachmentStore.classifyLocalFile(context, original, original.name, mime)
                assertTrue(File(attachment.textPath!!).isFile)
                OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { source, name, uploadMime ->
                    assertEquals(original.canonicalFile, source.canonicalFile)
                    assertArrayEquals(originalBytes, source.readBytes())
                    assertEquals(original.name, name)
                    assertEquals(mime, uploadMime)
                    "file_native_$extension"
                }
                assertArrayEquals(originalBytes, original.readBytes())
                assertTrue(File(attachment.textPath!!).isFile)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun openDocumentUploadsCompleteExtractedTextWithTxtSuffixWithoutChangingOriginal() {
        val dir = File(context.cacheDir, "analysis-odf-${System.nanoTime()}").apply { mkdirs() }
        try {
            for (extension in listOf("odt", "ods", "odp")) {
                val original = office(dir, extension)
                val originalBytes = original.readBytes()
                val attachment = OpenRouterAttachmentStore.classifyLocalFile(context, original, original.name, mimeForFilename(original.name))
                val extracted = File(attachment.textPath!!)
                assertTrue(extracted.readText().contains("Full original document text"))
                OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { source, name, mime ->
                    assertEquals(extracted.canonicalFile, source.canonicalFile)
                    assertArrayEquals(extracted.readBytes(), source.readBytes())
                    assertEquals("report.$extension.txt", name)
                    assertEquals("text/plain", mime)
                    "file_extracted_$extension"
                }
                assertArrayEquals(originalBytes, original.readBytes())
                assertTrue(extracted.isFile)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun fileAnalysisReusesExactPreviouslyUploadedUtf8FileAcrossTurnsAndAccountsStaySeparate() {
        val dir = File(context.cacheDir, "analysis-cache-${System.nanoTime()}").apply { mkdirs() }
        val store = context.getSharedPreferences("analysis-upload-${System.nanoTime()}", Context.MODE_PRIVATE)
        val cache = OpenAiFileUploadCache(store)
        try {
            val original = File(dir, "note.txt").apply { writeText("Complete UTF-8 — no extra upload", Charsets.UTF_8) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(context, original, original.name, "text/plain")
            var uploads = 0
            val names = mutableListOf<String>()
            fun upload(file: File, name: String, mime: String, credential: String) =
                cache.getOrUpload(file, name, mime, credential, { true }, "user_data") {
                    uploads++
                    names += name
                    "file_upload_$uploads"
                }
            val firstId = upload(original, "note.txt", "text/plain", "account-a")
            fun prepare(credential: String) = OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { file, name, mime ->
                upload(file, name, mime, credential)
            }
            assertEquals(firstId, prepare("account-a")[original.path])
            assertEquals(firstId, prepare("account-a")[original.path])
            assertEquals(1, uploads)
            assertEquals(listOf("note.txt"), names)
            assertNotEquals(firstId, prepare("account-b")[original.path])
            assertEquals(2, uploads)
            assertTrue(original.isFile)
        } finally { cache.clear(); dir.deleteRecursively() }
    }

    private fun user(attachment: OpenRouterLocalAttachment) =
        JSONObject().put("role", "user").put("attachments", JSONArray().put(attachment.toJson()))

    private fun mimeForFilename(name: String): String = when (name.substringAfterLast('.')) {
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "odt" -> "application/vnd.oasis.opendocument.text"
        "ods" -> "application/vnd.oasis.opendocument.spreadsheet"
        "odp" -> "application/vnd.oasis.opendocument.presentation"
        else -> "application/octet-stream"
    }

    private fun office(dir: File, extension: String): File = File(dir, "report.$extension").apply {
        val entries = when (extension) {
            "docx" -> mapOf("word/document.xml" to "<document><body><p><t>Full original document text</t></p></body></document>")
            "pptx" -> mapOf("ppt/slides/slide1.xml" to "<slide><p><t>Full original document text</t></p></slide>")
            "xlsx" -> mapOf(
                "xl/workbook.xml" to "<workbook xmlns:r=\"urn:test\"><sheets><sheet name=\"Data\" r:id=\"r1\"/></sheets></workbook>",
                "xl/_rels/workbook.xml.rels" to "<Relationships><Relationship Id=\"r1\" Target=\"worksheets/sheet1.xml\"/></Relationships>",
                "xl/worksheets/sheet1.xml" to "<worksheet><sheetData><row r=\"1\"><c r=\"A1\" t=\"inlineStr\"><is><t>Full original document text</t></is></c></row></sheetData></worksheet>"
            )
            else -> mapOf("content.xml" to "<document><body><p>Full original document text</p></body></document>")
        }
        ZipOutputStream(outputStream()).use { zip -> entries.forEach { (name, text) ->
            zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
        } }
    }
}
