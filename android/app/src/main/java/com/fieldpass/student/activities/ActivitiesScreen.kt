package com.fieldpass.student.activities

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.model.EnrollmentStatus
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.ErrorPanel
import com.fieldpass.student.ui.LoadingBox
import com.fieldpass.student.ui.SecondaryButton
import com.fieldpass.student.ui.SectionCard
import com.fieldpass.student.ui.StatusChip

private val PageGutter = 24.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActivitiesScreen(
    viewModel: ActivitiesViewModel,
    onOpenActivity: (String) -> Unit,
    onStartAction: (activityId: String, action: Action) -> Unit,
    onOpenPhoto: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PullToRefreshBox(isRefreshing = state.isRefreshing, onRefresh = viewModel::refresh, modifier = modifier.fillMaxSize()) {
        when {
            state.isLoading -> LoadingBox()
            state.error != null && state.activities.isEmpty() -> ErrorPanel(state.error!!, viewModel::refresh)
            else -> {
                val enrollment = state.enrollmentStatus
                val needsEnrollment = enrollment != null && enrollment != EnrollmentStatus.APPROVED
                val today = remember(state.activities) { TodayActivity.pick(state.activities) }
                val others = state.activities.filter { it.id != today?.id }
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = PageGutter, top = 44.dp, end = PageGutter, bottom = 32.dp),
                ) {
                    item { DayHeader(state.firstName, DaySummary.isOnTrack(state.activities)) }
                    if (needsEnrollment) {
                        item {
                            SectionCard(Modifier.padding(top = 32.dp)) {
                                Text(stringResource(R.string.activities_enrollment_notice))
                                SecondaryButton(stringResource(R.string.activities_open_photo), onOpenPhoto)
                            }
                        }
                    }
                    state.error?.let { code ->
                        item { Text(stringResource(code.messageRes), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 24.dp)) }
                    }
                    if (today != null) {
                        item(key = today.id) {
                            TodayActivityCard(
                                activity = today,
                                canAct = !needsEnrollment,
                                onOpen = { onOpenActivity(today.id) },
                                onAction = { action -> onStartAction(today.id, action) },
                                modifier = Modifier.padding(top = 36.dp),
                            )
                        }
                        if (!needsEnrollment && ActivityActions.visible(today).isNotEmpty()) {
                            item {
                                Text(
                                    stringResource(R.string.activity_submit_note),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = 24.dp),
                                )
                            }
                        }
                    }
                    if (today == null || others.isNotEmpty()) {
                        item {
                            Text(
                                stringResource(if (today == null) R.string.activities_title else R.string.activities_other_title),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = if (today == null) 36.dp else 48.dp, bottom = 4.dp),
                            )
                        }
                    }
                    if (state.activities.isEmpty()) {
                        item { Text(stringResource(R.string.activities_empty), modifier = Modifier.padding(top = 12.dp)) }
                    }
                    items(others, key = { it.id }) { activity ->
                        ActivityCard(activity, onClick = { onOpenActivity(activity.id) }, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun ActivityCard(activity: Activity, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 24.dp, vertical = 22.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(activity.name, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(R.string.activity_subtitle_format, activity.venueName, TimeFormat.formatRange(activity.startsAt, activity.endsAt)),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                activity.myAttendance?.status?.let { StatusChip(it) }
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
