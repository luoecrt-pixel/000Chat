package com.example.openaichat

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

/** Only a fully written, fsynced PNG is published as a READY artifact. */
object AtomicGeneratedImageFile {
    fun write(directory: File, bytes: ByteArray): File = write(directory, bytes) { temporary, payload ->
        FileOutputStream(temporary).use { output ->
            output.write(payload)
            output.fd.sync()
        }
    }

    internal fun write(directory: File, bytes: ByteArray, writer: (File, ByteArray) -> Unit): File {
        val signature = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
        if (bytes.size < signature.size || !bytes.copyOfRange(0, signature.size).contentEquals(signature))
            throw IOException("Ожидался PNG")
        if (!directory.isDirectory && !directory.mkdirs() && !directory.isDirectory)
            throw IOException("Не удалось создать папку изображений")
        val destination = File(directory, "astra_" + UUID.randomUUID() + ".png")
        val temporary = File.createTempFile(".image-", ".tmp", directory)
        try {
            writer(temporary, bytes)
            if (temporary.length() != bytes.size.toLong()) throw IOException("Изображение записано не полностью")
            // Same directory/filesystem, no non-atomic fallback and no overwrite.
            Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE)
            return destination
        } finally {
            temporary.delete()
        }
    }
}
