package com.example.openaichat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LosslessJpegExifPolicyTest {
    @Test
    fun rotationMappingCoversOnlyNonMirroredRotations() {
        assertEquals(180, LosslessJpegExifPolicy.rotationDegrees(3))
        assertEquals(90, LosslessJpegExifPolicy.rotationDegrees(6))
        assertEquals(270, LosslessJpegExifPolicy.rotationDegrees(8))
        assertEquals(null, LosslessJpegExifPolicy.rotationDegrees(2))
        assertEquals(null, LosslessJpegExifPolicy.rotationDegrees(4))
        assertEquals(null, LosslessJpegExifPolicy.rotationDegrees(5))
        assertEquals(null, LosslessJpegExifPolicy.rotationDegrees(7))
    }

    @Test
    fun rotationIsAllowedOnlyForWholeMcuDimensions() {
        assertTrue(LosslessJpegExifPolicy.isPerfectRotation(6, 32, 32, 16, 16))
        assertTrue(LosslessJpegExifPolicy.isPerfectRotation(3, 4000, 3000, 8, 8))
        assertFalse(LosslessJpegExifPolicy.isPerfectRotation(6, 4010, 3000, 8, 8))
        assertFalse(LosslessJpegExifPolicy.isPerfectRotation(2, 32, 32, 16, 16))
        assertFalse(LosslessJpegExifPolicy.isPerfectRotation(0, 32, 32, 16, 16))
        assertFalse(LosslessJpegExifPolicy.isPerfectRotation(6, 0, 32, 16, 16))
    }
}
