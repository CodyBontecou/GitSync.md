package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.FileDiff
import com.bontecou.syncmd.data.models.UnifiedDiffResult
import com.bontecou.syncmd.ui.components.GitWorkDiffLineRow
import com.bontecou.syncmd.ui.components.GitWorkDiffRow
import com.bontecou.syncmd.ui.components.GitWorkDiffKind
import com.bontecou.syncmd.ui.components.GitWorkPill
import com.bontecou.syncmd.ui.components.GitWorkPillStyle
import com.bontecou.syncmd.ui.components.GitWorkStatusPill
import com.bontecou.syncmd.ui.components.GitWorkWorking
import com.bontecou.syncmd.ui.components.RevertConfirmDialog
import com.bontecou.syncmd.ui.components.gitWorkDiffRows
import com.bontecou.syncmd.ui.components.gitWorkPreviewDiff
import com.bontecou.syncmd.ui.theme.BPrimaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideDisplay
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.DiffViewModel

// ─── Screen ──────────────────────────────────────────────────────────────────

/**
 * Full-screen diff for a single file — mirrors iOS Tide FileDiffView.
 *
 * Shows a folder caption in the top bar, the filename as the headline with
 * status / staged pills and "+N −M", then the diff on a white card: hunk
 * bands, green additions and red removals with changed words tinted. The
 * Discard pill opens [RevertConfirmDialog].
 */
@Composable
fun DiffScreen(
    repositoryPath: String,
    filePath: String,
    onNavigateBack: () -> Unit,
    staged: Boolean? = null,
    diffVM: DiffViewModel = hiltViewModel(),
) {
    LaunchedEffect(repositoryPath, filePath, staged) {
        if (repositoryPath.isNotBlank()) {
            diffVM.setRepositoryPath(repositoryPath)
            if (filePath.isNotBlank()) diffVM.selectFile(filePath, staged)
        }
    }

    val isLoading        by diffVM.isLoading.collectAsState()
    val selectedFileDiff by diffVM.selectedFileDiff.collectAsState()
    val diffError by diffVM.errorMessage.collectAsState()

    val discardReview by diffVM.discardReview.collectAsState()
    val reviewingDiscard by diffVM.isReviewingDiscard.collectAsState()
    val discarding by diffVM.isDiscarding.collectAsState()
    val isReverting = reviewingDiscard || discarding
    DisposableEffect(repositoryPath, filePath) { onDispose { diffVM.dismissDiscard() } }

    com.bontecou.syncmd.ui.util.RepositoryUpdatesEffect(repositoryPath) {
        // A periodic read must not close a frozen review. Confirmation revalidates its exact revision.
        if (!isReverting) {
            diffVM.refreshRepositoryPath(repositoryPath)
            if (filePath.isNotBlank()) diffVM.selectFile(filePath, staged)
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Tide.colors.paper).navigationBarsPadding()) {
        DiffContent(
            filePath = filePath,
            staged = staged,
            isLoading = isLoading,
            result = selectedFileDiff,
            error = diffError,
            isReverting = isReverting,
            onBack = onNavigateBack,
            onDiscard = { diffVM.requestDiscard(filePath) },
        )

        if (diffError != null && selectedFileDiff != null) {
            TideDialog(
                onDismissRequest = diffVM::clearError,
                title = stringResource(R.string.ui_error_54a0e8c1),
                text = {
                    Text(diffError!!, Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState()).testTag("diff-operation-error"))
                },
                confirmButton = { BPrimaryButton(stringResource(R.string.ui_ok_565339bc), onClick = diffVM::clearError) },
            )
        }
        // ── Revert confirm dialog ──────────────────────────────────────────
        discardReview?.takeIf { it.filePath == filePath }?.let { review ->
            RevertConfirmDialog(
                review = review,
                onConfirm = { diffVM.confirmDiscard(review, onComplete = onNavigateBack) },
                onDismiss = { diffVM.dismissDiscard(review) },
            )
        }
    }
}

