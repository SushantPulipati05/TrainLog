package com.example.workoutlog_androidstudio

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val AppBackground = Color(0xFF0B0B0D)
val AppSurface = Color(0xFF19191C)
val AppSurfaceVariant = Color(0xFF232327)
val AppBorder = Color(0xFF2A2A2E)
val AppAccent = Color(0xFFFF6A3D)
val AppTextPrimary = Color(0xFFF5F5F7)
val AppTextSecondary = Color(0xFF9A9AA0)
val AppTextMuted = Color(0xFF6E6E74)
val AppOnAccent = Color(0xFF1A0E08)
val AppDanger = Color(0xFFD64545)
val AppSuccess = Color(0xFF4CAF50)

private val AppColorScheme = darkColorScheme(
    background = AppBackground,
    surface = AppSurface,
    surfaceVariant = AppSurfaceVariant,
    primary = AppAccent,
    onPrimary = AppOnAccent,
    onBackground = AppTextPrimary,
    onSurface = AppTextPrimary,
    outline = AppBorder
)

private val AppFontFamily = FontFamily.Default

private val baseTypography = Typography()

val AppTypography = Typography(
    displayLarge = baseTypography.displayLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    displayMedium = baseTypography.displayMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    displaySmall = baseTypography.displaySmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    headlineLarge = baseTypography.headlineLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    headlineMedium = baseTypography.headlineMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    headlineSmall = baseTypography.headlineSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.Bold),
    titleLarge = baseTypography.titleLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
    titleMedium = baseTypography.titleMedium.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
    titleSmall = baseTypography.titleSmall.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
    bodyLarge = baseTypography.bodyLarge.copy(fontFamily = AppFontFamily),
    bodyMedium = baseTypography.bodyMedium.copy(fontFamily = AppFontFamily),
    bodySmall = baseTypography.bodySmall.copy(fontFamily = AppFontFamily),
    labelLarge = baseTypography.labelLarge.copy(fontFamily = AppFontFamily, fontWeight = FontWeight.SemiBold),
    labelMedium = baseTypography.labelMedium.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp
    ),
    labelSmall = baseTypography.labelSmall.copy(
        fontFamily = AppFontFamily,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.6.sp
    )
)

@Composable
fun WorkoutLogTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColorScheme,
        typography = AppTypography,
        content = content
    )
}
