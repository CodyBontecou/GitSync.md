package com.bontecou.syncmd.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection

/** Match the direction in which the navigation stack advances or returns. */
@Composable
fun navigationArrow(forward: Boolean): String {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    return if (forward != rtl) "→" else "←"
}
