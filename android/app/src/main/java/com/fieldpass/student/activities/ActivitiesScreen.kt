package com.fieldpass.student.activities

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.SecondaryButton
import com.fieldpass.student.ui.SectionCard
import com.fieldpass.student.ui.StatusChip

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    viewModel: ActivitiesViewModel,
    onOpenActivity: (String) -> Unit,
    onOpenPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingBox()
            state.error != null && state.activities.isEmpty() -> ErrorPanel(state.error!!, viewModel::refresh)
            else -> LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                item { Text(stringResource(R.string.activities_title), style = MaterialTheme.typography.headlineSmall) }
                val enrollment = state.enrollmentStatus
                if (enrollment != null && enrollment != EnrollmentStatus.APPROVED) {
                    item {
                        SectionCard {
                            Text(stringResource(R.string.activities_enrollment_notice))
                            SecondaryButton(stringResource(R.string.activities_open_photo), onOpenPhoto)
                        }
                    }
                }
                state.error?.let { code -> item { Text(stringResource(code.messageRes), color = MaterialTheme.colorScheme.error) } }
                if (state.activities.isEmpty()) {
                    item { Text(stringResource(R.string.activities_empty)) }
                }
                items(state.activities, key = { it.id }) { activity ->
                    ActivityCard(activity, onClick = { onOpenActivity(activity.id) })
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: Activity, onClick: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(activity.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.activity_venue_format, activity.venueName), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.activity_schedule_format, TimeFormat.formatRange(activity.startsAt, activity.endsAt)),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(requiredText(), style = MaterialTheme.typography.bodySmall)
            if (activity.openCheckpoint != null) {
                Text(stringResource(R.string.activity_checkpoint_open_short), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
            StatusChip(activity.myAttendance?.status)
        }
    }
}

@Composable
internal fun requiredText(): String = stringResource(R.string.activity_required_with_checkpoints)
