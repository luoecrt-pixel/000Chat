package com.example.openaichat

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.ExifInterface

internal object ImageOrientation {
    /** Pixel transform for thumbnails/explicit transport copies, never the original file. */
    fun upright(bitmap: Bitmap, path: String): Bitmap {
        val orientation = try { ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
            catch (_: Exception) { return bitmap }
        val matrix = Matrix()
        when (orientation) {
            2 -> matrix.setScale(-1f, 1f)
            3 -> matrix.setRotate(180f)
            4 -> matrix.setScale(1f, -1f)
            5 -> { matrix.setRotate(90f); matrix.postScale(-1f, 1f) }
            6 -> matrix.setRotate(90f)
            7 -> { matrix.setRotate(-90f); matrix.postScale(-1f, 1f) }
            8 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val result = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        if (result !== bitmap) bitmap.recycle()
        return result
    }
}
