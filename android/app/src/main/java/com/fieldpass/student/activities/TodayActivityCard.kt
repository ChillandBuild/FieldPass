package com.fieldpass.student.activities

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldpass.student.R
import com.fieldpass.student.core.model.Action
import com.fieldpass.student.core.model.Activity
import com.fieldpass.student.core.time.TimeFormat
import com.fieldpass.student.ui.PrimaryButton
import com.fieldpass.student.ui.StatusPresentation

private val ActionButtonHeight = 62.dp
private val NameSize = 26.sp
private val NameLineHeight = 32.sp
private val NameTracking = (-1).sp

/**
 * The large home card: one activity, its three steps and the one thing the student can do now.
 * [canAct] is false while the enrolment photo is not approved, so no action is offered.
 */
@Composable
fun TodayActivityCard(
    activity: Activity,
    canAct: Boolean,
    onOpen: () -> Unit,
    onAction: (Action) -> Unit,
    modifier: Modifier = Modifier,
) {
    val action = if (canAct) ActivityActions.visible(activity).firstOrNull() else null
    Surface(
        onClick = onOpen,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.extraLarge,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(start = 24.dp, top = 40.dp, end = 24.dp, bottom = 24.dp)) {
            Text(
                stringResource(if (TodayActivity.isToday(activity)) R.string.activity_today_label else R.string.activity_next_label),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 10.dp),
            )
            Spacer(Modifier.height(36.dp))
            Text(
                activity.name,
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontSize = NameSize,
                    lineHeight = NameLineHeight,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = NameTracking,
                ),
                modifier = Modifier.semantics { heading() },
            )
            Spacer(Modifier.height(18.dp))
            Text(subtitle(activity), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(44.dp))
            ProgressTracker(ActivityProgress.steps(activity), activity.myAttendance?.status)
            if (action != null) {
                Spacer(Modifier.height(40.dp))
                PrimaryButton(
                    stringResource(StatusPresentation.actionLabel(action)),
                    onClick = { onAction(action) },
                    modifier = Modifier.heightIn(min = ActionButtonHeight),
                    showArrow = true,
                    shape = MaterialTheme.shapes.large,
                )
            } else if (canAct) {
                Spacer(Modifier.height(32.dp))
                Text(
                    stringResource(R.string.activity_no_actions),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** "Engineering block · Until 13:00" while it runs; the full date and times otherwise. */
@Composable
private fun subtitle(activity: Activity): String =
    if (TodayActivity.isRunning(activity)) {
        stringResource(R.string.activity_until_format, activity.venueName, TimeFormat.formatTime(activity.endsAt))
    } else {
        stringResource(R.string.activity_subtitle_format, activity.venueName, TimeFormat.formatRange(activity.startsAt, activity.endsAt))
    }
