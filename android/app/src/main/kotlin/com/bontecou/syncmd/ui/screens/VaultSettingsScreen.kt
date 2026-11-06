package com.bontecou.syncmd.ui.screens

import android.text.format.Formatter
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.background.BackgroundHealth
import com.bontecou.syncmd.services.background.BackgroundHealthKind
import com.bontecou.syncmd.storage.collectPreference
import com.bontecou.syncmd.ui.components.folderCrumbs
import com.bontecou.syncmd.ui.components.relativeAgo
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3FilledButton
import com.bontecou.syncmd.ui.theme.M3Group
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3OutlinedButton
import com.bontecou.syncmd.ui.theme.M3SettingsRow
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSettingsViewModel

// ─── Vault settings & health (board 16) ─────────────────────────────────────

/** Where the rows go; the screen owns pin, name, Background Sync inclusion and removal itself. */
class VaultSettingsActions(
    val back: () -> Unit = {},
    val move: (String) -> Unit = {},
    val commitAs: (String) -> Unit = {},
    val addShortcut: (String) -> Unit = {},
    val stopTracking: (String) -> Unit = {},
    val removed: () -> Unit = {},
)

@Composable
fun VaultSettingsScreen(path: String, settings: SettingsViewModel, actions: VaultSettingsActions, subtitle: String? = null,
    model: VaultSettingsViewModel = hiltViewModel()) {
    LaunchedEffect(path) { model.open(path) }
    val state by model.state.collectAsState()
    val preference by model.preferences.collectPreference(path)
    val repositories by settings.allRepositories.collectAsState()
    val deletion by settings.deletionReview.collectAsState()
    val destructive by settings.destructiveBusy.collectAsState()
    val repository = repositories.firstOrNull { it.path == path }
    var editName by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    // Leave once the vault is gone from the catalog after a removal we started.
    LaunchedEffect(repository, removing, destructive) { if (removing && repository == null && !destructive) actions.removed() }

    VaultSettingsContent(
        repository = repository, path = path, subtitle = subtitle, state = state,
        included = preference.includeInBackgroundSync, pinned = preference.pinned,
        displayName = preference.resolvedName(repository?.alias.orEmpty(), repository?.name ?: java.io.File(path).name),
        onBack = actions.back,
        onIncluded = model::setIncluded,
        onPinned = model::setPinned,
        onName = { editName = true },
        onFolder = { actions.move(path) },
        onCommitAs = { actions.commitAs(path) },
        onShortcut = { actions.addShortcut(path) },
        onStopTracking = { actions.stopTracking(path) },
        onRemove = {
            if (repository?.managedStorage == true) settings.prepareRepositoryDeletion(path) else confirmRemove = true
        },
    )
    state.review?.let { plan ->
        M3Dialog(onDismissRequest = model::dismissReview, title = stringResource(R.string.settings_m3_vault_include_title), content = {
            Text(stringResource(R.string.background_binding, plan.branch.orEmpty(), plan.remote.orEmpty(), plan.remoteBranch.orEmpty()))
            listOfNotNull(plan.fetchUrl, plan.pushUrl).distinct().forEach { address ->
                com.bontecou.syncmd.services.repositories.RepositoryDiscovery.redactRemoteUrl(address)?.let {
                    Text(it, Modifier.padding(top = 6.dp), style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = M3.colors.onVar)
                }
            }
        }) {
            M3DialogAction(stringResource(android.R.string.cancel), model::dismissReview, enabled = !state.busy)
            M3DialogAction(stringResource(R.string.background_include), model::include, Modifier.testTag("vault-include-confirm"), filled = true, enabled = !state.busy)
        }
    }
    if (editName) {
        var draft by remember { mutableStateOf(preference.displayName.orEmpty()) }
        M3Dialog(onDismissRequest = { editName = false }, title = stringResource(R.string.settings_m3_vault_display_name), content = {
            M3TextField(draft, { draft = it }, stringResource(R.string.settings_m3_vault_display_name), Modifier.testTag("vault-display-name"),
                placeholder = repository?.alias?.ifBlank { repository.name } ?: repository?.name)
            Text(stringResource(R.string.settings_m3_vault_display_name_hint), Modifier.padding(top = 8.dp), style = M3Type.caption)
        }) {
            M3DialogAction(stringResource(android.R.string.cancel), { editName = false })
            M3DialogAction(stringResource(R.string.ui_save_1509f561), { editName = false; model.setDisplayName(draft) }, filled = true)
        }
    }
    if (confirmRemove) M3Dialog(onDismissRequest = { confirmRemove = false }, title = stringResource(R.string.settings_m3_vault_remove_title),
        icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error, text = stringResource(R.string.settings_m3_vault_remove_kept)) {
        M3DialogAction(stringResource(android.R.string.cancel), { confirmRemove = false })
        M3DialogAction(stringResource(R.string.settings_m3_vault_remove_action), {
            confirmRemove = false; removing = true; settings.removeRepository(path)
        }, Modifier.testTag("vault-remove-confirm"), destructive = true, filled = true)
    }
    deletion?.takeIf { it.repository.path == path }?.let { review ->
        val context = LocalContext.current
        M3Dialog(onDismissRequest = settings::clearRepositoryDeletion, title = stringResource(R.string.settings_m3_vault_delete_title),
            icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error, content = {
                Text(review.plan.path, style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = M3.colors.on)
                Text(stringResource(R.string.settings_m3_vault_delete_body), Modifier.padding(top = 12.dp))
            }) {
            M3DialogAction(stringResource(android.R.string.cancel), settings::clearRepositoryDeletion, enabled = !destructive)
            M3DialogAction(stringResource(R.string.settings_m3_vault_delete_action), { removing = true; settings.deleteRepository(review) },
                Modifier.testTag("vault-delete-confirm"), destructive = true, filled = true, loading = destructive)
        }
    }
}

