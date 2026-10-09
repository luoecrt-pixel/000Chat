package com.example.openaichat

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream

class BoundedAttachmentCopyTest {
    /** Synthetic >limit source: never allocate the full input in RAM. */
    private class RepeatingSource(private var remaining: Long) : InputStream() {
        override fun read(): Int = if (remaining-- > 0L) 42 else -1
        override fun read(buffer: ByteArray, off: Int, len: Int): Int {
            if (remaining <= 0L) return -1
            val bytes = minOf(len.toLong(), remaining).toInt()
            buffer.fill(42.toByte(), off, off + bytes)
            remaining -= bytes
            return bytes
        }
    }

    @Test fun syntheticOversizedInputIsRejectedAndPartialRemoved() {
        val dir = createTempDir()
        val ready = File(dir, "ready.txt").apply { writeText("KEEP ME") }
        val output = File(dir, "operation.tmp")
        assertThrows(IOException::class.java) {
            RepeatingSource(1024 * 1024L).use {
                BoundedAttachmentCopy.copy(it, output, maxBytes = 128 * 1024L)
            }
        }
        assertFalse(output.exists())
        assertTrue(ready.exists())
        assertEquals("KEEP ME", ready.readText())
    }

    @Test fun cancelledAndBrokenSourcesDoNotLeakPartialBytes() {
        val dir = createTempDir()
        val output = File(dir, "cancel.tmp")
        assertThrows(IOException::class.java) {
            RepeatingSource(100_000).use {
                BoundedAttachmentCopy.copy(it, output, shouldContinue = { false })
            }
        }
        assertFalse(output.exists())
        val broken = object : InputStream() {
            var reads = 0
            override fun read(): Int = throw IOException("source died")
            override fun read(target: ByteArray, off: Int, len: Int): Int {
                if (++reads >= 2) throw IOException("source died")
                target.fill(7.toByte(), off, off + len)
                return len
            }
        }
        assertThrows(IOException::class.java) {
            broken.use { BoundedAttachmentCopy.copy(it, output) }
        }
        assertFalse(output.exists())
    }

    @Test fun exactLimitIsLossless() {
        val output = File(createTempDir(), "ready.txt")
        RepeatingSource(131_072L).use {
            assertEquals(131_072L, BoundedAttachmentCopy.copy(it, output, 131_072L))
        }
        assertEquals(131_072L, output.length())
        assertTrue(output.inputStream().use { it.read() == 42 })
    }
}
