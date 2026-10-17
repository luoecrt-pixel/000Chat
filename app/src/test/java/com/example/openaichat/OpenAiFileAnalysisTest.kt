package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringWriter
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.Base64

class OpenAiFileAnalysisTest {
    @Test fun mixedPhotosAndTextOnlyPrepareTheTextAndKeepOriginalVisionBytesInOrder() {
        val dir = kotlin.io.path.createTempDirectory("analysis-vision-").toFile()
        try {
            val bytes = listOf(png(3, 2, 0xffd52c36.toInt()), png(4, 3, 0xff225fc7.toInt()))
            assertFalse(bytes[0].contentEquals(bytes[1]))
            val originals = bytes.mapIndexed { index, value -> File(dir, "photo-$index.png").apply { writeBytes(value) } }
            val photos = originals.map { OpenRouterAttachmentStore.classifyLocalFile(it, it.name, "image/png") }
            val text = File(dir, "note.txt").apply { writeText("full document contents") }
            val document = OpenRouterAttachmentStore.classifyLocalFile(text, text.name, "text/plain")
            val message = JSONObject().put("role", "user").put("text", "Compare these with my document")
                .put("image_path", originals[0].path).put("image_file_id", "file_previous_account_image")
                .put("attachments", JSONArray().apply { photos.forEach { put(it.toJson()) }; put(document.toJson()) })
            val uploaded = mutableListOf<String>()
            val container = OpenAiFileAnalysis.prepare(listOf(message), { true }) { source, name, mime ->
                assertFalse("A mixed document request must not upload its photos", mime.startsWith("image/"))
                assertEquals("note.txt", name)
                assertEquals(text.canonicalFile, source.canonicalFile)
                assertEquals(text.readText(), source.readText())
                uploaded.add(source.path)
                "file_current_document"
            }
            assertEquals(1, uploaded.size)
            assertEquals(setOf(text.path), container.keys)
            val wire = wire(OpenAiLocalContextEncoder.encode(listOf(message), container))
            val parts = wire.getJSONObject(0).getJSONArray("content")
            val images = (0 until parts.length()).map { parts.getJSONObject(it) }.filter { it.optString("type") == "input_image" }
            assertEquals(2, images.size)
            images.forEachIndexed { index, image ->
                assertFalse(image.has("file_id"))
                assertArrayEquals(bytes[index], Base64.getDecoder().decode(image.getString("image_url").substringAfter(',')))
                assertArrayEquals(bytes[index], originals[index].readBytes())
            }
            assertTrue(wire.toString().contains("file_current_document"))
            assertFalse(wire.toString().contains("file_previous_account_image"))
        } finally { dir.deleteRecursively() }
    }

