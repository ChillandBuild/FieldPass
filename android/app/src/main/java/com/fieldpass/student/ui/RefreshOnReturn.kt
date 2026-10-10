package com.fieldpass.student.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.LifecycleResumeEffect

/**
 * Runs [onRefresh] each time the screen comes back to the foreground, but not on first show.
 * The flag is saveable because a navigation destination leaves composition while another screen
 * is on top; plain remember would reset it and the return would look like a first show.
 */
@Composable
fun RefreshOnReturn(onRefresh: () -> Unit) {
    var isFirstResume by rememberSaveable { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        if (isFirstResume) isFirstResume = false else onRefresh()
        onPauseOrDispose { }
    }
}
