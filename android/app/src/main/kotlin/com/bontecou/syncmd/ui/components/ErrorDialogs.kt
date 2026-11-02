package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.semantics.clearAndSetSemantics
import com.bontecou.syncmd.ui.theme.TideChevron
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideDialog
import com.bontecou.syncmd.ui.theme.TideModalIcon
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideSectionLabel
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideSwitchColors

// ─── Vault-operations Tide pieces ───────────────────────────────────────────
// Shared by the per-vault management, LFS, automation and error surfaces.
// Internal and VaultOps-prefixed so names never collide with other areas.

/**
 * Tide bottom-sheet dialog whose title is a real composable, so callers keep
 * test tags and heading semantics on it. Body text defaults to inkMid copy.
 */
@Composable
internal fun VaultOpsSheet(
    onDismissRequest: () -> Unit,
    title: String,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    titleModifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    properties: DialogProperties = DialogProperties(),
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    TideDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = confirmButton,
        modifier = modifier.semantics { paneTitle = title },
        dismissButton = dismissButton,
        icon = icon,
        properties = properties,
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(title, style = TideType.display(26.sp), color = Tide.colors.ink,
                    modifier = titleModifier.semantics { heading() })
                content?.invoke(this)
            }
        },
    )
}

/** Danger glyph for destructive and failure sheets. */
@Composable
internal fun VaultOpsDangerIcon() = TideModalIcon()

/** Raw git / system detail: mono text in a rounded well, selectable, LTR. */
@Composable
internal fun VaultOpsDetail(text: String, modifier: Modifier = Modifier, textModifier: Modifier = Modifier) {
    SelectionContainer(modifier.fillMaxWidth()) {
        Text(
            text,
            style = TideType.mono(12.5.sp).copy(textDirection = TextDirection.Ltr, lineHeight = 18.sp),
            color = Tide.colors.inkMid,
            modifier = textModifier
                .fillMaxWidth()
                .background(Tide.colors.well, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 12.dp),
        )
    }
}

/** Mono caps caption over a mono git fact (path, remote, endpoint). */
@Composable
internal fun VaultOpsFact(label: String?, value: String, modifier: Modifier = Modifier, valueModifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (label != null) TideSectionLabel(label)
        SelectionContainer {
            Text(value, style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = Tide.colors.ink, modifier = valueModifier)
        }
    }
}

/** Sentence-case group heading above a card (keeps resource text exact). */
@Composable
internal fun VaultOpsHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = TideType.sans(17.sp, FontWeight.SemiBold),
        color = Tide.colors.ink,
        modifier = modifier.padding(start = 4.dp, top = 6.dp).semantics { heading() },
    )
}

/** Quiet secondary copy under a heading or inside a card. */
@Composable
internal fun VaultOpsNote(text: String, modifier: Modifier = Modifier, color: Color = Tide.colors.inkMid) {
    Text(text, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = color, modifier = modifier)
}

internal enum class VaultOpsButtonStyle { PRIMARY, DANGER }

/** Full-width pill: ink for the one right action, danger red for destructive. */
@Composable
internal fun VaultOpsButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    style: VaultOpsButtonStyle = VaultOpsButtonStyle.PRIMARY,
) {
    val c = Tide.colors
    val fill = if (style == VaultOpsButtonStyle.DANGER) c.danger else c.ink
    val tint = if (style == VaultOpsButtonStyle.DANGER) Color.White else c.paper
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(fill.copy(alpha = if (enabled) 1f else 0.3f), CircleShape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        else Text(title, style = TideType.button, color = tint, textAlign = TextAlign.Center)
    }
}

/** Outline pill for a secondary action. */
@Composable
internal fun VaultOpsOutlineButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, danger: Boolean = false) {
    val c = Tide.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .border(1.dp, if (danger) c.danger.copy(alpha = 0.3f) else c.lineSoft, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (danger) c.danger else c.ink, textAlign = TextAlign.Center)
    }
}

/** Text action with a 48dp target (Cancel, Dismiss). */
@Composable
internal fun VaultOpsTextButton(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, color: Color = Tide.colors.ink) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.Medium), color = color, textAlign = TextAlign.Center)
    }
}

/** Full-width labelled switch; the whole row toggles (checkbox semantics preserved). */
@Composable
internal fun VaultOpsToggleRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    labelModifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(label, style = TideType.body, color = Tide.colors.ink.copy(alpha = if (enabled) 1f else 0.45f), modifier = labelModifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null, enabled = enabled, colors = tideSwitchColors())
    }
}

