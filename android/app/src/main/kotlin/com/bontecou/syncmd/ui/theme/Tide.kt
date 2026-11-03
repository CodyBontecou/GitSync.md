package com.bontecou.syncmd.ui.theme

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R

// ─── Tide design system ─────────────────────────────────────────────────────
//
// "State first, git second." Mirrors iOS TideDesignSystem.swift: paper ground,
// white rounded cards, ink pills, Geist for words and Geist Mono for git facts
// (branches, paths, hashes, counts, times). Orange = yours to push, blue =
// theirs to pull, green = in sync.

@Immutable
data class TideColors(
    val paper: Color,
    val card: Color,
    val ink: Color,
    val inkMid: Color,
    val inkFaint: Color,
    val line: Color,
    /** Softer outline for outline pills and chips. */
    val lineSoft: Color,
    val well: Color,
    /** Local commits / changes to push. */
    val ahead: Color,
    /** Remote commits / changes to pull. */
    val behind: Color,
    val synced: Color,
    val danger: Color,
    /** Dark state hero: stays dark in both appearances. */
    val heroBg: Color,
    val heroBorder: Color,
    val isDark: Boolean,
) {
    val heroText get() = Color(0xFFF2F1EC)
    val heroMeta get() = Color(0xFFB5B5BB)
    val heroLine get() = Color(0xFF3A3A40)
    val heroAhead get() = Color(0xFFFF8A4C)
    val heroBehind get() = Color(0xFF7C95FF)
    val heroSynced get() = Color(0xFF5BC489)
    val heroDanger get() = Color(0xFFFF7A6B)
}

val TideLight = TideColors(
    paper = Color(0xFFF2F1EC),
    card = Color(0xFFFFFFFF),
    ink = Color(0xFF111113),
    inkMid = Color(0xFF55555C),
    inkFaint = Color(0xFF6B6B72),
    line = Color(0xFFE2E0D9),
    lineSoft = Color(0xFFD9D7CF),
    well = Color(0xFFE6E4DD),
    ahead = Color(0xFFB4410E),
    behind = Color(0xFF2D5BFF),
    synced = Color(0xFF17703F),
    danger = Color(0xFFB42318),
    heroBg = Color(0xFF111113),
    heroBorder = Color.Transparent,
    isDark = false,
)

val TideDark = TideColors(
    paper = Color(0xFF0E0E10),
    card = Color(0xFF1A1A1D),
    ink = Color(0xFFF2F1EC),
    inkMid = Color(0xFFA9A9B0),
    inkFaint = Color(0xFF8C8C94),
    line = Color(0xFF34343A),
    lineSoft = Color(0xFF3A3A40),
    well = Color(0xFF1A1A1D),
    ahead = Color(0xFFFF8A4C),
    behind = Color(0xFF7C95FF),
    synced = Color(0xFF5BC489),
    danger = Color(0xFFFF7A6B),
    heroBg = Color(0xFF1E1E22),
    heroBorder = Color(0xFF2E2E33),
    isDark = true,
)

val LocalTideColors = staticCompositionLocalOf { TideLight }

object Tide {
    val colors: TideColors
        @Composable @ReadOnlyComposable get() = LocalTideColors.current

    val radiusCard = 22.dp
    val radiusHero = 26.dp
    val radiusField = 18.dp
    val radiusSheet = 32.dp
    val gutter = 16.dp

    val cardShape = RoundedCornerShape(radiusCard)
    val heroShape = RoundedCornerShape(radiusHero)
    val fieldShape = RoundedCornerShape(radiusField)
}

// ─── Type ───────────────────────────────────────────────────────────────────

object TideFonts {
    val Sans = FontFamily(
        Font(R.font.geist_regular, FontWeight.Normal),
        Font(R.font.geist_medium, FontWeight.Medium),
        Font(R.font.geist_semibold, FontWeight.SemiBold),
        Font(R.font.geist_bold, FontWeight.Bold),
    )

    /** Geist Mono — git facts only: branches, paths, hashes, counts, times. */
    val Mono = FontFamily(
        Font(R.font.geist_mono_regular, FontWeight.Normal),
        Font(R.font.geist_mono_medium, FontWeight.Medium),
        Font(R.font.geist_mono_semibold, FontWeight.SemiBold),
        // Mono ships no Bold instance; bold reads as semibold.
        Font(R.font.geist_mono_semibold, FontWeight.Bold),
        Font(R.font.geist_mono_semibold, FontWeight.Black),
    )
}

