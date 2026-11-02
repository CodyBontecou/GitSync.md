package com.bontecou.syncmd.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.SyncRail
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideType

/** Quantity selection stays independent for the two sides of a rebase plan. */
@Composable
fun RebaseCommitCounts(local: Int, upstream: Int) {
    val localText = pluralStringResource(R.plurals.rebase_local_commits, local, local)
    val upstreamText = pluralStringResource(R.plurals.rebase_upstream_commits, upstream, upstream)
    Text("$localText · $upstreamText", modifier = Modifier.testTag("rebase-counts"),
        style = TideType.mono(12.sp), color = Tide.colors.inkMid)
}

@Composable
fun RebaseAdditionalBlockedPaths(count: Int) {
    if (count > 0) Text(pluralStringResource(R.plurals.rebase_additional_blocked_paths, count, count),
        modifier = Modifier.testTag("rebase-more-paths"), style = TideType.mono(12.sp), color = Tide.colors.inkFaint)
}

// ─── Shared Tide pieces for the diff / conflict / rebase / merge screens ────
// Internal and prefixed `GitWork` so they never collide with other areas.

internal enum class GitWorkPillStyle { PRIMARY, OUTLINE, DANGER, DANGER_OUTLINE, AHEAD, BEHIND }

/**
 * Pill button with an explicit enabled state and a 48dp minimum target.
 * The caller's [modifier] (test tags, weights) lands on the clickable node.
 */
@Composable
internal fun GitWorkPill(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: GitWorkPillStyle = GitWorkPillStyle.OUTLINE,
    enabled: Boolean = true,
    loading: Boolean = false,
    fill: Boolean = true,
    height: Dp = 52.dp,
) {
    val c = Tide.colors
    val (bg, border, fg) = when (style) {
        GitWorkPillStyle.PRIMARY -> Triple(c.ink, Color.Transparent, c.paper)
        GitWorkPillStyle.OUTLINE -> Triple(Color.Transparent, c.lineSoft, c.ink)
        GitWorkPillStyle.DANGER -> Triple(c.danger, Color.Transparent, Color.White)
        GitWorkPillStyle.DANGER_OUTLINE -> Triple(Color.Transparent, c.danger.copy(alpha = 0.35f), c.danger)
        GitWorkPillStyle.AHEAD -> Triple(c.ahead.copy(alpha = 0.10f), c.ahead.copy(alpha = 0.30f), c.ahead)
        GitWorkPillStyle.BEHIND -> Triple(c.behind.copy(alpha = 0.10f), c.behind.copy(alpha = 0.30f), c.behind)
    }
    Box(
        modifier
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = maxOf(height, 48.dp))
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .background(bg, CircleShape)
            .border(1.dp, border, CircleShape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 18.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = fg, textAlign = TextAlign.Center)
    }
}

/** Light pill on the dark hero — the hero's one right action. */
@Composable
internal fun GitWorkHeroButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
) {
    val c = Tide.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .background(c.heroText.copy(alpha = if (enabled) 1f else 0.35f), CircleShape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = c.heroBg, strokeWidth = 2.dp)
        else Text(title, style = TideType.button, color = c.heroBg, textAlign = TextAlign.Center)
    }
}

/** Outline pill on the dark hero (secondary or escape actions). */
@Composable
internal fun GitWorkHeroSecondary(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    danger: Boolean = false,
) {
    val c = Tide.colors
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .clip(CircleShape)
            .border(1.dp, if (danger) c.heroDanger.copy(alpha = 0.5f) else c.heroLine, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (danger) c.heroDanger else c.heroText,
            textAlign = TextAlign.Center)
    }
}

/** Quiet text-only action with a 48dp target (Cancel, Keep editing). */
@Composable
internal fun GitWorkGhost(title: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(title, style = TideType.sans(15.sp, FontWeight.Medium), color = Tide.colors.ink, textAlign = TextAlign.Center) }
}

/** Sheet title with a caller-owned modifier (test tags), styled like TideDialog's. */
@Composable
internal fun GitWorkSheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.semantics { heading() }, style = TideType.display(26.sp), color = Tide.colors.ink)
}

internal enum class GitWorkTone { NEUTRAL, AHEAD, BEHIND, SYNCED, DANGER }

@Composable
internal fun GitWorkTone.heroTint(): Color = when (this) {
    GitWorkTone.NEUTRAL -> Tide.colors.heroMeta
    GitWorkTone.AHEAD -> Tide.colors.heroAhead
    GitWorkTone.BEHIND -> Tide.colors.heroBehind
    GitWorkTone.SYNCED -> Tide.colors.heroSynced
    GitWorkTone.DANGER -> Tide.colors.heroDanger
}

@Composable
internal fun GitWorkTone.tint(): Color = when (this) {
    GitWorkTone.NEUTRAL -> Tide.colors.ink
    GitWorkTone.AHEAD -> Tide.colors.ahead
    GitWorkTone.BEHIND -> Tide.colors.behind
    GitWorkTone.SYNCED -> Tide.colors.synced
    GitWorkTone.DANGER -> Tide.colors.danger
}

