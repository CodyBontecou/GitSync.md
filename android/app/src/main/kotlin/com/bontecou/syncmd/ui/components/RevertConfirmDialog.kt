package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.DiscardReview
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideIcon
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TideType

/**
 * Tide "Discard changes" sheet (iOS RevertConfirmModal): danger tile, a
 * plain-language headline, what happens, every affected path on a card, then
 * a red Discard pill and a quiet Cancel.
 *
 * The displayed immutable review is the authority passed to confirmation,
 * including every full path.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
fun RevertConfirmDialog(review: DiscardReview, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RevertReviewSheet(
            keepsWorkingFiles = review.keepsWorkingFiles,
            singleFile = review.filePath != null,
            abandonsOperation = review.abandonsOperation,
            paths = review.paths,
            onConfirm = onConfirm,
            onDismiss = onDismiss,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        )
    }
}

/** Stateless sheet body, shared by the dialog and its previews. */
@Composable
private fun RevertReviewSheet(
    keepsWorkingFiles: Boolean,
    singleFile: Boolean,
    abandonsOperation: Boolean,
    paths: List<String>,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Tide.colors
    val cancel = stringResource(R.string.publication_cancel)
    val title = stringResource(when {
        keepsWorkingFiles -> R.string.discard_clear_staging
        !singleFile -> R.string.ui_revert_all_changes_06b0eb3d
        else -> R.string.ui_revert_changes_5b36e77f
    })
    // M3 alert dialog (board "19 · Discard changes — dialog"): centered, icon over a centered title.
    BoxWithConstraints(modifier.fillMaxSize().systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        // Dimmed backdrop: tapping outside cancels.
        Box(Modifier.matchParentSize().clickable(indication = null,
            interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss).clearAndSetSemantics {})
        // One bounded list keeps every path and both actions reachable, even in landscape at large text.
        LazyColumn(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                .clip(RoundedCornerShape(Tide.radiusSheet)).background(c.containerHigh)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .testTag("discard-review"),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 20.dp),
        ) {
            item(key = "title") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(48.dp))
                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                            TideModalIcon(tint = if (keepsWorkingFiles) c.ahead else c.danger)
                        }
                        Box(
                            Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                                .semantics { contentDescription = cancel }.testTag("discard-review-close"),
                            contentAlignment = Alignment.Center,
                        ) { TideIcon(com.bontecou.syncmd.R.drawable.ic_m3_close, tint = c.inkMid, size = 20.dp) }
                    }
                    Text(title, Modifier.padding(top = 8.dp).testTag("discard-review-title"),
                        style = TideType.sans(24.sp).copy(lineHeight = 32.sp), color = c.ink,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
            item(key = "count") {
                Text(pluralStringResource(R.plurals.discard_review_files, paths.size, paths.size),
                    Modifier.padding(top = 10.dp).testTag("discard-review-count"),
                    style = TideType.mono(12.sp, FontWeight.SemiBold), color = if (keepsWorkingFiles) c.ahead else c.danger)
            }
            item(key = "scope") {
                Text(stringResource(when {
                    keepsWorkingFiles -> R.string.discard_review_unborn
                    singleFile -> R.string.ui_all_local_changes_to_this_file_will_be_per_0919f539
                    else -> R.string.discard_review_all
                }), Modifier.padding(top = 6.dp).testTag("discard-review-scope"), style = TideType.copy, color = c.inkMid)
            }
            if (abandonsOperation) item(key = "operation") {
                Text(stringResource(R.string.discard_review_operation), Modifier.padding(top = 8.dp).testTag("discard-review-operation"),
                    style = TideType.sans(14.sp, FontWeight.Medium), color = c.danger)
            }
            // Paths read as one white card of hairline-separated mono rows.
            itemsIndexed(paths, key = { _, path -> "path:$path" }) { index, path ->
                val r = 18.dp
                val shape = when {
                    paths.size == 1 -> RoundedCornerShape(r)
                    index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
                    index == paths.lastIndex -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.padding(top = if (index == 0) 16.dp else 0.dp).fillMaxWidth().clip(shape).background(c.container)
                    .padding(horizontal = 16.dp)) {
                    if (index > 0) TideHairline()
                    Text(path, Modifier.fillMaxWidth().padding(vertical = 11.dp).testTag("discard-review-path:$path"),
                        style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr), color = c.ink)
                }
            }
            if (paths.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.discard_review_empty), Modifier.padding(top = 16.dp), style = TideType.copy, color = c.inkMid)
            }
            if (!keepsWorkingFiles) item(key = "warning") {
                Text(stringResource(R.string.ui_this_cannot_be_undone_b545dd16), Modifier.padding(top = 12.dp),
                    style = TideType.meta, color = c.danger)
            }
            item(key = "actions") {
                Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    GitWorkPill(
                        if (keepsWorkingFiles) stringResource(R.string.discard_clear_staging) else title,
                        onClick = onConfirm,
                        modifier = Modifier.testTag("discard-review-confirm"),
                        style = if (keepsWorkingFiles) GitWorkPillStyle.PRIMARY else GitWorkPillStyle.DANGER,
                        enabled = paths.isNotEmpty(),
                        height = 56.dp,
                    )
                    Box(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(CircleShape)
                            .clickable(role = Role.Button, onClick = onDismiss).testTag("discard-review-cancel"),
                        contentAlignment = Alignment.Center,
                    ) { Text(cancel, style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.primary) }
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Discard · one file · light", heightDp = 640) @Composable
private fun DiscardOneLight() = SyncMdTheme(darkTheme = false) {
    Box(Modifier.background(Tide.colors.ink.copy(alpha = 0.3f))) {
        RevertReviewSheet(false, true, false, listOf("ideas/launch-plan.md"), {}, {})
    }
}

@Preview(name = "Discard · all · dark", heightDp = 720) @Composable
private fun DiscardAllDark() = SyncMdTheme(darkTheme = true) {
    RevertReviewSheet(false, false, true, listOf("notes/today.md", "ideas/launch-plan.md", "assets/cover.png"), {}, {})
}

@Preview(name = "Discard · clear staging", heightDp = 640) @Composable
private fun DiscardUnborn() = SyncMdTheme(darkTheme = false) {
    RevertReviewSheet(true, false, false, listOf("new.md"), {}, {})
}

@Preview(name = "Discard · empty", heightDp = 520) @Composable
private fun DiscardEmpty() = SyncMdTheme(darkTheme = false) {
    RevertReviewSheet(false, false, false, emptyList(), {}, {})
}
