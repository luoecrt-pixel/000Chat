package com.example.openaichat

import android.graphics.Bitmap
import android.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenRouterAttachmentExifInstrumentationTest {
    @Test
    fun perfectRotatedJpegStaysJpegAndResetsOrientation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val attachmentsDir = File(context.filesDir, "attachments").apply { mkdirs() }
        val source = File(attachmentsDir, "exif_source.jpg")
        try {
            val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            for (y in 0 until 32) {
                for (x in 0 until 32) {
                    val color = when {
                        x < 16 && y < 16 -> android.graphics.Color.RED
                        x >= 16 && y < 16 -> android.graphics.Color.GREEN
                        x < 16 -> android.graphics.Color.BLUE
                        else -> android.graphics.Color.YELLOW
                    }
                    bitmap.setPixel(x, y, color)
                }
            }
            source.outputStream().use { out ->
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out))
            }
            bitmap.recycle()

            ExifInterface(source.absolutePath).apply {
                setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
                saveAttributes()
            }

            val attachment = OpenRouterAttachmentStore.classifyLocalFile(
                context,
                source,
                "camera.jpg",
                "image/jpeg"
            )

            assertEquals("image", attachment.kind)
            assertEquals("image/jpeg", attachment.wireMime)
            assertNotNull(attachment.wirePath)
            val wire = File(requireNotNull(attachment.wirePath))
            assertTrue(wire.isFile)
            assertFalse(wire.name.endsWith(".png", ignoreCase = true))
            assertEquals(
                ExifInterface.ORIENTATION_NORMAL,
                ExifInterface(wire.absolutePath).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_UNDEFINED
                )
            )
            val decoded = BitmapFactoryCompat.decode(wire)
            assertEquals(32, decoded.width)
            assertEquals(32, decoded.height)
            decoded.recycle()
        } finally {
            source.delete()
            attachmentsDir.listFiles()?.filter { it.name.startsWith("wire_") }?.forEach { it.delete() }
        }
    }

    private object BitmapFactoryCompat {
        fun decode(file: File): Bitmap =
            android.graphics.BitmapFactory.decodeFile(file.absolutePath)
                ?: error("wire JPEG did not decode")
    }
}
