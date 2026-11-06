package com.bontecou.syncmd.ui.screens

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.Commit
import com.bontecou.syncmd.ui.components.VaultNavigationBar
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideTopBarTitle
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.util.asString
import com.bontecou.syncmd.ui.viewmodels.HistoryViewModel

/**
 * Vault › History (Material 3 design board 04): the commit timeline grouped by
 * day, newest first. The tip of the branch is a filled dot; earlier commits are
 * rings on one line. Tap a commit for its full message, hash and parents.
 */
@Composable
fun HistoryScreen(
    repositoryPath: String,
    repositoryName: String,
    onNavigateBack: () -> Unit,
    showNavigation: Boolean = true,
    onSelectTab: (VaultTab) -> Unit = {},
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath) { if (repositoryPath.isNotBlank()) viewModel.setRepositoryPath(repositoryPath) }
    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) { viewModel.loadHistory() }
    val state by viewModel.state.collectAsState()
    val current = state.takeIf { it.repositoryPath == repositoryPath }
    HistoryContent(
        name = repositoryName,
        commits = current?.commits.orEmpty(),
        loading = current?.isLoading != false,
        error = current?.errorMessage?.asString(),
        onNavigateBack = onNavigateBack,
        showNavigation = showNavigation,
        onSelectTab = onSelectTab,
    )
}

private sealed interface HistoryItem {
    data class Day(val label: String) : HistoryItem
    data class Entry(val commit: Commit, val tip: Boolean, val last: Boolean) : HistoryItem
}

/** Commit times are epoch milliseconds (JGit `PersonIdent.when`). */
private fun dayLabel(millis: Long, now: Long): String =
    if (millis <= 0) "—"
    else DateUtils.getRelativeTimeSpanString(millis, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_SHOW_DATE).toString()

private fun timeLabel(millis: Long): String =
    if (millis <= 0) "—" else java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

@Composable
private fun HistoryContent(
    name: String,
    commits: List<Commit>,
    loading: Boolean,
    error: String?,
    onNavigateBack: () -> Unit,
    showNavigation: Boolean,
    onSelectTab: (VaultTab) -> Unit,
) {
    val c = Tide.colors
    val now = System.currentTimeMillis()
    val items = buildList {
        var day: String? = null
        commits.forEachIndexed { index, commit ->
            val label = dayLabel(commit.timestamp, now)
            if (label != day) { add(HistoryItem.Day(label)); day = label }
            add(HistoryItem.Entry(commit, tip = index == 0, last = index == commits.lastIndex))
        }
    }
    Column(Modifier.fillMaxSize().background(c.paper)) {
        TideTopBar(
            onBack = onNavigateBack,
            center = { TideTopBarTitle(name, stringResource(R.string.history_screen)) },
        )
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (loading && commits.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth(), color = c.primary, trackColor = c.primaryContainer)
        }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().testTag(if (loading) "history-loading" else "history-ready"),
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (error != null) item { TideBanner(error, Modifier.padding(16.dp), tone = TideBannerTone.DANGER) }
            if (commits.isEmpty() && !loading) item {
                TideCard(Modifier.padding(16.dp)) {
                    Text(stringResource(R.string.ui_no_commits_found_64ccb533), style = TideType.body, color = c.inkMid)
                }
            }
            items(items) { item ->
                when (item) {
                    is HistoryItem.Day -> TideSectionLabel(item.label, Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
                    is HistoryItem.Entry -> HistoryRow(item.commit, item.tip, item.last)
                }
            }
        }
        if (showNavigation) VaultNavigationBar(VaultTab.HISTORY, onSelectTab)
    }
}

@Composable
private fun HistoryRow(commit: Commit, tip: Boolean, last: Boolean) {
    val c = Tide.colors
    var expanded by rememberSaveable(commit.hash) { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 56.dp).clickable(role = Role.Button) { expanded = !expanded }
            .padding(start = 26.dp, end = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Timeline spine: a line through every commit, the tip filled.
        Column(Modifier.width(12.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.width(2.dp).height(14.dp).background(c.line))
            Box(
                Modifier.size(12.dp).then(
                    if (tip) Modifier.background(c.primary, CircleShape)
                    else Modifier.background(c.paper, CircleShape).border(2.dp, if (commit.parentHashes.size > 1) c.behind else c.inkMid, CircleShape)
                )
            )
            Box(Modifier.width(2.dp).weight(1f).background(if (last) Color.Transparent else c.line))
        }
        Column(Modifier.weight(1f).padding(vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(commit.message.lineSequence().firstOrNull().orEmpty(), style = TideType.sans(15.sp).copy(lineHeight = 22.sp), color = c.ink,
                maxLines = if (expanded) 4 else 1, overflow = TextOverflow.Ellipsis)
            Text(listOf(commit.shortHash, commit.author, timeLabel(commit.timestamp)).joinToString(" · "), style = TideType.meta, color = c.inkMid,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (expanded) {
                Column(
                    Modifier.padding(top = 8.dp).fillMaxWidth().background(c.card, RoundedCornerShape(16.dp)).padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val body = commit.message.substringAfter('\n', "").trim()
                    if (body.isNotEmpty()) Text(body, style = TideType.sans(14.sp), color = c.ink)
                    HistoryFact(stringResource(R.string.ui_hash_a9106914), commit.hash)
                    commit.email?.takeIf { it.isNotBlank() }?.let { HistoryFact(stringResource(R.string.ui_email_969ccbd3), it) }
                    if (commit.parentHashes.isNotEmpty()) HistoryFact(
                        stringResource(R.string.ui_parents_1_s_914efbd0, commit.parentHashes.size.toString()),
                        commit.parentHashes.joinToString(" ") { it.take(7) },
                    )
                }
            }
        }
    }
}

@Composable
private fun HistoryFact(label: String, value: String) {
    Column {
        Text(label, style = TideType.sans(12.sp, FontWeight.SemiBold), color = Tide.colors.inkMid, modifier = Modifier.semantics { heading() })
        Text(value, style = TideType.mono(12.sp), color = Tide.colors.ink)
    }
}

private val previewCommits = listOf(
    Commit("e93b1f0aa", author = "Phone", message = "Draft speaker tagging idea", timestamp = System.currentTimeMillis() - 1_200_000),
    Commit("7c0d2aa11", author = "Phone", message = "Add Nov 1 daily note", timestamp = System.currentTimeMillis() - 3_600_000),
    Commit("51fe80322", author = "Mac", message = "Merge draft/pipeline into main", timestamp = System.currentTimeMillis() - 90_000_000,
        parentHashes = listOf("a", "b")),
    Commit("0b8d77144", author = "Background Sync", message = "vault backup", timestamp = System.currentTimeMillis() - 200_000_000),
)

@Preview(name = "History · light", heightDp = 900)
@Composable
private fun PreviewHistory() = SyncMdTheme { HistoryContent("VoxVault", previewCommits, false, null, {}, true, {}) }

@Preview(name = "History · dark", heightDp = 900)
@Composable
private fun PreviewHistoryDark() = SyncMdTheme(darkTheme = true) { HistoryContent("VoxVault", previewCommits, false, null, {}, true, {}) }

