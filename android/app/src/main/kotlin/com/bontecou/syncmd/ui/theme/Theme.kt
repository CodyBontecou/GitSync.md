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

// ─── Shapes — Tide rounds everything: fields 18, cards 22, sheets 28+ ───────
private val TideShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small      = RoundedCornerShape(12.dp),
    medium     = RoundedCornerShape(18.dp),
    large      = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/**
 * Material components (dialogs, text fields, switches, menus) draw from this
 * scheme, so it is Tide underneath: ink is primary, paper the ground, cards
 * the raised surfaces, remote blue secondary and synced green tertiary.
 */
private fun TideColors.materialScheme(): ColorScheme {
    val base = if (isDark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = ink,
        onPrimary = paper,
        primaryContainer = well,
        onPrimaryContainer = ink,
        inversePrimary = paper,
        secondary = behind,
        onSecondary = Color.White,
        secondaryContainer = behind.copy(alpha = 0.12f),
        onSecondaryContainer = behind,
        tertiary = synced,
        onTertiary = Color.White,
        tertiaryContainer = synced.copy(alpha = 0.12f),
        onTertiaryContainer = synced,
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
        scrim = Color(0xFF111113),
        error = danger,
        onError = Color.White,
        errorContainer = danger.copy(alpha = 0.12f),
        onErrorContainer = danger,
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
    accent = behind,
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