/** Stateless body of the diff screen, one branch per load state. */
@Composable
private fun DiffContent(
    filePath: String,
    staged: Boolean?,
    isLoading: Boolean,
    result: UnifiedDiffResult?,
    error: String?,
    isReverting: Boolean,
    onBack: () -> Unit,
    onDiscard: () -> Unit,
) {
    val c = Tide.colors
    val filename = filePath.substringAfterLast('/').ifEmpty { filePath }
    val directory = filePath.substringBeforeLast('/', "")
    val fileDiff = result?.files?.firstOrNull()
    val rows = remember(result) { fileDiff?.let(::gitWorkDiffRows) ?: emptyList() }
    val added = rows.count { it.kind == GitWorkDiffKind.ADDED }
    val removed = rows.count { it.kind == GitWorkDiffKind.REMOVED }

    Column(Modifier.fillMaxSize()) {
        TideTopBar(
            onBack = onBack,
            backLabel = stringResource(R.string.publication_back),
            center = {
                Text(
                    if (directory.isBlank()) "/" else "$directory/",
                    style = TideType.mono(12.sp),
                    color = c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            },
        )

        LazyColumn(Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item(key = "header") {
                DiffHeader(filename, fileDiff, staged, added, removed, showStats = rows.isNotEmpty())
            }
            when {
                isLoading -> item(key = "loading") {
                    GitWorkWorking(stringResource(R.string.ui_loading_diff_3944c704),
                        Modifier.padding(horizontal = Tide.gutter, vertical = 20.dp))
                }
                rows.isEmpty() -> item(key = "empty") {
                    val (title, body) = when {
                        error != null -> stringResource(R.string.tide_merge_diff_error_title) to error
                        fileDiff?.isBinary == true -> stringResource(R.string.ui_binary_file_2c7ccf98) to
                            stringResource(R.string.tide_merge_diff_binary_body)
                        else -> stringResource(R.string.tide_merge_diff_empty_title) to stringResource(R.string.tide_merge_diff_empty_body)
                    }
                    DiffStateCard(title, body, danger = error != null)
                }
                else -> diffCard(rows)
            }
        }

        // ── Bottom bar: one quiet, destructive escape ─────────────────────
        Row(
            Modifier.fillMaxWidth().background(c.paper).padding(horizontal = Tide.gutter, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GitWorkPill(
                title = stringResource(R.string.tide_merge_diff_discard),
                onClick = onDiscard,
                style = GitWorkPillStyle.DANGER_OUTLINE,
                enabled = !isReverting && !isLoading,
                loading = isReverting,
                fill = false,
                height = 56.dp,
            )
            Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun DiffHeader(filename: String, fileDiff: FileDiff?, staged: Boolean?, added: Int, removed: Int, showStats: Boolean) {
    val c = Tide.colors
    Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TideDisplay(filename, size = 26.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            fileDiff?.let { GitWorkStatusPill(it.status) }
            val isStaged = fileDiff?.isStaged == true || staged == true
            if (fileDiff != null || staged != null) {
                TidePill(
                    stringResource(if (isStaged) R.string.tide_merge_diff_staged else R.string.tide_merge_diff_unstaged),
                    if (isStaged) c.synced else c.ahead,
                    (if (isStaged) c.synced else c.ahead).copy(alpha = 0.12f),
                )
            }
        }
        if (showStats) {
            val a11y = stringResource(R.string.tide_merge_diff_stats_a11y, added, removed)
            Row(Modifier.clearAndSetSemantics { contentDescription = a11y }, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("+$added", style = TideType.mono(12.sp, FontWeight.SemiBold), color = if (added > 0) c.synced else c.inkFaint)
                Text("−$removed", style = TideType.mono(12.sp, FontWeight.SemiBold), color = if (removed > 0) c.danger else c.inkFaint)
            }
        }
    }
}

@Composable
private fun DiffStateCard(title: String, body: String, danger: Boolean) {
    val c = Tide.colors
    TideCard(Modifier.padding(horizontal = Tide.gutter)) {
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (danger) c.danger else c.ink)
        Spacer(Modifier.height(4.dp))
        Text(body, style = TideType.meta, color = c.inkFaint)
    }
}

/** Lines laid out as one rounded white card spread across lazy items. */
private fun LazyListScope.diffCard(rows: List<GitWorkDiffRow>) {
    val last = rows.lastIndex
    itemsIndexed(rows) { index, row ->
        val r = Tide.radiusCard
        val shape = when {
            last == 0 -> RoundedCornerShape(r)
            index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
            index == last -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
            else -> RoundedCornerShape(0.dp)
        }
        Box(
            Modifier.padding(horizontal = Tide.gutter).fillMaxWidth().clip(shape).background(Tide.colors.card)
                .padding(top = if (index == 0 && row.kind != GitWorkDiffKind.HUNK) 8.dp else 0.dp,
                    bottom = if (index == last) 8.dp else 0.dp),
        ) { GitWorkDiffLineRow(row) }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun DiffPreview(isLoading: Boolean = false, result: UnifiedDiffResult? = null, error: String? = null, reverting: Boolean = false) {
    Box(Modifier.fillMaxSize().background(Tide.colors.paper)) {
        DiffContent("ideas/launch-plan.md", null, isLoading, result, error, reverting, {}, {})
    }
}

private val previewSingle = gitWorkPreviewDiff.copy(files = gitWorkPreviewDiff.files.take(1))
private val previewBinary = gitWorkPreviewDiff.copy(files = gitWorkPreviewDiff.files.drop(1))
private val previewEmpty = gitWorkPreviewDiff.copy(files = listOf(gitWorkPreviewDiff.files.first().copy(hunks = emptyList())))

@Preview(name = "Diff · lines · light", heightDp = 640) @Composable
private fun DiffLinesLight() = SyncMdTheme(darkTheme = false) { DiffPreview(result = previewSingle) }

@Preview(name = "Diff · lines · dark", heightDp = 640) @Composable
private fun DiffLinesDark() = SyncMdTheme(darkTheme = true) { DiffPreview(result = previewSingle) }

@Preview(name = "Diff · loading", heightDp = 400) @Composable
private fun DiffLoading() = SyncMdTheme(darkTheme = false) { DiffPreview(isLoading = true) }

@Preview(name = "Diff · binary · dark", heightDp = 400) @Composable
private fun DiffBinaryDark() = SyncMdTheme(darkTheme = true) { DiffPreview(result = previewBinary) }

@Preview(name = "Diff · empty", heightDp = 400) @Composable
private fun DiffEmpty() = SyncMdTheme(darkTheme = false) { DiffPreview(result = previewEmpty) }

@Preview(name = "Diff · error", heightDp = 400) @Composable
private fun DiffError() = SyncMdTheme(darkTheme = false) { DiffPreview(error = "Could not read the index.") }

@Preview(name = "Diff · reverting · dark", heightDp = 640) @Composable
private fun DiffReverting() = SyncMdTheme(darkTheme = true) { DiffPreview(result = previewSingle, reverting = true) }