/** Selection circle for multi-select (check) or single-choice (radio) rows. */
@Composable
internal fun VaultOpsSelectMark(selected: Boolean, radio: Boolean = false, enabled: Boolean = true) {
    val c = Tide.colors
    Box(
        Modifier
            .size(24.dp)
            .alpha(if (enabled) 1f else 0.45f)
            .then(if (selected && !radio) Modifier.background(c.ink, CircleShape) else Modifier.border(1.5.dp, if (selected) c.ink else c.lineSoft, CircleShape)),
        contentAlignment = Alignment.Center,
    ) {
        if (selected && radio) Box(Modifier.size(12.dp).background(c.ink, CircleShape))
        if (selected && !radio) Text("✓", style = TideType.sans(13.sp, FontWeight.Bold), color = c.paper)
    }
}

/**
 * Settings-style row inside a card. Unlike TideRow the chevron carries no
 * semantics, so a tagged row's text is exactly its title.
 */
@Composable
internal fun VaultOpsRow(
    title: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
    chevron: Boolean = false,
    value: String? = null,
    leading: (@Composable () -> Unit)? = null,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Text(title, style = TideType.body, color = if (destructive) c.danger else c.ink, modifier = Modifier.weight(1f))
        if (value != null) Text(value, style = TideType.mono(13.sp).copy(textDirection = TextDirection.Ltr), color = c.inkMid, maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        if (chevron) Box(Modifier.clearAndSetSemantics { }) { TideChevron() }
    }
}

/**
 * One slice of a card split across lazy-list items: rounded top on the first,
 * rounded bottom on the last, a hairline between neighbours.
 */
@Composable
internal fun Modifier.vaultOpsSlice(index: Int, count: Int): Modifier {
    val r = Tide.radiusCard
    val shape = RoundedCornerShape(
        topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
        bottomStart = if (index == count - 1) r else 0.dp, bottomEnd = if (index == count - 1) r else 0.dp,
    )
    val line = Tide.colors.line
    return this
        .fillMaxWidth()
        .clip(shape)
        .background(Tide.colors.card, shape)
        .then(if (index > 0) Modifier.drawBehind {
            drawLine(line, androidx.compose.ui.geometry.Offset(16.dp.toPx(), 0f), androidx.compose.ui.geometry.Offset(size.width - 16.dp.toPx(), 0f), 1.dp.toPx())
        } else Modifier)
}

/**
 * Inline notice in Tide banner colors. The text node carries [textModifier]
 * (tags, live region) so callers can address the exact copy.
 */
@Composable
internal fun VaultOpsBanner(
    text: String,
    textModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
    danger: Boolean = false,
    tone: TideBannerTone = if (danger) TideBannerTone.DANGER else TideBannerTone.NEUTRAL,
    title: String? = null,
) {
    val c = Tide.colors
    val tint = when (tone) {
        TideBannerTone.NEUTRAL -> c.ink
        TideBannerTone.BEHIND -> c.behind
        TideBannerTone.AHEAD -> c.ahead
        TideBannerTone.DANGER -> c.danger
        TideBannerTone.SYNCED -> c.synced
    }
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(if (tone == TideBannerTone.NEUTRAL) c.card else tint.copy(alpha = 0.10f), shape)
            .border(1.dp, if (tone == TideBannerTone.NEUTRAL) c.line else tint.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (title != null) Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (tone == TideBannerTone.NEUTRAL) c.ink else tint)
        Text(
            text,
            style = TideType.sans(14.sp, if (title == null && tone != TideBannerTone.NEUTRAL) FontWeight.Medium else FontWeight.Normal).copy(lineHeight = 20.sp),
            color = if (title == null && tone != TideBannerTone.NEUTRAL) tint else c.inkMid,
            modifier = textModifier,
        )
    }
}

/** Busy line: pulsing dots and what is happening, or a thin ink bar. */
@Composable
internal fun VaultOpsProgress(label: String? = null, modifier: Modifier = Modifier) {
    val c = Tide.colors
    if (label == null) {
        LinearProgressIndicator(modifier.fillMaxWidth().clip(CircleShape), color = c.ink, trackColor = c.lineSoft)
    } else {
        Row(modifier.fillMaxWidth().heightIn(min = 32.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TidePulse()
            Text(label, style = TideType.sans(15.sp, FontWeight.Medium), color = c.inkMid)
        }
    }
}

// ─── Public dialogs ─────────────────────────────────────────────────────────

/** Failure sheet: danger glyph, plain title, message, raw detail in a mono well. */
@Composable
fun ErrorDialog(
    title: String = "Error",
    message: String,
    details: String? = null,
    onDismiss: () -> Unit,
    onRetry: (() -> Unit)? = null
) {
    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = title,
        icon = { VaultOpsDangerIcon() },
        confirmButton = {
            if (onRetry != null) VaultOpsButton(stringResource(R.string.ui_retry_942087cc), onClick = { onRetry(); onDismiss() })
            else VaultOpsButton(stringResource(R.string.ui_ok_565339bc), onClick = onDismiss)
        },
        dismissButton = if (onRetry != null) ({ VaultOpsTextButton(stringResource(R.string.ui_dismiss_48845bff), onDismiss, Modifier.fillMaxWidth()) }) else null,
    ) {
        Text(message)
        if (!details.isNullOrBlank()) {
            Column(Modifier.heightIn(max = 240.dp).verticalScroll(rememberScrollState())) { VaultOpsDetail(details) }
        }
    }
}

