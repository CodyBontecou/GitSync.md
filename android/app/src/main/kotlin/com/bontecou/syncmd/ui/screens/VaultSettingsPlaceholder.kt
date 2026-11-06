package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type

/**
 * Temporary destination for the Vault's More button (route `vaultSettings/{repo}`). The settings
 * builder replaces it with the Vault settings board; until then it keeps the per-vault actions that
 * used to live in the Vault's overflow menu reachable.
 */
@Composable
fun VaultSettingsPlaceholderScreen(
    repositoryName: String,
    onBack: () -> Unit,
    onOpenInFiles: () -> Unit,
    onCopyUrl: (() -> Unit)?,
    onOpenConnection: () -> Unit,
    onEditAuthor: () -> Unit,
    onMoveRepository: () -> Unit,
) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).testTag("vault-settings-placeholder")) {
        M3TopBar(stringResource(R.string.vault_m3_vault_settings), subtitle = repositoryName, onBack = onBack)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(stringResource(R.string.vault_m3_settings_placeholder), style = M3Type.bodyMedium, color = c.onVar,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp))
            M3Group(null) {
                M3SettingsRow(stringResource(R.string.ui_open_in_files_e1d250d0), icon = R.drawable.ic_m3_open_in_new, onClick = onOpenInFiles)
                if (onCopyUrl != null) M3SettingsRow(stringResource(R.string.vault_copy_url), Modifier.testTag("vault-copy-url"),
                    icon = R.drawable.ic_m3_content_copy, onClick = onCopyUrl, divider = true)
                M3SettingsRow(stringResource(R.string.ui_repository_connection_1188001b), Modifier.testTag("vault-connection"),
                    icon = R.drawable.ic_m3_link, onClick = onOpenConnection, chevron = true, divider = true)
                M3SettingsRow(stringResource(R.string.ui_commit_author_e9376b3a), Modifier.testTag("vault-author"),
                    icon = R.drawable.ic_m3_person, onClick = onEditAuthor, chevron = true, divider = true)
                M3SettingsRow(stringResource(R.string.ui_move_repository_1c4a6083), Modifier.testTag("vault-move"),
                    icon = R.drawable.ic_m3_folder, onClick = onMoveRepository, chevron = true, divider = true)
            }
        }
    }
}
