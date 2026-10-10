package com.fieldpass.student.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1B5E7B),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFCDE9F6),
    onPrimaryContainer = Color(0xFF001F2C),
    tertiaryContainer = Color(0xFFFFE2B8),
    onTertiaryContainer = Color(0xFF2B1700),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8DCDEB),
    onPrimary = Color(0xFF00344A),
    primaryContainer = Color(0xFF004D68),
    onPrimaryContainer = Color(0xFFCDE9F6),
    tertiaryContainer = Color(0xFF5A3A00),
    onTertiaryContainer = Color(0xFFFFE2B8),
)

@Composable
fun FieldPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
