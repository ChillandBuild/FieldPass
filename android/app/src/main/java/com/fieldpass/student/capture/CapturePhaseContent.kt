package com.fieldpass.student.capture

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.ui.GatedPermission
import com.fieldpass.student.ui.PermissionGate
import com.fieldpass.student.ui.PrimaryButton
import com.fieldpass.student.ui.SecondaryButton
import com.fieldpass.student.ui.SectionCard
import com.fieldpass.student.ui.StatusChip
import com.fieldpass.student.ui.StatusPresentation
import kotlin.math.roundToInt

@Composable
fun CapturePhaseContent(state: CaptureUiState, vm: AttendanceCaptureViewModel, onFinished: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (state.phase) {
            CapturePhase.CREATING_ATTEMPT -> Progress(R.string.attempt_starting)
            CapturePhase.LOCATING -> {
                state.error?.let { ErrorText(it) }
                Progress(R.string.attempt_locating)
            }
            CapturePhase.LOCATION_FAILED -> LocationFailed(state.error ?: ErrorCode.LOCATION_TIMEOUT, vm)
            CapturePhase.POOR_ACCURACY -> PoorAccuracy(state, vm)
            CapturePhase.AWAITING_CODE -> CodeStep(state, vm)
            CapturePhase.AWAITING_SELFIE -> SelfieStep(state, vm)
            CapturePhase.READY_TO_SUBMIT, CapturePhase.SUBMITTING -> ReadyToSend(state, vm)
            CapturePhase.DONE -> Done(state, onFinished)
            CapturePhase.EXPIRED -> Expired(vm, onFinished)
            CapturePhase.ATTEMPT_FAILED -> Failed(state.error ?: ErrorCode.UNKNOWN, onFinished)
        }
    }
}

@Composable
private fun Progress(textRes: Int) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator()
        Text(stringResource(textRes))
    }
}

@Composable
private fun ErrorText(code: ErrorCode) {
    Text(stringResource(code.messageRes), color = MaterialTheme.colorScheme.error)
}

@Composable
private fun LocationFailed(code: ErrorCode, vm: AttendanceCaptureViewModel) {
    val context = LocalContext.current
    Text(stringResource(R.string.attempt_location_failed_heading), style = MaterialTheme.typography.titleMedium)
    ErrorText(code)
    if (code == ErrorCode.LOCATION_SERVICES_OFF) {
        SecondaryButton(
            stringResource(R.string.attempt_open_location_settings),
            onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
        )
    }
    PrimaryButton(stringResource(R.string.action_retry), vm::retryLocation)
}

@Composable
private fun PoorAccuracy(state: CaptureUiState, vm: AttendanceCaptureViewModel) {
    Text(stringResource(R.string.attempt_poor_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.attempt_poor_body, (state.fix?.accuracyM ?: 0.0).roundToInt()))
    PrimaryButton(stringResource(R.string.attempt_poor_retry), vm::retryLocation)
    SecondaryButton(stringResource(R.string.attempt_poor_anyway), vm::acceptPoorAccuracy)
}

@Composable
private fun CodeStep(state: CaptureUiState, vm: AttendanceCaptureViewModel) {
    Text(stringResource(R.string.attempt_code_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.attempt_code_body))
    Column(Modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        when (state.codeStatus) {
            CodeStatus.SCANNING -> Unit
            CodeStatus.CHECKING -> Text(stringResource(R.string.attempt_code_checking))
            CodeStatus.TOO_OLD -> Text(stringResource(R.string.attempt_code_too_old), color = MaterialTheme.colorScheme.error)
            CodeStatus.FAILED -> {
                state.error?.let { ErrorText(it) }
                Text(stringResource(R.string.attempt_code_failed_note))
            }
        }
    }
    // Skip sits above the preview so it stays on screen without scrolling; it is also the way out if the camera is refused.
    SecondaryButton(stringResource(R.string.attempt_code_skip), vm::skipCode)
    PermissionGate(GatedPermission.CAMERA) {
        CodeScanner(onCode = vm::onCodeScanned, onUnavailable = vm::skipCode)
    }
}

