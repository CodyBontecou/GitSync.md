package com.bontecou.syncmd.ui.screens

import com.bontecou.syncmd.ui.util.SampleGitData
import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.services.git.HistoryMarks
import com.bontecou.syncmd.sync.hostLabel
import com.bontecou.syncmd.ui.components.CommitDot
import com.bontecou.syncmd.ui.components.CommitItem
import com.bontecou.syncmd.ui.components.VaultNavigationBar
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3SectionLabel
import com.bontecou.syncmd.ui.theme.M3Tag
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel
import com.bontecou.syncmd.ui.viewmodels.VaultSyncViewModel

/**
 * Vault › History (Material 3 design board 04): commits not on the remote yet under "Only on this
 * phone" with filled dots in the ahead color, then the "origin/main · GitHub is here" marker, then
 * the rest grouped by day. Each row: message with tag chips, "hash · author · time · N files" or
 * "· merge". Tap a commit for its full message, hash and parents.
 */
@Composable
fun HistoryScreen(
    repositoryPath: String,
    repositoryName: String,
    onNavigateBack: () -> Unit,
    showNavigation: Boolean = true,
    onSelectTab: (VaultTab) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
    repositoryOwner: String? = null,
    /** The shared vault bar's More (Vault settings); hidden when null. */
    onOpenVaultSettings: (() -> Unit)? = null,
    syncViewModel: VaultSyncViewModel? = rememberVaultSyncViewModel(),
) {
    LaunchedEffect(repositoryPath) {
        if (repositoryPath.isNotBlank()) {
            viewModel.setRepositoryPath(repositoryPath)
            syncViewModel?.setRepositoryPath(repositoryPath)
        }
    }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.loadHistory(); syncViewModel?.refresh() }
    val state by viewModel.state.collectAsState()
    val current = state.takeIf { it.repositoryPath == repositoryPath }
    val commits = current?.commits.orEmpty()
    LaunchedEffect(commits) { if (commits.isNotEmpty()) syncViewModel?.loadHistoryMarks(commits.map { it.hash }) }
    val marks = syncViewModel?.historyMarks?.collectAsState()?.value
    val overview = syncViewModel?.overview?.collectAsState()?.value
    val run = syncViewModel?.run?.collectAsState()?.value
    // Fall back to the history read's own tag list when the decorations are not available.
    val tags = marks?.tags ?: current?.tags.orEmpty().groupBy({ it.commitHash }, { it.name })
    HistoryContent(
        name = repositoryName,
        subtitle = listOfNotNull(overview?.branch, repositoryOwner?.takeIf { it.isNotBlank() }?.let { "$it/$repositoryName" }).joinToString(" · ").ifBlank { null },
        commits = commits,
        marks = marks,
        tags = tags,
        hostLabel = hostLabel(overview?.remoteHost),
        loading = current?.isLoading != false,
        error = current?.errorMessage?.asString(),
        onNavigateBack = onNavigateBack,
        showNavigation = showNavigation,
        onSelectTab = onSelectTab,
        onCheckForChanges = syncViewModel?.let { vm -> { vm.pull() } },
        checking = run?.running == true,
        onOpenVaultSettings = onOpenVaultSettings,
    )
}

private sealed interface HistoryItem {
    data class Head(val label: String, val first: Boolean) : HistoryItem
    data class Marker(val upstream: String) : HistoryItem
    data class Entry(val commit: Commit, val dot: CommitDot?, val last: Boolean) : HistoryItem
}

@Composable
private fun dayLabel(millis: Long, now: Long): String = when {
    millis <= 0 -> "—"
    DateUtils.isToday(millis) -> stringResource(R.string.vault_m3_today)
    DateUtils.isToday(millis + DateUtils.DAY_IN_MILLIS) -> stringResource(R.string.vault_m3_yesterday)
    else -> DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_SHOW_DATE).toString()
}

/** "20m", "6h" today; the time of day before that (design: "e93b1f0 · phone · 20m", "· 23:02"). */
private fun timeLabel(millis: Long, now: Long): String {
    if (millis <= 0) return "—"
    val minutes = ((now - millis) / 60_000L).coerceAtLeast(0)
    return when {
        DateUtils.isToday(millis) && minutes < 60 -> "${minutes.coerceAtLeast(1)}m"
        DateUtils.isToday(millis) -> "${minutes / 60}h"
        else -> java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))
    }
}

@Composable
private fun commitMeta(commit: Commit, marks: HistoryMarks?, now: Long): String {
    val extra = when {
        commit.parentHashes.size > 1 -> stringResource(R.string.vault_m3_merge)
        else -> marks?.fileCounts?.get(commit.hash)?.let { pluralStringResource(R.plurals.vault_m3_file_count, it, it) }
    }
    return listOfNotNull(commit.shortHash, commit.author, timeLabel(commit.timestamp, now), extra).joinToString(" · ")
}

