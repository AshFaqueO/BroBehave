package com.brobehave.core.designsystem.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// PLACEHOLDER TOKENS — replace with the exact values from the Claude Design handoff.
// Dark-first; no dynamic color, for brand consistency.

internal val Volt = Color(0xFFB6FF3C)
internal val VoltDeep = Color(0xFF3F6B00)
internal val Ink = Color(0xFF0E0F13)
internal val InkRaised = Color(0xFF17191F)
internal val InkHigh = Color(0xFF22252D)
internal val Mist = Color(0xFFF2F3F7)
internal val Paper = Color(0xFFFAFAFC)
internal val Ember = Color(0xFFFF6B4A)
internal val Sky = Color(0xFF5CC8FF)

internal val DarkColors = darkColorScheme(
    primary = Volt,
    onPrimary = Color(0xFF142000),
    primaryContainer = Color(0xFF2C4A00),
    onPrimaryContainer = Color(0xFFD2FF8A),
    secondary = Sky,
    onSecondary = Color(0xFF00344A),
    background = Ink,
    onBackground = Color(0xFFE6E7EC),
    surface = Ink,
    onSurface = Color(0xFFE6E7EC),
    surfaceVariant = InkHigh,
    onSurfaceVariant = Color(0xFFB4B8C4),
    surfaceContainerLow = InkRaised,
    surfaceContainer = InkRaised,
    surfaceContainerHigh = InkHigh,
    outline = Color(0xFF8A8F9C),
    outlineVariant = Color(0xFF3A3E49),
    error = Color(0xFFFFB4A6),
    onError = Color(0xFF5F1508),
)

internal val LightColors = lightColorScheme(
    primary = VoltDeep,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD2FF8A),
    onPrimaryContainer = Color(0xFF142000),
    secondary = Color(0xFF00658D),
    onSecondary = Color.White,
    background = Paper,
    onBackground = Color(0xFF16181D),
    surface = Paper,
    onSurface = Color(0xFF16181D),
    surfaceVariant = Mist,
    onSurfaceVariant = Color(0xFF4A4E5A),
    surfaceContainerLow = Color.White,
    surfaceContainer = Mist,
    surfaceContainerHigh = Color(0xFFE6E8EF),
    outline = Color(0xFF6B7080),
    outlineVariant = Color(0xFFC9CCD6),
    error = Color(0xFFB3261E),
    onError = Color.White,
)

/** Semantic colors that Material 3 has no slot for. */
data class BroColors(
    val success: Color,
    val warning: Color,
    val verified: Color,
    val dayClean: Color,
    val dayFlagged: Color,
    val dayRelapse: Color,
)

internal val DarkBroColors = BroColors(
    success = Color(0xFF7BE495),
    warning = Color(0xFFFFC857),
    verified = Sky,
    dayClean = Color(0xFF7BE495),
    dayFlagged = Color(0xFFFFC857),
    dayRelapse = Ember,
)

internal val LightBroColors = BroColors(
    success = Color(0xFF1B7A3A),
    warning = Color(0xFF8A5A00),
    verified = Color(0xFF00658D),
    dayClean = Color(0xFF1B7A3A),
    dayFlagged = Color(0xFF8A5A00),
    dayRelapse = Color(0xFFB3391E),
)
