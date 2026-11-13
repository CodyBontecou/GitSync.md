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
import androidx.compose.ui.res.painterResource
import androidx.annotation.DrawableRes
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

// ─── Tide design system, Material 3 edition ─────────────────────────────────
//
// "State first, git second." The Android build follows the GitSync.md Material 3
// design: a warm surface, tonal containers, a blue primary, Roboto for words and a
// monospace face for git facts (branches, paths, hashes, counts, times). Orange =
// yours to push, blue = theirs to pull, green = in sync. The legacy Tide names
// (paper, card, ink…) map onto Material roles so every screen moves together.

@Immutable
data class TideColors(
    /** M3 surface — the screen ground. */
    val paper: Color,
    /** M3 surfaceContainerLow — grouped lists and cards. */
    val card: Color,
    /** M3 onSurface. */
    val ink: Color,
    /** M3 onSurfaceVariant. */
    val inkMid: Color,
    /** Supporting text, still 4.5:1 on surface. */
    val inkFaint: Color,
    /** M3 outlineVariant — dividers. */
    val line: Color,
    /** M3 outline — outlined buttons, chips, fields. */
    val lineSoft: Color,
    /** M3 surfaceContainerHighest — wells, tracks, muted badges. */
    val well: Color,
    /** Local commits / changes to push. */
    val ahead: Color,
    /** Remote commits / changes to pull. */
    val behind: Color,
    val synced: Color,
    val danger: Color,
    /** Inverse state hero: stays dark in both appearances. */
    val heroBg: Color,
    val heroBorder: Color,
    val isDark: Boolean,
    val primary: Color,
    val onPrimary: Color,
    val primaryContainer: Color,
    val onPrimaryContainer: Color,
    val secondaryContainer: Color,
    val onSecondaryContainer: Color,
    val tertiary: Color,
    val tertiaryContainer: Color,
    val onTertiaryContainer: Color,
    /** Pinned-vault card fill: a whisper of tertiary. */
    val tertiarySoft: Color,
    /** M3 surfaceContainer — navigation bar, menus. */
    val container: Color,
    /** M3 surfaceContainerHigh — dialogs, notifications. */
    val containerHigh: Color,
    val onError: Color,
    val errorContainer: Color,
    val onErrorContainer: Color,
    val okContainer: Color,
    val onOkContainer: Color,
    /** Diff ink for added and removed lines. */
    val addInk: Color,
    val delInk: Color,
    val addBg: Color,
    val delBg: Color,
) {
    val heroText get() = Color(0xFFF2F1EC)
    val heroMeta get() = Color(0xFFB5B5BB)
    val heroLine get() = Color(0xFF3A3A40)
    val heroAhead get() = Color(0xFFFF8A4C)
    val heroBehind get() = Color(0xFF7C95FF)
    val heroSynced get() = Color(0xFF5BC489)
    val heroDanger get() = Color(0xFFFF7A6B)

    /** Tonal container (fill, ink) for a state tint, or null for any other color. */
    fun containerFor(tint: Color): Pair<Color, Color>? = when (tint.copy(alpha = 1f)) {
        danger -> errorContainer to onErrorContainer
        synced -> okContainer to onOkContainer
        ahead -> tertiaryContainer to onTertiaryContainer
        behind, primary -> primaryContainer to onPrimaryContainer
        else -> null
    }
}

val TideLight = TideColors(
    paper = Color(0xFFFBF8F3),
    card = Color(0xFFF5F2EC),
    ink = Color(0xFF1C1B1A),
    inkMid = Color(0xFF4A4850),
    inkFaint = Color(0xFF5C5A62),
    line = Color(0xFFCBC6CC),
    lineSoft = Color(0xFF79767E),
    well = Color(0xFFE3E0DA),
    ahead = Color(0xFFB4410E),
    behind = Color(0xFF2D5BFF),
    synced = Color(0xFF17703F),
    danger = Color(0xFFB42318),
    heroBg = Color(0xFF111113),
    heroBorder = Color.Transparent,
    isDark = false,
    primary = Color(0xFF2D4FD8),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDEE1FF),
    onPrimaryContainer = Color(0xFF00164F),
    secondaryContainer = Color(0xFFE3E1EC),
    onSecondaryContainer = Color(0xFF1B1B24),
    tertiary = Color(0xFFA33F0E),
    tertiaryContainer = Color(0xFFFFDBCB),
    onTertiaryContainer = Color(0xFF370E00),
    tertiarySoft = Color(0xFFFCEDE4),
    container = Color(0xFFEFECE6),
    containerHigh = Color(0xFFE9E6E0),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
    okContainer = Color(0xFFCDEED8),
    onOkContainer = Color(0xFF00210E),
    addInk = Color(0xFF17703F),
    delInk = Color(0xFFB42318),
    addBg = Color(0xFFE3F4E8),
    delBg = Color(0xFFFCE8E5),
)