@Composable
private fun HistoryContent(
    name: String,
    subtitle: String?,
    commits: List<Commit>,
    marks: HistoryMarks?,
    tags: Map<String, List<String>>,
    hostLabel: String?,
    loading: Boolean,
    error: String?,
    onNavigateBack: () -> Unit,
    showNavigation: Boolean,
    onSelectTab: (VaultTab) -> Unit,
    onCheckForChanges: (() -> Unit)? = null,
    checking: Boolean = false,
    onOpenVaultSettings: (() -> Unit)? = null,
) {
    val c = M3.colors
    val now = System.currentTimeMillis()
    val todayLabel = stringResource(R.string.vault_m3_today)
    val dayLabels = commits.associate { it.hash to dayLabel(it.timestamp, now) }
    val items = buildList {
        val unpushed = commits.filter { marks != null && it.hash in marks.unpushed }
        val rest = commits.filterNot { marks != null && it.hash in marks.unpushed }
        if (unpushed.isNotEmpty()) {
            add(HistoryItem.Head("", first = true))
            unpushed.forEach { add(HistoryItem.Entry(it, CommitDot(c.ahead, ring = false), last = false)) }
        }
        var day: String? = null
        rest.forEachIndexed { index, commit ->
            val upstream = marks?.upstream
            if (upstream != null && marks.upstreamHead == commit.hash) add(HistoryItem.Marker(upstream))
            val label = dayLabels.getValue(commit.hash)
            if (label != day) { add(HistoryItem.Head(label, first = isEmpty())); day = label }
            val dot = when {
                marks == null && index == 0 && unpushed.isEmpty() -> CommitDot(c.primary, ring = false)
                label == todayLabel -> CommitDot(c.behind, ring = true)
                else -> CommitDot(c.onVar, ring = true)
            }
            add(HistoryItem.Entry(commit, dot, last = index == rest.lastIndex))
        }
    }
    Column(Modifier.fillMaxSize().background(c.surface)) {
        M3TopBar(name, subtitle = subtitle, onBack = onNavigateBack) {
            if (onCheckForChanges != null) M3IconButton(R.drawable.ic_m3_sync, stringResource(R.string.vault_m3_check_for_changes), onCheckForChanges,
                enabled = !checking)
            if (onOpenVaultSettings != null) M3IconButton(R.drawable.ic_m3_more_vert, stringResource(R.string.vault_m3_vault_settings), onOpenVaultSettings)
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if ((loading && commits.isNotEmpty()) || checking) M3IndeterminateProgressBar()
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(if (loading) "history-loading" else "history-ready"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (error != null) item { M3Note(error, Modifier.padding(16.dp), tone = M3NoteTone.Warn) }
            if (commits.isEmpty() && !loading) item {
                M3ListCard(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_no_commits_found_64ccb533), style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(20.dp))
                }
            }
            items(items) { item ->
                when (item) {
                    is HistoryItem.Head -> M3SectionLabel(
                        item.label.ifEmpty { stringResource(R.string.vault_m3_only_on_phone) },
                        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = if (item.first) 8.dp else 16.dp, bottom = 4.dp),
                    )
                    is HistoryItem.Marker -> Row(
                        Modifier.fillMaxWidth().padding(start = 26.dp, end = 16.dp, top = 4.dp, bottom = 4.dp).testTag("history-upstream-marker"),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        M3Tag(item.upstream, tone = M3Tone.Behind)
                        Text(stringResource(R.string.vault_m3_remote_is_here, hostLabel ?: stringResource(R.string.vault_m3_remote_generic)
                            .replaceFirstChar { it.titlecase() }), style = M3Type.caption, color = c.onVar)
                        Box(Modifier.weight(1f).height(1.dp).background(c.behind))
                    }
                    is HistoryItem.Entry -> {
                        var expanded by rememberSaveable(item.commit.hash) { mutableStateOf(false) }
                        CommitItem(
                            item.commit, expanded, onToggleExpand = { expanded = !expanded },
                            dot = item.dot, meta = commitMeta(item.commit, marks, now), tags = tags[item.commit.hash].orEmpty(),
                            lastInList = item.last,
                        )
                    }
                }
            }
        }
        if (showNavigation) VaultNavigationBar(VaultTab.HISTORY, onSelectTab)
    }
}

private val previewCommits = listOf(
    Commit("e93b1f0aa", author = "Cody Bontecou", message = "Draft speaker tagging idea", timestamp = System.currentTimeMillis() - 1_200_000),
    Commit("7c0d2aa11", author = "Cody Bontecou", message = "Add Nov 1 daily note", timestamp = System.currentTimeMillis() - 3_600_000),
    Commit("a41c9e2aa", author = "Obsidian Git", message = SampleGitData.MORNING, timestamp = System.currentTimeMillis() - 6 * 3_600_000),
    Commit("51fe80322", author = "Cody Bontecou", message = "Merge draft/pipeline into main", timestamp = System.currentTimeMillis() - 9 * 3_600_000,
        parentHashes = listOf("a", "b")),
    Commit("0b8d77144", author = "Background Sync", message = SampleGitData.BACKUP, timestamp = System.currentTimeMillis() - 30 * 3_600_000),
)

private val previewMarks = HistoryMarks("origin/main", "a41c9e2aa", setOf("e93b1f0aa", "7c0d2aa11"),
    mapOf("e93b1f0aa" to 1, "7c0d2aa11" to 1, "a41c9e2aa" to 3, "0b8d77144" to 9), mapOf("51fe80322" to listOf("v0.4")))

@Preview(name = "History · light", heightDp = 915, widthDp = 412)
@Composable
private fun PreviewHistory() = SyncMdTheme {
    HistoryContent("VoxVault", androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_main_codybontecou_voxvault_c727a0dc), previewCommits, previewMarks, previewMarks.tags, "GitHub", false, null, {}, true, {})
}

@Preview(name = "History · dark", heightDp = 915, widthDp = 412)
@Composable
private fun PreviewHistoryDark() = SyncMdTheme(darkTheme = true) {
    HistoryContent("VoxVault", androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_main_codybontecou_voxvault_c727a0dc), previewCommits, previewMarks, previewMarks.tags, "GitHub", false, null, {}, true, {})
}
