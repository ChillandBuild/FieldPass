package com.fieldpass.student.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.fieldpass.student.R

private val Light = lightColorScheme(
    primary = Color(0xFFBD0B50),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFCE8EF),
    onPrimaryContainer = Color(0xFF830333),
    secondary = Color(0xFF236342),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8F4ED),
    onSecondaryContainer = Color(0xFF236342),
    tertiary = Color(0xFF714B08),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFF4DC),
    onTertiaryContainer = Color(0xFF714B08),
    background = Color(0xFFEDEEF7),
    onBackground = Color(0xFF262530),
    surface = Color.White,
    onSurface = Color(0xFF262530),
    surfaceVariant = Color(0xFFEDEEF7),
    onSurfaceVariant = Color(0xFF5D5C6C),
    surfaceDim = Color(0xFFE2E2ED),
    surfaceBright = Color.White,
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFFAFAFD),
    surfaceContainer = Color(0xFFF6F6FA),
    surfaceContainerHigh = Color(0xFFEDEEF7),
    surfaceContainerHighest = Color(0xFFE4E7F3),
    outline = Color(0xFF858594),
    outlineVariant = Color(0xFFDCDDE8),
    error = Color(0xFFA52B32),
    onError = Color.White,
    errorContainer = Color(0xFFFFE9EA),
    onErrorContainer = Color(0xFF7B1C25),
    inverseSurface = Color(0xFF302E3A),
    inverseOnSurface = Color(0xFFF5F3FB),
    inversePrimary = Color(0xFFFFA7C6),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFA7C6),
    onPrimary = Color(0xFF650029),
    primaryContainer = Color(0xFF6F1438),
    onPrimaryContainer = Color(0xFFFFD9E6),
    secondary = Color(0xFF9CD5B2),
    onSecondary = Color(0xFF00391F),
    secondaryContainer = Color(0xFF1B4530),
    onSecondaryContainer = Color(0xFFBCEBCB),
    tertiary = Color(0xFFE7C078),
    onTertiary = Color(0xFF402D04),
    tertiaryContainer = Color(0xFF514020),
    onTertiaryContainer = Color(0xFFFFDF9F),
    background = Color(0xFF17151D),
    onBackground = Color(0xFFE9E5EF),
    surface = Color(0xFF211F29),
    onSurface = Color(0xFFE9E5EF),
    surfaceVariant = Color(0xFF302D3A),
    onSurfaceVariant = Color(0xFFCAC4D4),
    surfaceDim = Color(0xFF17151D),
    surfaceBright = Color(0xFF3D3946),
    surfaceContainerLowest = Color(0xFF121017),
    surfaceContainerLow = Color(0xFF211F29),
    surfaceContainer = Color(0xFF27242F),
    surfaceContainerHigh = Color(0xFF302D3A),
    surfaceContainerHighest = Color(0xFF393745),
    outline = Color(0xFF948D9F),
    outlineVariant = Color(0xFF484350),
    error = Color(0xFFFFB3B8),
    onError = Color(0xFF640F1B),
    errorContainer = Color(0xFF76232C),
    onErrorContainer = Color(0xFFFFDADC),
    inverseSurface = Color(0xFFE9E5EF),
    inverseOnSurface = Color(0xFF302E3A),
    inversePrimary = Color(0xFFBD0B50),
)

/**
 * Both files are variable fonts (SIL Open Font License). The weight axis must be set explicitly:
 * without it every entry draws the file's default weight.
 */
private fun variableFont(resId: Int, weight: FontWeight) =
    Font(resId, weight = weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))

private val HeadingFamily = FontFamily(
    variableFont(R.font.inter, FontWeight.Normal),
    variableFont(R.font.inter, FontWeight.SemiBold),
    variableFont(R.font.inter, FontWeight.Bold),
)
private val BodyFamily = FontFamily(
    variableFont(R.font.figtree, FontWeight.Normal),
    variableFont(R.font.figtree, FontWeight.Medium),
    variableFont(R.font.figtree, FontWeight.SemiBold),
    variableFont(R.font.figtree, FontWeight.Bold),
)

private fun TextStyle.heading() = copy(fontFamily = HeadingFamily)
private fun TextStyle.body() = copy(fontFamily = BodyFamily)

private val CampusTypography = Typography().let { base ->
    base.copy(
        displayLarge = base.displayLarge.heading(),
        displayMedium = base.displayMedium.heading(),
        displaySmall = base.displaySmall.heading().copy(fontWeight = FontWeight.Bold, letterSpacing = (-1).sp),
        headlineLarge = base.headlineLarge.heading(),
        headlineMedium = base.headlineMedium.heading(),
        headlineSmall = base.headlineSmall.heading().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        titleLarge = base.titleLarge.heading().copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.3).sp),
        titleMedium = base.titleMedium.body().copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = base.titleSmall.body(),
        bodyLarge = base.bodyLarge.body(),
        bodyMedium = base.bodyMedium.body(),
        bodySmall = base.bodySmall.body(),
        labelLarge = base.labelLarge.body().copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.body(),
        labelSmall = base.labelSmall.body(),
    )
}

private val CampusShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(24.dp),
)

@Composable
fun FieldPassTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Dark else Light,
        typography = CampusTypography,
        shapes = CampusShapes,
        content = content,
    )
}
