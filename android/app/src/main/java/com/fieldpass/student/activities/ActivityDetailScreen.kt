package com.fieldpass.student.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.PrimaryButton
import com.fieldpass.student.ui.RecordSummary
import com.fieldpass.student.ui.SecondaryButton
import com.fieldpass.student.ui.SectionCard
import com.fieldpass.student.ui.StatusPresentation

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivityDetailScreen(
    viewModel: ActivityDetailViewModel,
    onAction: (activityId: String, action: Action) -> Unit,
    onOpenPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
        val activity = state.activity
        when {
            state.isLoading -> LoadingBox()
            activity == null -> ErrorPanel(state.error ?: com.fieldpass.student.core.error.ErrorCode.UNKNOWN, viewModel::refresh)
            else -> DetailContent(activity, state.needsEnrollment, state.error, onAction, onOpenPhoto)
        }
    }
}

@Composable
private fun DetailContent(
    activity: Activity,
    needsEnrollment: Boolean,
    refreshError: com.fieldpass.student.core.error.ErrorCode?,
    onAction: (String, Action) -> Unit,
    onOpenPhoto: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val actions = if (needsEnrollment) emptyList() else ActivityActions.visible(activity)
        SectionCard {
            Text(activity.name, style = MaterialTheme.typography.headlineSmall)
            Text(
                stringResource(R.string.activity_subtitle_format, activity.venueName, TimeFormat.formatRange(activity.startsAt, activity.endsAt)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ProgressTracker(ActivityProgress.steps(activity), activity.myAttendance?.status, Modifier.padding(vertical = 8.dp))
            if (needsEnrollment) {
                Text(stringResource(R.string.activities_enrollment_notice))
                SecondaryButton(stringResource(R.string.activities_open_photo), onOpenPhoto)
            } else if (actions.isEmpty()) {
                Text(stringResource(R.string.activity_no_actions), style = MaterialTheme.typography.bodyMedium)
            }
            actions.forEach { action ->
                PrimaryButton(stringResource(StatusPresentation.actionLabel(action)), onClick = { onAction(activity.id, action) }, showArrow = true)
            }
        }
        refreshError?.let { Text(stringResource(it.messageRes), color = MaterialTheme.colorScheme.error) }
        if (actions.isNotEmpty()) {
            Text(stringResource(R.string.activity_submit_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard {
            Text(stringResource(R.string.activity_record_heading), style = MaterialTheme.typography.titleMedium)
            val record = activity.myAttendance
            if (record == null) {
                Text(stringResource(R.string.activity_no_events))
            } else {
                RecordSummary(record)
            }
        }
        Text(stringResource(R.string.activity_times_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
