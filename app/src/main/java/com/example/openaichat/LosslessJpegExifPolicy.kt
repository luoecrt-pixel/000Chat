package com.example.openaichat

import android.media.ExifInterface

object LosslessJpegExifPolicy {
    fun rotationDegrees(orientation: Int): Int? = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90
        ExifInterface.ORIENTATION_ROTATE_180 -> 180
        ExifInterface.ORIENTATION_ROTATE_270 -> 270
        else -> null
    }

    fun isPerfectRotation(
        orientation: Int,
        width: Int,
        height: Int,
        mcuWidth: Int,
        mcuHeight: Int
    ): Boolean {
        if (rotationDegrees(orientation) == null) return false
        if (width <= 0 || height <= 0 || mcuWidth <= 0 || mcuHeight <= 0) return false
        return width % mcuWidth == 0 && height % mcuHeight == 0
    }
}