@Composable
private fun VaultSettingsContent(repository: SavedRepository?, path: String, subtitle: String?, state: VaultSettingsViewModel.State, included: Boolean, pinned: Boolean,
    displayName: String, onBack: () -> Unit, onIncluded: (Boolean) -> Unit, onPinned: (Boolean) -> Unit, onName: () -> Unit, onFolder: () -> Unit,
    onCommitAs: () -> Unit, onShortcut: () -> Unit, onStopTracking: () -> Unit, onRemove: () -> Unit) {
    val c = M3.colors
    val managed = repository?.managedStorage == true
    Column(Modifier.fillMaxSize().background(c.surface).testTag("vault-settings-screen")) {
        M3TopBar(stringResource(R.string.settings_m3_vault_title), subtitle = subtitle, onBack = onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            HealthCard(state.health)
            state.error?.let { M3Note(it, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), tone = M3NoteTone.Warn) }
            M3Group(stringResource(R.string.settings_m3_vault_group)) {
                val binding = state.binding
                M3SettingsRow(stringResource(R.string.settings_m3_vault_include), Modifier.testTag("vault-include"),
                    subtitle = binding?.let { stringResource(R.string.settings_m3_vault_include_sub, it.first, it.second, it.third) }
                        ?: stringResource(R.string.settings_m3_vault_include_review),
                    checked = included, onCheckedChange = onIncluded, enabled = !state.busy)
                M3SettingsRow(stringResource(R.string.settings_m3_vault_pin), Modifier.testTag("vault-pin"), checked = pinned, onCheckedChange = onPinned,
                    enabled = !state.busy, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_vault_display_name), Modifier.testTag("vault-name"), value = displayName, onClick = onName, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_vault_folder), Modifier.testTag("vault-folder"), value = folderCrumbs(path), onClick = onFolder, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_commit_as), Modifier.testTag("vault-commit-as"), icon = R.drawable.ic_m3_person, onClick = onCommitAs, divider = true)
                M3SettingsRow(stringResource(R.string.settings_m3_vault_shortcut), Modifier.testTag("vault-shortcut"), icon = R.drawable.ic_m3_shortcut,
                    subtitle = stringResource(R.string.settings_m3_vault_shortcut_sub), onClick = onShortcut, divider = true)
            }
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                M3OutlinedButton(stringResource(R.string.settings_m3_vault_stop_tracking), onStopTracking, Modifier.fillMaxWidth().testTag("vault-stop-tracking"),
                    destructive = true)
                M3FilledButton(stringResource(if (managed) R.string.settings_m3_vault_remove_delete else R.string.settings_m3_vault_remove), onRemove,
                    Modifier.fillMaxWidth().testTag("vault-remove"), destructive = true)
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

