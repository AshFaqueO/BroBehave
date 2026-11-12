package com.brobehave.core.designsystem.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// PLACEHOLDER TOKENS — replace with the exact values from the Claude Design handoff.

/** Spacing scale. Use these instead of raw dp values in screens. */
data class BroSpacing(
    val xxs: Dp = 2.dp,
    val xs: Dp = 4.dp,
    val sm: Dp = 8.dp,
    val md: Dp = 16.dp,
    val lg: Dp = 24.dp,
    val xl: Dp = 32.dp,
    val xxl: Dp = 48.dp,
    /** Minimum touch target (accessibility). */
    val touchTarget: Dp = 48.dp,
)

internal val BroShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

// System font for now: it covers Bangla. Line heights are generous because Bangla
// glyphs are taller and strings run ~30% longer.
private fun style(weight: FontWeight, size: Int, lineHeight: Int) = TextStyle(
    fontFamily = FontFamily.Default,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

internal val BroTypography = Typography(
    displayLarge = style(FontWeight.Black, size = 64, lineHeight = 72),
    displayMedium = style(FontWeight.Black, size = 48, lineHeight = 56),
    displaySmall = style(FontWeight.ExtraBold, size = 36, lineHeight = 44),
    headlineLarge = style(FontWeight.ExtraBold, size = 30, lineHeight = 38),
    headlineMedium = style(FontWeight.Bold, size = 26, lineHeight = 34),
    headlineSmall = style(FontWeight.Bold, size = 22, lineHeight = 30),
    titleLarge = style(FontWeight.Bold, size = 20, lineHeight = 28),
    titleMedium = style(FontWeight.SemiBold, size = 16, lineHeight = 24),
    titleSmall = style(FontWeight.SemiBold, size = 14, lineHeight = 22),
    bodyLarge = style(FontWeight.Normal, size = 16, lineHeight = 26),
    bodyMedium = style(FontWeight.Normal, size = 14, lineHeight = 22),
    bodySmall = style(FontWeight.Normal, size = 12, lineHeight = 18),
    labelLarge = style(FontWeight.Bold, size = 14, lineHeight = 20),
    labelMedium = style(FontWeight.SemiBold, size = 12, lineHeight = 18),
    labelSmall = style(FontWeight.SemiBold, size = 11, lineHeight = 16),
)

private val LocalBroColors = staticCompositionLocalOf { DarkBroColors }
private val LocalBroSpacing = staticCompositionLocalOf { BroSpacing() }

/** Access to BroBehave tokens that sit alongside [MaterialTheme]. */
object BroTheme {
    val colors: BroColors
        @Composable @ReadOnlyComposable
        get() = LocalBroColors.current

    val spacing: BroSpacing
        @Composable @ReadOnlyComposable
        get() = LocalBroSpacing.current
}

/** App theme. Dark-first, with a full light theme; dynamic color is deliberately not used. */
@Composable
fun BroBehaveTheme(darkTheme: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalBroColors provides if (darkTheme) DarkBroColors else LightBroColors,
        LocalBroSpacing provides BroSpacing(),
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = BroTypography,
            shapes = BroShapes,
            content = content,
        )
    }
}
