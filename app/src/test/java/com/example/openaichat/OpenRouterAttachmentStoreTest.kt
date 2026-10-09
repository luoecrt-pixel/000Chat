package com.example.openaichat

import java.io.File
import java.nio.charset.Charset
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAttachmentStoreTest {
    @Test
    fun ktOctetStreamClassifiesAsTextAndPersistsUtf8Artifact() {
        val dir = createTempDir()
        val file = File(dir, "Sample.kt").apply { writeText("fun main() = println(\"привет\")", Charsets.UTF_8) }
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "Sample.kt", "application/octet-stream")
        assertEquals("text", a.kind)
        assertEquals("UTF-8", a.textEncoding)
        assertEquals("fun main() = println(\"привет\")", OpenRouterAttachmentStore.textContent(a))
        assertTrue(a.textPath?.let { File(it).isFile } == true)
    }

    @Test
    fun markdownFallsBackToWindows1251() {
        val dir = createTempDir()
        val file = File(dir, "readme.md")
        file.writeBytes("Привет, мир".toByteArray(Charset.forName("windows-1251")))
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "readme.md", "application/octet-stream")
        assertEquals("text", a.kind)
        assertEquals("windows-1251", a.textEncoding)
        assertEquals("Привет, мир", OpenRouterAttachmentStore.textContent(a))
    }

    @Test(expected = java.io.IOException::class)
    fun nulByteIsNotText() {
        val dir = createTempDir()
        val file = File(dir, "bad.txt").apply { writeBytes(byteArrayOf(65, 0, 66)) }
        OpenRouterAttachmentStore.classifyLocalFile(file, "bad.txt", "text/plain")
    }

    @Test
    fun imageUsesOriginalBytesForDataUrl() {
        val dir = createTempDir()
        val original = byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte(),1,2,3)
        val file = File(dir, "x.jpg").apply { writeBytes(original) }
        val a = OpenRouterLocalAttachment("1", "x.jpg", "image", file.absolutePath, "image/jpeg", original.size.toLong(), "hash")
        val data = OpenRouterAttachmentStore.toDataUrl(a)
        assertTrue(data.startsWith("data:image/jpeg;base64,"))
        val encoded = data.substringAfter(",")
        assertTrue(Base64.getDecoder().decode(encoded).contentEquals(original))
    }

    @Test
    fun legacyPngWireIsDetectedOnlyWhenOriginalIsJpeg() {
        val dir = createTempDir()
        val jpeg = File(dir, "camera.jpg").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())) }
        val png = File(dir, "camera.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50.toByte(), 0x4E.toByte(), 0x47.toByte())) }

        val legacy = OpenRouterLocalAttachment(
            "legacy", "camera.jpg", "image", jpeg.absolutePath, "image/jpeg", jpeg.length(), "sha",
            wirePath = File(dir, "wire.png").absolutePath, wireMime = "image/png"
        )
        val actualPng = OpenRouterLocalAttachment(
            "png", "camera.png", "image", png.absolutePath, "image/png", png.length(), "sha",
            wirePath = File(dir, "wire.png").absolutePath, wireMime = "image/png"
        )

        assertTrue(OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(legacy))
        assertTrue(!OpenRouterAttachmentStore.shouldRefreshLegacyPngWire(actualPng))
    }

    @Test
    fun largeTextIsNotTruncated() {
        val dir = createTempDir()
        val expected = "абвгд".repeat(30000)
        val file = File(dir, "large.txt").apply { writeText(expected, Charsets.UTF_8) }
        val a = OpenRouterAttachmentStore.classifyLocalFile(file, "large.txt", "application/octet-stream")
        assertEquals(expected, OpenRouterAttachmentStore.textContent(a))
    }

    @Test
    fun unsafeZipPathDoesNotEscapeAttachmentName() {
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath(".." + "/escape.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("a/" + "../escape.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("/absolute.txt"))
        assertTrue(OpenRouterAttachmentStore.isUnsafeZipPath("C:/absolute.txt"))
        assertTrue(!OpenRouterAttachmentStore.isUnsafeZipPath("nested/ok.txt"))
    }
    @Test
    fun pdfBlockUsesFileContentShape() {
        val dir = createTempDir()
        val file = File(dir, "x.pdf").apply { writeBytes("%PDF-1.7".toByteArray()) }
        val a = OpenRouterLocalAttachment("1", "x.pdf", "pdf", file.absolutePath, "application/pdf", file.length(), "hash")
        val block = OpenRouterAttachmentStore.toContentBlock(a)
        assertEquals("file", block.optString("type"))
        assertEquals("x.pdf", block.optJSONObject("file")?.optString("filename"))
        assertTrue(block.optJSONObject("file")?.optString("file_data")?.startsWith("data:application/pdf;base64,") == true)
    }
    @Test
    fun mismatchedFilenameAndPickerMimeUseActualJpegBytes() {
        val dir = createTempDir()
        val jpg = File(dir, "wrong.png").apply {
            writeBytes(byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte(),42))
        }
        val attachment = OpenRouterAttachmentStore.classifyLocalFile(jpg, "wrong.png", "image/png")
        assertEquals("image", attachment.kind)
        assertEquals("image/jpeg", attachment.mime)
        val block = OpenRouterAttachmentStore.toContentBlock(attachment)
        val url = block.getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertTrue(Base64.getDecoder().decode(url.substringAfter(",")).contentEquals(jpg.readBytes()))
    }

    @Test
    fun legacyNullWireFieldsRoundTripAsAbsent() {
        val dir = createTempDir()
        val jpeg = File(dir, "camera.jpg").apply {
            writeBytes(byteArrayOf(0xFF.toByte(),0xD8.toByte(),0xFF.toByte(),7))
        }
        val a = OpenRouterLocalAttachment.fromJson(
            org.json.JSONObject()
                .put("id","one").put("name","camera.jpg").put("path",jpeg.absolutePath)
                .put("kind","image").put("mime","image/jpeg")
                .put("wire_path",org.json.JSONObject.NULL).put("wire_mime",org.json.JSONObject.NULL)
        )
        assertEquals(null, a.wirePath)
        assertEquals(null, a.wireMime)
        assertTrue(OpenRouterAttachmentStore.toDataUrl(a).startsWith("data:image/jpeg;base64,"))
    }

    @Test(expected = java.io.IOException::class)
    fun rejectsFakeImageEvenWithJpegExtensionAndMime() {
        val dir = createTempDir()
        val fake = File(dir, "fake.jpg").apply { writeText("NOT A JPEG") }
        val a = OpenRouterLocalAttachment("id","fake.jpg","image",fake.absolutePath,"image/jpeg",fake.length(),"sha")
        OpenRouterAttachmentStore.toContentBlock(a)
    }

    @Test(expected = java.io.IOException::class)
    fun missingWireFileDoesNotProduceEmptyBase64() {
        val a = OpenRouterLocalAttachment("id","x.png","image","/missing/app04-image.png","image/png",12L,"sha")
        OpenRouterAttachmentStore.toDataUrl(a)
    }

}
