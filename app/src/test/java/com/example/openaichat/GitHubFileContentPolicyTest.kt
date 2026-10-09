package com.example.openaichat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.Base64

class GitHubFileContentPolicyTest {
    private fun file(content: String?, size: Int, encoding: String = "base64"): JSONObject =
        JSONObject().put("type", "file").put("size", size)
            .put("encoding", encoding).put("sha", "safe_sha")
            .put("content", content ?: JSONObject.NULL)

    @Test fun trueEmptyFileIsDifferentFromOmittedBytes() {
        val empty = GitHubFileContentPolicy.decode("empty.txt", file("", 0))
        assertTrue(empty.getBoolean("ok"))
        assertEquals("", empty.getString("content"))
        assertFalse(empty.getBoolean("truncated"))
        assertThrows(IOException::class.java) {
            GitHubFileContentPolicy.decode("missing.txt", file(null, 0))
        }
    }

    @Test fun nonBase64AndDecodeFailuresNeverReportSuccess() {
        for (input in listOf(
            file("", 128, "none"),
            file("", 0, ""),
            file("!!!!", 3),
            file("YQ==", 3),
            file(Base64.getEncoder().encodeToString(byteArrayOf(-1, -2)), 2),
            JSONObject().put("type", "dir").put("encoding", "base64").put("content", "")
        )) {
            assertThrows(IOException::class.java) {
                GitHubFileContentPolicy.decode("test-file", input)
            }
        }
    }

    @Test fun validTextAndExplicitTruncation() {
        val source = "Hello, Мир!\n"
        val encoded = Base64.getEncoder().encodeToString(source.toByteArray(Charsets.UTF_8))
        val success = GitHubFileContentPolicy.decode("notes.md",
            file(encoded.chunked(12).joinToString("\n"), source.toByteArray().size))
        assertEquals(source, success.getString("content"))
        val long = "abc".repeat(17000)
        val result = GitHubFileContentPolicy.decode("long.md",
            file(Base64.getEncoder().encodeToString(long.toByteArray()), long.length))
        assertTrue(result.getBoolean("truncated"))
        assertEquals(40000, result.getString("content").length)
    }

    @Test fun oversizedPayloadIsBoundedBeforeDecode() {
        val encoded = "A".repeat(400_000)
        assertThrows(IOException::class.java) {
            GitHubFileContentPolicy.decode("huge.md", file(encoded, 300_000))
        }
    }
}