val TideDark = TideColors(
    paper = Color(0xFF131316),
    card = Color(0xFF1B1B1F),
    ink = Color(0xFFE5E2E1),
    inkMid = Color(0xFFC8C5CD),
    inkFaint = Color(0xFFB4B1BA),
    line = Color(0xFF47464E),
    lineSoft = Color(0xFF928F98),
    well = Color(0xFF353539),
    ahead = Color(0xFFFF8A4C),
    behind = Color(0xFF7C95FF),
    synced = Color(0xFF5BC489),
    danger = Color(0xFFFFB4AB),
    heroBg = Color(0xFF1E1E22),
    heroBorder = Color.Transparent,
    isDark = true,
    primary = Color(0xFFB9C3FF),
    onPrimary = Color(0xFF06228A),
    primaryContainer = Color(0xFF2440B8),
    onPrimaryContainer = Color(0xFFDEE1FF),
    secondaryContainer = Color(0xFF45464F),
    onSecondaryContainer = Color(0xFFE3E1EC),
    tertiary = Color(0xFFFFB596),
    tertiaryContainer = Color(0xFF7F2C05),
    onTertiaryContainer = Color(0xFFFFDBCB),
    tertiarySoft = Color(0xFF2C1D16),
    container = Color(0xFF1F1F23),
    containerHigh = Color(0xFF2A2A2E),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
    okContainer = Color(0xFF0F5130),
    onOkContainer = Color(0xFFB6F2CB),
    addInk = Color(0xFF5BC489),
    delInk = Color(0xFFFF8A7E),
    addBg = Color(0xFF16301F),
    delBg = Color(0xFF3A1A17),
)

val LocalTideColors = staticCompositionLocalOf { TideLight }

object Tide {
    val colors: TideColors
        @Composable @ReadOnlyComposable get() = LocalTideColors.current

    /** M3 large shape: list containers, cards, the hero. */
    val radiusCard = 28.dp
    val radiusHero = 28.dp
    /** M3 extra-small shape: outlined text fields. */
    val radiusField = 4.dp
    /** M3 extra-large shape: sheets and dialogs. */
    val radiusSheet = 28.dp
    val gutter = 16.dp

    val cardShape = RoundedCornerShape(radiusCard)
    val heroShape = RoundedCornerShape(radiusHero)
    val fieldShape = RoundedCornerShape(radiusField)
}

// ─── Type ───────────────────────────────────────────────────────────────────

object TideFonts {
    /** Roboto (Roboto Flex where the system ships it) — the Material 3 type scale. */
    val Sans: FontFamily = FontFamily.SansSerif

    /** Roboto Mono for git facts only: branches, paths, hashes, counts, times (design: 400/500/600). */
    val Mono = FontFamily(
        Font(R.font.roboto_mono_regular, FontWeight.Normal),
        Font(R.font.roboto_mono_medium, FontWeight.Medium),
        Font(R.font.roboto_mono_semibold, FontWeight.SemiBold),
        // The design tops out at 600; bold and black read as semibold.
        Font(R.font.roboto_mono_semibold, FontWeight.Bold),
        Font(R.font.roboto_mono_semibold, FontWeight.Black),
    )
}

object TideType {
    /** Big state headline ("1 vault needs you.") — M3 display/headline, weight 500. */
    fun display(size: TextUnit = 36.sp) = TextStyle(
        fontFamily = TideFonts.Sans, fontWeight = FontWeight.Medium,
        fontSize = size, lineHeight = size * 1.2f, letterSpacing = (-0.012).em,
    )

    fun sans(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = TideFonts.Sans, fontWeight = weight, fontSize = size, lineHeight = size * 1.4f)

    fun mono(size: TextUnit, weight: FontWeight = FontWeight.Normal) =
        TextStyle(fontFamily = TideFonts.Mono, fontWeight = weight, fontSize = size, lineHeight = size * 1.35f)

