package com.example.openaichat

import java.io.File
import java.nio.charset.Charset
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
        val original = byteArrayOf(1,2,3,4,5,6)
        val file = File(dir, "x.jpg").apply { writeBytes(original) }
        val a = OpenRouterLocalAttachment("1", "x.jpg", "image", file.absolutePath, "image/jpeg", original.size.toLong(), "hash")
        val data = OpenRouterAttachmentStore.toDataUrl(a)
        assertTrue(data.startsWith("data:image/jpeg;base64,"))
        val encoded = data.substringAfter(",")
        assertTrue(android.util.Base64.decode(encoded, android.util.Base64.DEFAULT).contentEquals(original))
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
}
