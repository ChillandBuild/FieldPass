package com.fieldpass.student.capture

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Selfie

/** Preview of a just-captured JPEG, decoded once per capture. */
@Composable
fun PhotoPreview(photo: Selfie, modifier: Modifier = Modifier) {
    val bitmap = remember(photo) { BitmapFactory.decodeByteArray(photo.bytes, 0, photo.bytes.size)?.asImageBitmap() }
    if (bitmap != null) {
        Image(
            bitmap = bitmap,
            contentDescription = stringResource(R.string.photo_preview_description),
            contentScale = ContentScale.Fit,
            modifier = modifier.fillMaxWidth().aspectRatio(bitmap.width.toFloat() / bitmap.height),
        )
    }
}
