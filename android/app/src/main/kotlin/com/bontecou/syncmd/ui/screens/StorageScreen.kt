package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.DeviceFolderButton
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.StorageViewModel

@Composable
fun StorageScreen(onBack: () -> Unit, viewModel: StorageViewModel = hiltViewModel()) {
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
        onBack = onBack,
        onUseDefault = viewModel::useDefault,
        onUseAppStorage = viewModel::useAppStorage,
        folderButton = {
            DeviceFolderButton(stringResource(R.string.ui_choose_a_device_folder_d00b7daf), !state.busy, viewModel::choose, viewModel::showError)
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
    onBack: () -> Unit,
    onUseDefault: () -> Unit,
    onUseAppStorage: () -> Unit,
    folderButton: @Composable () -> Unit,
) {
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("storage-screen"), containerColor = c.paper, topBar = {
        WrappingTopBar(stringResource(R.string.ui_clone_storage_47cafc8a), stringResource(R.string.publication_back), onBack,
            titleModifier = Modifier.testTag("storage-title"))
    }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())
                .padding(start = Tide.gutter, end = Tide.gutter, top = 4.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.ui_choose_the_default_folder_for_new_clones_e_9782dc64),
                modifier = Modifier.padding(horizontal = 4.dp), style = TideType.copy, color = c.inkMid)

            StorageFolderCard(stringResource(R.string.ui_current_default_d914f86e), currentPath, "storage-default-path", c.synced)
            if (busy) SettingsAreaProgress()
            folderButton()

            if (chosenPath != null) {
                StorageFolderCard(stringResource(R.string.ui_selected_folder_0cf59bef), chosenPath, "storage-selected-path", c.ahead) {
                    BPrimaryButton(stringResource(R.string.ui_use_for_new_clones_be174c95), Modifier.testTag("storage-save-default"),
                        isDisabled = busy, onClick = onUseDefault)
                }
            }
            BSecondaryButton(stringResource(R.string.ui_use_app_storage_d199e8e0), Modifier.testTag("storage-use-app"),
                isDisabled = busy, onClick = onUseAppStorage)

            message?.let { SettingsAreaNotice(it, tint = c.synced, textModifier = Modifier.testTag("storage-message")) }
            error?.let { SettingsAreaNotice(it, tint = c.danger) }

            Text(stringResource(R.string.ui_choose_local_device_or_attached_storage_cl_69bc112f),
                modifier = Modifier.padding(horizontal = 4.dp), style = TideType.sans(12.5.sp), color = c.inkFaint)
        }
    }
}

/** Card with a small label, a dot and the folder path in mono (always left to right). */
@Composable
private fun StorageFolderCard(
    label: String,
    path: String?,
    tag: String,
    dot: Color,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(Tide.radiusCard)
    Column(
        Modifier.fillMaxWidth().clip(shape).background(c.card, shape).padding(horizontal = 18.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(label, style = TideType.sans(13.sp, FontWeight.SemiBold), color = dot)
        if (path != null) {
            Text(path, modifier = Modifier.testTag(tag), style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = c.ink)
        }
        if (action != null) Column(Modifier.padding(top = 8.dp), content = action)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Storage · app default")
@Composable private fun StorageDefaultPreview() = SyncMdTheme(darkTheme = false) {
    StorageContent("/data/user/0/com.bontecou.syncmd/files/repos", null, false, null, null, {}, {}, {}) {
        BSecondaryButton("Choose a device folder", onClick = {})
    }
}

@Preview(name = "Storage · folder chosen · dark")
@Composable private fun StorageChosenDarkPreview() = SyncMdTheme(darkTheme = true) {
    StorageContent("/data/user/0/com.bontecou.syncmd/files/repos", "/storage/emulated/0/Documents/Vaults", false,
        "New clones will use this folder.", null, {}, {}, {}) { BSecondaryButton("Choose a device folder", onClick = {}) }
}

@Preview(name = "Storage · busy with error")
@Composable private fun StorageErrorPreview() = SyncMdTheme(darkTheme = false) {
    StorageContent(null, null, true, null, "Folder access was not granted.", {}, {}, {}) { BSecondaryButton("Choose a device folder", onClick = {}) }
}

@Preview(name = "Storage · busy with error · dark")
@Composable private fun StorageErrorDarkPreview() = SyncMdTheme(darkTheme = true) {
    StorageContent(null, null, true, null, "Folder access was not granted.", {}, {}, {}) { BSecondaryButton("Choose a device folder", onClick = {}) }
}