    /** Vault names, sheet and top-bar titles — M3 title large, 22/500. */
    val title = TextStyle(fontFamily = TideFonts.Sans, fontWeight = FontWeight.Medium, fontSize = 22.sp, lineHeight = 28.sp)
    /** List headlines and row labels — M3 body large, 16/24. */
    val body = TextStyle(fontFamily = TideFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 16.sp, lineHeight = 24.sp)
    /** Supporting copy — 15/22. */
    val copy = TextStyle(fontFamily = TideFonts.Sans, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    /** Button label — 16/600. */
    val button = TextStyle(fontFamily = TideFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp)
    /** Meta line — mono 12/16. */
    val meta = mono(12.sp).copy(lineHeight = 16.sp)
    val metaStrong = mono(12.sp, FontWeight.SemiBold).copy(lineHeight = 16.sp)
    /** List section headers ("Changes · 3", "Latest commit") — 14/600 in primary. */
    val section = TextStyle(fontFamily = TideFonts.Sans, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 20.sp)
}

@Composable
fun TideDisplay(text: String, modifier: Modifier = Modifier, size: TextUnit = 36.sp, color: Color = Tide.colors.ink) {
    Text(text, modifier = modifier.semantics { heading() }, style = TideType.display(size), color = color)
}

/** "gitsync.md" wordmark — the ".md" in primary. */
@Composable
fun TideWordmark(modifier: Modifier = Modifier, size: TextUnit = 22.sp, mono: Boolean = false) {
    val c = Tide.colors
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.ink)) { append("gitsync") }
            withStyle(SpanStyle(color = c.primary)) { append(".md") }
        },
        modifier = modifier.semantics { contentDescription = "GitSync.md" },
        style = if (mono) TideType.mono(size, FontWeight.SemiBold) else TideType.sans(size, FontWeight.SemiBold).copy(letterSpacing = (-0.014).em),
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
        Box(Modifier.fillMaxWidth().height(2.dp).background(if (onHero) c.heroLine else c.line))
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

/**
 * M3 badge chip (8dp corners, 12/600). A state tint passed with its translucent
 * fill becomes the matching tonal container, so "synced green on 12% green"
 * renders as okContainer / onOkContainer.
 */
@Composable
fun TidePill(text: String, fg: Color, bg: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    val c = Tide.colors
    val tonal = if (bg.alpha < 1f && bg.copy(alpha = 1f) == fg.copy(alpha = 1f)) c.containerFor(fg) else null
    val fill = tonal?.first ?: if (bg == c.well) c.well else bg
    val ink = tonal?.second ?: fg
    val glyphs = mono || text.firstOrNull()?.let { it == '↑' || it == '↓' || it.isDigit() } == true
    Text(
        text,
        modifier = modifier.background(fill, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = if (glyphs) TideType.metaStrong else TideType.sans(12.sp, FontWeight.SemiBold).copy(lineHeight = 16.sp),
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
fun TideStatePill(state: TideSyncState, headline: String, modifier: Modifier = Modifier, activity: TideActivity? = null) {
    val c = Tide.colors
    when (activity) {
        TideActivity.PAUSED -> TidePill(headline, c.inkMid, c.well, modifier)
        TideActivity.SYNCING -> TidePill(headline, c.onPrimary, c.primary, modifier)
        null -> TidePill(headline, state.tint(), state.tint().copy(alpha = 0.12f), modifier)
    }
}

/** M3 list section header ("Changes · 3", "Latest commit"): 14/600 in primary, sentence case. */
@Composable
fun TideSectionLabel(title: String, modifier: Modifier = Modifier, color: Color = Tide.colors.primary) {
    Text(
        title,
        modifier = modifier.semantics { heading() },
        style = TideType.section,
        color = color,
    )
}

// ─── Containers ─────────────────────────────────────────────────────────────

/** Tonal rounded container on the surface (M3 surfaceContainerLow, 28dp). */
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

/** Inverse state hero — stays dark in both appearances (Home and Vault headers). */
@Composable
fun TideHero(modifier: Modifier = Modifier, color: Color = Tide.colors.heroBg, content: @Composable ColumnScope.() -> Unit) {
    val c = Tide.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(color, Tide.heroShape)
            .border(1.dp, c.heroBorder, Tide.heroShape)
            .padding(20.dp),
        content = content,
    )
}

/** An M3 list in a 28dp tonal container under an optional primary section header. */
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
                style = TideType.sans(12.sp).copy(lineHeight = 16.sp),
                color = Tide.colors.inkMid,
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

/** M3 list item: headline (+ supporting text) on the left, mono value and chevron on the right. */
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
            .heightIn(min = 56.dp)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) CompositionLocalProvider(LocalContentColor provides c.inkMid) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.body, color = if (destructive) c.danger else c.ink)
            if (subtitle != null) Text(subtitle, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = c.inkMid)
        }
        if (value != null) {
            Text(
                value,
                style = if (valueIsMono) TideType.mono(12.sp) else TideType.sans(14.sp),
                color = valueTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke(this)
        when (accessory) {
            TideAccessory.CHEVRON -> TideChevron()
            TideAccessory.EXTERNAL -> Icon(painterResource(R.drawable.ic_m3_open_in_new), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(20.dp))
            TideAccessory.NONE -> Unit
        }
    }
}