@Composable
private fun SelfieStep(state: CaptureUiState, vm: AttendanceCaptureViewModel) {
    val attempt = state.attempt ?: return
    var onePhoto by rememberSaveable { mutableStateOf(false) }
    Text(stringResource(R.string.attempt_selfie_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.attempt_selfie_body))
    if (state.codeVerified) Text(stringResource(R.string.presence_code_scanned), color = MaterialTheme.colorScheme.tertiary)
    state.error?.let { ErrorText(it) }
    Text(stringResource(R.string.camera_tips), style = MaterialTheme.typography.bodySmall)
    PermissionGate(GatedPermission.CAMERA) {
        if (onePhoto) {
            Text(stringResource(R.string.liveness_one_photo_note), style = MaterialTheme.typography.bodySmall)
            CameraCapture(onCaptured = { vm.onPhotosCaptured(it, it.copyOf()) }, onCaptureFailed = vm::onCameraError)
        } else {
            LivenessCamera(
                challenge = attempt.challenge,
                onCaptured = vm::onPhotosCaptured,
                onCaptureFailed = vm::onCameraError,
                onUseOnePhoto = { onePhoto = true },
            )
        }
    }
}

@Composable
private fun ReadyToSend(state: CaptureUiState, vm: AttendanceCaptureViewModel) {
    val sending = state.phase == CapturePhase.SUBMITTING
    Text(stringResource(R.string.attempt_ready_heading), style = MaterialTheme.typography.titleMedium)
    state.selfie?.let { PhotoPreview(it) }
    state.turnPhoto?.let { PhotoPreview(it) }
    if (state.pendingReplay) {
        state.error?.let { ErrorText(it) }
        Text(stringResource(R.string.attempt_replay_note))
    }
    val label = when {
        sending -> R.string.attempt_sending
        state.pendingReplay -> R.string.attempt_send_again
        else -> R.string.attempt_send
    }
    PrimaryButton(stringResource(label), vm::submit, enabled = state.canSubmit)
    if (!state.pendingReplay) {
        SecondaryButton(stringResource(R.string.photo_retake), vm::retakeSelfie, enabled = !sending)
    }
}

@Composable
private fun Done(state: CaptureUiState, onFinished: () -> Unit) {
    val event = state.result
    Text(stringResource(R.string.attempt_done_heading), style = MaterialTheme.typography.titleMedium)
    if (event != null) {
        SectionCard {
            StatusChip(event.status)
            Text(stringResource(StatusPresentation.meaning(event.status)))
            if (event.presenceVerified) Text(stringResource(R.string.presence_code_scanned), style = MaterialTheme.typography.bodySmall)
            if (event.reviewReasons.isNotEmpty()) {
                Text(stringResource(R.string.reasons_heading), style = MaterialTheme.typography.labelLarge)
                event.reviewReasons.forEach { Text("- " + stringResource(StatusPresentation.reason(it)), style = MaterialTheme.typography.bodySmall) }
            }
        }
    }
    Text(stringResource(R.string.attempt_done_note), style = MaterialTheme.typography.bodySmall)
    PrimaryButton(stringResource(R.string.attempt_done_button), onFinished)
}

@Composable
private fun Expired(vm: AttendanceCaptureViewModel, onFinished: () -> Unit) {
    Text(stringResource(R.string.attempt_expired_heading), style = MaterialTheme.typography.titleMedium)
    Text(stringResource(R.string.attempt_expired_body))
    PrimaryButton(stringResource(R.string.attempt_start_again), vm::startOver)
    SecondaryButton(stringResource(R.string.action_close), onFinished)
}

@Composable
private fun Failed(code: ErrorCode, onFinished: () -> Unit) {
    Text(stringResource(R.string.attempt_failed_heading), style = MaterialTheme.typography.titleMedium)
    ErrorText(code)
    PrimaryButton(stringResource(R.string.action_close), onFinished)
}