object TideType {
    /** Big state headline ("1 vault needs you.") — 38/600, tight tracking. */
    fun display(size: TextUnit = 38.sp) = TextStyle(
        fontFamily = TideFonts.Sans, fontWeight = FontWeight.SemiBold,
        fontSize = size, lineHeight = size * 1.08f, letterSpacing = (-0.035).em,
    )

    fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = TideFonts.Sans, fontWeight = weight, fontSize = size, lineHeight = size * 1.3f)

    fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = TideFonts.Mono, fontWeight = weight, fontSize = size, lineHeight = size * 1.35f)

    /** Vault names, sheet titles — 19/600. */
    val title = sans(19.sp, FontWeight.SemiBold)
    /** Body copy and row labels — 15/500. */
    val body = sans(15.sp, FontWeight.Medium)
    /** Plain reading copy — 15/400. */
    val copy = sans(15.sp)
    /** Primary button label — 16/600. */
    val button = sans(16.sp, FontWeight.SemiBold)
    /** Meta line — mono 11.5/400. */
    val meta = mono(11.5.sp)
    val metaStrong = mono(11.5.sp, FontWeight.SemiBold)
    /** Section labels ("INCLUDED", "LAST PUSHED") — mono 11, caps, +0.06em. */
    val section = mono(11.sp).copy(letterSpacing = 0.06.em)
}

@Composable
fun TideDisplay(text: String, modifier: Modifier = Modifier, size: TextUnit = 38.sp, color: Color = Tide.colors.ink) {
    Text(text, modifier = modifier.semantics { heading() }, style = TideType.display(size), color = color)
}

/** "gitsync.md" wordmark — mono, the ".md" in remote blue. */
@Composable
fun TideWordmark(modifier: Modifier = Modifier) {
    val c = Tide.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.ink)) { append("gitsync") }
            withStyle(SpanStyle(color = c.behind)) { append(".md") }
        },
        modifier = modifier.semantics { contentDescription = "GitSync.md" },
        style = TideType.mono(14.sp, FontWeight.SemiBold),
    )
}

// ─── Sync state ─────────────────────────────────────────────────────────────

sealed interface TideSyncState {
    data object InSync : TideSyncState
    data class Ahead(val count: Int) : TideSyncState
    data class Behind(val count: Int) : TideSyncState
    data class Diverged(val local: Int, val remote: Int) : TideSyncState

    companion object {
        fun of(ahead: Int, behind: Int): TideSyncState = when {
            ahead > 0 && behind > 0 -> Diverged(ahead, behind)
            ahead > 0 -> Ahead(ahead)
            behind > 0 -> Behind(behind)
            else -> InSync
        }
    }
}

@Composable
@ReadOnlyComposable
fun TideSyncState.tint(): Color = when (this) {
    TideSyncState.InSync -> Tide.colors.synced
    is TideSyncState.Ahead, is TideSyncState.Diverged -> Tide.colors.ahead
    is TideSyncState.Behind -> Tide.colors.behind
}

/** Transient activity layered on top of git state. */
enum class TideActivity { PAUSED, SYNCING }

// ─── SyncRail — the signature component ─────────────────────────────────────
//
// Device on the left, remote on the right, sync point in the middle.
// Filled dots = local commits, rings = remote commits.

@Composable
fun SyncRail(
    state: TideSyncState,
    modifier: Modifier = Modifier,
    maxDots: Int = 5,
    dot: Dp = 9.dp,
    onHero: Boolean = false,
    description: String? = null,
) {
    val c = Tide.colors
    val aheadTint = if (onHero) c.heroAhead else c.ahead
    val behindTint = if (onHero) c.heroBehind else c.behind
    val (local, remote) = when (state) {
        TideSyncState.InSync -> 0 to 0
        is TideSyncState.Ahead -> state.count to 0
        is TideSyncState.Behind -> 0 to maxOf(state.count, 1)
        is TideSyncState.Diverged -> state.local to maxOf(state.remote, 1)
    }
    Box(
        modifier
            .fillMaxWidth()
            .height(18.dp)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(if (onHero) c.heroLine else c.line))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(minOf(local, maxDots)) { Box(Modifier.size(dot).background(aheadTint, CircleShape)) }
            }
            if (local > 0) Spacer(Modifier.width(6.dp))
            when (state) {
                TideSyncState.InSync -> Box(Modifier.size(18.dp).background(if (onHero) c.heroSynced else c.synced, CircleShape))
                is TideSyncState.Diverged -> Box(
                    Modifier.size(13.dp).rotate(45f)
                        .background(if (onHero) c.heroText else c.ink, RoundedCornerShape(3.dp))
                )
                else -> Box(
                    Modifier.size(if (onHero) 16.dp else 14.dp)
                        .background(if (onHero) c.heroBg else c.card, CircleShape)
                        .border(2.dp, if (onHero) c.heroText else c.ink, CircleShape)
                )
            }
            if (remote > 0) Spacer(Modifier.width(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(minOf(remote, maxDots)) { Box(Modifier.size(dot).border(2.dp, behindTint, CircleShape)) }
            }
        }
    }
}