/** Row disclosure chevron; mirrors in right-to-left layouts. */
@Composable
fun TideChevron(color: Color = Tide.colors.inkMid) {
    Icon(painterResource(R.drawable.ic_m3_chevron_right), contentDescription = null, tint = color, modifier = Modifier.size(24.dp))
}

/** 24dp Material icon from the GitSync.md set. */
@Composable
fun TideIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp, contentDescription: String? = null) {
    Icon(painterResource(id), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

// ─── Buttons ────────────────────────────────────────────────────────────────

/** M3 filled button, 56dp: the one right action, optionally with a count badge. */
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
    val fill = if (enabled) c.primary else c.ink.copy(alpha = 0.12f)
    val ink = if (enabled) c.onPrimary else c.ink.copy(alpha = 0.38f)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .background(fill)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(start = 24.dp, end = if (badge == null) 24.dp else 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), color = ink, strokeWidth = 2.dp)
            }
        } else {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(title, style = TideType.button, color = ink, modifier = Modifier.weight(1f))
            if (badge != null) {
                Box(
                    Modifier.height(40.dp).background(if (enabled) c.onPrimary else Color.Transparent, CircleShape).padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(badge, style = TideType.metaStrong.copy(fontSize = 14.sp), color = if (enabled) c.primary else ink) }
            }
        }
    }
}

/** M3 outlined button — secondary action next to the primary. */
@Composable
fun TideOutlineButton(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 48.dp,
    onHero: Boolean = false,
) {
    val c = Tide.colors
    Box(
        modifier
            .heightIn(min = height)
            .clip(CircleShape)
            .border(1.dp, if (onHero) c.heroLine else c.lineSoft, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .padding(horizontal = 20.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(14.sp, FontWeight.SemiBold), color = if (onHero) c.heroText else c.primary,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(vertical = 10.dp))
    }
}

/** M3 icon button, 48dp (back, more, add). `filled = false` draws the outlined variant. */
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
            .then(if (filled) Modifier else Modifier.border(1.dp, c.lineSoft, CircleShape))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides c.ink) { content() }
    }
}

// ─── Chips & segmented control ──────────────────────────────────────────────

/** M3 filter chip: 32dp, 8dp corners, a check when selected, inside a 48dp touch target. */
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
    val shape = RoundedCornerShape(8.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Tab, onClick = onClick, indication = null,
                interactionSource = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() })
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(c.secondaryContainer, shape) else Modifier.border(1.dp, c.line, shape))
                .padding(start = if (selected) 8.dp else 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val ink = if (selected) c.onSecondaryContainer else c.inkMid
            if (selected) Icon(painterResource(R.drawable.ic_m3_check), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
            else if (dot != null) Box(Modifier.size(8.dp).background(dot, CircleShape))
            Text(label + if (count != null) " $count" else "", style = TideType.sans(14.sp, FontWeight.Medium).copy(lineHeight = 20.sp), color = ink)
        }
    }
}

@Composable
fun TideChipRow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** M3 segmented button: outlined, the selected segment tonal with a check. */
@Composable
fun TideSegmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    badges: List<Int?> = emptyList(),
) {
    val c = Tide.colors
    val outer = RoundedCornerShape(20.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .padding(vertical = 4.dp)
            .clip(outer)
            .border(1.dp, c.lineSoft, outer),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            if (index > 0) Box(Modifier.width(1.dp).heightIn(min = 40.dp).background(c.lineSoft))
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .then(if (selected) Modifier.background(c.secondaryContainer) else Modifier)
                    .clickable(role = Role.Tab) { onSelect(index) }
                    .semantics { this.selected = selected }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val ink = if (selected) c.onSecondaryContainer else c.ink
                if (selected) {
                    Icon(painterResource(R.drawable.ic_m3_check), contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    option,
                    style = TideType.sans(14.sp, FontWeight.Medium),
                    color = ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val badge = badges.getOrNull(index)
                if (badge != null && badge > 0) {
                    Spacer(Modifier.width(6.dp))
                    Text("$badge", style = TideType.mono(11.sp, FontWeight.SemiBold), color = c.ahead)
                }
            }
        }
    }
}

// ─── Toast & banners ────────────────────────────────────────────────────────

