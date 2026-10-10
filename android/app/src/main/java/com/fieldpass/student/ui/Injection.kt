package com.fieldpass.student.ui

import androidx.compose.runtime.staticCompositionLocalOf
import com.fieldpass.student.AppContainer

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
