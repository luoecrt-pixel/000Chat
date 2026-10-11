package com.example.openaichat

import java.io.File
import java.io.IOException
import java.io.InputStream

/** Container validation only: fixed-size block reads, stopping at EOI before vendor trailers. */
internal object JpegContainerReader {
    private val frameMarkers = setOf(0xC0, 0xC1, 0xC2, 0xC3, 0xC5, 0xC6, 0xC7, 0xC9, 0xCA, 0xCB, 0xCD, 0xCE, 0xCF)
    fun complete(file: File): Boolean = file.inputStream().use { complete(it) }
    internal fun complete(input: InputStream): Boolean {
        val source = Blocks(input)
        if (source.word() != 0xFFD8) return false
        var frame = false
        var scan = false
        var pending: Int? = null
        while (true) {
            val marker = pending ?: run {
                if (source.byte() != 0xFF) return false
                var code = source.byte()
                while (code == 0xFF) code = source.byte()
                code
            }
            pending = null
            if (marker == 0xD9) return frame && scan
            if (marker == 0 || marker == 0xD8 || marker in 0xD0..0xD7) return false
            if (marker == 0x01) continue
            val length = source.word() - 2
            if (length < 0) return false
            if (marker in frameMarkers) {
                if (length < 6) return false
                source.byte(); val height = source.word(); val width = source.word()
                if (height == 0 || width == 0) return false
                frame = true; source.skip(length - 5)
            } else source.skip(length)
            if (marker == 0xDA) { scan = true; pending = source.entropyMarker() }
        }
    }
    private class Blocks(private val input: InputStream) {
        private val buffer = ByteArray(64 * 1024)
        private var position = 0
        private var size = 0
        private fun fill() {
            if (Thread.currentThread().isInterrupted) throw IOException("Image validation cancelled")
            size = input.read(buffer, 0, buffer.size)
            position = 0
            if (size <= 0) throw IOException("Incomplete JPEG")
        }
        fun byte(): Int {
            if (position == size) fill()
            return buffer[position++].toInt() and 255
        }
        fun word(): Int = (byte() shl 8) or byte()
        fun skip(count: Int) {
            require(count >= 0)
            var remaining = count
            while (remaining > 0) {
                if (position == size) fill()
                val step = minOf(remaining, size - position)
                position += step; remaining -= step
            }
        }
        fun entropyMarker(): Int {
            while (true) {
                if (position == size) fill()
                // Most entropy bytes have no marker; scan the array instead of InputStream.read().
                while (position < size && buffer[position] != 0xFF.toByte()) position++
                if (position == size) continue
                position++
                var code = byte()
                while (code == 0xFF) code = byte()
                if (code == 0 || code in 0xD0..0xD7) continue
                return code
            }
        }
    }
}