/** Rail with "Phone" / "Remote" end labels, as on the Home vault cards. */
@Composable
fun LabeledSyncRail(
    state: TideSyncState,
    deviceLabel: String,
    remoteLabel: String,
    modifier: Modifier = Modifier,
    description: String? = null,
) {
    val c = Tide.colors
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(deviceLabel, style = TideType.mono(11.sp), color = c.inkFaint)
        SyncRail(state, Modifier.weight(1f).padding(horizontal = 10.dp), description = description)
        Text(remoteLabel, style = TideType.mono(11.sp), color = c.inkFaint)
    }
}

// ─── Pills, badges, labels ──────────────────────────────────────────────────

@Composable
fun TidePill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.background(bg, RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
        style = TideType.metaStrong,
        color = fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TideStatePill(state: TideSyncState, headline: String, modifier: Modifier = Modifier, activity: TideActivity? = null) {
    val c = Tide.colors
    when (activity) {
        TideActivity.PAUSED -> TidePill(headline, c.inkMid, c.well, modifier)
        TideActivity.SYNCING -> TidePill(headline, c.paper, c.ink, modifier)
        null -> TidePill(headline, state.tint(), state.tint().copy(alpha = 0.12f), modifier)
    }
}

/** Mono caps section label ("LAST PUSHED"). */
@Composable
fun TideSectionLabel(title: String, modifier: Modifier = Modifier, color: Color = Tide.colors.inkFaint) {
    // Uppercase in the UI language, so Turkish "i" becomes "İ".
    val locale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0] ?: java.util.Locale.getDefault()
    Text(
        title.uppercase(locale),
        modifier = modifier.semantics { heading() },
        style = TideType.section,
        color = color,
    )
}

// ─── Containers ─────────────────────────────────────────────────────────────

/** White rounded card on paper — Tide's basic container. */
@Composable
fun TideCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    radius: Dp = Tide.radiusCard,
    color: Color = Tide.colors.card,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

/** Dark state hero — stays dark in both appearances (Vault header). */
@Composable
fun TideHero(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = Tide.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.heroBg, Tide.heroShape)
            .border(1.dp, c.heroBorder, Tide.heroShape)
            .padding(18.dp),
        content = content,
    )
}

/** A card of rows separated by hairlines, under an optional section label. */
@Composable
fun TideSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) TideSectionLabel(title, Modifier.padding(start = 4.dp, bottom = 8.dp))
        TideCard(padding = 0.dp, content = { Column(Modifier.padding(horizontal = 16.dp), content = content) })
        if (footer != null) {
            Text(
                footer,
                style = TideType.sans(13.sp),
                color = Tide.colors.inkFaint,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp),
            )
        }
    }
}

@Composable
fun TideHairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Tide.colors.line))
}

enum class TideAccessory { NONE, CHEVRON, EXTERNAL }

/** Settings-style row: title (+ subtitle) on the left, mono value and chevron on the right. */
@Composable
fun TideRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    valueTint: Color = Tide.colors.inkMid,
    valueIsMono: Boolean = true,
    accessory: TideAccessory = TideAccessory.NONE,
    destructive: Boolean = false,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        leading?.invoke()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.body, color = if (destructive) c.danger else c.ink)
            if (subtitle != null) Text(subtitle, style = TideType.mono(11.sp), color = c.inkFaint)
        }
        if (value != null) {
            Text(
                value,
                style = if (valueIsMono) TideType.mono(13.sp) else TideType.sans(15.sp),
                color = valueTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke(this)
        when (accessory) {
            TideAccessory.CHEVRON -> TideChevron()
            TideAccessory.EXTERNAL -> Text("↗", style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.inkFaint)
            TideAccessory.NONE -> Unit
        }
    }
}

