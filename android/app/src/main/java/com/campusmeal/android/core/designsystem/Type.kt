package com.campusmeal.android.core.designsystem

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.campusmeal.android.R

/** Inter, bundled in `res/font`. Licence: `android/licenses/Inter-OFL.txt`. */
internal val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

private fun inter(size: Int, lineHeight: Int, weight: FontWeight) = TextStyle(
    fontFamily = Inter,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = lineHeight.sp,
)

/**
 * The typography scale from the "Fundamentos" frame, mapped onto Material 3 styles:
 *
 * | Figma      | Size / line | Weight    | Material 3     |
 * | ---------- | ----------- | --------- | -------------- |
 * | Display/L  | 32 / 40     | Bold      | displaySmall   |
 * | Heading/XL | 24 / 32     | Bold      | headlineMedium |
 * | Heading/L  | 20 / 28     | Semi Bold | headlineSmall  |
 * | Heading/M  | 17 / 24     | Semi Bold | titleLarge     |
 * | Body/L     | 16 / 24     | Regular   | bodyLarge      |
 * | Body/M     | 15 / 22     | Regular   | bodyMedium     |
 * | Body/S     | 13 / 18     | Regular   | bodySmall      |
 * | Label/L    | 16 / 20     | Semi Bold | labelLarge     |
 * | Label/M    | 14 / 18     | Medium    | labelMedium    |
 * | Label/S    | 12 / 16     | Semi Bold | labelSmall     |
 * | Caption    | 11 / 14     | Medium    | titleSmall     |
 *
 * Letter spacing is left at the Figma default (0) rather than the Material defaults, because the
 * design was drawn with Inter's own metrics.
 */
internal val CampusMealTypography = Typography(
    displaySmall = inter(32, 40, FontWeight.Bold),
    headlineMedium = inter(24, 32, FontWeight.Bold),
    headlineSmall = inter(20, 28, FontWeight.SemiBold),
    titleLarge = inter(17, 24, FontWeight.SemiBold),
    bodyLarge = inter(16, 24, FontWeight.Normal),
    bodyMedium = inter(15, 22, FontWeight.Normal),
    bodySmall = inter(13, 18, FontWeight.Normal),
    labelLarge = inter(16, 20, FontWeight.SemiBold),
    labelMedium = inter(14, 18, FontWeight.Medium),
    labelSmall = inter(12, 16, FontWeight.SemiBold),
    titleSmall = inter(11, 14, FontWeight.Medium),
)
