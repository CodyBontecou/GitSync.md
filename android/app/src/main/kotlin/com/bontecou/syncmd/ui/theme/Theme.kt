package com.bontecou.syncmd.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ─── Shapes — zero corner radius everywhere (brutalist) ─────────────────────
private val BrutalShapes = Shapes(
    extraSmall = RoundedCornerShape(0.dp),
    small      = RoundedCornerShape(0.dp),
    medium     = RoundedCornerShape(0.dp),
    large      = RoundedCornerShape(0.dp),
    extraLarge = RoundedCornerShape(0.dp),
)

// ─── Color schemes ───────────────────────────────────────────────────────────
private val BrutalLightColorScheme = lightColorScheme(
    primary              = BrutalAccentLight,
    onPrimary            = Color.White,
    primaryContainer     = Color(0x1A007AFF),
    onPrimaryContainer   = BrutalAccentLight,
    secondary            = BrutalTextLight,
    onSecondary          = BrutalBgLight,
    secondaryContainer   = BrutalSurfaceLight,
    onSecondaryContainer = BrutalTextLight,
    background           = BrutalBgLight,
    onBackground         = BrutalTextLight,
    surface              = BrutalBgLight,
    onSurface            = BrutalTextLight,
    surfaceVariant       = BrutalSurfaceLight,
    onSurfaceVariant     = BrutalTextMidLight,
    outline              = BrutalBorderLight,
    outlineVariant       = BrutalBorderSoftLight,
    error                = BrutalErrorLight,
    onError              = Color.White,
    errorContainer       = Color(0x1AD70015),
    onErrorContainer     = BrutalErrorLight,
)

private val BrutalDarkColorScheme = darkColorScheme(
    primary              = BrutalAccentDark,
    onPrimary            = Color.Black,
    primaryContainer     = Color(0x1A0A84FF),
    onPrimaryContainer   = BrutalAccentDark,
    secondary            = BrutalTextDark,
    onSecondary          = BrutalBgDark,
    secondaryContainer   = BrutalSurfaceDark,
    onSecondaryContainer = BrutalTextDark,
    background           = BrutalBgDark,
    onBackground         = BrutalTextDark,
    surface              = BrutalBgDark,
    onSurface            = BrutalTextDark,
    surfaceVariant       = BrutalSurfaceDark,
    onSurfaceVariant     = BrutalTextMidDark,
    outline              = BrutalBorderDark,
    outlineVariant       = BrutalBorderSoftDark,
    error                = BrutalErrorDark,
    onError              = Color.Black,
    errorContainer       = Color(0x1AFF453A),
    onErrorContainer     = BrutalErrorDark,
)

@Composable
fun SyncMdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) BrutalDarkColorScheme else BrutalLightColorScheme

    // Build the BrutalColors object for the current theme
    val brutalColors = if (darkTheme) {
        BrutalColors(
            bg          = BrutalBgDark,
            surface     = BrutalSurfaceDark,
            border      = BrutalBorderDark,
            borderSoft  = BrutalBorderSoftDark,
            text        = BrutalTextDark,
            textMid     = BrutalTextMidDark,
            textFaint   = BrutalTextFaintDark,
            accent      = BrutalAccentDark,
            error       = BrutalErrorDark,
            success     = BrutalSuccessDark,
            warning     = BrutalWarningDark,
        )
    } else {
        BrutalColors(
            bg          = BrutalBgLight,
            surface     = BrutalSurfaceLight,
            border      = BrutalBorderLight,
            borderSoft  = BrutalBorderSoftLight,
            text        = BrutalTextLight,
            textMid     = BrutalTextMidLight,
            textFaint   = BrutalTextFaintLight,
            accent      = BrutalAccentLight,
            error       = BrutalErrorLight,
            success     = BrutalSuccessLight,
            warning     = BrutalWarningLight,
        )
    }

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    CompositionLocalProvider(LocalBrutalColors provides brutalColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography  = Typography,
            shapes      = BrutalShapes,
            content     = content,
        )
    }
}
