package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3CodeBlock
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3ListRow
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3RadioIndicator
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.SavedRepository

/** M3 dialog for adding a repository by path. */
@Composable
fun AddRepositoryDialog(
    onDismiss: () -> Unit,
    onAddRepository: (name: String, path: String, alias: String) -> Unit
) {
    var repoName by remember { mutableStateOf("") }
    var repoPath by remember { mutableStateOf("") }
    var repoAlias by remember { mutableStateOf("") }
    val canAdd = repoName.isNotBlank() && repoPath.isNotBlank()
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.ui_add_repository_33da2ac1), content = {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            M3TextField(repoName, { repoName = it }, stringResource(R.string.publication_name), placeholder = stringResource(R.string.ui_e_g_myproject_e18eb00a))
            M3TextField(repoPath, { repoPath = it }, stringResource(R.string.ui_repository_path_037711af), placeholder = stringResource(R.string.ui_path_to_repo_b9d23344), mono = true)
            M3TextField(repoAlias, { repoAlias = it }, stringResource(R.string.ui_alias_optional_0d402abb), placeholder = stringResource(R.string.ui_e_g_prod_7d4c667a))
            Text(stringResource(R.string.ui_enter_the_full_path_to_your_git_repository_f926e25c), style = M3Type.caption, color = M3.colors.onVar)
        }
    }) {
        M3DialogAction(stringResource(R.string.publication_cancel), onDismiss)
        M3DialogAction(stringResource(R.string.ui_add_9fd728c6), { if (canAdd) { onAddRepository(repoName, repoPath, repoAlias); onDismiss() } }, filled = true, enabled = canAdd)
    }
}

/** M3 sheet for picking a saved repository; removal asks first. */
@Composable
fun RepositoryListDialog(
    repositories: List<SavedRepository>,
    currentRepository: String = "",
    onSelectRepository: (String) -> Unit,
    onRemoveRepository: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var repositoryToRemove by remember { mutableStateOf<String?>(null) }
    M3ModalSheet(onDismissRequest = onDismiss, title = stringResource(R.string.ui_select_repository_64bd6444),
        footer = { M3Button(stringResource(R.string.guide_done), onDismiss, Modifier.fillMaxWidth(), size = M3ButtonSize.XLarge) }) {
        RepositoryListBody(repositories, currentRepository, { onSelectRepository(it); onDismiss() }, { repositoryToRemove = it })
    }
    repositoryToRemove?.let { path ->
        M3Dialog(onDismissRequest = { repositoryToRemove = null }, title = stringResource(R.string.ui_remove_repository_fc68168b), icon = R.drawable.ic_m3_delete,
            iconTint = M3.colors.error, text = stringResource(R.string.ui_remove_this_repository_and_delete_its_loca_5533c124),
            content = { M3CodeBlock(path) }) {
            M3DialogAction(stringResource(R.string.publication_cancel), { repositoryToRemove = null })
            M3DialogAction(stringResource(R.string.ui_remove_c3812fc4), { onRemoveRepository(path); repositoryToRemove = null }, destructive = true, filled = true)
        }
    }
}

@Composable
private fun RepositoryListBody(repositories: List<SavedRepository>, currentRepository: String, onSelect: (String) -> Unit, onRemove: (String) -> Unit) {
    val c = M3.colors
    if (repositories.isEmpty()) {
        M3Note(stringResource(R.string.ui_no_repositories_saved_add_one_in_settings_16062236), Modifier.padding(top = 12.dp), tone = M3NoteTone.Muted)
        return
    }
    M3ListCard(Modifier.padding(top = 12.dp), color = c.surface, radius = 16.dp) {
        LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp)) {
            itemsIndexed(repositories) { index, repo ->
                M3ListRow(repo.name, meta = listOfNotNull(repo.path, repo.alias.takeIf { it.isNotBlank() }?.let { stringResource(R.string.ui_alias_1_s_e7e8c040, it) })
                    .joinToString(" · "), divider = index > 0, selected = repo.path == currentRepository, onClick = { onSelect(repo.path) },
                    contentPadding = PaddingValues(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                    leading = { M3RadioIndicator(repo.path == currentRepository) },
                    trailing = { M3IconButton(R.drawable.ic_m3_delete, stringResource(R.string.ui_remove_c3812fc4), { onRemove(repo.path) }, tint = c.error) })
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
    M3SheetSurface(title = stringResource(R.string.ui_select_repository_64bd6444)) { RepositoryListBody(previewRepositories, previewRepositories.first().path, {}, {}) }
}

@Preview(name = "Repository list · empty · dark")
@Composable
private fun RepositoryListEmptyDark() = SyncMdTheme(darkTheme = true) {
    M3SheetSurface(title = stringResource(R.string.ui_select_repository_64bd6444)) { RepositoryListBody(emptyList(), "", {}, {}) }
}

