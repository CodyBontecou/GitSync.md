package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.RepositoryUpstreamPlan
import com.bontecou.syncmd.ui.components.GitPage
import com.bontecou.syncmd.ui.components.GitSheetLabel
import com.bontecou.syncmd.ui.components.GitStatePill
import com.bontecou.syncmd.ui.components.gitMonoInline
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3FilterChip
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ModalSheet
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SheetSurface
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3TextField
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.UpstreamViewModel

/**
 * Upstream branch as its own screen (reached from Connection). The Remote screen shows the same
 * form as a sheet ([UpstreamSheet], Material 3 board GitRemote overlay "upstream").
 */
@Composable
fun UpstreamScreen(path: String, onBack: () -> Unit, model: UpstreamViewModel = hiltViewModel()) {
    val state = model.state.collectAsState().value
    LaunchedEffect(path) { model.load(path) }
    LaunchedEffect(state.requestId, state.saved) { if (state.saved && model.state.value === state) onBack() }
    BackHandler(state.busy && state.plan != null) {}
    var form by remember(path, state.requestId, state.plan) { mutableStateOf(UpstreamDraft.of(state.plan)) }
    GitPage(stringResource(R.string.upstream_title), state.plan?.localBranch, onBack = { if (!state.busy || state.plan == null) onBack() },
        modifier = Modifier.testTag("upstream-screen")) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            UpstreamForm(state, form, { form = it })
            UpstreamActions(state, form, onSave = { model.save(state, form.remote, form.branch) }, onReload = { model.load(path) }, onCancel = onBack)
        }
    }
}

/** The upstream form as a bottom sheet over the Remote screen; closes itself after a save. */
@Composable
fun UpstreamSheet(path: String, onDismiss: () -> Unit, onSaved: () -> Unit, model: UpstreamViewModel = hiltViewModel()) {
    val state by model.state.collectAsState()
    LaunchedEffect(path) { model.load(path) }
    LaunchedEffect(state.requestId, state.saved) { if (state.saved && model.state.value === state) onSaved() }
    var form by remember(path, state.requestId, state.plan) { mutableStateOf(UpstreamDraft.of(state.plan)) }
    M3ModalSheet(onDismissRequest = { if (!state.busy) onDismiss() }, modifier = Modifier.testTag("upstream-sheet"),
        title = stringResource(R.string.upstream_title),
        footer = { UpstreamActions(state, form, onSave = { model.save(state, form.remote, form.branch) }, onReload = { model.load(path) }, onCancel = onDismiss) }) {
        UpstreamForm(state, form, { form = it })
    }
}

/** What the user is editing: remote name and branch. */
data class UpstreamDraft(val remote: String, val branch: String) {
    companion object {
        fun of(plan: RepositoryUpstreamPlan?) = UpstreamDraft(plan?.let { it.remote ?: it.remotes.firstOrNull() }.orEmpty(),
            plan?.remoteBranch ?: plan?.localBranch.orEmpty())
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.UpstreamForm(state: UpstreamViewModel.State, form: UpstreamDraft, onChange: (UpstreamDraft) -> Unit) {
    val c = M3.colors
    val plan = state.plan
    val local = plan?.localBranch ?: "—"
    Text(gitMonoInline(stringResource(R.string.git_m3_upstream_body), local), Modifier.padding(top = 4.dp), style = M3Type.bodyMedium, color = c.onVar)
    if (state.busy && plan == null) M3IndeterminateProgressBar(Modifier.padding(top = 16.dp), description = stringResource(R.string.tide_merge_upstream_loading))
    if (plan != null) {
        Row(Modifier.padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.upstream_local_branch, local), Modifier.testTag("upstream-local"), style = M3Type.count, color = c.on)
            if (plan.remote != null && !plan.remoteBranch.isNullOrBlank()) GitStatePill(stringResource(R.string.git_m3_tracking_cap, "${plan.remote}/${plan.remoteBranch}"), c.okc, c.onOkc)
            else GitStatePill(stringResource(R.string.tide_merge_upstream_unset), c.tc, c.onTc)
        }
        if (!plan.editable || plan.remotes.isEmpty()) M3Note(stringResource(R.string.upstream_unavailable), Modifier.padding(top = 12.dp), tone = M3NoteTone.Ahead)
        val editable = plan.editable && !state.busy && !state.saved
        GitSheetLabel(stringResource(R.string.upstream_remote), bottom = 8.dp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            plan.remotes.forEach { name ->
                M3FilterChip(name, form.remote == name, { onChange(form.copy(remote = name)) }, Modifier.testTag("upstream-remote-$name"),
                    compact = true, enabled = editable, role = Role.RadioButton)
            }
        }
        M3TextField(form.branch, { onChange(form.copy(branch = it)) }, stringResource(R.string.upstream_branch), Modifier.padding(top = 16.dp).testTag("upstream-branch"),
            mono = true, enabled = editable, supportingText = stringResource(R.string.git_m3_upstream_help))
    }
    state.error?.let { M3Note(it.asString(), Modifier.padding(top = 12.dp).testTag("upstream-error"), tone = M3NoteTone.Warn) }
}

@Composable
private fun UpstreamActions(state: UpstreamViewModel.State, form: UpstreamDraft, onSave: () -> Unit, onReload: () -> Unit, onCancel: () -> Unit) {
    val plan = state.plan
    Column(Modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        M3Button(stringResource(R.string.ui_save_1509f561), onSave, Modifier.fillMaxWidth().testTag("upstream-save"), size = M3ButtonSize.Hero,
            enabled = plan != null && plan.editable && !state.busy && !state.saved && form.remote in plan.remotes && form.branch.isNotBlank(),
            loading = state.busy && plan != null)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3TextButton(stringResource(R.string.background_reload), onReload, Modifier.weight(1f).testTag("upstream-reload"), enabled = !state.busy)
            M3TextButton(stringResource(R.string.git_m3_cancel), onCancel, Modifier.weight(1f), enabled = !state.busy || plan == null)
        }
    }
}

@Preview(name = "Upstream sheet · light")
@Composable
private fun UpstreamSheetPreview() = SyncMdTheme(darkTheme = false) {
    val plan = RepositoryUpstreamPlan("/vault", "refs/heads/main", "origin", "main", listOf("origin"), true, "1", "r", null)
    val state = UpstreamViewModel.State(busy = false, plan = plan)
    M3SheetSurface(title = stringResource(R.string.upstream_title), footer = { UpstreamActions(state, UpstreamDraft.of(plan), {}, {}, {}) }) {
        UpstreamForm(state, UpstreamDraft.of(plan)) {}
    }
}
