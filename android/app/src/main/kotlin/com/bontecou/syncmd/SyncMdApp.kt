package com.bontecou.syncmd

import androidx.compose.runtime.Composable
import com.bontecou.syncmd.ui.AppShell

/**
 * Root composable — delegates entirely to AppShell which owns the NavHost.
 */
@Composable
fun SyncMdApp() {
    AppShell()
}
