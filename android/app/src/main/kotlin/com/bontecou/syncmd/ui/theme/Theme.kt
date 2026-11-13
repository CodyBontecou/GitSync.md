package com.bontecou.syncmd.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

// ─── Shapes — Material 3: fields 4, chips 8, cards 12–28, sheets 28 ─────────
private val TideShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small      = RoundedCornerShape(8.dp),
    medium     = RoundedCornerShape(12.dp),
    large      = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** The GitSync.md Material 3 scheme (blue primary, warm surface, tonal containers). */
private fun TideColors.materialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = primaryContainer,
        onPrimaryContainer = onPrimaryContainer,
        inversePrimary = if (isDark) Color(0xFF2D4FD8) else Color(0xFFB9C3FF),
        secondary = if (isDark) Color(0xFFC6C5D0) else Color(0xFF5C5D72),
        onSecondary = if (isDark) Color(0xFF2E2F42) else Color.White,
        secondaryContainer = secondaryContainer,
        onSecondaryContainer = onSecondaryContainer,
        tertiary = tertiary,
        onTertiary = if (isDark) Color(0xFF5A1C00) else Color.White,
        tertiaryContainer = tertiaryContainer,
        onTertiaryContainer = onTertiaryContainer,
        background = paper,
        onBackground = ink,
        surface = paper,
        onSurface = ink,
        surfaceVariant = well,
        onSurfaceVariant = inkMid,
        surfaceTint = Color.Transparent,
        inverseSurface = heroBg,
        inverseOnSurface = heroText,
        outline = lineSoft,
        outlineVariant = line,
        scrim = Color(0xFF000000),
        error = danger,
        onError = onError,
        errorContainer = errorContainer,
        onErrorContainer = onErrorContainer,
    )
}

/** The legacy B* palette, rendered in Tide (matches iOS BrutalDesignSystem.swift). */
private fun TideColors.brutal() = BrutalColors(
    bg = paper,
    surface = well,
    border = line,
    borderSoft = lineSoft,
    text = ink,
    textMid = inkMid,
    textFaint = inkFaint,
    accent = primary,
    error = danger,
    success = synced,
    warning = ahead,
)

@Composable
fun SyncMdTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val tide = if (darkTheme) TideDark else TideLight

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = tide.paper.toArgb()
            window.navigationBarColor = tide.paper.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !darkTheme
                isAppearanceLightNavigationBars = !darkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalTideColors provides tide,
        LocalBrutalColors provides tide.brutal(),
    ) {
        MaterialTheme(
            colorScheme = tide.materialScheme(),
            typography  = Typography,
            shapes      = TideShapes,
            content     = content,
        )
    }
}
