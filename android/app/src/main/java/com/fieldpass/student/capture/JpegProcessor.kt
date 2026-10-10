package com.fieldpass.student.capture

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import com.fieldpass.student.core.error.AppError
import com.fieldpass.student.core.error.ErrorCode
import java.io.ByteArrayOutputStream

/**
 * Turns the raw camera JPEG into the upload image: upright, long edge about 1280 px, quality 85.
 * Works entirely in memory; the output carries no EXIF (orientation is baked into the pixels).
 */
object JpegProcessor {
    fun process(raw: ByteArray, rotationDegrees: Int): ByteArray {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw AppError(ErrorCode.CAPTURE_FAILED)

        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight)
        }
        val decoded = BitmapFactory.decodeByteArray(raw, 0, raw.size, options)
            ?: throw AppError(ErrorCode.CAPTURE_FAILED)

        val (rotatedW, rotatedH) = ImageSizing.rotatedSize(decoded.width, decoded.height, rotationDegrees)
        val (targetW, targetH) = ImageSizing.targetSize(rotatedW, rotatedH)
        val scale = targetW.toFloat() / rotatedW
        val matrix = Matrix().apply {
            postRotate(rotationDegrees.toFloat())
            postScale(scale, scale)
        }
        val output = Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, matrix, true)
        if (output !== decoded) decoded.recycle()

        val stream = ByteArrayOutputStream()
        val ok = output.compress(Bitmap.CompressFormat.JPEG, ImageSizing.JPEG_QUALITY, stream)
        output.recycle()
        if (!ok) throw AppError(ErrorCode.CAPTURE_FAILED)
        return stream.toByteArray()
    }
}
