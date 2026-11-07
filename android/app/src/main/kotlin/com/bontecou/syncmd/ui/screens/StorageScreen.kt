package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.DeviceFolderButton
import com.bontecou.syncmd.ui.components.SettingsFolderCard
import com.bontecou.syncmd.ui.components.SettingsHint
import com.bontecou.syncmd.ui.components.SettingsIntro
import com.bontecou.syncmd.ui.components.folderCrumbs
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Chevron
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.StorageViewModel

// ─── Save new clones in (board Storage: storage · chosen; the move states live in MoveRepositoryScreen) ──

@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel(), repositories: List<SavedRepository> = emptyList(),
    onMove: (String) -> Unit = {}) {
    val state by viewModel.state.collectAsState()
    StorageContent(
        currentPath = state.current?.path,
        chosenPath = state.chosen?.path,
        busy = state.busy,
        message = state.message?.let { message -> stringResource(when (message) {
            StorageViewModel.Message.DEFAULT_SAVED -> R.string.storage_default_saved
            StorageViewModel.Message.APP_STORAGE_SAVED -> R.string.storage_app_saved
        }) },
        error = state.error,
        repositories = repositories,
        onBack = onBack,
        onUseDefault = viewModel::useDefault,
        onUseAppStorage = viewModel::useAppStorage,
        onMove = onMove,
        folderButton = { modifier ->
            DeviceFolderButton(stringResource(R.string.ui_choose_a_device_folder_d00b7daf), !state.busy, viewModel::choose, viewModel::showError, modifier = modifier)
        },
    )
}

@Composable
private fun StorageContent(
    currentPath: String?,
    chosenPath: String?,
    busy: Boolean,
    message: String?,
    error: String?,
    repositories: List<SavedRepository>,
    onBack: () -> Unit,
    onUseDefault: () -> Unit,
    onUseAppStorage: () -> Unit,
    onMove: (String) -> Unit,
    folderButton: @Composable (Modifier) -> Unit,
) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("storage-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_save_clones_in), onBack = onBack, modifier = Modifier.testTag("storage-title"))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            SettingsIntro(stringResource(R.string.settings_m3_storage_intro))
            SettingsFolderCard(stringResource(R.string.ui_current_default_d914f86e), c.ok, currentPath, pathModifier = Modifier.testTag("storage-default-path"))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folderButton(Modifier.weight(1f))
                M3Button(stringResource(R.string.ui_use_app_storage_d199e8e0), onUseAppStorage, Modifier.testTag("storage-use-app"),
                    style = M3ButtonStyle.Outlined, size = M3ButtonSize.Large, enabled = !busy)
            }
            if (busy) M3IndeterminateProgressBar(Modifier.padding(start = 20.dp, end = 20.dp, top = 12.dp))
            if (chosenPath != null) SettingsFolderCard(stringResource(R.string.ui_selected_folder_0cf59bef), c.tertiary, chosenPath,
                pathModifier = Modifier.testTag("storage-selected-path")) {
                M3Button(stringResource(R.string.ui_use_for_new_clones_be174c95), onUseDefault, Modifier.fillMaxWidth().testTag("storage-save-default"),
                    size = M3ButtonSize.Medium, enabled = !busy)
            }
            message?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("storage-message"), tone = M3NoteTone.Ok) }
            error?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Warn) }
            SettingsHint(stringResource(R.string.settings_m3_storage_hint))
            if (repositories.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                M3Group(stringResource(R.string.settings_m3_storage_move_title)) {
                    repositories.forEachIndexed { index, repo ->
                        M3ListRow(repo.alias.ifBlank { repo.name }, Modifier.testTag("storage-move-${repo.path}"), meta = folderCrumbs(repo.path),
                            leading = { M3Icon(R.drawable.ic_m3_folder) }, trailing = { M3Chevron() },
                            onClick = { onMove(repo.path) }, divider = index > 0, enabled = !busy && !repo.path.startsWith("github://"),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp))
                    }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewRepos = listOf(SavedRepository("VoxVault", "/storage/emulated/0/Documents/Obsidian/VoxVault"),
    SavedRepository("health-md", "/storage/emulated/0/Documents/GitSync.md/health-md"))

@Preview(name = "Storage · storage", heightDp = 915)
@Composable private fun StorageDefaultPreview() = SyncMdTheme(darkTheme = false) {
    StorageContent("/storage/emulated/0/Documents/Obsidian", null, false, null, null, previewRepos, {}, {}, {}, {}) {
        M3Button("Choose a device folder", {}, it, style = M3ButtonStyle.Tonal, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_folder)
    }
}

@Preview(name = "Storage · chosen · dark", heightDp = 915)
@Composable private fun StorageChosenDarkPreview() = SyncMdTheme(darkTheme = true) {
    StorageContent("/storage/emulated/0/Documents/Obsidian", "/storage/3A1F-0E22/Vaults", false, null, null, previewRepos, {}, {}, {}, {}) {
        M3Button("Choose a device folder", {}, it, style = M3ButtonStyle.Tonal, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_folder)
    }
}
