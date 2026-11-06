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
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.DiscardEffect
import com.bontecou.syncmd.services.git.DiscardReview
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3CheckboxRow
import com.bontecou.syncmd.ui.theme.M3Divider
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.SyncMdTheme

/**
 * M3 alert dialog "Discard 3 changes?" (board "19 · Discard changes — dialog"): a delete icon over
 * the centered title, what the files go back to, every reviewed path with what discarding does to
 * it ("delete new file", "−8 lines", "restore"), "Stash a copy first, just in case", then Keep them
 * and a red Discard N.
 *
 * The displayed immutable review is the authority passed to confirmation, including every full
 * path. This overload has no stash option; use the one taking `onConfirmDiscard` to offer it.
 */
@Composable
fun RevertConfirmDialog(review: DiscardReview, onConfirm: () -> Unit, onDismiss: () -> Unit) =
    RevertConfirmDialogHost(review, stashSupported = false, onConfirm = { onConfirm() }, onDismiss = onDismiss)

/**
 * The discard dialog with "Stash a copy first, just in case" (checked by default when the review
 * allows a stash). [onConfirmDiscard] receives that choice; pass it on as
 * `DiffViewModel.confirmDiscard(review, stashFirst = it)`, which really stashes before discarding.
 */
@Composable
fun RevertConfirmDialog(review: DiscardReview, onConfirmDiscard: (stashFirst: Boolean) -> Unit, onDismiss: () -> Unit) =
    RevertConfirmDialogHost(review, stashSupported = true, onConfirm = onConfirmDiscard, onDismiss = onDismiss)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
private fun RevertConfirmDialogHost(review: DiscardReview, stashSupported: Boolean, onConfirm: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val canStash = stashSupported && review.canStash
    var stash by rememberSaveable(review) { mutableStateOf(canStash) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        RevertReviewSheet(
            keepsWorkingFiles = review.keepsWorkingFiles,
            singleFile = review.filePath != null,
            abandonsOperation = review.abandonsOperation,
            paths = review.paths,
            effects = review.effects,
            baseCommit = review.baseCommit,
            canStash = canStash,
            stash = stash,
            onStashChange = { stash = it },
            onConfirm = { onConfirm(stash && canStash) },
            onDismiss = onDismiss,
            modifier = Modifier.semantics { testTagsAsResourceId = true },
        )
    }
}

