package com.bontecou.syncmd

import androidx.compose.runtime.Composable
import com.bontecou.syncmd.ui.AppShell
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Root composable — delegates entirely to AppShell which owns the NavHost.
 */
@Composable
fun SyncMdApp(settingsViewModel: SettingsViewModel) {
    AppShell(settingsViewModel = settingsViewModel)
}
