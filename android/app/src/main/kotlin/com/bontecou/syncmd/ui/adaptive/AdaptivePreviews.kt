package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.ui.util.SampleGitData
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.services.files.FileInsights
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.ui.components.CommitFile
import com.bontecou.syncmd.ui.components.ConflictExcerpts
import com.bontecou.syncmd.ui.components.RailChrome
import com.bontecou.syncmd.ui.components.VaultRailRow
import com.bontecou.syncmd.ui.components.VaultTab
import com.bontecou.syncmd.ui.screens.LineNumberedEditor
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.util.AdaptiveInfo

// Previews at the board sizes: Tablet 1280×800, Fold inner 840×700, cover 412×800, split 403×800.

private val previewDiff = FileInsights.compare(
    "ideas/voice-memo-pipeline.md",
    "# Voice memo pipeline\n\n## Steps\n1. Record in Voice Memos\n2. Transcribe by hand\n3. Paste into daily note\n\n## Open questions\n- Where do audio files live?\n- Is LFS worth it?\n- Share with Sam?\n",
    "# Voice memo pipeline\n\n## Steps\n1. Record in Voice Memos\n2. Whisper → transcript.md\n3. Tag speakers (A/B)\n4. Link from the daily note\n\n## Open questions\n- Where do audio files live?\n- LFS for anything over 5 MB ✓\n- Speaker names from contacts?\n- Auto-push after recording\n- Weekly summary note\n- Share with Sam?\n",
    headShortHash = "a41c9e2",
)

private val previewRows = listOf(
    ChangeRowUi("daily/2026-11-01.md", "A", M3Tone.Ok, 42, null, false),
    ChangeRowUi("ideas/voice-memo-pipeline.md", "M", M3Tone.Ahead, 8, 3, false),
    ChangeRowUi("old-inbox.md", "D", M3Tone.Warn, null, 17, false),
)

private val previewVaults = listOf(
    VaultListItem("/v/VoxVault", "VoxVault", "main · ↑3", "↑3", M3Tone.Ahead),
    VaultListItem("/v/notes", "notes", "main · 12m ago", "✓", M3Tone.Ok),
    VaultListItem("/v/health-md", "health-md", "main · 1h ago", "✓", M3Tone.Ok),
    VaultListItem("/v/sacralsol", "sacralsol", "draft/spring · 3h ago", "✓", M3Tone.Ok),
    VaultListItem("/v/site", "codybontecou.com", "main · yesterday", "✓", M3Tone.Ok),
)

private val chrome = RailChrome(toPush = 3, syncing = false, onSyncAll = {}, avatarInitials = "CB", onAccount = {})

private enum class PreviewPane { Diff, Editor, Conflict }

