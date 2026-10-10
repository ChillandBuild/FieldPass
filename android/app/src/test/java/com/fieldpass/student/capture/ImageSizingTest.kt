package com.fieldpass.student.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {
    @Test fun downscalesLongEdgeTo1280() {
        assertEquals(1280 to 960, ImageSizing.targetSize(4000, 3000))
        assertEquals(960 to 1280, ImageSizing.targetSize(3000, 4000))
    }

    @Test fun neverUpscales() {
        assertEquals(800 to 600, ImageSizing.targetSize(800, 600))
    }

    @Test fun sampleSizeKeepsAtLeastTargetResolution() {
        assertEquals(1, ImageSizing.sampleSize(1280, 720))
        assertEquals(2, ImageSizing.sampleSize(4000, 3000))
        assertEquals(4, ImageSizing.sampleSize(5200, 3900))
    }

    @Test fun quarterTurnsSwapDimensions() {
        assertEquals(480 to 640, ImageSizing.rotatedSize(640, 480, 90))
        assertEquals(480 to 640, ImageSizing.rotatedSize(640, 480, 270))
        assertEquals(640 to 480, ImageSizing.rotatedSize(640, 480, 180))
        assertEquals(640 to 480, ImageSizing.rotatedSize(640, 480, 0))
    }
}
