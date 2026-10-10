package com.fieldpass.student.enrollment

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.R
import com.fieldpass.student.capture.CameraCapture
import com.fieldpass.student.capture.PhotoPreview
import com.fieldpass.student.core.model.Enrollment
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.GatedPermission
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.PermissionGate
import com.fieldpass.student.ui.PrimaryButton
import com.fieldpass.student.ui.SecondaryButton
import com.fieldpass.student.ui.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnrollmentScreen(viewModel: EnrollmentViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(enabled = state.step != EnrollmentStep.STATUS) { viewModel.cancelCapture() }

    if (state.confirmReplace) {
        AlertDialog(
            onDismissRequest = viewModel::dismissReplace,
            title = { Text(stringResource(R.string.enrollment_replace_title)) },
            text = { Text(stringResource(R.string.enrollment_replace_body)) },
            confirmButton = { TextButton(onClick = viewModel::confirmReplace) { Text(stringResource(R.string.enrollment_replace_confirm)) } },
            dismissButton = { TextButton(onClick = viewModel::dismissReplace) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    when (state.step) {
        EnrollmentStep.STATUS -> PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
            when {
                state.isLoading -> LoadingBox()
                state.enrollment == null -> ErrorPanel(state.loadError ?: com.fieldpass.student.core.error.ErrorCode.UNKNOWN, viewModel::refresh)
                else -> StatusContent(state.enrollment!!, viewModel)
            }
        }
        EnrollmentStep.CAMERA -> Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.enrollment_camera_heading), style = MaterialTheme.typography.titleLarge)
            state.guidance?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
            Text(stringResource(R.string.camera_tips), style = MaterialTheme.typography.bodyMedium)
            PermissionGate(GatedPermission.CAMERA) {
                CameraCapture(onCaptured = viewModel::onPhoto, onCaptureFailed = viewModel::onCameraError)
            }
            SecondaryButton(stringResource(R.string.action_cancel), viewModel::cancelCapture)
        }
        EnrollmentStep.PREVIEW -> Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.enrollment_preview_heading), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.enrollment_preview_body))
            state.photo?.let { PhotoPreview(it) }
            state.guidance?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
            PrimaryButton(
                stringResource(if (state.isSubmitting) R.string.enrollment_submitting else R.string.enrollment_submit),
                viewModel::submit, enabled = !state.isSubmitting,
            )
            SecondaryButton(stringResource(R.string.photo_retake), viewModel::retake, enabled = !state.isSubmitting)
        }
    }
}

@Composable
private fun StatusContent(enrollment: Enrollment, viewModel: EnrollmentViewModel) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.enrollment_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.enrollment_intro))
        SectionCard {
            val (heading, body) = when (enrollment.status) {
                EnrollmentStatus.NONE -> R.string.enrollment_none_heading to stringResource(R.string.enrollment_none_body)
                EnrollmentStatus.PENDING -> R.string.enrollment_pending_heading to
                    stringResource(R.string.enrollment_pending_body, TimeFormat.formatDateTime(enrollment.submittedAt))
                EnrollmentStatus.APPROVED -> R.string.enrollment_approved_heading to stringResource(
                    R.string.enrollment_approved_body, enrollment.decidedBy.orEmpty(), TimeFormat.formatDateTime(enrollment.decidedAt),
                )
                EnrollmentStatus.REJECTED -> R.string.enrollment_rejected_heading to stringResource(R.string.enrollment_rejected_body)
            }
            Text(stringResource(heading), style = MaterialTheme.typography.titleMedium)
            Text(body)
            if (enrollment.status == EnrollmentStatus.REJECTED && !enrollment.decisionReason.isNullOrBlank()) {
                Text(stringResource(R.string.enrollment_rejected_reason, enrollment.decisionReason), style = MaterialTheme.typography.bodyMedium)
            }
        }
        val labelRes = when (enrollment.status) {
            EnrollmentStatus.NONE -> R.string.enrollment_take_photo
            EnrollmentStatus.REJECTED -> R.string.enrollment_take_new_photo
            else -> R.string.enrollment_replace_photo
        }
        if (enrollment.status == EnrollmentStatus.NONE || enrollment.status == EnrollmentStatus.REJECTED) {
            PrimaryButton(stringResource(labelRes), viewModel::beginCapture)
        } else {
            SecondaryButton(stringResource(labelRes), viewModel::beginCapture)
        }
    }
}
