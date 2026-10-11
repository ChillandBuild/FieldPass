package com.fieldpass.student.activities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldpass.student.R
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.StatusPresentation

private val MarkerSize = 32.dp
private val ConnectorWidth = 1.dp
private const val CONNECTOR_ALPHA = 0.45f
private val StepGap = 38.dp
private val TitleSize = 17.sp
private val DetailSize = 15.sp

/** Check in, checkpoint, check out as a vertical list with a connecting line. */
@Composable
fun ProgressTracker(steps: List<ProgressStep>, checkInStatus: AttendanceStatus?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        steps.forEachIndexed { index, step ->
            TrackerRow(step, checkInStatus, position = index + 1, total = steps.size)
        }
    }
}

@Composable
private fun TrackerRow(step: ProgressStep, checkInStatus: AttendanceStatus?, position: Int, total: Int) {
    val isLast = position == total
    val (title, detail) = stepText(step, checkInStatus)
    val stateLabel = stringResource(
        when (step.state) {
            StepState.DONE -> R.string.step_state_done
            StepState.CURRENT -> R.string.step_state_current
            StepState.UPCOMING -> R.string.step_state_upcoming
        },
    )
    val positionLabel = stringResource(R.string.step_position_format, position, total)
    Row(
        Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .semantics(mergeDescendants = true) { stateDescription = "$positionLabel, $stateLabel" },
    ) {
        Column(Modifier.fillMaxHeight().width(MarkerSize), horizontalAlignment = Alignment.CenterHorizontally) {
            StepMarker(step.state, position)
            if (!isLast) {
                Box(Modifier.weight(1f).width(ConnectorWidth).background(MaterialTheme.colorScheme.outline.copy(alpha = CONNECTOR_ALPHA)))
            }
        }
        Column(
            Modifier.padding(start = 18.dp, bottom = if (isLast) 0.dp else StepGap),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontSize = TitleSize)
            Text(detail, style = MaterialTheme.typography.bodyMedium, fontSize = DetailSize, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StepMarker(state: StepState, position: Int) {
    val colors = MaterialTheme.colorScheme
    when (state) {
        StepState.DONE -> Box(Modifier.size(MarkerSize).background(colors.primary, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = colors.onPrimary, modifier = Modifier.size(16.dp))
        }
        // The next step and later steps look the same; the wording and the screen-reader state tell them apart.
        StepState.CURRENT, StepState.UPCOMING -> Surface(
            shape = CircleShape,
            color = colors.surface,
            border = BorderStroke(1.dp, colors.outlineVariant),
            modifier = Modifier.size(MarkerSize),
        ) { MarkerNumber(position, colors.onSurfaceVariant) }
    }
}

@Composable
private fun MarkerNumber(position: Int, color: Color) {
    Box(contentAlignment = Alignment.Center) {
        Text(position.toString(), style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun stepText(step: ProgressStep, checkInStatus: AttendanceStatus?): Pair<String, String> = when (step.kind) {
    StepKind.CHECK_IN ->
        if (step.state == StepState.DONE) {
            stringResource(R.string.step_checkin_done) to stringResource(
                R.string.step_detail_time_status_format,
                TimeFormat.formatTime(step.timeIso),
                stringResource(StatusPresentation.awaitingLabel(checkInStatus)),
            )
        } else {
            stringResource(R.string.step_checkin_todo) to stringResource(R.string.step_checkin_todo_detail)
        }
    StepKind.CHECKPOINT -> when {
        step.state == StepState.CURRENT ->
            stringResource(R.string.step_checkpoint_open) to stringResource(R.string.step_checkpoint_open_detail, TimeFormat.formatTime(step.timeIso))
        step.state == StepState.DONE ->
            stringResource(R.string.step_checkpoint_done) to stringResource(R.string.step_checkpoint_done_detail, step.count)
        step.skipped ->
            stringResource(R.string.step_checkpoint_todo) to stringResource(R.string.step_checkpoint_skipped_detail)
        else ->
            stringResource(R.string.step_checkpoint_todo) to stringResource(R.string.step_checkpoint_todo_detail)
    }
    StepKind.CHECK_OUT ->
        if (step.state == StepState.DONE) {
            stringResource(R.string.step_checkout_done) to TimeFormat.formatTime(step.timeIso)
        } else {
            stringResource(R.string.step_checkout_todo) to stringResource(R.string.step_checkout_todo_detail)
        }
}
