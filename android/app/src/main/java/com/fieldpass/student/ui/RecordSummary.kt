package com.fieldpass.student.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fieldpass.student.R
import com.fieldpass.student.core.model.AttendanceEvent
import com.fieldpass.student.core.model.AttendanceRecord
import com.fieldpass.student.core.model.AttendanceStatus
import com.fieldpass.student.core.time.TimeFormat

/** Everything the student can see about one activity's record, using the contract's status meanings. */
@Composable
fun RecordSummary(record: AttendanceRecord, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        StatusChip(record.status)
        record.status?.let { Text(stringResource(StatusPresentation.meaning(it)), style = MaterialTheme.typography.bodyMedium) }
        DecisionLine(record)
        if (record.status == AttendanceStatus.NEEDS_REVIEW && record.reviewReasons.isNotEmpty()) {
            Text(stringResource(R.string.reasons_heading), style = MaterialTheme.typography.labelLarge)
            record.reviewReasons.forEach { Text("- " + stringResource(StatusPresentation.reason(it)), style = MaterialTheme.typography.bodySmall) }
        }
        val events = listOfNotNull(record.checkIn) + record.checkpoints + listOfNotNull(record.checkOut)
        if (events.isEmpty()) {
            Text(stringResource(R.string.activity_no_events), style = MaterialTheme.typography.bodyMedium)
        } else {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            events.forEach { EventLine(it) }
        }
        CompletionMarker(record)
    }
}

@Composable
private fun DecisionLine(record: AttendanceRecord) {
    val decision = record.decision?.takeIf { it.isCurrent } ?: return
    Text(
        stringResource(
            R.string.activity_decision_format,
            stringResource(StatusPresentation.label(record.status)),
            decision.actorName,
            TimeFormat.formatDateTime(decision.decidedAt),
        ),
        style = MaterialTheme.typography.bodyMedium,
    )
    if (decision.reason.isNotBlank()) {
        Text(stringResource(R.string.status_reason_format, decision.reason), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun EventLine(event: AttendanceEvent) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            stringResource(
                R.string.event_at_format,
                stringResource(StatusPresentation.eventLabel(event.action)),
                TimeFormat.formatTime(event.receivedAt),
            ) + " - " + stringResource(StatusPresentation.label(event.status)),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
    }
}

@Composable
private fun CompletionMarker(record: AttendanceRecord) {
    val markerRes = when {
        record.missingCheckout -> R.string.marker_missing_checkout
        record.complete -> R.string.marker_complete
        record.checkIn != null -> R.string.marker_incomplete
        else -> return
    }
    Text(stringResource(markerRes), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
}