/** Row disclosure chevron; mirrors in right-to-left layouts. */
@Composable
fun TideChevron(color: Color = Tide.colors.inkFaint) {
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    Text(if (rtl) "‹" else "›", style = TideType.sans(22.sp, FontWeight.Medium).copy(lineHeight = 22.sp), color = color)
}

// ─── Buttons ────────────────────────────────────────────────────────────────

/** Solid ink pill with a lift — the one right action, optionally with a count badge. */
@Composable
fun TidePrimaryButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    icon: Painter? = null,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .shadow(if (enabled) 12.dp else 0.dp, CircleShape, ambientColor = Color.Black.copy(alpha = 0.2f), spotColor = Color.Black.copy(alpha = 0.25f))
            .clip(CircleShape)
            .background(c.ink.copy(alpha = if (enabled) 1f else 0.3f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(start = 24.dp, end = if (badge == null) 24.dp else 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = c.paper, strokeWidth = 2.dp)
            }
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = c.paper, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(title, style = TideType.button, color = c.paper, modifier = Modifier.weight(1f))
            if (badge != null) {
                Box(
                    Modifier.height(44.dp).background(c.behind, CircleShape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(badge, style = TideType.metaStrong, color = Color.White) }
            }
        }
    }
}

/** Outline pill — secondary action next to the primary. */
@Composable
fun TideOutlineButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    onHero: Boolean = false,
) {
    val c = Tide.colors
    Box(
        modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .border(1.dp, if (onHero) c.heroLine else c.lineSoft, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (onHero) c.heroText else c.ink,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
    }
}

/** 48dp circle icon button (back, more, add). */
@Composable
fun TideCircleButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 48.dp,
    filled: Boolean = true,
    enabled: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val c = Tide.colors
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .then(if (filled) Modifier.background(c.card, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.4f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.ink) { content() }
    }
}

// ─── Chips & segmented control ──────────────────────────────────────────────

@Composable
fun TideFilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    count: Int? = null,
    dot: Color? = null,
) {
    val c = Tide.colors
    Row(
        modifier
            .height(44.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(c.ink, CircleShape) else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .clickable(role = Role.Tab, onClick = onClick)
            .semantics { this.selected = selected }
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(label, style = TideType.sans(15.sp, FontWeight.Medium), color = if (selected) c.paper else c.ink)
        if (count != null) Text("$count", style = TideType.sans(15.sp, FontWeight.Medium), color = if (selected) c.paper else c.ink)
    }
}

@Composable
fun TideChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Changes · Files · History · Git — rounded well with a raised selected segment. */
@Composable
fun TideSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<Int?> = emptyList(),
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.well.copy(alpha = if (c.isDark) 1f else 0.7f), RoundedCornerShape(14.dp))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .then(if (selected) Modifier.background(if (c.isDark) Color(0xFF2A2A2F) else c.card, RoundedCornerShape(10.dp)) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    option,
                    style = TideType.sans(14.sp, if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = if (selected) c.ink else c.inkMid,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val badge = badges.getOrNull(index)
                if (badge != null && badge > 0) {
                    Spacer(Modifier.width(5.dp))
                    Text("$badge", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.ahead)
                }
            }
        }
    }
}

// ─── Toast & banners ────────────────────────────────────────────────────────

enum class TideToastStyle { SUCCESS, BEHIND, DANGER, NEUTRAL }

/** Dark floating toast ("Pushed 3 commits · VoxVault · e19c4a0  Undo"). */
@Composable
fun TideToast(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    style: TideToastStyle = TideToastStyle.SUCCESS,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val c = Tide.colors
    val badgeFill = when (style) {
        TideToastStyle.SUCCESS -> c.synced
        TideToastStyle.BEHIND -> Color(0xFF2D5BFF)
        TideToastStyle.DANGER -> Color(0xFFB42318)
        TideToastStyle.NEUTRAL -> c.heroLine
    }
    val glyph = when (style) {
        TideToastStyle.SUCCESS -> "✓"
        TideToastStyle.BEHIND -> "↓"
        TideToastStyle.DANGER -> "!"
        TideToastStyle.NEUTRAL -> "i"
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .shadow(14.dp, shape, ambientColor = Color.Black.copy(alpha = 0.18f))
            .background(c.heroBg, shape)
            .border(1.dp, c.heroBorder, shape)
            .padding(start = 14.dp, end = if (actionTitle == null) 14.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.size(28.dp).background(badgeFill, CircleShape), contentAlignment = Alignment.Center) {
            Text(glyph, style = TideType.mono(13.sp, FontWeight.SemiBold), color = Color.White)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = c.heroText, maxLines = 2)
            if (detail != null) Text(detail, style = TideType.mono(11.sp), color = c.heroMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actionTitle != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 44.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 10.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionTitle, style = TideType.sans(14.sp, FontWeight.Medium), color = if (style == TideToastStyle.DANGER) c.heroText else Color(0xFFAFC0FF))
            }
        }
    }
}

