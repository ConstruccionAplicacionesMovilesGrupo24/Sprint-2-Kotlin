package com.campusmeal.android.core.designsystem

import androidx.compose.ui.graphics.Color

/**
 * CampusMeal primitives, taken from the "Fundamentos" frame of the CampusMeal UI Figma file.
 *
 * Screens should not read these directly: they use the Material 3 color scheme in [CampusMealTheme]
 * or the semantic tokens in [CampusMealColors].
 */

// Brand ramp — derived from CampusMeal Orange #E6791C.
internal val Brand50 = Color(0xFFFDF4EC)
internal val Brand100 = Color(0xFFFAE4CE)
internal val Brand300 = Color(0xFFEFAA6D)
internal val Brand500 = Color(0xFFE6791C)
internal val Brand600 = Color(0xFFC4620F)
internal val Brand700 = Color(0xFF984511)
internal val Brand900 = Color(0xFF4A2108)

// Accent ramp — derived from Yellow #F1C002.
internal val Accent50 = Color(0xFFFEF9E0)
internal val Accent100 = Color(0xFFFDEFB3)
internal val Accent300 = Color(0xFFF6CF28)
internal val Accent500 = Color(0xFFF1C002)
internal val Accent700 = Color(0xFF8F7101)

// Sand ramp — borders and warm surfaces.
internal val Sand100 = Color(0xFFF6F0E9)
internal val Sand200 = Color(0xFFE9DDCE)
internal val Sand300 = Color(0xFFD3BDA6)
internal val Sand400 = Color(0xFFB99F84)

// Neutrals.
internal val Neutral0 = Color(0xFFFFFFFF)
internal val Neutral50 = Color(0xFFF3F5F4)
internal val Neutral100 = Color(0xFFE8EBEA)
internal val Neutral200 = Color(0xFFD6DAD9)
internal val Neutral300 = Color(0xFFB3BAB8)
internal val Neutral500 = Color(0xFF6B7471)
internal val Neutral700 = Color(0xFF3A403E)
internal val Neutral900 = Color(0xFF22201E)

// Positive state.
internal val Positive100 = Color(0xFFE2EFE4)
internal val Positive500 = Color(0xFF3D7A4E)
internal val Positive700 = Color(0xFF2A5636)

/**
 * Semantic tokens the design system defines beyond the Material 3 scheme: urgency levels and the
 * status banners. Read them through `MaterialTheme.campusMealColors`.
 */
data class CampusMealColors(
    val canvas: Color,
    val surface: Color,
    val border: Color,
    val textPrimary: Color,
    val textSecondary: Color,
    /** Low urgency / success banner, as in "Location enabled". */
    val positiveBackground: Color,
    val positiveForeground: Color,
    /** Medium urgency / warning banner, as in "No location access". */
    val warningBackground: Color,
    val warningForeground: Color,
    /** High urgency, as in "Due today". */
    val urgentBackground: Color,
    val urgentForeground: Color,
)

internal val LightCampusMealColors = CampusMealColors(
    canvas = Neutral50,
    surface = Neutral0,
    border = Sand300,
    textPrimary = Neutral900,
    textSecondary = Neutral500,
    positiveBackground = Positive100,
    positiveForeground = Positive700,
    warningBackground = Accent100,
    warningForeground = Accent700,
    urgentBackground = Brand100,
    urgentForeground = Brand700,
)

/**
 * Dark values are a prototype approximation: the Figma file only delivers the light theme, so the
 * ramps are re-pointed rather than redesigned. Revisit once dark mode is designed.
 */
internal val DarkCampusMealColors = CampusMealColors(
    canvas = Neutral900,
    surface = Neutral700,
    border = Sand400,
    textPrimary = Neutral50,
    textSecondary = Neutral300,
    positiveBackground = Positive700,
    positiveForeground = Positive100,
    warningBackground = Accent700,
    warningForeground = Accent100,
    urgentBackground = Brand700,
    urgentForeground = Brand100,
)
