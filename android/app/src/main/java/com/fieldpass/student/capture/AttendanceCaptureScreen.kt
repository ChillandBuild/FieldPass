package com.fieldpass.student.capture

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.GatedPermission
import com.fieldpass.student.ui.PermissionGate

/** Hosts one attendance attempt. Location permission is requested here, only for this attempt. */
@Composable
fun AttendanceCaptureScreen(
    viewModel: AttendanceCaptureViewModel,
    onFinished: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler { viewModel.discard(); onFinished() }
    // No discard-on-dispose here: a screen rotation also disposes this composition while the ViewModel
    // survives, and discarding would cancel an in-flight submit and leave the screen stuck. The
    // ViewModel wipes the selfie and stops its jobs in onCleared() when the user truly leaves.

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(titleRes(viewModel.actionType)), style = MaterialTheme.typography.headlineSmall)
        PermissionGate(GatedPermission.LOCATION) {
            LaunchedEffect(Unit) { viewModel.start() }
            Countdown(state)
            CapturePhaseContent(state, viewModel, onFinished)
        }
    }
}

@Composable
private fun Countdown(state: CaptureUiState) {
    val remaining = state.remainingMillis ?: return
    val running = state.phase !in setOf(CapturePhase.DONE, CapturePhase.EXPIRED, CapturePhase.ATTEMPT_FAILED)
    if (running) {
        Text(
            stringResource(R.string.attempt_countdown_format, TimeFormat.formatCountdown(remaining)),
            style = MaterialTheme.typography.titleMedium,
            color = if (remaining < 20_000) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun titleRes(action: Action): Int = when (action) {
    Action.CHECK_IN -> R.string.attempt_title_check_in
    Action.CHECKPOINT -> R.string.attempt_title_checkpoint
    Action.CHECK_OUT -> R.string.attempt_title_check_out
}