/** Warning sheet for potentially destructive actions. */
@Composable
fun WarningDialog(
    title: String = "Warning",
    message: String,
    confirmText: String = "Continue",
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = title,
        icon = { VaultOpsDangerIcon() },
        confirmButton = { VaultOpsButton(confirmText, onClick = { onConfirm(); onDismiss() }, style = VaultOpsButtonStyle.DANGER) },
        dismissButton = { VaultOpsTextButton(stringResource(R.string.publication_cancel), onDismiss, Modifier.fillMaxWidth()) },
    ) { Text(message) }
}

/** Success sheet: synced-green check, title and message. */
@Composable
fun SuccessDialog(
    title: String = "Success",
    message: String,
    onDismiss: () -> Unit
) {
    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = title,
        icon = { TideModalIcon(glyph = "✓", tint = Tide.colors.synced) },
        confirmButton = { VaultOpsButton(stringResource(R.string.ui_ok_565339bc), onClick = onDismiss) },
    ) { Text(message) }
}

/** Informational sheet. */
@Composable
fun InfoDialog(
    title: String = "Information",
    message: String,
    onDismiss: () -> Unit
) {
    VaultOpsSheet(
        onDismissRequest = onDismiss,
        title = title,
        icon = { TideModalIcon(glyph = "i", tint = Tide.colors.behind) },
        confirmButton = { VaultOpsButton(stringResource(R.string.ui_ok_565339bc), onClick = onDismiss) },
    ) { Text(message) }
}

/**
 * Helpful error messages for common git scenarios.
 */
object ErrorMessages {
    fun repositoryNotFound(path: String): String =
        "Repository not found at: $path\n\nCheck the path and try again."

    fun dirtyWorkTree(): String =
        "Cannot perform this operation with uncommitted changes.\n\nCommit or stash your changes first."

    fun mergeConflicts(count: Int): String =
        "Merge has $count unresolved conflicts.\n\nResolve them before completing the merge."

    fun networkError(): String =
        "Network error. Check your connection and try again."

    fun invalidGitRepository(): String =
        "This doesn't appear to be a valid Git repository.\n\nEnsure the path points to a .git folder or a working directory."

    fun commitFailed(reason: String): String =
        "Commit failed: $reason"

    fun pushFailed(reason: String): String =
        "Push failed: $reason"

    fun pullFailed(reason: String): String =
        "Pull failed: $reason"

    fun branchAlreadyExists(name: String): String =
        "Branch '$name' already exists.\n\nChoose a different name."

    fun cannotDeleteCurrentBranch(): String =
        "Cannot delete the current branch.\n\nSwitch to another branch first."

    fun invalidBranchName(): String =
        "Invalid branch name.\n\nUse alphanumeric characters, hyphens, and underscores."
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Error · retry · light")
@Composable
private fun ErrorPreviewLight() = SyncMdTheme(darkTheme = false) {
    ErrorDialog("Push was rejected", "The remote has commits you don't have yet. Pull first, then push again.",
        "! [rejected] main -> main (non-fast-forward)\nerror: failed to push some refs", onDismiss = {}, onRetry = {})
}

@Preview(name = "Error · dark")
@Composable
private fun ErrorPreviewDark() = SyncMdTheme(darkTheme = true) {
    ErrorDialog("Push was rejected", "The remote has commits you don't have yet.", "fatal: unable to access", onDismiss = {})
}

@Preview(name = "Warning · light")
@Composable
private fun WarningPreview() = SyncMdTheme(darkTheme = false) {
    WarningDialog("Discard changes?", "3 files will go back to the last commit.", "Discard", onConfirm = {}, onDismiss = {})
}

@Preview(name = "Success · dark")
@Composable
private fun SuccessPreview() = SyncMdTheme(darkTheme = true) { SuccessDialog("Pushed", "3 commits are on GitHub.", onDismiss = {}) }

@Preview(name = "Info · light")
@Composable
private fun InfoPreview() = SyncMdTheme(darkTheme = false) { InfoDialog("About LFS", "Large files live outside Git history.", onDismiss = {}) }