    @Test fun imageOnlyHistoryDoesNotPrepareAnyImplicitContainerUpload() {
        val dir = kotlin.io.path.createTempDirectory("analysis-image-only-").toFile()
        try {
            val original = File(dir, "photo.png").apply { writeBytes(png(3, 2, 0xffd52c36.toInt())) }
            val image = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "image/png")
            val message = JSONObject().put("role", "user").put("attachments", JSONArray().put(image.toJson()))
            val container = OpenAiFileAnalysis.prepare(listOf(message), { true }) { _, _, _ ->
                throw AssertionError("Vision photos must not trigger implicit file uploads")
            }
            assertTrue(container.isEmpty())
            val block = wire(OpenAiLocalContextEncoder.encode(listOf(message), container))
                .getJSONObject(0).getJSONArray("content").getJSONObject(0)
            assertEquals("input_image", block.getString("type"))
            assertArrayEquals(original.readBytes(), Base64.getDecoder().decode(block.getString("image_url").substringAfter(',')))
        } finally { dir.deleteRecursively() }
    }

    @Test fun invalidVisionSourceIsRejectedBeforeUploadingTheFollowingDocument() {
        for (remove in listOf(false, true)) {
            for (imageFirst in listOf(false, true)) {
            val dir = kotlin.io.path.createTempDirectory("analysis-invalid-original-").toFile()
            try {
                val photo = File(dir, "photo.png").apply { writeBytes(png(3, 2, 0xffd52c36.toInt())) }
                val image = OpenRouterAttachmentStore.classifyLocalFile(photo, photo.name, "image/png")
                val text = File(dir, "note.txt").apply { writeText("document") }
                val document = OpenRouterAttachmentStore.classifyLocalFile(text, text.name, "text/plain")
                val message = JSONObject().put("role", "user").put("attachments",
                    JSONArray().apply {
                        (if (imageFirst) listOf(image, document) else listOf(document, image)).forEach { put(it.toJson()) }
                    })
                if (remove) assertTrue(photo.delete()) else photo.writeBytes(png(3, 2, 0xff225fc7.toInt()))
                assertThrows(IOException::class.java) {
                    OpenAiFileAnalysis.prepare(listOf(message), { true }) { _, _, _ ->
                        throw AssertionError("Source preflight must fail before any upload")
                    }
                }
            } finally { dir.deleteRecursively() }
            }
        }
    }

    @Test fun byteIdenticalUtf8UsesOriginalAndKeepsTextAndCodeFilenames() {
        val dir = kotlin.io.path.createTempDirectory("analysis-utf8-original-").toFile()
        try {
            for (name in listOf("note.txt", "code.kt", "table.csv", "README")) {
                val original = File(dir, name).apply { writeText("UTF-8 content — 42", Charsets.UTF_8) }
                val attachment = OpenRouterAttachmentStore.classifyLocalFile(original, name, "text/plain")
                val derivative = File(attachment.textPath!!)
                assertNotEquals(original.canonicalPath, derivative.canonicalPath)
                assertArrayEquals(original.readBytes(), derivative.readBytes())
                var calls = 0
                val message = user(attachment)
                val ids = OpenAiFileAnalysis.prepare(listOf(message, message), { true }) { source, uploadName, mime ->
                    calls++
                    assertEquals(original.canonicalFile, source.canonicalFile)
                    assertEquals(name, uploadName)
                    assertEquals(attachment.mime, mime)
                    "file_exact_original"
                }
                assertEquals(mapOf(original.path to "file_exact_original"), ids)
                assertEquals("Repeated references share one prepared path", 1, calls)
                assertTrue(original.isFile)
                assertTrue(derivative.isFile)
            }
        } finally { dir.deleteRecursively() }
    }

    @Test fun legacyEncodingUploadsUtf8DerivativeAndPreservesOriginalBytesAndName() {
        val dir = kotlin.io.path.createTempDirectory("analysis-legacy-encoding-").toFile()
        try {
            val text = "Привет, полный документ"
            val bytes = text.toByteArray(Charset.forName("windows-1251"))
            val original = File(dir, "note.txt").apply { writeBytes(bytes) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "text/plain")
            assertEquals("windows-1251", attachment.textEncoding)
            OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { source, name, mime ->
                assertEquals(File(attachment.textPath!!).canonicalFile, source.canonicalFile)
                assertArrayEquals(text.toByteArray(Charsets.UTF_8), source.readBytes())
                assertEquals("note.txt", name)
                assertEquals("text/plain", mime)
                "file_transcoded"
            }
            assertArrayEquals(bytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun equalLengthDifferentEncodingNeverChoosesRawOriginal() {
        val dir = kotlin.io.path.createTempDirectory("analysis-equal-length-").toFile()
        try {
            // The Cyrillic character is two bytes in UTF-16LE and also two bytes in UTF-8.
            val originalBytes = "а".toByteArray(Charsets.UTF_16LE)
            val original = File(dir, "equal.txt").apply { writeBytes(originalBytes) }
            val normalized = File(dir, "normalized.utf8")
            TextFileFormatPolicy.normalize(original, normalized, false, "UTF-16LE")
            assertEquals(original.length(), normalized.length())
            assertFalse(original.readBytes().contentEquals(normalized.readBytes()))
            val attachment = OpenRouterLocalAttachment("equal", "equal.txt", "text", original.path, "text/plain",
                original.length(), sha256(original), normalized.path, "UTF-16LE")
            OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { source, _, _ ->
                assertEquals(normalized.canonicalFile, source.canonicalFile)
                assertArrayEquals("а".toByteArray(Charsets.UTF_8), source.readBytes())
                "file_utf8"
            }
            assertArrayEquals(originalBytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun utf8BomRequiresNormalizedDerivativeInsteadOfOriginal() {
        val dir = kotlin.io.path.createTempDirectory("analysis-bom-").toFile()
        try {
            val bytes = byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()) + "text".toByteArray()
            val original = File(dir, "bom.txt").apply { writeBytes(bytes) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "text/plain")
            OpenAiFileAnalysis.prepare(listOf(user(attachment)), { true }) { source, _, _ ->
                assertEquals(File(attachment.textPath!!).canonicalFile, source.canonicalFile)
                assertArrayEquals("text".toByteArray(), source.readBytes())
                "file_bom_removed"
            }
            assertArrayEquals(bytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun disappearingRequiredDerivativeFailsBeforeUploadAndKeepsOriginal() {
        val dir = kotlin.io.path.createTempDirectory("analysis-missing-derivative-").toFile()
        try {
            val bytes = "Привет".toByteArray(Charset.forName("windows-1251"))
            val original = File(dir, "note.txt").apply { writeBytes(bytes) }
            val attachment = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "text/plain")
            var removed = false
            assertThrows(IOException::class.java) {
                OpenAiFileAnalysis.prepare(listOf(user(attachment)), {
                    // materialize ran already; emulate removal before source selection.
                    if (!removed) { assertTrue(File(attachment.textPath!!).delete()); removed = true }
                    true
                }) { _, _, _ -> throw AssertionError("Encoded original must not be uploaded as normalized text") }
            }
            assertTrue(removed)
            assertArrayEquals(bytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun missingLegacyDerivativeIsRebuiltUsingPersistedExplicitEncoding() {
        val dir = kotlin.io.path.createTempDirectory("analysis-rebuild-encoding-").toFile()
        try {
            val text = "Философия"
            val originalBytes = text.toByteArray(Charset.forName("KOI8-R"))
            val original = File(dir, "note.txt").apply { writeBytes(originalBytes) }
            val initiallyGuessed = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "text/plain")
            val selected = OpenRouterAttachmentStore.withTextEncoding(initiallyGuessed, "KOI8-R")
            assertTrue(File(selected.textPath!!).delete())
            OpenAiFileAnalysis.prepare(listOf(user(selected)), { true }) { source, name, _ ->
                assertNotEquals(original.canonicalFile, source.canonicalFile)
                assertArrayEquals(text.toByteArray(Charsets.UTF_8), source.readBytes())
                assertEquals("note.txt", name)
                "file_selected_encoding"
            }
            assertArrayEquals(originalBytes, original.readBytes())
        } finally { dir.deleteRecursively() }
    }

    @Test fun skippingContainerUploadsStillRejectsMissingOrModifiedOriginalVisionBytes() {
        for (remove in listOf(false, true)) {
            val dir = kotlin.io.path.createTempDirectory("analysis-invalid-vision-").toFile()
            try {
                val original = File(dir, "photo.png").apply { writeBytes(png(3, 2, 0xffd52c36.toInt())) }
                val image = OpenRouterAttachmentStore.classifyLocalFile(original, original.name, "image/png")
                val message = JSONObject().put("role", "user").put("attachments", JSONArray().put(image.toJson()))
                val container = OpenAiFileAnalysis.prepare(listOf(message), { true }) { _, _, _ ->
                    throw AssertionError("Images are not container uploads")
                }
                if (remove) assertTrue(original.delete()) else original.writeBytes(png(3, 2, 0xff225fc7.toInt()))
                assertThrows(IOException::class.java) { OpenAiLocalContextEncoder.encode(listOf(message), container) }
            } finally { dir.deleteRecursively() }
        }
    }

    private fun wire(input: JSONArray): JSONArray {
        val writer = StringWriter()
        StreamingJsonRequestBody.writeJson(input, writer)
        return JSONArray(writer.toString())
    }

    private fun png(width: Int, height: Int, color: Int): ByteArray =
        TestPngFixtures.png(width, height, color)

    private fun user(attachment: OpenRouterLocalAttachment): JSONObject =
        JSONObject().put("role", "user").put("attachments", JSONArray().put(attachment.toJson()))

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes()).joinToString("") { "%02x".format(it) }
}
