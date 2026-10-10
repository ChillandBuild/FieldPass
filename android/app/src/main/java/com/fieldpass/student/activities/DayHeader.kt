package com.fieldpass.student.activities

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldpass.student.R
import java.time.LocalTime

/** Home greeting: a small time-of-day line and a large summary of the day. */
@Composable
fun DayHeader(firstName: String?, isOnTrack: Boolean, modifier: Modifier = Modifier) {
    val period = remember { GreetingPeriod.forHour(LocalTime.now().hour) }
    val greeting = stringResource(
        when (period) {
            GreetingPeriod.MORNING -> R.string.greeting_morning
            GreetingPeriod.AFTERNOON -> R.string.greeting_afternoon
            GreetingPeriod.EVENING -> R.string.greeting_evening
        },
    )
    val eyebrow = if (firstName != null) stringResource(R.string.greeting_with_name_format, greeting, firstName) else greeting
    val headline = stringResource(if (isOnTrack) R.string.day_headline_on_track else R.string.day_headline_attention)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            eyebrow.uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            letterSpacing = 1.6.sp,
        )
        Text(headline, style = MaterialTheme.typography.displaySmall, modifier = Modifier.semantics { heading() })
    }
}
