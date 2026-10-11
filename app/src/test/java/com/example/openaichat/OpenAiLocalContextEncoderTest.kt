package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.Base64

class OpenAiLocalContextEncoderTest {
    private fun serialized(value: JSONArray): JSONArray {
        val writer = java.io.StringWriter()
        StreamingJsonRequestBody.writeJson(value, writer)
        return JSONArray(writer.toString())
    }
    private fun realJpeg(): ByteArray = Base64.getDecoder().decode(
        "/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAMCAgICAgMCAgIDAwMDBAYEBAQEBAgGBgUGCQgKCgkICQkKDA8MCgsOCwkJDRENDg8QEBEQCgwSExIQEw8QEBD/2wBDAQMDAwQDBAgEBAgQCwkLEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBD/wAARCAACAAIDAREAAhEBAxEB/8QAHwAAAQUBAQEBAQEAAAAAAAAAAAECAwQFBgcICQoL/8QAtRAAAgEDAwIEAwUFBAQAAAF9AQIDAAQRBRIhMUEGE1FhByJxFDKBkaEII0KxwRVS0fAkM2JyggkKFhcYGRolJicoKSo0NTY3ODk6Q0RFRkdISUpTVFVWV1hZWmNkZWZnaGlqc3R1dnd4eXqDhIWGh4iJipKTlJWWl5iZmqKjpKWmp6ipqrKztLW2t7i5usLDxMXGx8jJytLT1NXW19jZ2uHi4+Tl5ufo6erx8vP09fb3+Pn6/8QAHwEAAwEBAQEBAQEBAQAAAAAAAAECAwQFBgcICQoL/8QAtREAAgECBAQDBAcFBAQAAQJ3AAECAxEEBSExBhJBUQdhcRMiMoEIFEKRobHBCSMzUvAVYnLRChYkNOEl8RcYGRomJygpKjU2Nzg5OkNERUZHSElKU1RVVldYWVpjZGVmZ2hpanN0dXZ3eHl6goOEhYaHiImKkpOUlZaXmJmaoqOkpaanqKmqsrO0tba3uLm6wsPExcbHyMnK0tPU1dbX2Nna4uPk5ebn6Onq8vP09fb3+Pn6/9oADAMBAAIRAxEAPwDnK/Bz9oP/2Q=="
    )

    @Test
    fun realJpegPhotoSurvivesManifestPersistenceAndProviderReplay() {
        val bytes = realJpeg()
        val folder = createTempDir()
        val source = File(folder, "camera.jpg").apply { writeBytes(bytes) }
        val attachment = OpenRouterAttachmentStore.classifyLocalFile(source, "camera.jpg", "image/png")
        assertEquals("image/jpeg", attachment.mime)
        val orMessage = JSONObject().put("role", "user").put("provider", "openrouter")
            .put("text", "Что на фото?")
            .put("attachments", JSONArray().put(attachment.toJson()))
        val persisted = JSONObject(orMessage.toString())
        val history = listOf(persisted,
            JSONObject().put("role", "assistant").put("provider", "openrouter").put("text", "Ответ"),
            JSONObject().put("role", "user").put("provider", "openai").put("text", "А теперь GPT?"))
        val replay = serialized(OpenAiLocalContextEncoder.encode(history))
        assertEquals(3, replay.length())
        val content = replay.getJSONObject(0).getJSONArray("content")
        val image = content.getJSONObject(1)
        assertEquals("input_image", image.getString("type"))
        val data = image.getString("image_url")
        assertTrue(data.startsWith("data:image/jpeg;base64,"))
        val received = Base64.getDecoder().decode(data.substringAfter(","))
        assertArrayEquals(bytes, received)
        assertEquals(digest(bytes), digest(received))
        assertEquals("А теперь GPT?", replay.getJSONObject(2).getJSONArray("content")
            .getJSONObject(0).getString("text"))
        assertEquals("openrouter", persisted.getString("provider"))
        assertTrue(source.isFile)
    }

    @Test
    fun missingLocalBytesStopBeforeProducingAnyInput() {
        val folder = createTempDir()
        val valid = File(folder, "text.txt").apply { writeText("hello") }
        val lost = File(folder, "deleted.jpg")
        val preserved = OpenRouterAttachmentStore.classifyLocalFile(valid, "text.txt", "text/plain")
        val message = JSONObject().put("role", "user").put("text", "Do not delete my text")
            .put("attachments", JSONArray().put(preserved.toJson())
                .put(OpenRouterLocalAttachment("lost", "deleted.jpg", "image", lost.path,
                    "image/jpeg", 1L, "").toJson()))
        try {
            OpenAiLocalContextEncoder.encode(listOf(message))
            fail("Missing image must fail the whole replay")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("deleted.jpg"))
        }
        assertTrue("Existing READY bytes must survive failure", valid.isFile)

        val idOnly = JSONObject().put("role", "user").put("text", "Remote-only")
            .put("file_ids", JSONArray().put("file_foreign"))
        try {
            OpenAiLocalContextEncoder.encode(listOf(idOnly))
            fail("Foreign file_id without local bytes must fail")
        } catch (e: java.io.IOException) {
            assertTrue(e.message.orEmpty().contains("локальн"))
        }
    }

    @Test
    fun mixedLocalContextEncodesAllWithoutForeignIds() {
        val folder = createTempDir()
        val photo = File(folder, "preview.png").apply {
            writeBytes(Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAACCAIAAAD91JpzAAAAFklEQVR4nGP8yyfNwMDAxMDAwMDAAAAO1AEqT6J0VgAAAABJRU5ErkJggg=="
            ))
        }
        val doc = File(folder, "note.txt").apply { writeText("note") }
        val img = OpenRouterAttachmentStore.classifyLocalFile(photo, "preview.png", "image/png")
        val msg = JSONObject().put("role", "user").put("provider", "openai").put("text", "Both")
            .put("attachments", JSONArray().put(img.toJson()))
            .put("file_paths", JSONArray().put(doc.path))
            .put("file_names", JSONArray().put(doc.name))
            .put("file_ids", JSONArray().put("file_openai_before"))
        val result = serialized(OpenAiLocalContextEncoder.encode(listOf(msg))).toString()
        assertTrue(result.contains("\"input_image\""))
        assertTrue(result.contains("note"))
        assertFalse(result.contains("file_openai_before"))
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