/** Rail state for a reviewed ahead/behind pair (diverged / behind / ahead / in sync). */
internal fun gitWorkRail(ahead: Int, behind: Int): TideSyncState = TideSyncState.of(ahead, behind)

/**
 * The state hero used by rebase and merge: mono caps kicker, plain-language
 * headline, optional SyncRail with "↑N yours / ↓N from remote", then actions.
 */
@Composable
internal fun GitWorkStateHero(
    kicker: String,
    headline: String,
    modifier: Modifier = Modifier,
    tone: GitWorkTone = GitWorkTone.NEUTRAL,
    rail: TideSyncState? = null,
    yours: Int? = null,
    theirs: Int? = null,
    busy: Boolean = false,
    kickerModifier: Modifier = Modifier,
    detail: (@Composable ColumnScope.() -> Unit)? = null,
    actions: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Tide.colors
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    TideHero(modifier) {
        Text(kicker.uppercase(locale), kickerModifier, style = TideType.mono(11.sp, FontWeight.SemiBold).copy(letterSpacing = 0.06.em),
            color = tone.heroTint())
        Spacer(Modifier.size(8.dp))
        Text(headline, style = TideType.display(24.sp), color = c.heroText, modifier = Modifier.semantics { heading() })
        if (detail != null) Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = detail)
        if (rail != null) {
            Spacer(Modifier.size(18.dp))
            SyncRail(rail, dot = 10.dp, onHero = true)
            if (yours != null || theirs != null) {
                Row(Modifier.fillMaxWidth().padding(top = 10.dp)) {
                    if (yours != null) Text(stringResource(R.string.tide_merge_yours_count, yours), style = TideType.mono(11.sp), color = c.heroMeta)
                    Spacer(Modifier.weight(1f))
                    if (theirs != null) Text(stringResource(R.string.tide_merge_theirs_count, theirs), style = TideType.mono(11.sp), color = c.heroMeta)
                }
            }
        }
        if (busy) {
            Spacer(Modifier.size(16.dp))
            TidePulse(color = c.heroText)
        }
        if (actions != null) {
            Column(Modifier.fillMaxWidth().padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = actions)
        }
    }
}

/**
 * Tinted note on paper. [textModifier] goes on the Text itself so tests can
 * read its text layout through a test tag.
 */
@Composable
internal fun GitWorkNote(
    text: String,
    modifier: Modifier = Modifier,
    tone: GitWorkTone = GitWorkTone.NEUTRAL,
    title: String? = null,
    textModifier: Modifier = Modifier,
) {
    val c = Tide.colors
    val tint = tone.tint()
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier
            .fillMaxWidth()
            .background(if (tone == GitWorkTone.NEUTRAL) c.card else tint.copy(alpha = 0.10f), shape)
            .border(1.dp, if (tone == GitWorkTone.NEUTRAL) c.line else tint.copy(alpha = 0.25f), shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (title != null) Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (tone == GitWorkTone.NEUTRAL) c.ink else tint)
        Text(text, textModifier, style = TideType.sans(14.sp, if (title == null) FontWeight.Medium else FontWeight.Normal),
            color = if (title == null && tone != GitWorkTone.NEUTRAL) tint else c.inkMid)
    }
}

/** Small dot that marks a side: filled orange for yours, blue ring for theirs. */
@Composable
internal fun GitWorkSideDot(yours: Boolean, modifier: Modifier = Modifier) {
    val c = Tide.colors
    if (yours) Box(modifier.size(9.dp).background(c.ahead, CircleShape))
    else Box(modifier.size(9.dp).border(2.dp, c.behind, CircleShape))
}

/** Quiet "working" row on paper: three pulsing dots and a caption. */
@Composable
internal fun GitWorkWorking(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TidePulse(color = Tide.colors.inkMid)
        Text(text, style = TideType.sans(14.sp, FontWeight.Medium), color = Tide.colors.inkMid)
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun PiecesPreviewBody() {
    Column(Modifier.background(Tide.colors.paper).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GitWorkStateHero(
            kicker = "Both sides moved", headline = "The remote moved on while you were writing.",
            tone = GitWorkTone.DANGER, rail = TideSyncState.Diverged(2, 3), yours = 2, theirs = 3,
            detail = { RebaseCommitCounts(2, 3) },
            actions = { GitWorkHeroButton("Rebase onto reviewed upstream", {}) },
        )
        GitWorkNote("Rebase completed successfully", tone = GitWorkTone.SYNCED)
        GitWorkWorking("Working…")
        GitWorkPill("Use local commit", {}, style = GitWorkPillStyle.AHEAD)
        GitWorkPill("Use upstream", {}, style = GitWorkPillStyle.BEHIND)
        GitWorkPill("Disabled", {}, enabled = false)
        RebaseAdditionalBlockedPaths(3)
    }
}

@Preview(name = "Git work pieces · light") @Composable
private fun GitWorkPiecesLight() = SyncMdTheme(darkTheme = false) { PiecesPreviewBody() }

@Preview(name = "Git work pieces · dark") @Composable
private fun GitWorkPiecesDark() = SyncMdTheme(darkTheme = true) { PiecesPreviewBody() }
