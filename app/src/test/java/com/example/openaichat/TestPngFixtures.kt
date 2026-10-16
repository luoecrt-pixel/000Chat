package com.example.openaichat

import org.junit.Assert.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.util.zip.CRC32
import java.util.zip.Inflater
import kotlin.math.abs

/** Standard Pillow-encoded RGB PNGs, independently decoded without a java.desktop dependency. */
internal object TestPngFixtures {
    fun png(width: Int, height: Int, color: Int): ByteArray {
        val name = "solid-${width}x$height-${"%06x".format(color and 0xffffff)}.png"
        val bytes = requireNotNull(javaClass.getResourceAsStream("/images/$name")) { "Missing PNG fixture $name" }
            .use { it.readBytes() }
        val compressed = ByteArrayOutputStream()
        val chunks = mutableListOf<String>()
        DataInputStream(ByteArrayInputStream(bytes)).use { input ->
            assertArrayEquals(byteArrayOf(0x89.toByte(), 80, 78, 71, 13, 10, 26, 10),
                ByteArray(8).also(input::readFully))
            while (input.available() > 0) {
                val size = input.readInt()
                assertTrue("Complete PNG chunk", size >= 0 && size <= input.available() - 8)
                val type = ByteArray(4).also(input::readFully)
                val data = ByteArray(size).also(input::readFully)
                val kind = String(type, Charsets.US_ASCII)
                assertEquals("PNG CRC for $kind", CRC32().apply { update(type); update(data) }.value,
                    input.readInt().toLong() and 0xffffffffL)
                chunks.add(kind)
                when (kind) {
                    "IHDR" -> DataInputStream(ByteArrayInputStream(data)).use { header ->
                        assertEquals(13, size)
                        assertEquals(width, header.readInt()); assertEquals(height, header.readInt())
                        assertEquals("8-bit RGB fixture", 8, header.readUnsignedByte())
                        assertEquals(2, header.readUnsignedByte())
                        assertEquals(0, header.readUnsignedByte()); assertEquals(0, header.readUnsignedByte())
                        assertEquals("Noninterlaced fixture", 0, header.readUnsignedByte())
                    }
                    "IDAT" -> compressed.write(data)
                    "IEND" -> { assertEquals(0, size); assertEquals("No trailing PNG bytes", 0, input.available()) }
                }
            }
        }
        assertEquals("IHDR", chunks.first()); assertEquals(1, chunks.count { it == "IHDR" })
        assertTrue(chunks.contains("IDAT")); assertEquals("IEND", chunks.last())
        val stride = width * 3
        val expected = (stride + 1) * height
        val scanlines = ByteArray(expected + 1)
        val inflater = Inflater()
        try {
            inflater.setInput(compressed.toByteArray())
            var count = 0
            while (!inflater.finished() && count < scanlines.size) {
                val added = inflater.inflate(scanlines, count, scanlines.size - count)
                assertTrue("Complete zlib image data", added > 0)
                count += added
            }
            assertTrue("Complete PNG zlib stream", inflater.finished())
            assertEquals("No trailing compressed data", 0, inflater.remaining)
            assertEquals("Decoded dimensions match IHDR", expected, count)
        } finally { inflater.end() }
        val decoded = ByteArray(stride * height)
        for (y in 0 until height) {
            val filter = scanlines[y * (stride + 1)].toInt() and 255
            assertTrue("Known PNG scanline filter", filter in 0..4)
            for (x in 0 until stride) {
                val index = y * stride + x
                val left = if (x >= 3) decoded[index - 3].toInt() and 255 else 0
                val up = if (y > 0) decoded[index - stride].toInt() and 255 else 0
                val upperLeft = if (y > 0 && x >= 3) decoded[index - stride - 3].toInt() and 255 else 0
                val predictor = when (filter) {
                    0 -> 0; 1 -> left; 2 -> up; 3 -> (left + up) / 2
                    else -> paeth(left, up, upperLeft)
                }
                decoded[index] = ((scanlines[y * (stride + 1) + 1 + x].toInt() and 255) + predictor).toByte()
                assertEquals("Decoded pixel channel ($x, $y)", (color ushr ((2 - x % 3) * 8)) and 255,
                    decoded[index].toInt() and 255)
            }
        }
        return bytes
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val predictor = left + up - upperLeft
        val a = abs(predictor - left); val b = abs(predictor - up); val c = abs(predictor - upperLeft)
        return if (a <= b && a <= c) left else if (b <= c) up else upperLeft
    }
}
