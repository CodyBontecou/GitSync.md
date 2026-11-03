package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.repositories.RepositoryUpstreamPlan
import com.bontecou.syncmd.ui.components.GitWorkNote
import com.bontecou.syncmd.ui.components.GitWorkPill
import com.bontecou.syncmd.ui.components.GitWorkTone
import com.bontecou.syncmd.ui.components.GitWorkWorking
import com.bontecou.syncmd.ui.components.WrappingTopBar
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideFieldColors
import com.bontecou.syncmd.ui.util.UiText
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.UpstreamViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UpstreamScreen(path: String, onBack: () -> Unit, model: UpstreamViewModel = hiltViewModel()) {
    val state = model.state.collectAsState().value
    var remote by remember(path, state.requestId, state.plan) {
        mutableStateOf(state.plan?.let { it.remote ?: it.remotes.firstOrNull().orEmpty() }.orEmpty())
    }
    var branch by remember(path, state.requestId, state.plan) { mutableStateOf(state.plan?.remoteBranch.orEmpty()) }
    LaunchedEffect(path) { model.load(path) }
    LaunchedEffect(state.requestId, state.saved) { if(state.saved && model.state.value === state) onBack() }
    BackHandler(state.busy && state.plan != null) {}
    Scaffold(Modifier.testTag("upstream-screen"), containerColor = Tide.colors.paper, topBar = {
        WrappingTopBar(stringResource(R.string.upstream_title),stringResource(R.string.publication_back),onBack,
            backEnabled = !state.busy,titleModifier = Modifier.testTag("upstream-title"))
    }) { padding ->
        UpstreamContent(
            state = state, remote = remote, branch = branch,
            modifier = Modifier.padding(padding),
            onRemote = { remote = it }, onBranch = { branch = it },
            onSave = { model.save(state, remote, branch) }, onReload = { model.load(path) },
        )
    }
}

/** Stateless upstream body: current tracking, remote chips, branch field, one Save. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UpstreamContent(
    state: UpstreamViewModel.State,
    remote: String,
    branch: String,
    modifier: Modifier = Modifier,
    onRemote: (String) -> Unit,
    onBranch: (String) -> Unit,
    onSave: () -> Unit,
    onReload: () -> Unit,
) {
    val c = Tide.colors
    Column(
        modifier.fillMaxSize().background(c.paper).imePadding().verticalScroll(rememberScrollState())
            .padding(horizontal = Tide.gutter, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(R.string.upstream_body), Modifier.padding(horizontal = 4.dp), style = TideType.sans(14.sp), color = c.inkMid)
        if (state.busy && state.plan == null) GitWorkWorking(stringResource(R.string.tide_merge_upstream_loading))
        state.plan?.let { plan ->
            TrackingCard(plan)
            if (!plan.editable || plan.remotes.isEmpty()) {
                GitWorkNote(stringResource(R.string.upstream_unavailable), tone = GitWorkTone.AHEAD)
            }
            val editable = plan.editable && !state.busy && !state.saved
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TideSectionLabel(stringResource(R.string.upstream_remote), Modifier.padding(start = 4.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    plan.remotes.forEach { name ->
                        RemoteChip(name, selected = remote == name, enabled = editable, onClick = { onRemote(name) },
                            modifier = Modifier.testTag("upstream-remote-$name"))
                    }
                }
            }
            OutlinedTextField(
                branch, onBranch, singleLine = true, enabled = editable,
                label = { Text(stringResource(R.string.upstream_branch)) },
                modifier = Modifier.fillMaxWidth().testTag("upstream-branch"),
                shape = Tide.fieldShape,
                colors = tideFieldColors(),
                textStyle = TideType.mono(14.sp).copy(color = c.ink, textDirection = TextDirection.Ltr),
            )
            TidePrimaryButton(
                title = stringResource(R.string.ui_save_1509f561),
                onClick = onSave,
                enabled = plan.editable && !state.busy && !state.saved && remote in plan.remotes && branch.isNotBlank(),
                loading = state.busy,
                modifier = Modifier.testTag("upstream-save"),
            )
        }
        state.error?.let { GitWorkNote(it.asString(), tone = GitWorkTone.DANGER, textModifier = Modifier.testTag("upstream-error")) }
        GitWorkPill(stringResource(R.string.background_reload), onReload, Modifier.testTag("upstream-reload"), enabled = !state.busy)
        Spacer(Modifier.size(16.dp))
    }
}

/** Local branch and what it tracks today: set (green) or unset (orange). */
@Composable
private fun TrackingCard(plan: RepositoryUpstreamPlan) {
    val c = Tide.colors
    val tracked = plan.remote != null && !plan.remoteBranch.isNullOrBlank()
    Column(Modifier.fillMaxWidth()) {
        TideSectionLabel(stringResource(R.string.tide_merge_upstream_section), Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard(padding = 16.dp) {
            Text(stringResource(R.string.upstream_local_branch, plan.localBranch ?: "—"), Modifier.testTag("upstream-local"),
                style = TideType.mono(14.sp, FontWeight.SemiBold), color = c.ink)
            Spacer(Modifier.size(10.dp))
            if (tracked) {
                TidePill(stringResource(R.string.tide_merge_upstream_set, plan.remote.orEmpty(), plan.remoteBranch.orEmpty()),
                    c.synced, c.synced.copy(alpha = 0.12f))
            } else {
                TidePill(stringResource(R.string.tide_merge_upstream_unset), c.ahead, c.ahead.copy(alpha = 0.12f))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.tide_merge_upstream_unset_body), style = TideType.sans(13.sp), color = c.inkFaint)
            }
        }
    }
}

