package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.DiffLineType
import com.bontecou.syncmd.data.models.DiffStatus
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.sync.hostLabel
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3CheckboxRow
import com.bontecou.syncmd.ui.theme.M3DiffStat
import com.bontecou.syncmd.ui.theme.M3InsetList
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3SuggestionChip
import com.bontecou.syncmd.ui.theme.M3Switch
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.BranchViewModel
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/** One row of the commit sheet's Included list. */
internal data class CommitFile(val path: String, val staged: Boolean, val deleted: Boolean, val added: Boolean)

/**
 * Commit bottom sheet (Material 3 board "08 · Commit bottom sheet"): the message with suggestions
 * generated from the diff, the included files as checkboxes (checked = staged) with their +/−
 * counts, "Push right after" showing who commits, then "Commit & push ↑N" and "Commit locally".
 * Staging and the local commit go through [DiffViewModel]; with a [syncViewModel], Commit & push
 * runs as a vault sync with progress, Steps and a notification.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CommitSheet(
    repositoryPath: String,
    vaultName: String,
    onDismiss: () -> Unit,
    diffVM: DiffViewModel = hiltViewModel(),
    branchVM: BranchViewModel = hiltViewModel(),
    syncViewModel: VaultSyncViewModel? = null,
) {
    LaunchedEffect(repositoryPath) {
        // Refresh, not re-own: on list-detail layouts the diff pane shares this view model, and
        // re-owning the path would drop the file diff it is loading.
        diffVM.refreshRepositoryPath(repositoryPath)
        branchVM.setRepositoryPath(repositoryPath)
        syncViewModel?.setRepositoryPath(repositoryPath)
    }
    val c = M3.colors
    val diff by diffVM.diff.collectAsState()
    val message by diffVM.commitMessage.collectAsState()
    val isLoading by diffVM.isLoading.collectAsState()
    val isCommitting by diffVM.isCommitting.collectAsState()
    val isPushing by diffVM.isPushing.collectAsState()
    val error = diffVM.errorMessage.collectAsState().value?.asString()
    val notice = diffVM.operationMessage.collectAsState().value?.asString()
    val branchState by branchVM.state.collectAsState()
    val branch = branchState.currentBranch?.name
    val overview = syncViewModel?.overview?.collectAsState()?.value
    val author = syncViewModel?.authorName?.collectAsState()?.value
    val running = syncViewModel?.run?.collectAsState()?.value?.running == true
    val files = diff?.files.orEmpty().groupBy { it.filePath }.map { (path, entries) ->
        CommitFile(path, entries.any { it.isStaged }, entries.any { it.status == DiffStatus.DELETED }, entries.any { it.status == DiffStatus.ADDED })
    }.sortedBy { it.path }
    // Line counts: the vault's HEAD → working tree counts, or the loaded diff's own hunks.
    val counts: Map<String, FileLineCount> = overview?.lineCounts ?: diff?.files.orEmpty().groupBy { it.filePath }.mapValues { (_, entries) ->
        FileLineCount(
            entries.sumOf { f -> f.hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.ADDITION } } },
            entries.sumOf { f -> f.hunks.sumOf { h -> h.lines.count { it.type == DiffLineType.DELETION } } },
            entries.all { it.isBinary },
        )
    }
    // The board opens with every change included: stage all once when nothing is staged yet.
    // A caller that staged a selection first (e.g. "Commit this file") keeps that selection.
    var includedAll by rememberSaveable(repositoryPath) { mutableStateOf(false) }
    LaunchedEffect(diff, isLoading) {
        val loaded = diff ?: return@LaunchedEffect
        if (!includedAll && !isLoading && loaded.files.isNotEmpty()) {
            includedAll = true
            if (loaded.files.none { it.isStaged }) diffVM.stageAll()
        }
    }
    val staged = files.count { it.staged }
    var pushAfter by rememberSaveable(repositoryPath) { mutableStateOf(true) }
    var committing by remember { mutableStateOf(false) }
    // Close once a commit started here finishes cleanly; failures stay on the sheet.
    LaunchedEffect(isLoading, error) {
        if (committing && !isLoading) {
            committing = false
            if (error == null) onDismiss()
        }
    }
    val canCommit = staged > 0 && message.isNotBlank() && !isLoading && !running
    val suggestions = commitSuggestions(files.filter { it.staged }.ifEmpty { files })
    val host = hostLabel(overview?.remoteHost) ?: stringResource(R.string.vault_m3_remote_generic)
    val commitAndPush: () -> Unit = {
        if (syncViewModel != null) {
            // The sync owns the commit from here; the Vault shows its progress.
            syncViewModel.commitAndPush(message.trim(), staged)
            diffVM.setCommitMessage("")
            onDismiss()
        } else { committing = true; branch?.let { diffVM.commitAndPush(it) } }
    }
    val commitLocally: () -> Unit = { committing = true; diffVM.commitOnly() }

    M3ModalSheet(
        onDismissRequest = { if (!isLoading) onDismiss() },
        modifier = Modifier.testTag("commit-sheet"),
        title = pluralStringResource(R.plurals.vault_m3_commit_title, staged, staged),
        titleMeta = listOfNotNull(vaultName, branch).joinToString(" → "),
        footer = {
            if (pushAfter) {
                M3Button(stringResource(R.string.vault_m3_commit_push), commitAndPush, Modifier.fillMaxWidth().testTag("commit-sheet-push"),
                    style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero, trailingMeta = if (staged > 0) "↑$staged" else null,
                    enabled = canCommit && (branch != null || syncViewModel != null), loading = isPushing)
                M3Button(stringResource(if (isCommitting && !isPushing) R.string.tide_git_committing else R.string.vault_m3_commit_locally), commitLocally,
                    Modifier.fillMaxWidth().testTag("commit-sheet-local"), style = M3ButtonStyle.Text, size = M3ButtonSize.Medium, enabled = canCommit)
            } else {
                M3Button(stringResource(if (isCommitting) R.string.tide_git_committing else R.string.vault_m3_commit_locally), commitLocally,
                    Modifier.fillMaxWidth().testTag("commit-sheet-local"), style = M3ButtonStyle.Filled, size = M3ButtonSize.Hero,
                    enabled = canCommit, loading = isCommitting)
            }
        },
    ) {
        M3TextField(
            value = message,
            onValueChange = diffVM::setCommitMessage,
            label = stringResource(R.string.vault_m3_message),
            placeholder = stringResource(R.string.ui_commit_message_a4b361b7),
            singleLine = false,
            minLines = 3,
            enabled = !isLoading,
            modifier = Modifier.padding(top = 20.dp).testTag("commit-sheet-message"),
        )
        if (suggestions.isNotEmpty()) {
            Text(stringResource(R.string.vault_m3_suggested), style = M3Type.caption, color = c.onVar, modifier = Modifier.padding(start = 16.dp, top = 4.dp))
            FlowRow(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                suggestions.forEach { suggestion ->
                    M3SuggestionChip(suggestion, { diffVM.setCommitMessage(suggestion) }, Modifier.testTag("commit-sheet-suggestion"))
                }
            }
        }
        M3SectionLabel(stringResource(R.string.vault_m3_included), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp))
        M3InsetList {
            if (files.isEmpty()) Text(stringResource(R.string.ui_no_local_changes_56319dc5), style = M3Type.bodyMedium, color = c.onVar,
                modifier = Modifier.padding(16.dp))
            files.forEachIndexed { index, file ->
                val count = counts[file.path]
                M3CheckboxRow(
                    label = if (file.deleted) stringResource(R.string.vault_m3_deleted_path, file.path) else file.path,
                    checked = file.staged,
                    onCheckedChange = { on -> if (on) diffVM.stageFile(file.path) else diffVM.unstageFile(file.path) },
                    modifier = Modifier.testTag("commit-sheet-file-${file.path}"),
                    divider = index > 0,
                    enabled = !isLoading,
                    trailing = if (count != null && !count.binary && (count.added > 0 || count.removed > 0)) { {
                        M3DiffStat(count.added.takeIf { it > 0 }?.let { "+$it" }, count.removed.takeIf { it > 0 }?.let { "−$it" }, style = M3Type.metaStrong)
                    } } else null,
                )
            }
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 12.dp).heightIn(min = 56.dp)
                .toggleable(pushAfter, role = Role.Switch, onValueChange = { pushAfter = it })
                .padding(horizontal = 4.dp, vertical = 8.dp).testTag("commit-sheet-push-after"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.vault_m3_push_right_after), style = M3Type.body, color = c.on)
                if (author != null) Text(stringResource(R.string.vault_m3_as_author, author, host),
                    style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.onVar)
            }
            M3Switch(pushAfter, onCheckedChange = null)
        }
        if (notice != null) M3Note(notice, Modifier.padding(top = 12.dp), tone = M3NoteTone.Info)
        if (error != null) M3Note(error, Modifier.padding(top = 12.dp), tone = M3NoteTone.Warn, action = {
            M3Button(stringResource(R.string.vault_m3_dismiss), { diffVM.clearError() }, style = M3ButtonStyle.OutlinedContent, size = M3ButtonSize.XSmall)
        })
    }
}

/**
 * Messages suggested from what changed: an Obsidian Git style backup line with today's date, then
 * one describing the files ("Add 2026-11-01", "Update voice-memo-pipeline", "Update ideas", "Update 3 files").
 */
@Composable
internal fun commitSuggestions(files: List<CommitFile>): List<String> {
    if (files.isEmpty()) return emptyList()
    val locale = LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    val date = java.text.SimpleDateFormat(pattern, locale).format(java.util.Date())
    val backup = stringResource(R.string.vault_m3_suggest_backup, date)
    fun name(path: String) = path.substringAfterLast('/').substringBeforeLast('.').ifBlank { path.substringAfterLast('/') }
    val single = files.singleOrNull()
    val folders = files.map { it.path.substringBeforeLast('/', "") }.distinct()
    val describe = when {
        single != null && single.deleted -> stringResource(R.string.vault_m3_suggest_delete, name(single.path))
        single != null && single.added -> stringResource(R.string.vault_m3_suggest_add, name(single.path))
        single != null -> stringResource(R.string.vault_m3_suggest_update, name(single.path))
        folders.size == 1 && folders[0].isNotEmpty() -> stringResource(R.string.vault_m3_suggest_update, folders[0].substringAfterLast('/'))
        else -> pluralStringResource(R.plurals.vault_m3_suggest_files, files.size, files.size)
    }
    return listOf(backup, describe).distinct()
}
