package com.example.openaichat

import java.io.File
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class LocalUtf8FilePatchTest {
    @Test fun originalRangesPreserveUtf8AndCrossBufferMatchesWithoutLoadingWholeFile() {
        val dir = kotlin.io.path.createTempDirectory("patch-test").toFile()
        try {
            val before = "x".repeat(65534) + "α😀\nold=1\nlast=2\n"
            val source = File(dir, "source.txt").apply { writeText(before) }
            val result = File(dir, "result.txt")
            val patches = listOf(LocalUtf8FilePatch.Replacement("α😀\nold=1", "β😀\nnew=10"),
                LocalUtf8FilePatch.Replacement("last=2", "last=3"))
            LocalUtf8FilePatch.prepare(source, dir, patches, LocalUtf8FilePatch.sha256(source)).use { plan ->
                assertEquals(listOf(1L, 3L), plan.hunks.map { it.line })
                assertEquals(plan.write(result), LocalUtf8FilePatch.sha256(result))
            }
            assertEquals(before.replace("α😀\nold=1", "β😀\nnew=10").replace("last=2", "last=3"), result.readText())
            assertEquals(before, source.readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun validationIsAtomicForMissingAmbiguousOverlappingAndStaleSearches() {
        val dir = kotlin.io.path.createTempDirectory("patch-reject").toFile()
        val source = File(dir, "source.txt").apply { writeText("aaa abcdef end") }
        val invalid = listOf(
            listOf(LocalUtf8FilePatch.Replacement("missing", "x")),
            listOf(LocalUtf8FilePatch.Replacement("aa", "x")),
            listOf(LocalUtf8FilePatch.Replacement("abcdef", "x"), LocalUtf8FilePatch.Replacement("cde", "y")),
            listOf(LocalUtf8FilePatch.Replacement("end", "new"), LocalUtf8FilePatch.Replacement("new", "z")),
            listOf(LocalUtf8FilePatch.Replacement("", "x")))
        try {
            invalid.forEach { patches ->
                assertThrows(IOException::class.java) { LocalUtf8FilePatch.prepare(source, dir, patches, null).close() }
                assertEquals("aaa abcdef end", source.readText())
                assertEquals(listOf(source.name), dir.listFiles()!!.map { it.name })
            }
            assertThrows(IOException::class.java) { LocalUtf8FilePatch.prepare(source, dir,
                listOf(LocalUtf8FilePatch.Replacement("end", "x")), "0".repeat(64)).close() }
            assertThrows(IOException::class.java) { LocalUtf8FilePatch.prepare(source, dir,
                listOf(LocalUtf8FilePatch.Replacement("end", "x")), null) { false }.close() }
            try {
                Thread.currentThread().interrupt()
                assertThrows(IOException::class.java) { LocalUtf8FilePatch.prepare(source, dir,
                    listOf(LocalUtf8FilePatch.Replacement("end", "x")), null).close() }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            source.writeBytes(byteArrayOf(0xc3.toByte(), 0x28))
            assertThrows(IOException::class.java) { LocalUtf8FilePatch.prepare(source, dir,
                listOf(LocalUtf8FilePatch.Replacement("x", "y")), null).close() }
            assertEquals(1, dir.listFiles()!!.size)
        } finally { dir.deleteRecursively() }
    }
}
