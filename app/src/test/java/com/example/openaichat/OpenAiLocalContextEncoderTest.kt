package com.example.openaichat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.Random
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam

class OpenAiLocalContextEncoderTest {
    private fun largeRealJpeg(): ByteArray {
        // Decodable pixels throughout the image, never padded signature bytes.
        val image = BufferedImage(3100, 2700, BufferedImage.TYPE_INT_RGB)
        val random = Random(6102026L)
        for (y in 0 until image.height) {
            for (x in 0 until image.width) image.setRGB(x, y, random.nextInt() and 0xFFFFFF)
        }
        val output = ByteArrayOutputStream()
        val writer = ImageIO.getImageWritersByFormatName("jpeg").next()
        try {
            writer.output = ImageIO.createImageOutputStream(output)
            val param = writer.defaultWriteParam.apply {
                compressionMode = ImageWriteParam.MODE_EXPLICIT
                compressionQuality = 0.92f
            }
            writer.write(null, IIOImage(image, null, null), param)
        } finally {
            writer.dispose()
        }
        val bytes = output.toByteArray()
        assertTrue("Must exercise a real 6+ MB JPEG: " + bytes.size, bytes.size >= 6_000_000)
        assertTrue(bytes.size < 10_000_000)
        assertNotNull(ImageIO.read(bytes.inputStream()))
        return bytes
    }

    @Test
    fun sixMegabytePhotoSurvivesManifestPersistenceAndProviderReplay() {
        val bytes = largeRealJpeg()
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
        val replay = OpenAiLocalContextEncoder.encode(history)
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
            assertTrue(e.message.orEmpty().contains("локальных"))
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
        val result = OpenAiLocalContextEncoder.encode(listOf(msg)).toString()
        assertTrue(result.contains("\"input_image\""))
        assertTrue(result.contains("note"))
        assertFalse(result.contains("file_openai_before"))
    }

    private fun digest(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
