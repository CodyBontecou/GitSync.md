package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.ui.util.CountedPhrase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.screens.HomeVault
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Colors
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.colors
import com.bontecou.syncmd.ui.util.AdaptiveInfo

// ─── Palette and pane chrome ────────────────────────────────────────────────

/** The boards' surface-container-lowest, behind the detail pane (white / near-black). */
val M3Colors.cLowest: Color get() = if (tide.isDark) Color(0xFF0E0E10) else Color.White

/** Pane radius on the Tablet and Fold boards. */
internal val PaneRadius = 28.dp
internal val PaneGap = 12.dp

/** The list panes (vaults, changes): rounded cLow cards. */
@Composable
internal fun Modifier.listPane(): Modifier = clip(RoundedCornerShape(PaneRadius)).background(M3.colors.cLow)

/** The detail pane: a rounded cLowest card with an outlineVar edge. */
@Composable
internal fun Modifier.detailPane(radius: Dp = PaneRadius): Modifier {
    val shape = RoundedCornerShape(radius)
    return clip(shape).background(M3.colors.cLowest, shape).border(1.dp, M3.colors.outlineVar, shape)
}

/** "gitsync" with ".md" in primary, in the given style. */
@Composable
internal fun Wordmark(style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val c = M3.colors
    Text(
        androidx.compose.ui.text.buildAnnotatedString {
            append("gitsync")
            pushStyle(androidx.compose.ui.text.SpanStyle(color = c.primary)); append(".md"); pop()
        },
        modifier.semantics { heading() }, style = style, color = c.on,
    )
}

/**
 * The detail pane's header: title over a mono subtitle on the left, [actions] on the right,
 * 64dp high with an outlineVar rule under it.
 */
@Composable
internal fun DetailHeader(title: String, subtitle: String, modifier: Modifier = Modifier, minHeight: Dp = 64.dp,
    titleSize: Int = 18, actions: @Composable RowScope.() -> Unit = {}) {
    val c = M3.colors
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight).padding(start = 20.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = M3Type.body.copy(fontSize = titleSize.sp, lineHeight = (titleSize + 6).sp, fontWeight = FontWeight.Medium),
                    color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pane-title"))
                Text(subtitle, style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag("pane-subtitle"))
            }
            actions()
        }
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.outlineVar))
    }
}

/** The detail pane's footer: an outlineVar rule, then the actions right-aligned, 12/16 padding. */
@Composable
internal fun DetailFooter(content: @Composable RowScope.() -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().heightIn(min = 1.dp).background(c.outlineVar))
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), content = content)
    }
}

/**
 * The compact segmented control in pane headers (36dp, 18dp corners, outline separators; the
 * selected segment on sc in 600), sized to its labels rather than the pane.
 */
@Composable
internal fun CompactSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(18.dp)
    Row(modifier.height(IntrinsicSize.Min).heightIn(min = 36.dp).clip(shape).border(1.dp, c.outline, shape).testTag("pane-view")) {
        options.forEachIndexed { index, label ->
            val on = index == selected
            if (index > 0) Box(Modifier.width(1.dp).heightIn(min = 36.dp).fillMaxHeight().background(c.outline))
            Box(
                Modifier.heightIn(min = 36.dp).then(if (on) Modifier.background(c.sc) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(index) }.semantics { this.selected = on }
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = M3Type.bodySmall.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (on) c.onSc else c.on, maxLines = 1)
            }
        }
    }
}

