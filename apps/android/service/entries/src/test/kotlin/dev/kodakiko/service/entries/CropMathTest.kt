package dev.kodakiko.service.entries

import org.junit.Assert.assertEquals
import org.junit.Test

const val CLOSE_DELTA = 0.005

class CropMathTest {
    @Test
    fun convertsANormalizedRectangleToWholePixelsInsideTheImage() {
        assertEquals(PixelCrop(originX = 100, originY = 160, width = 500, height = 400), toPixels(CropRect(0.1, 0.2, 0.5, 0.5), 1000, 800))
        assertEquals(PixelCrop(originX = 0, originY = 0, width = 333, height = 221), toPixels(FULL_CROP, 333, 221))
    }

    @Test
    fun dragsACornerButKeepsAMinimumSizeAndStaysInsideTheImage() {
        val shrunk = dragCorner(FULL_CROP, CropCorner.TOP_LEFT, 0.99, 0.99)
        assertEquals(CROP_MIN_FRACTION, shrunk.width, CLOSE_DELTA)
        assertEquals(1.0, shrunk.x + shrunk.width, CLOSE_DELTA)
        val grown = dragCorner(CropRect(0.2, 0.2, 0.5, 0.5), CropCorner.BOTTOM_RIGHT, 0.9, 0.9)
        assertEquals(0.7, grown.width, CLOSE_DELTA)
        assertEquals(0.7, grown.height, CLOSE_DELTA)
        val outside = dragCorner(CropRect(0.2, 0.2, 0.5, 0.5), CropCorner.TOP_LEFT, -0.5, -0.5)
        assertEquals(0.0, outside.x, 0.0)
        assertEquals(0.0, outside.y, 0.0)
    }

    @Test
    fun movesTheWholeRectangleWithoutLeavingTheImage() {
        assertEquals(CropRect(x = 0.6, y = 0.0, width = 0.4, height = 0.4), moveCrop(CropRect(0.5, 0.5, 0.4, 0.4), 0.3, -0.7))
    }

    @Test
    fun keepsTheMinimumFractionAndTheFullCropOfVersion031() {
        assertEquals(0.1, CROP_MIN_FRACTION, 0.0)
        assertEquals(CropRect(0.0, 0.0, 1.0, 1.0), FULL_CROP)
    }
}