/** Selectable remote chip (ink when chosen) with a real disabled state. */
@Composable
private fun RemoteChip(name: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.ink, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { this.selected = selected }
            .alpha(if (enabled || selected) 1f else 0.45f)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.size(8.dp).border(2.dp, if (selected) c.paper else c.behind, CircleShape))
        Text(name, style = TideType.mono(14.sp, FontWeight.Medium), color = if (selected) c.paper else c.ink)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

private fun previewUpstreamPlan(remote: String?, branch: String?, editable: Boolean = true, remotes: List<String> = listOf("origin", "backup")) =
    RepositoryUpstreamPlan(path = "/vault", branchRef = "refs/heads/main", remote = remote, remoteBranch = branch,
        remotes = remotes, editable = editable, revision = "1", rootIdentity = "r", metadataIdentity = null)

@Composable
private fun UpstreamPreview(state: UpstreamViewModel.State, remote: String = "origin", branch: String = "main") {
    UpstreamContent(state, remote, branch, Modifier, {}, {}, {}, {})
}

@Preview(name = "Upstream · loading", heightDp = 400) @Composable
private fun UpstreamLoading() = SyncMdTheme(darkTheme = false) { UpstreamPreview(UpstreamViewModel.State()) }

@Preview(name = "Upstream · set · light", heightDp = 720) @Composable
private fun UpstreamSetLight() = SyncMdTheme(darkTheme = false) {
    UpstreamPreview(UpstreamViewModel.State(busy = false, plan = previewUpstreamPlan("origin", "main")))
}

@Preview(name = "Upstream · unset · dark", heightDp = 720) @Composable
private fun UpstreamUnsetDark() = SyncMdTheme(darkTheme = true) {
    UpstreamPreview(UpstreamViewModel.State(busy = false, plan = previewUpstreamPlan(null, null)), remote = "origin", branch = "")
}

@Preview(name = "Upstream · unavailable", heightDp = 720) @Composable
private fun UpstreamUnavailable() = SyncMdTheme(darkTheme = false) {
    UpstreamPreview(UpstreamViewModel.State(busy = false, plan = previewUpstreamPlan(null, null, editable = false, remotes = emptyList())), remote = "")
}

@Preview(name = "Upstream · error", heightDp = 400) @Composable
private fun UpstreamError() = SyncMdTheme(darkTheme = true) {
    UpstreamPreview(UpstreamViewModel.State(busy = false, error = UiText.Raw("Could not load the upstream branch")))
}
