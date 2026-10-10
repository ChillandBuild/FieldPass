package com.fieldpass.student.capture

import android.content.Context
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.HeadTurn
import com.fieldpass.student.ui.SecondaryButton
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STUCK_AFTER_MILLIS = 20_000L
private const val STEP_COUNT = 3

/** What the screen shows, plus the front photo waiting for its partner. Memory only; wiped when dropped. */
private class LivenessSession {
    var guide by mutableStateOf(LivenessState())
    var faceVisible by mutableStateOf(true)
    var capturing by mutableStateOf(false)
    var front: ByteArray? = null

    fun dropFront() {
        front?.fill(0)
        front = null
    }

    fun startAgain() {
        dropFront()
        guide = LivenessState()
        capturing = false
    }
}

/**
 * Guided front-camera capture: look straight (photo A), blink, turn as [challenge] asks (photo B).
 * The app presses the shutter itself. Face detection runs on the phone, offline; frames are never stored or sent.
 */
@Composable
fun LivenessCamera(
    challenge: HeadTurn,
    onCaptured: (selfie: ByteArray, turnPhoto: ByteArray) -> Unit,
    onCaptureFailed: (ErrorCode) -> Unit,
    onUseOnePhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val imageCapture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
    val analysis = remember { ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build() }
    val detector = remember { FaceDetection.getClient(detectorOptions()) }
    val session = remember { LivenessSession() }
    var stuck by remember { mutableStateOf(false) }
    val currentChallenge by rememberUpdatedState(challenge)
    val currentOnCaptured by rememberUpdatedState(onCaptured)
    val currentOnFailed by rememberUpdatedState(onCaptureFailed)
    val previewDescription = stringResource(R.string.camera_preview_description)

    fun onShot(shot: LivenessShot, jpeg: ByteArray) {
        session.capturing = false
        if (shot == LivenessShot.FRONT) {
            session.front = jpeg
            return
        }
        val front = session.front
        session.front = null // handed to the caller, who now owns and wipes it
        if (front == null) currentOnFailed(ErrorCode.CAPTURE_FAILED) else currentOnCaptured(front, jpeg)
    }

    fun onReading(reading: FaceReading) {
        if (session.capturing) return
        session.faceVisible = reading.faces == 1
        val before = session.guide.step
        val update = LivenessGuide.next(session.guide, reading, currentChallenge)
        session.guide = update.state
        if (before != LivenessStep.LOOK_STRAIGHT && update.state.step == LivenessStep.LOOK_STRAIGHT) session.dropFront()
        val shot = update.shot ?: return
        session.capturing = true
        imageCapture.takeJpeg(
            context,
            scope,
            onFailed = {
                session.startAgain()
                currentOnFailed(ErrorCode.CAPTURE_FAILED)
            },
            onJpeg = { onShot(shot, it) },
        )
    }

    LaunchedEffect(Unit) {
        delay(STUCK_AFTER_MILLIS)
        stuck = true
    }

    DisposableEffect(lifecycleOwner) {
        analysis.setAnalyzer(ContextCompat.getMainExecutor(context)) { proxy -> detector.read(proxy) { onReading(it) } }
        onDispose {
            analysis.clearAnalyzer()
            detector.close()
            session.dropFront()
            runCatching { ProcessCameraProvider.getInstance(context).get().unbindAll() }
        }
    }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(stringResource(R.string.liveness_step, stepNumber(session.guide.step), STEP_COUNT), style = MaterialTheme.typography.labelLarge)
        Text(
            stringResource(promptFor(session, challenge)),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
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
                            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, analysis)
                        } catch (_: Exception) {
                            // Some old phones cannot run preview + photo + analysis together: the one-photo path still works.
                            stuck = true
                            onCaptureFailed(ErrorCode.CAPTURE_FAILED)
                        }
                    }, ContextCompat.getMainExecutor(ctx))
                }
            },
        )
        if (stuck) SecondaryButton(stringResource(R.string.liveness_take_one_photo), onUseOnePhoto)
    }
}

private fun detectorOptions(): FaceDetectorOptions = FaceDetectorOptions.Builder()
    .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
    .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL) // eye-open values, for the blink
    .build()

private fun stepNumber(step: LivenessStep): Int = when (step) {
    LivenessStep.LOOK_STRAIGHT -> 1
    LivenessStep.BLINK -> 2
    LivenessStep.TURN, LivenessStep.DONE -> 3
}

@StringRes
private fun promptFor(session: LivenessSession, challenge: HeadTurn): Int = when {
    session.capturing -> R.string.liveness_taking_photo
    !session.faceVisible -> R.string.liveness_no_face
    session.guide.step == LivenessStep.LOOK_STRAIGHT -> R.string.liveness_look_straight
    session.guide.step == LivenessStep.BLINK -> R.string.liveness_blink
    challenge == HeadTurn.LEFT -> R.string.liveness_turn_left
    else -> R.string.liveness_turn_right
}

/** Runs the detector on one frame and always closes the frame. A frame the detector cannot read counts as "no face". */
@OptIn(ExperimentalGetImage::class)
private fun FaceDetector.read(proxy: ImageProxy, onReading: (FaceReading) -> Unit) {
    val media = proxy.image
    if (media == null) {
        proxy.close()
        return
    }
    process(InputImage.fromMediaImage(media, proxy.imageInfo.rotationDegrees))
        .addOnSuccessListener { faces -> onReading(faces.toReading()) }
        .addOnFailureListener { onReading(FaceReading(faces = 0)) }
        .addOnCompleteListener { proxy.close() }
}

private fun List<Face>.toReading(): FaceReading {
    val face = singleOrNull() ?: return FaceReading(faces = size)
    return FaceReading(1, face.headEulerAngleY, face.leftEyeOpenProbability, face.rightEyeOpenProbability)
}

private fun ImageCapture.takeJpeg(context: Context, scope: CoroutineScope, onFailed: () -> Unit, onJpeg: (ByteArray) -> Unit) {
    takePicture(
        ContextCompat.getMainExecutor(context),
        object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val raw = ByteArray(image.planes[0].buffer.remaining()).also { image.planes[0].buffer.get(it) }
                val rotation = image.imageInfo.rotationDegrees
                image.close()
                scope.launch {
                    runCatching { withContext(Dispatchers.Default) { JpegProcessor.process(raw, rotation) } }
                        .onSuccess(onJpeg)
                        .onFailure { onFailed() }
                }
            }

            override fun onError(exception: ImageCaptureException) = onFailed()
        },
    )
}
