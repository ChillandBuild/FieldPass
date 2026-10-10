package com.fieldpass.student.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.AttendanceStatus

val MinTouchTarget = 48.dp

@Composable
fun LoadingBox(modifier: Modifier = Modifier) {
    val label = stringResource(R.string.loading)
    Box(modifier.fillMaxSize().semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/** Plain-language error with an optional retry action. */
@Composable
fun ErrorPanel(code: ErrorCode, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        color = MaterialTheme.colorScheme.errorContainer,
        contentColor = MaterialTheme.colorScheme.onErrorContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(code.messageRes), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
            if (onRetry != null) {
                Button(onClick = onRetry, modifier = Modifier.heightIn(min = MinTouchTarget)) {
                    Text(stringResource(R.string.action_retry))
                }
            }
        }
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, showArrow: Boolean = false) {
    Button(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
        Text(text, textAlign = TextAlign.Center)
        if (showArrow) {
            Spacer(Modifier.width(8.dp))
            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    OutlinedButton(onClick = onClick, enabled = enabled, shape = MaterialTheme.shapes.small, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) { Text(text, textAlign = TextAlign.Center) }
}

@Composable
fun SectionCard(modifier: Modifier = Modifier, emphasized: Boolean = false, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, if (emphasized) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { content() }
    }
}

/** Status pill. Colour is never the only signal: the label text always shows. */
@Composable
fun StatusChip(status: AttendanceStatus?, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val (container, content) = when (status) {
        AttendanceStatus.CONFIRMED -> colors.secondaryContainer to colors.onSecondaryContainer
        AttendanceStatus.NEEDS_REVIEW -> colors.tertiaryContainer to colors.onTertiaryContainer
        AttendanceStatus.NOT_CONFIRMED -> colors.errorContainer to colors.onErrorContainer
        AttendanceStatus.RECORDED -> colors.surfaceContainerHighest to colors.onSurface
        null -> colors.surfaceContainer to colors.onSurfaceVariant
    }
    Surface(color = container, contentColor = content, shape = MaterialTheme.shapes.small, modifier = modifier) {
        Text(
            stringResource(StatusPresentation.label(status)),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
        )
    }
}
