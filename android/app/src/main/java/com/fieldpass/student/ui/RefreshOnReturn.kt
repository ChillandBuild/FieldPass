package com.fieldpass.student.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/** Runs [onRefresh] each time the screen comes back to the foreground, but not on first show. */
@Composable
fun RefreshOnReturn(onRefresh: () -> Unit) {
    var isFirstResume by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (isFirstResume) isFirstResume = false else onRefresh()
        onPauseOrDispose { }
    }
}
