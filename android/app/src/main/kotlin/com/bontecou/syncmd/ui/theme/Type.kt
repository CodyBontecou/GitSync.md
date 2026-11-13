package com.bontecou.syncmd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ─── Material 3 type scale ───────────────────────────────────────────────────
// Roboto for words (the system sans), a monospace face for git facts. Labels stay
// sentence case; weights top out at 600 like the GitSync.md Material 3 design.
// Display and hero headline sizes use 500 with the design's negative tracking.

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = TideFonts.Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val Typography = Typography(
    displayLarge   = sans(57, 64, FontWeight.Medium, -0.026),   // hero count "3", -1.5px
    displayMedium  = sans(45, 52, FontWeight.Medium, -0.018),   // onboarding headline, -0.8px
    displaySmall   = sans(36, 44, FontWeight.Medium, -0.014),   // upgrade headline, -0.5px

    headlineLarge  = sans(32, 40, FontWeight.Normal),
    headlineMedium = sans(28, 34, FontWeight.Medium, -0.014),   // hero headline 28/34, -0.4px
    headlineSmall  = sans(24, 32, FontWeight.Normal),            // dialog title (sheets use 500)

    titleLarge     = sans(22, 28, FontWeight.Medium),            // screen bar title, vault name
    titleMedium    = sans(16, 24, FontWeight.Medium, 0.009),
    titleSmall     = sans(14, 20, FontWeight.Medium, 0.007),

    bodyLarge      = sans(16, 24, FontWeight.Normal, 0.031),
    bodyMedium     = sans(14, 20, FontWeight.Normal, 0.018),
    bodySmall      = sans(12, 16, FontWeight.Normal, 0.033),

    labelLarge     = sans(14, 20, FontWeight.SemiBold, 0.007),
    labelMedium    = sans(12, 16, FontWeight.SemiBold, 0.042),
    labelSmall     = sans(11, 16, FontWeight.Medium, 0.045),
)
