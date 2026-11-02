package com.bontecou.syncmd.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// ─── Tide Typography ─────────────────────────────────────────────────────────
// Geist for words, Geist Mono for git facts. Tide tops out at semibold and
// keeps display sizes tightly tracked; labels are sentence case, mono, small.

private fun sans(size: Int, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = TideFonts.Sans,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

private fun mono(size: Double, line: Int, weight: FontWeight, tracking: Double = 0.0) = TextStyle(
    fontFamily = TideFonts.Mono,
    fontWeight = weight,
    fontSize = size.sp,
    lineHeight = line.sp,
    letterSpacing = tracking.em,
)

val Typography = Typography(
    // Display — state headlines ("1 vault needs you.")
    displayLarge   = sans(56, 60, FontWeight.SemiBold, -0.035),
    displayMedium  = sans(44, 48, FontWeight.SemiBold, -0.035),
    displaySmall   = sans(34, 38, FontWeight.SemiBold, -0.035),

    // Headline — screen titles
    headlineLarge  = sans(34, 38, FontWeight.SemiBold, -0.035),
    headlineMedium = sans(26, 30, FontWeight.SemiBold, -0.03),
    headlineSmall  = sans(21, 26, FontWeight.SemiBold, -0.02),

    // Title — vault names, sheet titles, row headings
    titleLarge     = sans(19, 24, FontWeight.SemiBold, -0.01),
    titleMedium    = sans(16, 22, FontWeight.SemiBold),
    titleSmall     = sans(15, 20, FontWeight.SemiBold),

    // Body — reading copy
    bodyLarge      = sans(16, 23, FontWeight.Normal),
    bodyMedium     = sans(15, 21, FontWeight.Normal),
    bodySmall      = sans(13, 18, FontWeight.Normal),

    // Label — buttons use sans; small labels are mono git facts
    labelLarge     = sans(15, 20, FontWeight.SemiBold),
    labelMedium    = mono(12.0, 16, FontWeight.Medium),
    labelSmall     = mono(11.0, 15, FontWeight.Medium, 0.02),
)
