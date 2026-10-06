package com.example.workoutlog_androidstudio

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

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
        typography = AppTypography
    ) {
        CompositionLocalProvider(
            // Material's ripples (IconButton, TextButton...) take their color
            // from LocalContentColor, which defaults to black - invisible on
            // this dark theme. Light content color makes them show up.
            LocalContentColor provides AppTextPrimary,
            // Every plain .clickable { } in the app uses this: a quick light
            // flash over the tapped element, so a tap always visibly registers.
            LocalIndication provides PressHighlightIndication,
            content = content
        )
    }
}

/** How strong the white flash over a pressed element is. */
private const val PRESSED_HIGHLIGHT_ALPHA = 0.12f

/**
 * Tap feedback for anything made clickable with Modifier.clickable: the
 * element lightens the instant it's pressed, then fades back over 250 ms
 * once released - long enough to see even on a quick tap. Clip the element
 * to its shape before .clickable so the flash follows rounded corners.
 */
private object PressHighlightIndication : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        PressHighlightNode(interactionSource)

    override fun equals(other: Any?): Boolean = other === this

    override fun hashCode(): Int = -1
}

private class PressHighlightNode(
    private val interactionSource: InteractionSource
) : Modifier.Node(), DrawModifierNode {
    private val highlightAlpha = Animatable(0f)

    override fun onAttach() {
        coroutineScope.launch {
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> launch { highlightAlpha.snapTo(PRESSED_HIGHLIGHT_ALPHA) }
                    is PressInteraction.Release, is PressInteraction.Cancel ->
                        launch { highlightAlpha.animateTo(0f, tween(durationMillis = 250)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = highlightAlpha.value
        if (alpha > 0f) {
            drawRect(color = Color.White.copy(alpha = alpha))
        }
    }
}