@Composable
private fun BoardFrame(width: Int, pane: PreviewPane, info: AdaptiveInfo = AdaptiveInfo(width, 800)) {
    val wide = info.isExpanded
    VaultRailRow(VaultTab.CHANGES, {}, chrome, wide = wide) {
        Row(Modifier.fillMaxSize().padding(top = 12.dp, end = 16.dp, bottom = 16.dp)) {
            if (wide) {
                VaultListPane(previewVaults, needYou = 1, inSync = 4, selectedPath = "/v/VoxVault", onSelect = {})
                Spacer(Modifier.width(PaneGap))
            }
            val rows = if (pane == PreviewPane.Conflict) previewRows.mapIndexed { i, r -> if (i == 1) r.copy(letter = "!", tone = M3Tone.Warn, conflict = true) else r } else previewRows
            PaneRow(info, if (wide) 300.dp else 333.dp, Modifier.weight(1f),
                list = {
                    ChangesPaneContent("main · CodyBontecou/VoxVault", "VoxVault", 3, changesOnDevice(wide, 3), "Remote has no new commits · fetched 2m ago",
                        rows, selected = rows[if (pane == PreviewPane.Editor) 0 else 1].path, onOpen = {},
                        banner = if (pane == PreviewPane.Conflict) "Merging origin/main · 2 conflicts left" else null) {
                        M3Button(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.shortcut_m3_commit_short), {}, Modifier.fillMaxWidth(), size = M3ButtonSize.Large, trailingMeta = "↑3", enabled = pane != PreviewPane.Conflict)
                    }
                },
                detail = {
                    when (pane) {
                        PreviewPane.Diff -> DiffPaneContent("ideas/voice-memo-pipeline.md", previewDiff, false, false, wide, DiffPaneStyle.Pane,
                            1 to 3, false, {}, {}, {}, {})
                        PreviewPane.Editor -> EditorPaneFrame(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_2026_11_01_md_4381fb10), androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_daily_new_file_42_31057cdf), markdown = true, preview = false, onPreview = {},
                            status = "Saved · 3 lines changed", dot = M3.colors.ok, canCommit = true, onCommit = {}) {
                            LineNumberedEditor("daily/2026-11-01.md", "# Sunday, Nov 1\n\n## Morning\nWalked the beach for forty minutes.\n\n## Today\n- [x] Ship Tide mockups\n- [ ] Port tokens to Compose\n- [ ] Record a Play Store demo\n",
                                true, {}, setOf(1, 2, 3))
                        }
                        PreviewPane.Conflict -> ConflictPreview(wide)
                    }
                })
        }
    }
}

@Composable
private fun ConflictPreview(tablet: Boolean) {
    val ours = "## Step 3\nTranscribe with on-device Whisper,\nthen tag by speaker."
    val theirs = "## Step 3\nTranscribe with the API,\nthen summarize to inbox."
    Column(Modifier.fillMaxSize().detailPane()) {
        DetailHeader(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_voice_memo_pipeline_md_2a5a57d3), androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_conflict_1_of_2_ideas_23283a20)) {
            M3TextButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.adaptive_abort_merge), {}, destructive = true)
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp)) {
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_both_sides_edited_step_3_077f9640), style = com.bontecou.syncmd.ui.theme.M3Type.title, color = M3.colors.on)
            androidx.compose.material3.Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_conflict_body), style = com.bontecou.syncmd.ui.theme.M3Type.bodyMedium, color = M3.colors.onVar)
        }
        ConflictSides(
            ConflictSideUi(if (tablet) "This tablet" else "This phone", "edited 40m ago", ConflictExcerpts.excerpt(ours, theirs), null),
            ConflictSideUi("GitHub", "from Mac · 14m ago", ConflictExcerpts.excerpt(theirs, ours), null),
            enabled = true, onKeepOurs = {}, onKeepTheirs = {}, modifier = Modifier.weight(1f),
        )
        DetailFooter {
            M3Button(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_keep_both), {}, style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium)
            M3Button(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_edit_by_hand), {}, style = M3ButtonStyle.Outlined, size = M3ButtonSize.Medium)
        }
    }
}

@Preview(name = "Tablet · vaults, changes, diff", widthDp = 1280, heightDp = 800)
@Composable private fun TabletPreview() = SyncMdTheme(darkTheme = false) { BoardFrame(1280, PreviewPane.Diff) }

@Preview(name = "Tablet · dark", widthDp = 1280, heightDp = 800)
@Composable private fun TabletDarkPreview() = SyncMdTheme(darkTheme = true) { BoardFrame(1280, PreviewPane.Diff) }

@Preview(name = "Tablet · write + commit", widthDp = 1280, heightDp = 800)
@Composable private fun TabletEditorPreview() = SyncMdTheme(darkTheme = false) { BoardFrame(1280, PreviewPane.Editor) }

@Preview(name = "Tablet · conflict, side by side", widthDp = 1280, heightDp = 800)
@Composable private fun TabletConflictPreview() = SyncMdTheme(darkTheme = false) { BoardFrame(1280, PreviewPane.Conflict) }

@Preview(name = "Foldable inner · changes + diff", widthDp = 840, heightDp = 700)
@Composable private fun FoldVaultPreview() = SyncMdTheme(darkTheme = false) { BoardFrame(840, PreviewPane.Diff, AdaptiveInfo(840, 700, foldable = true)) }