/** A letter tile for a change (A ok, M tertiary, D / ! error), [size] square with 10dp corners. */
@Composable
internal fun ChangeLetter(letter: String, tone: M3Tone, size: Dp = 32.dp, radius: Dp = 10.dp, fontSize: Int = 13) {
    val (bg, fg) = tone.colors()
    Box(Modifier.size(size).background(bg, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
        Text(letter, style = M3Type.metaStrong.copy(fontSize = fontSize.sp), color = fg)
    }
}

/** "+8 −3" in add/del ink, mono 12/600; "conflict" in delInk. */
@Composable
internal fun ChangeDelta(added: Int?, removed: Int?, conflict: Boolean = false, fontSize: Int = 12) {
    val c = M3.colors
    val style = M3Type.metaStrong.copy(fontSize = fontSize.sp, textDirection = TextDirection.Ltr)
    if (conflict) { Text(stringResource(R.string.adaptive_conflict), style = style, color = c.delInk); return }
    Text(androidx.compose.ui.text.buildAnnotatedString {
        if (added != null && added > 0) { pushStyle(androidx.compose.ui.text.SpanStyle(color = c.addInk)); append("+$added"); pop() }
        if (added != null && added > 0 && removed != null && removed > 0) append(" ")
        if (removed != null && removed > 0) { pushStyle(androidx.compose.ui.text.SpanStyle(color = c.delInk)); append("−$removed"); pop() }
    }, style = style)
}

/** Empty detail: shown beside the change list until a file is picked. */
@Composable
fun DetailPlaceholder(modifier: Modifier = Modifier) {
    val c = M3.colors
    Column(
        modifier.fillMaxSize().detailPane().padding(32.dp).testTag("vault-detail-empty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.size(56.dp).background(c.pc, RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
            M3Icon(R.drawable.ic_m3_changes, tint = c.onPc)
        }
        Text(stringResource(R.string.m3_pick_a_file), Modifier.padding(top = 16.dp), style = M3Type.body, color = c.onVar, textAlign = TextAlign.Center)
    }
}

// ─── Hinge-aware pane row ───────────────────────────────────────────────────

/**
 * The list pane and the detail pane, 12dp apart. With a vertical hinge or fold (board
 * FoldVault) the list ends just before it and the detail starts just after it; otherwise the
 * list is [listWidth] wide (never more than half the row).
 */
@Composable
internal fun PaneRow(info: AdaptiveInfo, listWidth: Dp, modifier: Modifier = Modifier, list: @Composable () -> Unit, detail: @Composable () -> Unit) {
    val density = LocalDensity.current
    var origin by remember { mutableStateOf(Offset.Zero) }
    var width by remember { mutableStateOf(0) }
    val split = info.verticalSplit
    Row(modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInWindow(); width = it.size.width }) {
        val available = with(density) { width.toDp() }
        val half = PaneGap / 2
        val hingeStart = split?.let { with(density) { (it.left - origin.x).toDp() } }
        if (split != null && hingeStart != null && hingeStart > 120.dp && hingeStart < available - 120.dp) {
            val hinge = with(density) { (split.right - split.left).toDp() }
            Box(Modifier.width(hingeStart - half).fillMaxHeight()) { list() }
            Spacer(Modifier.width(hinge + PaneGap))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        } else {
            val first = if (available > 0.dp) minOf(listWidth, available / 2) else listWidth
            Box(Modifier.width(first).fillMaxHeight()) { list() }
            Spacer(Modifier.width(PaneGap))
            Box(Modifier.weight(1f).fillMaxHeight()) { detail() }
        }
    }
}

// ─── Vault list (board Tablet) ──────────────────────────────────────────────

/** One row of the tablet vault list. */
data class VaultListItem(val path: String, val name: String, val meta: String, val badge: String, val tone: M3Tone)

/** Builds the list rows from the shared per-vault reads. */
@Composable
internal fun vaultListItems(vaults: List<HomeVault>): List<VaultListItem> {
    val now = com.bontecou.syncmd.ui.components.rememberMinuteClock()
    return vaults.map { v ->
        val (badge, tone) = compactBadge(v)
        VaultListItem(v.repo.path, v.name, compactMeta(v, now), badge, tone)
    }
}

/**
 * The vault list pane: the wordmark, "1 needs you · 4 in sync", and one row per vault with its
 * branch, push count or last sync, and a badge. The open vault sits on sc.
 */
