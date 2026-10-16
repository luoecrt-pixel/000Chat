package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.StringWriter
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
            val container = OpenAiFileAnalysis.prepare(listOf(message), { true }) { source, _, mime ->
                assertFalse("A mixed document request must not upload its photos", mime.startsWith("image/"))
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
            val dir = kotlin.io.path.createTempDirectory("analysis-invalid-original-").toFile()
            try {
                val photo = File(dir, "photo.png").apply { writeBytes(png(3, 2, 0xffd52c36.toInt())) }
                val image = OpenRouterAttachmentStore.classifyLocalFile(photo, photo.name, "image/png")
                val text = File(dir, "note.txt").apply { writeText("document") }
                val document = OpenRouterAttachmentStore.classifyLocalFile(text, text.name, "text/plain")
                val message = JSONObject().put("role", "user").put("attachments",
                    JSONArray().put(image.toJson()).put(document.toJson()))
                if (remove) assertTrue(photo.delete()) else photo.writeBytes(png(3, 2, 0xff225fc7.toInt()))
                assertThrows(IOException::class.java) {
                    OpenAiFileAnalysis.prepare(listOf(message), { true }) { _, _, _ ->
                        throw AssertionError("Source preflight must fail before any upload")
                    }
                }
            } finally { dir.deleteRecursively() }
        }
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
}