/** okc card (ec when something needs attention): "Health · good", 412 files / 48 MB LFS / 0 errors · 7d, last background check. */
@Composable
private fun HealthCard(health: VaultSettingsViewModel.Health) {
    val c = M3.colors
    val good = health.good
    val (bg, fg) = if (good) c.okc to c.onOkc else c.ec to c.onEc
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp).background(bg, RoundedCornerShape(28.dp))
        .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp).semantics(mergeDescendants = true) {}.testTag("vault-health")) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.settings_m3_vault_health), Modifier.weight(1f), style = M3Type.section, color = fg)
            Text(stringResource(if (good) R.string.settings_m3_vault_health_good else R.string.settings_m3_vault_health_attention),
                Modifier.background(c.surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = M3Type.badge, color = if (good) c.ok else c.error)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Stat(health.files?.let { java.text.NumberFormat.getIntegerInstance().format(it) } ?: "–", stringResource(R.string.settings_m3_vault_files), fg, Modifier.weight(1f))
            Stat(health.lfsBytes?.let { Formatter.formatShortFileSize(context, it) } ?: "–", stringResource(R.string.settings_m3_vault_lfs), fg, Modifier.weight(1f))
            Stat(health.errors7d.toString(), stringResource(R.string.settings_m3_vault_errors), fg, Modifier.weight(1f))
        }
        Text(lastCheckLine(health.lastCheck), Modifier.padding(top = 10.dp), style = M3Type.meta, color = fg)
    }
}

@Composable
private fun lastCheckLine(health: BackgroundHealth?): String = if (health == null) stringResource(R.string.settings_m3_vault_no_check)
else stringResource(R.string.settings_m3_vault_last_check, relativeAgo(health.attemptedAt), stringResource(when (health.kind) {
    BackgroundHealthKind.UP_TO_DATE, BackgroundHealthKind.UPDATED -> R.string.settings_m3_vault_check_ok
    else -> healthLabel(health.kind)
}))

@Composable
private fun Stat(value: String, label: String, ink: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    Column(modifier) {
        Text(value, style = M3Type.headline.copy(letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified), color = ink, maxLines = 1)
        Text(label, style = M3Type.caption, color = ink)
    }
}

@Preview(name = "16 · Vault settings", heightDp = 915)
@Composable private fun VaultSettingsPreview() = SyncMdTheme(darkTheme = false) {
    VaultSettingsContent(SavedRepository("VoxVault", "/storage/emulated/0/Documents/Obsidian/VoxVault", managedStorage = true),
        "/storage/emulated/0/Documents/Obsidian/VoxVault", "CodyBontecou/VoxVault",
        VaultSettingsViewModel.State(health = VaultSettingsViewModel.Health(412, 48_000_000, 0,
            BackgroundHealth(BackgroundHealthKind.UP_TO_DATE, attemptedAt = System.currentTimeMillis() - 14 * 60_000)), binding = Triple("main", "origin", "main")),
        true, true, "VoxVault", {}, {}, {}, {}, {}, {}, {}, {}, {})
}

@Preview(name = "16 · Vault settings · dark", heightDp = 915)
@Composable private fun VaultSettingsDarkPreview() = SyncMdTheme(darkTheme = true) {
    VaultSettingsContent(null, "/storage/emulated/0/Documents/Obsidian/VoxVault", "CodyBontecou/VoxVault",
        VaultSettingsViewModel.State(health = VaultSettingsViewModel.Health(412, 0, 2, null)), false, false, "VoxVault", {}, {}, {}, {}, {}, {}, {}, {}, {})
}
