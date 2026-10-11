package com.example.openaichat

import android.content.Context
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.Base64

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class FileBackedWireRegressionTest {
    @Test fun fullAnalysisUsesOriginalImageAfterCompatibleOpenRouterCopy() {
        val context: Context = RuntimeEnvironment.getApplication()
        val original = File(context.cacheDir, "original.png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val copy = File(context.cacheDir, "compatible.jpg").apply { writeBytes(byteArrayOf(4)) }
        try {
            val image = OpenRouterLocalAttachment("a", original.name, "image", original.path, "image/png", original.length(), "",
                wirePath = copy.path, wireMime = "image/jpeg")
            val message = JSONObject().put("role", "user").put("provider", "openrouter").put("attachments", org.json.JSONArray().put(image.toJson()))
            val result = OpenAiFileAnalysis.prepare(listOf(message), { true }) { file, _, mime ->
                assertEquals(original.path, file.path); assertEquals("image/png", mime)
                assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes()); "file_original"
            }
            assertEquals("file_original", result[original.path])
        } finally { original.delete(); copy.delete() }
    }
    @Test fun diskWireMatchesCanonicalJsonAndCopiesWithoutMaterializingBase64() {
        val dir = File(RuntimeEnvironment.getApplication().cacheDir, "wire_test").apply { mkdirs() }
        try {
            val file = File(dir, "input").apply { writeBytes(ByteArray(131077) { (it * 37).toByte() }) }
            val streamed = JSONObject().put("file_data", FileBackedDataUrl(file, "application/pdf"))
            val copy = JsonProtocol.objectCopy(streamed)
            assertSame(streamed.get("file_data"), copy.get("file_data"))
            val expected = JSONObject().put("file_data", "data:application/pdf;base64," + Base64.getEncoder().encodeToString(file.readBytes()))
            assertEquals(StreamingJsonRequestBody.sha256(expected), StreamingJsonRequestBody.sha256(copy))
            val body = StreamingJsonRequestBody(copy); val sink = Buffer(); body.writeTo(sink)
            assertEquals(body.contentLength(), sink.size)
            assertEquals(expected.getString("file_data"), JSONObject(sink.readUtf8()).getString("file_data"))
            file.writeBytes(ByteArray(131077))
            assertThrows(java.io.IOException::class.java) { body.writeTo(Buffer()) }
        } finally { dir.deleteRecursively() }
    }
    @Test fun uploadReuseChecksBytesAndAccountAcrossCacheInstances() {
        val context: Context = RuntimeEnvironment.getApplication()
        val store = context.getSharedPreferences("upload_cache_test", Context.MODE_PRIVATE)
        store.edit().clear().commit()
        val file = File(context.cacheDir, "reuse.txt").apply { writeText("first") }
        var calls = 0
        fun prepare(key: String) = OpenAiFileUploadCache(store).getOrUpload(file, "reuse.txt", "text/plain", key, { true }) { "file_${++calls}" }
        try {
            assertEquals("file_1", prepare("account-a")); assertEquals("file_1", prepare("account-a"))
            assertEquals("file_2", prepare("account-b")); file.writeText("second")
            assertEquals("file_3", prepare("account-a"))
            OpenAiFileUploadCache(store).invalidate("file_3")
            assertEquals("file_4", prepare("account-a"))
        } finally { file.delete() }
    }
}
