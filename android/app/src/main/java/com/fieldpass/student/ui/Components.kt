package com.fieldpass.student.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fieldpass.student.R
import com.fieldpass.student.core.error.ErrorCode
import com.fieldpass.student.core.model.AttendanceStatus

val MinTouchTarget = 48.dp

private const val BADGE_VIEWPORT = 48f
private const val BADGE_SKEW_Y = -0.08748866f
private const val BADGE_SHIFT_Y = 2.0997278f

// Same shapes as dashboard/public/favicon.svg: a tile with one sharp corner, tilted 5 degrees, and an outlined Georgia Bold f.
private const val BADGE_TILE_PATH = "M14 3H34A11 11 0 0 1 45 14V34A11 11 0 0 1 34 45H6A3 3 0 0 1 3 42V14A11 11 0 0 1 14 3Z"
private const val BADGE_LETTER_PATH =
    "M33.4110,13.4428Q33.4110,13.8090 33.2938,14.2587Q33.1767,14.7084 32.9130,15.0391Q32.6054,15.4322 32.2245,15.6559" +
        "Q31.8437,15.8797 31.0819,15.9463Q29.9247,16.0476 29.2655,15.2996Q28.6063,14.5516 27.8593,12.7419" +
        "Q27.2733,13.0422 26.9071,14.1143Q26.5409,15.1864 26.5409,18.6141L26.5409,19.7714L30.5839,19.4176L30.5839,20.9997" +
        "L26.5702,21.3508L26.5702,31.4436Q26.5702,32.0149 26.8339,32.3068Q27.0976,32.5986 27.5224,32.7079" +
        "Q27.8739,32.7944 28.4452,32.8543Q29.0165,32.9141 29.4706,32.9184L29.4706,33.9877L19.5829,34.8528L19.5829,33.7834" +
        "Q19.9638,33.7208 20.3227,33.6455Q20.6815,33.5701 20.9599,33.4432Q21.4286,33.2264 21.5971,32.8821" +
        "Q21.7655,32.5378 21.7655,31.9958L21.7655,21.7712L18.7919,22.0313L18.7919,20.4493L21.7655,20.1891" +
        "Q21.7655,18.6218 22.2196,17.1099Q22.6737,15.5980 23.6259,14.3574Q24.5487,13.1341 26.0575,12.2990" +
        "Q27.5663,11.4639 29.6024,11.2857Q31.4188,11.1268 32.4149,11.7281Q33.4110,12.3295 33.4110,13.4428Z"

/** The FieldPass badge, drawn from the same shapes as the dashboard favicon. Decorative: the product name sits beside it. */
@Composable
fun BrandMark(modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tileColor = MaterialTheme.colorScheme.primary
    val letterColor = MaterialTheme.colorScheme.onPrimary
    val tile = remember {
        val tilt = Matrix().apply {
            values[Matrix.SkewY] = BADGE_SKEW_Y
            values[Matrix.TranslateY] = BADGE_SHIFT_Y
        }
        PathParser().parsePathString(BADGE_TILE_PATH).toPath().apply { transform(tilt) }
    }
    val letter = remember { PathParser().parsePathString(BADGE_LETTER_PATH).toPath() }
    Canvas(modifier.size(size).clearAndSetSemantics { }) {
        scale(this.size.width / BADGE_VIEWPORT, pivot = Offset.Zero) {
            drawPath(tile, tileColor)
            drawPath(letter, letterColor)
        }
    }
}

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
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    showArrow: Boolean = false,
    shape: Shape = MaterialTheme.shapes.small,
) {
    Button(onClick = onClick, enabled = enabled, shape = shape, modifier = modifier.fillMaxWidth().heightIn(min = 52.dp)) {
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