@Preview(name = "Foldable inner · conflict · dark", widthDp = 840, heightDp = 700)
@Composable private fun FoldConflictPreview() = SyncMdTheme(darkTheme = true) { BoardFrame(840, PreviewPane.Conflict, AdaptiveInfo(840, 700, foldable = true)) }

@Preview(name = "Foldable inner · write + commit", widthDp = 840, heightDp = 700)
@Composable private fun FoldEditorPreview() = SyncMdTheme(darkTheme = false) { BoardFrame(840, PreviewPane.Editor, AdaptiveInfo(840, 700, foldable = true)) }

@Preview(name = "Foldable cover · vault status + sync", widthDp = 412, heightDp = 800)
@Composable private fun FoldCoverPreview() = SyncMdTheme(darkTheme = false) {
    CoverHomeContent(
        attentionCount = 1, total = 5, inSyncCount = 4, checked = "2m ago",
        cards = listOf(CoverCardUi("/v/VoxVault", "VoxVault", "main · CodyBontecou/VoxVault", "↑3 to push", 3, previewRows)),
        inSync = listOf(CoverRowUi("/v/notes", "notes", "12m ago", true), CoverRowUi("/v/health-md", "health-md", "1h ago", true),
            CoverRowUi("/v/sacralsol", "sacralsol", "3h ago", true), CoverRowUi("/v/site", "codybontecou.com", "yesterday", true)),
        behind = 0, ahead = 3, syncing = false, syncProgress = null, canSync = true, avatarInitials = "CB",
        onOpenVault = {}, onSyncAll = {}, onAddVault = {}, onAccount = {},
    )
}

@Preview(name = "Foldable half-open · diff above, commit below", widthDp = 840, heightDp = 700)
@Composable private fun FoldTabletopPreview() = SyncMdTheme(darkTheme = false) {
    // Two equal halves, as either side of the board's fold line at 350dp.
    Box(Modifier.fillMaxSize().background(M3.colors.surface)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                DiffPaneContent("ideas/voice-memo-pipeline.md", previewDiff, false, false, false, DiffPaneStyle.Tabletop, 1 to 3, false, {}, {}, {}, {})
            }
            Box(Modifier.weight(1f).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)) {
                InlineCommitFormContent(
                    files = listOf(CommitFile("daily/2026-11-01.md", true, false, true), CommitFile("ideas/voice-memo-pipeline.md", true, false, false),
                        CommitFile("old-inbox.md", true, true, false)),
                    counts = mapOf("daily/2026-11-01.md" to FileLineCount(42, 0), "ideas/voice-memo-pipeline.md" to FileLineCount(8, 3), "old-inbox.md" to FileLineCount(0, 17)),
                    branch = "main", remote = "origin", message = SampleGitData.DAILY_STEPS, onMessage = {}, onToggle = { _, _ -> },
                    enabled = true, canCommit = true, canPush = true, committing = false, pushing = false, onDiscard = {}, onCommitOnly = {}, onCommitAndPush = {},
                )
            }
        }
    }
}

@Preview(name = "Foldable split screen · beside your notes app", widthDp = 403, heightDp = 800)
@Composable private fun FoldSplitPreview() = SyncMdTheme(darkTheme = false) {
    SplitVaultContent("VoxVault", "main", 3, listOf(
        SplitRowUi("daily/2026-11-01.md", "A", M3Tone.Ok, 42, null, typing = true),
        SplitRowUi("ideas/voice-memo-pipeline.md", "M", M3Tone.Behind, 8, 3, typing = false),
        SplitRowUi("ideas/audio/transcript.md", "A", M3Tone.Ok, 120, null, typing = false),
        SplitRowUi("old-inbox.md", "D", M3Tone.Warn, null, 17, typing = false),
    ), autoCommit = true, onAutoCommit = {}, onOpen = {}, onCommit = {})
}