/** Stateless dialog body, shared by the dialog and its previews. */
@Composable
private fun RevertReviewSheet(
    keepsWorkingFiles: Boolean,
    singleFile: Boolean,
    abandonsOperation: Boolean,
    paths: List<String>,
    effects: Map<String, DiscardEffect>,
    baseCommit: String?,
    canStash: Boolean,
    stash: Boolean,
    onStashChange: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = M3.colors
    val title = when {
        keepsWorkingFiles -> stringResource(R.string.discard_clear_staging)
        else -> pluralStringResource(R.plurals.vault_m3_discard_title, paths.size, paths.size)
    }
    BoxWithConstraints(modifier.fillMaxSize().background(c.scrim).systemBarsPadding().padding(24.dp), contentAlignment = Alignment.Center) {
        // Scrim: tapping outside keeps the changes.
        Box(Modifier.matchParentSize().clickable(indication = null,
            interactionSource = remember { MutableInteractionSource() }, onClick = onDismiss).clearAndSetSemantics {})
        // One bounded list keeps every path and both actions reachable, even in landscape at large text.
        LazyColumn(
            Modifier.widthIn(min = 280.dp, max = 560.dp).fillMaxWidth().heightIn(max = maxHeight)
                .clip(RoundedCornerShape(28.dp)).background(c.cHigh)
                .clickable(indication = null, interactionSource = remember { MutableInteractionSource() }) {}
                .semantics { paneTitle = title }
                .testTag("discard-review"),
            contentPadding = PaddingValues(24.dp),
        ) {
            item(key = "title") {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    M3Icon(R.drawable.ic_m3_delete, tint = if (keepsWorkingFiles) c.tertiary else c.error)
                    Text(title, Modifier.padding(top = 16.dp).testTag("discard-review-title").semantics { heading() },
                        style = M3Type.dialogTitle, color = c.on, textAlign = TextAlign.Center)
                }
            }
            item(key = "scope") {
                val body = when {
                    keepsWorkingFiles -> buildAnnotatedString { append(stringResource(R.string.discard_review_unborn)) }
                    baseCommit != null -> {
                        val template = stringResource(R.string.vault_m3_discard_body, "\u0000")
                        buildAnnotatedString {
                            append(template.substringBefore('\u0000'))
                            withStyle(SpanStyle(fontFamily = M3Type.meta.fontFamily, color = c.on)) { append(baseCommit) }
                            append(template.substringAfter('\u0000'))
                        }
                    }
                    else -> buildAnnotatedString { append(stringResource(if (singleFile) R.string.ui_all_local_changes_to_this_file_will_be_per_0919f539 else R.string.discard_review_all)) }
                }
                Text(body, Modifier.padding(top = 16.dp).testTag("discard-review-scope"), style = M3Type.bodyMedium, color = c.onVar)
            }
            if (abandonsOperation) item(key = "operation") {
                Text(stringResource(R.string.discard_review_operation), Modifier.padding(top = 8.dp).testTag("discard-review-operation"),
                    style = M3Type.bodyMedium, color = c.error)
            }
            // Paths and their effects read as one inset card of mono rows.
            itemsIndexed(paths, key = { _, path -> "path:$path" }) { index, path ->
                val r = 12.dp
                val shape = when {
                    paths.size == 1 -> RoundedCornerShape(r)
                    index == 0 -> RoundedCornerShape(topStart = r, topEnd = r)
                    index == paths.lastIndex -> RoundedCornerShape(bottomStart = r, bottomEnd = r)
                    else -> RoundedCornerShape(0.dp)
                }
                Column(Modifier.padding(top = if (index == 0) 16.dp else 0.dp).fillMaxWidth().clip(shape).background(c.surface)) {
                    if (index > 0) M3Divider()
                    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(path, Modifier.weight(1f).padding(vertical = 10.dp).testTag("discard-review-path:$path"),
                            style = M3Type.meta.copy(textDirection = TextDirection.Ltr), color = c.on, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        effects[path]?.let { effect ->
                            Text(effectText(effect), Modifier.padding(start = 8.dp).testTag("discard-review-effect:$path"), style = M3Type.caption, color = c.onVar)
                        }
                    }
                }
            }
            if (paths.isEmpty()) item(key = "empty") {
                Text(stringResource(R.string.discard_review_empty), Modifier.padding(top = 16.dp), style = M3Type.bodyMedium, color = c.onVar)
            }
            if (canStash) item(key = "stash") {
                M3CheckboxRow(stringResource(R.string.vault_m3_stash_first), stash, onStashChange,
                    Modifier.padding(top = 12.dp).testTag("discard-review-stash"), contentPadding = PaddingValues(0.dp))
            }
            item(key = "actions") {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.heightIn(min = 48.dp).testTag("discard-review-cancel"), contentAlignment = Alignment.Center) {
                        M3Button(stringResource(R.string.vault_m3_keep_them), onDismiss, style = M3ButtonStyle.Text, size = M3ButtonSize.Medium)
                    }
                    Box(Modifier.heightIn(min = 48.dp).testTag("discard-review-confirm"), contentAlignment = Alignment.Center) {
                        M3Button(
                            if (keepsWorkingFiles) stringResource(R.string.discard_clear_staging)
                            else pluralStringResource(R.plurals.vault_m3_discard_n, paths.size, paths.size),
                            onConfirm,
                            style = if (keepsWorkingFiles) M3ButtonStyle.Filled else M3ButtonStyle.Danger,
                            size = M3ButtonSize.Medium,
                            enabled = paths.isNotEmpty(),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun effectText(effect: DiscardEffect): String = when (effect.kind) {
    DiscardEffect.Kind.DELETE_NEW_FILE -> stringResource(R.string.vault_m3_effect_delete)
    DiscardEffect.Kind.RESTORE -> stringResource(R.string.vault_m3_effect_restore)
    DiscardEffect.Kind.UNSTAGE -> stringResource(R.string.vault_m3_effect_unstage)
    DiscardEffect.Kind.REVERT -> if (effect.linesLost > 0) pluralStringResource(R.plurals.vault_m3_effect_lines, effect.linesLost, effect.linesLost)
        else stringResource(R.string.vault_m3_effect_revert)
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewEffects = mapOf(
    "daily/2026-11-01.md" to DiscardEffect(DiscardEffect.Kind.DELETE_NEW_FILE, 42),
    "ideas/voice-memo-pipeline.md" to DiscardEffect(DiscardEffect.Kind.REVERT, 8),
    "old-inbox.md" to DiscardEffect(DiscardEffect.Kind.RESTORE),
)

@Preview(name = "Discard · all · light", heightDp = 720) @Composable
private fun DiscardAllLight() = SyncMdTheme(darkTheme = false) {
    RevertReviewSheet(false, false, false, previewEffects.keys.toList(), previewEffects, "a41c9e2", true, true, {}, {}, {})
}

@Preview(name = "Discard · all · dark", heightDp = 720) @Composable
private fun DiscardAllDark() = SyncMdTheme(darkTheme = true) {
    RevertReviewSheet(false, false, true, previewEffects.keys.toList(), previewEffects, "a41c9e2", false, false, {}, {}, {})
}

@Preview(name = "Discard · clear staging", heightDp = 640) @Composable
private fun DiscardUnborn() = SyncMdTheme(darkTheme = false) {
    RevertReviewSheet(true, false, false, listOf("new.md"), mapOf("new.md" to DiscardEffect(DiscardEffect.Kind.UNSTAGE)), null, false, false, {}, {}, {})
}