@Composable
fun VaultListPane(
    items: List<VaultListItem>,
    needYou: Int,
    inSync: Int,
    selectedPath: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = M3.colors
    Column(modifier.width(260.dp).fillMaxHeight().listPane().padding(horizontal = 8.dp, vertical = 16.dp).testTag("vault-list-pane")) {
        Wordmark(M3Type.wordmark, Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp))
        Text(
            listOf(pluralStringResource(R.plurals.adaptive_vaults_need_you, needYou, needYou), stringResource(R.string.m3home_meta_in_sync, inSync)).joinToString(" · "),
            Modifier.padding(start = 12.dp, end = 12.dp, top = 2.dp, bottom = 12.dp), style = M3Type.meta, color = c.onVar,
        )
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            items.forEach { item ->
                val on = item.path == selectedPath
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
                        .then(if (on) Modifier.background(c.sc) else Modifier)
                        .clickable(role = Role.Tab) { if (!on) onSelect(item.path) }
                        .semantics { selected = on }
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .testTag("vault-list-${item.path}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(item.name, style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                            color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.meta, style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    val (bg, fg) = item.tone.colors()
                    Text(item.badge, Modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        style = M3Type.metaStrong, color = fg, maxLines = 1)
                }
            }
        }
    }
}

// ─── Changes pane ───────────────────────────────────────────────────────────

/** One changed file in the Changes pane. */
data class ChangeRowUi(val path: String, val letter: String, val tone: M3Tone, val added: Int?, val removed: Int?, val conflict: Boolean)

/**
 * The Changes pane (boards Tablet / Fold*): "main · owner/repo", the vault name, the big count
 * with "changes on this tablet", the remote line, the changed files, and the primary action
 * ("Commit & push ↑3") pinned at the bottom under an optional merge banner. [body] replaces the
 * file list while a sync runs or after a rejected push.
 */
@Composable
fun ChangesPaneContent(
    eyebrow: String,
    name: String,
    count: Int,
    countLabel: CountedPhrase,
    remote: String,
    rows: List<ChangeRowUi>,
    selected: String?,
    onOpen: (ChangeRowUi) -> Unit,
    banner: String?,
    modifier: Modifier = Modifier,
    notices: @Composable ColumnScope.() -> Unit = {},
    body: (@Composable ColumnScope.() -> Unit)? = null,
    footer: @Composable ColumnScope.() -> Unit,
) {
    val c = M3.colors
    Column(modifier.fillMaxSize().listPane().testTag("changes-pane")) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Text(eyebrow, style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(name, Modifier.padding(top = 2.dp).semantics { heading() }, style = M3Type.title, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(countLabel.value, style = M3Type.displayMedium.copy(fontSize = 45.sp, lineHeight = 50.sp, letterSpacing = 0.sp),
                    color = if (count > 0) c.ahead else c.onVar, modifier = Modifier.testTag("changes-count"))
                Text(countLabel.label, Modifier.padding(bottom = 8.dp), style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.on)
            }
            Text(remote, Modifier.padding(top = 2.dp), style = M3Type.caption, color = c.onVar, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        notices()
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            if (body != null) body()
            else if (rows.isEmpty()) Text(stringResource(R.string.adaptive_no_changes), Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = M3Type.bodyMedium, color = c.onVar)
            else rows.forEach { row -> ChangeRow(row, row.path == selected) { onOpen(row) } }
        }
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (banner != null) Text(banner,
                Modifier.padding(start = 4.dp, end = 4.dp, bottom = 4.dp).fillMaxWidth().background(c.tc, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp).testTag("changes-merge-banner"),
                style = M3Type.bodySmall, color = c.onTc)
            footer()
        }
    }
}

@Composable
private fun ChangeRow(row: ChangeRowUi, on: Boolean, onClick: () -> Unit) {
    val c = M3.colors
    Row(
        Modifier.padding(horizontal = 8.dp).fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp))
            .then(if (on) Modifier.background(c.sc) else Modifier)
            .clickable(role = Role.Button, onClick = onClick).semantics { selected = on }
            .padding(horizontal = 12.dp, vertical = 6.dp).testTag("vault-change:${row.path}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ChangeLetter(row.letter, row.tone)
        Column(Modifier.weight(1f)) {
            Text(nameOf(row.path), style = M3Type.bodyMedium, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(folderOf(row.path), style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        ChangeDelta(row.added, row.removed, row.conflict)
    }
}
