package com.bontecou.syncmd.ui.theme

import androidx.compose.ui.graphics.Color

// ─── Legacy palette names, now Tide values ──────────────────────────────────
// Screens should read Tide.colors (or LocalBrutalColors) so dark mode follows;
// these constants remain for code that needs a fixed color.

// Light mode
val BrutalBgLight          = TideLight.paper
val BrutalSurfaceLight     = TideLight.well
val BrutalBorderLight      = TideLight.line
val BrutalBorderSoftLight  = TideLight.lineSoft
val BrutalTextLight        = TideLight.ink
val BrutalTextMidLight     = TideLight.inkMid
val BrutalTextFaintLight   = TideLight.inkFaint

// Dark mode
val BrutalBgDark           = TideDark.paper
val BrutalSurfaceDark      = TideDark.well
val BrutalBorderDark       = TideDark.line
val BrutalBorderSoftDark   = TideDark.lineSoft
val BrutalTextDark         = TideDark.ink
val BrutalTextMidDark      = TideDark.inkMid
val BrutalTextFaintDark    = TideDark.inkFaint

// Semantic (light / dark variants)
val BrutalAccentLight      = TideLight.behind
val BrutalAccentDark       = TideDark.behind
val BrutalErrorLight       = TideLight.danger
val BrutalErrorDark        = TideDark.danger
val BrutalSuccessLight     = TideLight.synced
val BrutalSuccessDark      = TideDark.synced
val BrutalWarningLight     = TideLight.ahead
val BrutalWarningDark      = TideDark.ahead

// Status colors for diff and file lists: orange = yours, blue = new, red = conflict.
val Modified   = Color(0xFFB4410E)
val Staged     = Color(0xFF17703F)
val Untracked  = Color(0xFF2D5BFF)
val Conflict   = Color(0xFFB42318)
