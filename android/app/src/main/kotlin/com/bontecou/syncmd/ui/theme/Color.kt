package com.bontecou.syncmd.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Brutal Design System — Adaptive Palette ─────────────────────────────────
// Light mode
val BrutalBgLight          = Color(0xFFFFFFFF)
val BrutalSurfaceLight     = Color(0xFFF2F2F7)
val BrutalBorderLight      = Color(0xE0000000)   // black ~88%
val BrutalBorderSoftLight  = Color(0x2E000000)   // black ~18%
val BrutalTextLight        = Color(0xFF000000)
val BrutalTextMidLight     = Color(0xFF515151)
val BrutalTextFaintLight   = Color(0xFF808080)

// Dark mode
val BrutalBgDark           = Color(0xFF000000)
val BrutalSurfaceDark      = Color(0xFF1C1C1E)
val BrutalBorderDark       = Color(0xE0FFFFFF)   // white ~88%
val BrutalBorderSoftDark   = Color(0x2EFFFFFF)   // white ~18%
val BrutalTextDark         = Color(0xFFFFFFFF)
val BrutalTextMidDark      = Color(0xFFD1D1D6)
val BrutalTextFaintDark    = Color(0xFF8E8E93)

// Semantic (light / dark variants)
val BrutalAccentLight      = Color(0xFF007AFF)
val BrutalAccentDark       = Color(0xFF0A84FF)
val BrutalErrorLight       = Color(0xFFD70015)
val BrutalErrorDark        = Color(0xFFFF453A)
val BrutalSuccessLight     = Color(0xFF1A7A1A)
val BrutalSuccessDark      = Color(0xFF32D74B)
val BrutalWarningLight     = Color(0xFFB25000)
val BrutalWarningDark      = Color(0xFFFF9F0A)

// Legacy status colors (kept for diff viewers)
val Modified   = Color(0xFFFFA500)
val Staged     = Color(0xFF4CAF50)
val Untracked  = Color(0xFF2196F3)
val Conflict   = Color(0xFFF44336)
