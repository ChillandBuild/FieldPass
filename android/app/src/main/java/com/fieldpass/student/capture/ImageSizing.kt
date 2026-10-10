package com.fieldpass.student.capture

import kotlin.math.max
import kotlin.math.roundToInt

/** Pure sizing maths for the uploaded JPEG: long edge about 1280 px, never upscaled. */
object ImageSizing {
    const val MAX_LONG_EDGE = 1280
    const val JPEG_QUALITY = 85

    /** Final (width, height) after orientation, preserving aspect ratio. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE): Pair<Int, Int> {
        require(width > 0 && height > 0) { "empty image" }
        val longEdge = max(width, height)
        if (longEdge <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longEdge
        return (width * scale).roundToInt().coerceAtLeast(1) to (height * scale).roundToInt().coerceAtLeast(1)
    }

    /** Largest power-of-two decode sample size that still leaves the long edge >= [maxEdge]. */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = MAX_LONG_EDGE): Int {
        var sample = 1
        var longEdge = max(width, height)
        while (longEdge / 2 >= maxEdge) {
            sample *= 2
            longEdge /= 2
        }
        return sample
    }

    /** Quarter-turn rotations swap width and height. */
    fun rotatedSize(width: Int, height: Int, rotationDegrees: Int): Pair<Int, Int> =
        if (((rotationDegrees % 360) + 360) % 360 % 180 == 90) height to width else width to height
}