enum class TideToastStyle { SUCCESS, BEHIND, DANGER, NEUTRAL }

/** M3 snackbar on the inverse surface ("Pushed 3 commits · VoxVault · e19c4a0  Undo"). */
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
        TideToastStyle.SUCCESS -> c.heroSynced
        TideToastStyle.BEHIND -> c.heroBehind
        TideToastStyle.DANGER -> c.heroDanger
        TideToastStyle.NEUTRAL -> c.heroMeta
    }
    val icon = when (style) {
        TideToastStyle.SUCCESS -> R.drawable.ic_m3_check
        TideToastStyle.BEHIND -> R.drawable.ic_m3_arrow_downward
        TideToastStyle.DANGER, TideToastStyle.NEUTRAL -> R.drawable.ic_m3_info
    }
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .shadow(6.dp, shape)
            .background(c.heroBg, shape)
            .padding(start = 16.dp, end = if (actionTitle == null) 16.dp else 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Icon(painterResource(icon), contentDescription = null, tint = badgeFill, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.sans(14.sp, FontWeight.Medium), color = c.heroText, maxLines = 2)
            if (detail != null) Text(detail, style = TideType.mono(11.sp), color = c.heroMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (actionTitle != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(actionTitle, style = TideType.sans(14.sp, FontWeight.SemiBold), color = Color(0xFFB9C3FF))
            }
        }
    }
}

enum class TideBannerTone { NEUTRAL, BEHIND, AHEAD, DANGER, SYNCED }

/** Inline tonal banner: container fill, title, detail and an optional action. */
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
    val (fill, ink) = when (tone) {
        TideBannerTone.NEUTRAL -> c.well to c.ink
        TideBannerTone.BEHIND -> c.primaryContainer to c.onPrimaryContainer
        TideBannerTone.AHEAD -> c.tertiaryContainer to c.onTertiaryContainer
        TideBannerTone.DANGER -> c.errorContainer to c.onErrorContainer
        TideBannerTone.SYNCED -> c.okContainer to c.onOkContainer
    }
    val shape = RoundedCornerShape(16.dp)
    Row(
        modifier
            .fillMaxWidth()
            .background(fill, shape)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.sans(15.sp, FontWeight.SemiBold), color = ink)
            if (detail != null) Text(detail, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = ink.copy(alpha = 0.86f))
        }
        if (actionTitle != null && onAction != null) {
            val (btn, onBtn) = if (tone == TideBannerTone.DANGER) (if (c.isDark) c.danger to c.onError else c.danger to c.onError) else c.primary to c.onPrimary
            Box(
                Modifier.heightIn(min = 40.dp).clip(CircleShape).background(btn, CircleShape)
                    .clickable(role = Role.Button, onClick = onAction).padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) { Text(actionTitle, style = TideType.sans(14.sp, FontWeight.SemiBold), color = onBtn) }
        }
        if (onDismiss != null) {
            Box(
                Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onDismiss)
                    .semantics { contentDescription = dismissLabel },
                contentAlignment = Alignment.Center,
            ) { Icon(painterResource(R.drawable.ic_m3_close), contentDescription = null, tint = ink, modifier = Modifier.size(20.dp)) }
        }
    }
}

// ─── Modal pieces ───────────────────────────────────────────────────────────

/** M3 dialog hero icon: a 24dp glyph in the dialog's tint. */
@Composable
fun TideModalIcon(glyph: String = "!", tint: Color = Tide.colors.danger, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp), contentAlignment = Alignment.Center) {
        if (glyph == "!") Icon(painterResource(R.drawable.ic_m3_info), contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        else Text(glyph, style = TideType.sans(22.sp, FontWeight.Medium), color = tint)
    }
}

// ─── Top bar ────────────────────────────────────────────────────────────────

/**
 * M3 small top app bar: a back icon button, the title block start-aligned after
 * it (often the vault name over a mono branch line), and trailing icon actions.
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
    Row(
        modifier
            .fillMaxWidth()
            .background(Tide.colors.paper)
            .statusBarsPadding()
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else 20.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TideCircleButton(backLabel, onBack, backModifier, enabled = backEnabled) {
                Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(24.dp))
            }
        }
        Box(Modifier.weight(1f).padding(start = if (onBack != null) 4.dp else 0.dp)) { center?.invoke() }
        if (actions != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(0.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

/** Title block for [TideTopBar]: 22/500 title over an optional mono subtitle. */
@Composable
fun TideTopBarTitle(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(title, style = TideType.title, color = Tide.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() })
        if (subtitle != null) Text(subtitle, style = TideType.meta, color = Tide.colors.inkMid, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
