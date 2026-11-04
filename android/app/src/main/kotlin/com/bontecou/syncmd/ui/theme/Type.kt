package com.bontecou.syncmd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ─── Material 3 type scale ───────────────────────────────────────────────────
// Roboto for words (the system sans), a monospace face for git facts. Labels stay
// sentence case; weights top out at 600 like the GitSync.md Material 3 design.

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = TideFonts.Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val Typography = Typography(
    displayLarge   = sans(57, 64, FontWeight.Normal, -0.0044),
    displayMedium  = sans(45, 52, FontWeight.Normal),
    displaySmall   = sans(36, 44, FontWeight.Normal),

    headlineLarge  = sans(32, 40, FontWeight.Normal),
    headlineMedium = sans(28, 36, FontWeight.Normal),
    headlineSmall  = sans(24, 32, FontWeight.Normal),

    titleLarge     = sans(22, 28, FontWeight.Medium),
    titleMedium    = sans(16, 24, FontWeight.Medium, 0.009),
    titleSmall     = sans(14, 20, FontWeight.Medium, 0.007),

    bodyLarge      = sans(16, 24, FontWeight.Normal, 0.031),
    bodyMedium     = sans(14, 20, FontWeight.Normal, 0.018),
    bodySmall      = sans(12, 16, FontWeight.Normal, 0.033),

    labelLarge     = sans(14, 20, FontWeight.SemiBold, 0.007),
    labelMedium    = sans(12, 16, FontWeight.SemiBold, 0.042),
    labelSmall     = sans(11, 16, FontWeight.Medium, 0.045),
)
