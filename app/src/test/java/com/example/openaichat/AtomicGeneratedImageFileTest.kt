package com.example.openaichat

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class AtomicGeneratedImageFileTest {
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 12, 34)

    @Test fun fullBytesArePublishedOnceWithoutReplacingAnotherImage() {
        val root = createTempDir(prefix = "atomic-image-")
        try {
            val first = AtomicGeneratedImageFile.write(root, png)
            val second = AtomicGeneratedImageFile.write(root, png)
            assertNotEquals(first, second)
            assertArrayEquals(png, first.readBytes())
            assertArrayEquals(png, second.readBytes())
            assertTrue(root.listFiles()!!.all { it.extension == "png" })
        } finally { root.deleteRecursively() }
    }

    @Test fun partialWriteOrSyncFailureNeverPublishesAPng() {
        val root = createTempDir(prefix = "atomic-failure-")
        try {
            for (throwAfterWrite in listOf(false, true)) {
                try {
                    AtomicGeneratedImageFile.write(root, png) { temporary, bytes ->
                        temporary.writeBytes(if (throwAfterWrite) bytes else bytes.copyOfRange(0, 4))
                        throw IOException(if (throwAfterWrite) "fsync failed" else "disk full")
                    }
                    fail("Do not accept an interrupted local save")
                } catch (_: IOException) {}
                assertTrue("No partial READY image or temporary orphan", root.listFiles()!!.isEmpty())
            }
        } finally { root.deleteRecursively() }
    }

    @Test fun shortSuccessfulWriterAndNonPngAreRejected() {
        val root = createTempDir(prefix = "atomic-short-")
        try {
            try {
                AtomicGeneratedImageFile.write(root, png) { file, bytes -> file.writeBytes(bytes.copyOfRange(0, 4)) }
                fail("A writer returning early must not publish")
            } catch (_: IOException) {}
            try {
                AtomicGeneratedImageFile.write(root, "not PNG".toByteArray())
                fail("Do not label arbitrary bytes PNG")
            } catch (_: IOException) {}
            assertTrue(root.listFiles()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
}
