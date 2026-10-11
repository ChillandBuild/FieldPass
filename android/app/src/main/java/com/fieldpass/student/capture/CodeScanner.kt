package com.fieldpass.student.capture

import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldpass.student.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage

/**
 * Back-camera QR scanner. It only reports decoded strings; every decision about them (prefix, repeats,
 * what to send) lives in the ViewModel. Decoding runs on the phone with the bundled ML Kit model, offline;
 * frames are never stored or sent. Its use cases are unbound on dispose, before the selfie step binds the front camera.
 *
 * [onUnavailable] is called if the back camera cannot be bound, so the caller can move on without it.
 */
@Composable
fun CodeScanner(
    onCode: (String) -> Unit,
    onUnavailable: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnCode by rememberUpdatedState(onCode)
    val currentOnUnavailable by rememberUpdatedState(onUnavailable)
    val preview = remember { Preview.Builder().build() }
    val analysis = remember { ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() }
    val scanner = remember {
        BarcodeScanning.getClient(BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build())
    }
    val previewDescription = stringResource(R.string.code_scanner_preview_description)

    DisposableEffect(lifecycleOwner) {
        var disposed = false
        var provider: ProcessCameraProvider? = null
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy ->
            if (disposed) proxy.close() else scanner.read(proxy) { if (!disposed) currentOnCode(it) }
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameraProvider = future.get()
                if (!cameraProvider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA)) {
                    currentOnUnavailable()
                    return@addListener
                }
                cameraProvider.unbind(preview, analysis)
                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                provider = cameraProvider
            } catch (_: Exception) {
                // No back camera, or the camera is busy: the code only helps, so carry on without it.
                currentOnUnavailable()
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            provider?.unbind(preview, analysis)
            preview.setSurfaceProvider(null)
            scanner.close()
        }
    }

    AndroidView(
        modifier = modifier.fillMaxWidth().aspectRatio(1f).semantics { contentDescription = previewDescription },
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                preview.surfaceProvider = this.surfaceProvider
            }
        },
    )
}

/** Runs the QR decoder on one frame and always closes the frame. A frame that cannot be read is just a missed frame. */
@OptIn(ExperimentalGetImage::class)
private fun BarcodeScanner.read(proxy: ImageProxy, onCode: (String) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    val task = try {
        process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
    } catch (e: Exception) {
        proxy.close()
        return
    }
    task.addOnSuccessListener { barcodes -> barcodes.forEach { barcode -> barcode.rawValue?.let(onCode) } }
        .addOnCompleteListener { proxy.close() }
}
