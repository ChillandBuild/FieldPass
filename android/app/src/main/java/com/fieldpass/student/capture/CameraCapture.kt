package com.fieldpass.student.capture

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.ui.PrimaryButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * In-app front-camera capture. There is deliberately no gallery picker, so every photo is fresh.
 * The JPEG is processed (upright, downscaled) and handed back in memory; nothing is written to disk.
 */
@Composable
fun CameraCapture(
    onCaptured: (ByteArray) -> Unit,
    onCaptureFailed: (ErrorCode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    var capturing by remember { mutableStateOf(false) }
    val previewDescription = stringResource(R.string.camera_preview_description)

    DisposableEffect(lifecycleOwner) {
        onDispose { runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() } }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        AndroidView(
            modifier = Modifier.fillMaxWidth().aspectRatio(3f / 4f).semantics { contentDescription = previewDescription },
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    val future = ProcessCameraProvider.getInstance(ctx)
                    future.addListener({
                        try {
                            val provider = future.get()
                            val preview = Preview.Builder().build().also { it.surfaceProvider = this.surfaceProvider }
                            provider.unbindAll()
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture)
                        } catch (_: Exception) {
                            onCaptureFailed(ErrorCode.CAPTURE_FAILED)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        PrimaryButton(
            text = stringResource(if (capturing) R.string.camera_taking_photo else R.string.camera_take_photo),
            enabled = !capturing,
            onClick = {
                capturing = true
                imageCapture.takePicture(
                    ContextCompat.getMainExecutor(context),
                    object : ImageCapture.OnImageCapturedCallback() {
                        override fun onCaptureSuccess(image: ImageProxy) {
                            val raw = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                            val rotation = image.imageInfo.rotationDegrees
                            image.close()
                            scope.launch {
                                val result = runCatching { withContext(Dispatchers.Default) { JpegProcessor.process(raw, rotation) } }
                                capturing = false
                                result.onSuccess(onCaptured).onFailure { onCaptureFailed(ErrorCode.CAPTURE_FAILED) }
                            }
                        }

                        override fun onError(exception: ImageCaptureException) {
                            capturing = false
                            onCaptureFailed(ErrorCode.CAPTURE_FAILED)
                        }
                    },
                )
            },
        )
    }
}