enum class TideBannerTone { NEUTRAL, BEHIND, AHEAD, DANGER, SYNCED }

/** Inline banner on paper: tinted well with a title, detail and optional action. */
@Composable
fun TideBanner(
    title: String,
    modifier: Modifier = Modifier,
    detail: String? = null,
    tone: TideBannerTone = TideBannerTone.NEUTRAL,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    onDismiss: (() -> Unit)? = null,
    dismissLabel: String = androidx.compose.ui.res.stringResource(R.string.ui_dismiss_48845bff),
) {
    val c = Tide.colors
    val tint = when (tone) {
        TideBannerTone.NEUTRAL -> c.ink
        TideBannerTone.BEHIND -> c.behind
        TideBannerTone.AHEAD -> c.ahead
        TideBannerTone.DANGER -> c.danger
        TideBannerTone.SYNCED -> c.synced
    }
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (tone == TideBannerTone.NEUTRAL) c.card else tint.copy(alpha = 0.10f), shape)
            .border(1.dp, if (tone == TideBannerTone.NEUTRAL) c.line else tint.copy(alpha = 0.25f), shape)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = if (tone == TideBannerTone.NEUTRAL) c.ink else tint)
            if (detail != null) Text(detail, style = TideType.sans(13.sp), color = c.inkMid)
        }
        if (actionTitle != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 40.dp).clip(CircleShape).background(c.ink, CircleShape)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(actionTitle, style = TideType.sans(14.sp, FontWeight.SemiBold), color = c.paper) }
        }
        if (onDismiss != null) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = dismissLabel },
                contentAlignment = Alignment.Center,
            ) { Text("✕", style = TideType.sans(15.sp), color = c.inkMid) }
        }
    }
}

// ─── Modal pieces ───────────────────────────────────────────────────────────

/** Tinted rounded-square glyph at the top of a confirmation sheet. */
@Composable
fun TideModalIcon(glyph: String = "!", tint: Color = Tide.colors.danger, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).background(tint.copy(alpha = 0.12f), RoundedCornerShape(16.dp)),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = TideType.sans(20.sp, FontWeight.SemiBold), color = tint) }
}

// ─── Top bar ────────────────────────────────────────────────────────────────

/**
 * Tide navigation bar: circular back button, a small centred caption (often a
 * mono branch or the wordmark), and trailing circle actions.
 */
@Composable
fun TideTopBar(
    modifier: Modifier = Modifier,
    onBack: (() -> Unit)? = null,
    backLabel: String = androidx.compose.ui.res.stringResource(R.string.publication_back),
    backEnabled: Boolean = true,
    backModifier: Modifier = Modifier,
    center: (@Composable () -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    Box(
        modifier
            .fillMaxWidth()
            .background(Tide.colors.paper)
            .statusBarsPadding()
            .heightIn(min = 60.dp)
            .padding(horizontal = Tide.gutter, vertical = 8.dp),
    ) {
        if (onBack != null) {
            TideCircleButton(backLabel, onBack, backModifier.align(Alignment.CenterStart), enabled = backEnabled) {
                Icon(androidx.compose.ui.res.painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(20.dp))
            }
        }
        if (center != null) Box(Modifier.align(Alignment.Center).padding(horizontal = 56.dp)) { center() }
        if (actions != null) {
            Row(Modifier.align(Alignment.CenterEnd), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

// ─── Activity ───────────────────────────────────────────────────────────────

/** Three pulsing dots — "syncing…" without a spinner. */
@Composable
fun TidePulse(modifier: Modifier = Modifier, color: Color = Tide.colors.ink) {
    val t = rememberInfiniteTransition(label = "pulse")
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(3) { i ->
            val a by t.animateFloat(
                0.25f, 1f,
                infiniteRepeatable(tween(600, delayMillis = i * 150), RepeatMode.Reverse),
                label = "dot$i",
            )
            Box(Modifier.size(7.dp).alpha(a).background(color, CircleShape))
        }
    }
}
