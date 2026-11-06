package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.GitHeroTitle
import com.bontecou.syncmd.ui.components.GitKicker
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitPlainNote
import com.bontecou.syncmd.ui.components.GitToneHero
import com.bontecou.syncmd.ui.components.StashLegend
import com.bontecou.syncmd.ui.components.StashRow
import com.bontecou.syncmd.ui.components.gitAge
import com.bontecou.syncmd.ui.components.gitShortDate
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel
import com.bontecou.syncmd.ui.viewmodels.GitWorkspaceViewModel
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import kotlinx.coroutines.launch

/**
 * Stashes (Material 3 board GitStashes): an inverse "Set aside" hero with the uncommitted count, an
 * optional message and "Stash N changes" (or "Nothing to stash." when clean), the saved stashes
 * with Apply / Pop / Drop, what each does, and a snackbar after every action.
 */
@Composable
fun GitStashesScreen(
    path: String,
    vaultName: String,
    onBack: () -> Unit,
    historyVM: HistoryViewModel = hiltViewModel(),
    diffVM: DiffViewModel = hiltViewModel(),
    workspaceVM: GitWorkspaceViewModel = hiltViewModel(),
) {
    LaunchedEffect(path) { historyVM.setRepositoryPath(path); diffVM.setRepositoryPath(path); workspaceVM.load(path, stashes = true) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(path) { historyVM.loadHistory(); diffVM.loadDiff(); workspaceVM.load(path, stashes = true) }
    val state by historyVM.state.collectAsState()
    LaunchedEffect(state.operationRevision) { if (state.operationRevision > 0) { diffVM.loadDiff(); workspaceVM.load(path, stashes = true) } }
    val workspace by workspaceVM.state.collectAsState()
    val diff by diffVM.diff.collectAsState()
    val changes = diff?.files.orEmpty().distinctBy { it.filePath }.size
    val context = state.selection?.takeIf { it.path == path }
    val canAct = context != null && state.actionsReady && !state.isLoading
    val androidContext = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun toast(message: String) { scope.launch { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(message, actionLabel = androidContext.getString(R.string.git_m3_ok)) } }
    var message by rememberSaveable(path) { mutableStateOf("") }
    val summary = workspace.summary?.takeIf { workspace.path == path }
    val branch = summary?.branch ?: "—"

    GitPage(
        title = stringResource(R.string.git_m3_stashes),
        subtitle = stringResource(R.string.git_m3_subtitle, vaultName, branch),
        onBack = onBack,
        modifier = Modifier.testTag("git-stashes-screen"),
        scrollTag = "git-stashes",
        snackbar = snackbar,
        bottomSpace = 96.dp,
    ) {
        val c = M3.colors
        if (changes > 0) GitToneHero(c.inv, c.onInv) {
            GitKicker(stringResource(R.string.git_m3_set_aside), color = c.invAhead)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("$changes", style = M3Type.display)
                Text(pluralStringResource(R.plurals.git_m3_uncommitted_changes, changes), Modifier.padding(bottom = 10.dp), style = M3Type.lead)
            }
            Text(stringResource(R.string.git_m3_stash_explain, summary?.head?.shortId ?: "HEAD"), Modifier.padding(top = 2.dp), style = M3Type.bodyMedium, color = c.invMeta)
            OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth().padding(top = 20.dp).testTag("git-stash-message"),
                enabled = canAct, singleLine = true, textStyle = M3Type.body.copy(color = c.onInv),
                label = { Text(stringResource(R.string.git_m3_message_optional)) },
                placeholder = { Text(stringResource(R.string.git_m3_stash_placeholder), style = M3Type.body) },
                shape = RoundedCornerShape(4.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent, disabledContainerColor = Color.Transparent,
                    focusedBorderColor = c.onInv, unfocusedBorderColor = c.invMeta, disabledBorderColor = c.invLine,
                    focusedLabelColor = c.onInv, unfocusedLabelColor = c.invMeta, cursorColor = c.onInv,
                    focusedTextColor = c.onInv, unfocusedTextColor = c.onInv, focusedPlaceholderColor = c.invMeta, unfocusedPlaceholderColor = c.invMeta))
            M3Button(pluralStringResource(R.plurals.git_m3_stash_n, changes, changes), {
                val expected = context ?: return@M3Button
                val submitted = message
                val hadStash = state.stashes.isNotEmpty()
                historyVM.stashSave(expected, submitted) {
                    if (message == submitted) message = ""
                    toast(androidContext.resources.getQuantityString(R.plurals.git_m3_stashed_toast, changes, changes) +
                        if (hadStash) " " + androidContext.getString(R.string.git_m3_stashed_older) else "")
                }
            }, Modifier.fillMaxWidth().padding(top = 12.dp).testTag("git-stash-save"), style = M3ButtonStyle.OnHero, size = M3ButtonSize.Large,
                icon = R.drawable.ic_m3_stash, enabled = canAct, loading = state.isLoading && !state.actionsReady && context != null)
        } else GitToneHero(c.cHighest, c.on) {
            Text(stringResource(R.string.git_m3_nothing_to_stash), style = M3Type.headlineSmall)
            Text(stringResource(R.string.git_m3_nothing_to_stash_body), Modifier.padding(top = 6.dp), style = M3Type.bodyMedium, color = c.onVar)
        }

        val stashes = state.stashes.takeIf { context != null }.orEmpty()
        M3SectionLabel(stringResource(R.string.git_m3_saved), count = stashes.size)
        if (stashes.isEmpty()) Text(stringResource(R.string.git_m3_no_stashes), Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp),
            style = M3Type.bodyMedium, color = c.onVar)
        else M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) {
            stashes.forEachIndexed { index, stash ->
                val detail = workspace.stashes.firstOrNull { it.commit == stash.commitHash } ?: workspace.stashes.firstOrNull { it.id == stash.id }
                val time = detail?.time ?: stash.timestamp.takeIf { it > 0 }
                val meta = detail?.let { d ->
                    listOfNotNull(d.branch?.let { stringResource(R.string.git_m3_stash_on, it) }, d.baseShort,
                        pluralStringResource(R.plurals.git_m3_files, d.files, d.files)).joinToString(" · ")
                }.orEmpty()
                val title = stash.message?.takeIf { it.isNotBlank() }?.substringAfter(": ")?.ifBlank { null } ?: stash.name
                StashRow(stash.id, title, meta, time?.let { if (System.currentTimeMillis() - it < 7 * 86_400_000L) gitAge(androidContext, it) else gitShortDate(androidContext, it) }.orEmpty(),
                    divider = index > 0, enabled = canAct,
                    onApply = { context?.let { historyVM.stashApply(it, stash) { toast(androidContext.getString(R.string.git_m3_applied_toast, stash.id)) } } },
                    onPop = { context?.let { historyVM.stashPop(it, stash) { toast(androidContext.getString(R.string.git_m3_popped_toast, stash.id)) } } },
                    onDrop = { context?.let { historyVM.stashDrop(it, stash) { toast(androidContext.getString(R.string.git_m3_dropped_toast, stash.id)) } } })
            }
        }
        M3SectionLabel(stringResource(R.string.git_m3_what_each_does), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 4.dp))
        StashLegend(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp))
        GitPlainNote(stringResource(R.string.git_m3_stash_local_note))
    }

    state.errorMessage?.takeIf { context != null }?.asString()?.let { error ->
        M3Dialog(onDismissRequest = { historyVM.clearError(context) }, title = stringResource(R.string.tide_git_git_operation),
            content = { Text(error, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).testTag("git-operation-message"), style = M3Type.bodyMedium) }) {
            M3DialogAction(stringResource(R.string.ui_ok_565339bc), { historyVM.clearError(context) })
        }
    }
}
