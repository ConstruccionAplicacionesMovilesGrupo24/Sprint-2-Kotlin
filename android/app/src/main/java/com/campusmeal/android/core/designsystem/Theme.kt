package com.campusmeal.android.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf

private val LightColors = lightColorScheme(
    primary = Brand500,
    onPrimary = Neutral900,
    primaryContainer = Brand100,
    onPrimaryContainer = Brand700,
    secondary = Accent500,
    onSecondary = Neutral900,
    secondaryContainer = Accent100,
    onSecondaryContainer = Accent700,
    tertiary = Sand300,
    onTertiary = Neutral900,
    tertiaryContainer = Sand100,
    onTertiaryContainer = Brand700,
    background = Neutral50,
    onBackground = Neutral900,
    surface = Neutral0,
    onSurface = Neutral900,
    surfaceVariant = Sand100,
    onSurfaceVariant = Neutral500,
    outline = Sand300,
    outlineVariant = Neutral200,
    error = Brand700,
    onError = Neutral0,
    errorContainer = Brand100,
    onErrorContainer = Brand700,
)

/**
 * Prototype approximation: the Figma file only delivers the light theme, so the same ramps are
 * re-pointed for dark rather than redesigned. Revisit once dark mode is designed.
 */
private val DarkColors = darkColorScheme(
    primary = Brand300,
    onPrimary = Brand900,
    primaryContainer = Brand700,
    onPrimaryContainer = Brand100,
    secondary = Accent300,
    onSecondary = Accent700,
    secondaryContainer = Accent700,
    onSecondaryContainer = Accent100,
    tertiary = Sand400,
    onTertiary = Neutral900,
    tertiaryContainer = Neutral700,
    onTertiaryContainer = Sand200,
    background = Neutral900,
    onBackground = Neutral50,
    surface = Neutral700,
    onSurface = Neutral50,
    surfaceVariant = Neutral700,
    onSurfaceVariant = Neutral300,
    outline = Sand400,
    outlineVariant = Neutral500,
    error = Brand300,
    onError = Brand900,
    errorContainer = Brand700,
    onErrorContainer = Brand100,
)

private val LocalCampusMealColors = staticCompositionLocalOf { LightCampusMealColors }

/** Semantic tokens the Material 3 scheme has no slot for: urgency levels and status banners. */
val MaterialTheme.campusMealColors: CampusMealColors
    @Composable @ReadOnlyComposable get() = LocalCampusMealColors.current

/** CampusMeal theme. Dynamic color is disabled so the brand palette stays consistent. */
@Composable
fun CampusMealTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalCampusMealColors provides if (darkTheme) DarkCampusMealColors else LightCampusMealColors,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) DarkColors else LightColors,
            typography = CampusMealTypography,
            content = content,
        )
    }
}
