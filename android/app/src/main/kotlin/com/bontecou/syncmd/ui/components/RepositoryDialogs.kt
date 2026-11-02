package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/**
 * Sheet for adding a repository by path.
 */
@Composable
fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onAddRepository: (name: String, path: String, alias: String) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var repoPath by remember { mutableStateOf("") }
    var repoAlias by remember { mutableStateOf("") }
    val canAdd = repoName.isNotBlank() && repoPath.isNotBlank()

    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_add_repository_33da2ac1),
        confirmButton = {
            VaultOpsButton(stringResource(R.string.ui_add_9fd728c6), onClick = {
                if (canAdd) {
                    onAddRepository(repoName, repoPath, repoAlias)
                    onDismiss()
                }
            }, enabled = canAdd)
        },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), onDismiss, Modifier.fillMaxWidth()) },
    ) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = repoName,
                onValueChange = { repoName = it },
                label = { Text(stringResource(R.string.publication_name)) },
                placeholder = { Text(stringResource(R.string.ui_e_g_myproject_e18eb00a)) },
                singleLine = true,
                textStyle = TideType.sans(16.sp),
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repoPath,
                onValueChange = { repoPath = it },
                label = { Text(stringResource(R.string.ui_repository_path_037711af)) },
                placeholder = { Text(stringResource(R.string.ui_path_to_repo_b9d23344)) },
                singleLine = true,
                textStyle = TideType.mono(14.sp).copy(textDirection = TextDirection.Ltr),
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = repoAlias,
                onValueChange = { repoAlias = it },
                label = { Text(stringResource(R.string.ui_alias_optional_0d402abb)) },
                placeholder = { Text(stringResource(R.string.ui_e_g_prod_7d4c667a)) },
                singleLine = true,
                textStyle = TideType.sans(16.sp),
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(stringResource(R.string.ui_enter_the_full_path_to_your_git_repository_f926e25c), style = TideType.meta, color = Tide.colors.inkFaint)
        }
    }
}

/**
 * Sheet for picking a saved repository; removal asks first.
 */
@Composable
fun RepositoryListDialog(
    repositories: List<SavedRepository>,
    currentRepository: String = "",
    onSelectRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var showRemoveConfirm by remember { mutableStateOf(false) }
    var repositoryToRemove by remember { mutableStateOf("") }
    RepositoryListSheet(repositories, currentRepository, { onSelectRepository(it); onDismiss() },
        { repositoryToRemove = it; showRemoveConfirm = true }, onDismiss)

    if (showRemoveConfirm) {
        VaultOpsSheet(
            onDismissRequest = { showRemoveConfirm = false },
            title = stringResource(R.string.ui_remove_repository_fc68168b),
            icon = { VaultOpsDangerIcon() },
            confirmButton = {
                VaultOpsButton(stringResource(R.string.ui_remove_c3812fc4), onClick = {
                    onRemoveRepository(repositoryToRemove)
                    showRemoveConfirm = false
                }, style = VaultOpsButtonStyle.DANGER)
            },
            dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), { showRemoveConfirm = false }, Modifier.fillMaxWidth()) },
        ) {
            Text(stringResource(R.string.ui_remove_this_repository_and_delete_its_loca_5533c124))
            VaultOpsDetail(repositoryToRemove)
        }
    }
}

@Composable
private fun RepositoryListSheet(
    repositories: List<SavedRepository>,
    currentRepository: String,
    onSelect: (String) -> Unit,
    onRemove: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Tide.colors
    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.ui_select_repository_64bd6444),
        confirmButton = { VaultOpsButton(stringResource(R.string.guide_done), onDismiss) },
    ) {
        if (repositories.isEmpty()) {
            VaultOpsBanner(stringResource(R.string.ui_no_repositories_saved_add_one_in_settings_16062236))
        } else {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
                itemsIndexed(repositories) { index, repo ->
                    val current = repo.path == currentRepository
                    Row(
                        Modifier
                            .vaultOpsSlice(index, repositories.size)
                            .heightIn(min = 60.dp)
                            .clickable(role = Role.Button) { onSelect(repo.path) }
                            .semantics { selected = current }
                            .padding(start = 16.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        VaultOpsSelectMark(current, radio = true)
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(repo.name, style = TideType.sans(16.sp, FontWeight.SemiBold), color = c.ink)
                            Text(repo.path, style = TideType.mono(11.sp).copy(textDirection = TextDirection.Ltr), color = c.inkFaint)
                            if (repo.alias.isNotBlank()) {
                                Text(stringResource(R.string.ui_alias_1_s_e7e8c040, (repo.alias).toString()), style = TideType.meta, color = c.inkMid)
                            }
                        }
                        TideCircleButton(stringResource(R.string.ui_remove_c3812fc4), { onRemove(repo.path) }, size = 48.dp, filled = false) {
                            Icon(Icons.Default.Delete, contentDescription = null, tint = c.danger, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewRepositories = listOf(
    SavedRepository(name = "VoxVault", path = "/storage/emulated/0/Documents/VoxVault", alias = "cody/voxvault"),
    SavedRepository(name = "Field notes", path = "/data/user/0/com.bontecou.syncmd/files/field-notes"),
)

@Preview(name = "Repository list · light")
@Composable
private fun RepositoryListLight() = SyncMdTheme(darkTheme = false) {
    RepositoryListSheet(previewRepositories, previewRepositories.first().path, {}, {}, {})
}

@Preview(name = "Repository list · empty · dark")
@Composable
private fun RepositoryListEmptyDark() = SyncMdTheme(darkTheme = true) { RepositoryListSheet(emptyList(), "", {}, {}, {}) }

@Preview(name = "Add repository · dark")
@Composable
private fun AddRepositoryDark() = SyncMdTheme(darkTheme = true) { AddRepositoryDialog(onDismiss = {}, onAddRepository = { _, _, _ -> }) }
