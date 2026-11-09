package com.bontecou.syncmd.ui.theme

import com.bontecou.syncmd.ui.util.CountedPhrase
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.components.VaultTab

// ═══ M3Kit ══════════════════════════════════════════════════════════════════
//
// The GitSync.md Material 3 design's recurring patterns, built to its measurements
// (412dp-wide boards: px in the mockups = dp/sp here). Read colors through
// [M3.colors], which uses the design's palette names (c.cLow, c.pc, c.inv…) on
// top of the Tide tokens, so mockup styles translate one-for-one.
//
// Layout conventions from the boards:
//   screen gutter 16 · cards 28 radius · section label margin 20/20/4 · card top margin 8
//   list rows: 72 min (two lines) / 56 min (one line), padding 8 16, gap 16, 1dp outlineVar
//   divider above every row but the first · hero padding 20 · dialogs 24 padding, 28 radius.

// ─── Palette ────────────────────────────────────────────────────────────────

/** The design palette (`c.*` in the mockups) mapped onto the Tide tokens; light and dark follow the theme. */
@JvmInline
value class M3Colors(val tide: TideColors) {
    val surface: Color get() = tide.paper
    val cLow: Color get() = tide.card
    val c: Color get() = tide.container
    val cHigh: Color get() = tide.containerHigh
    val cHighest: Color get() = tide.well
    val on: Color get() = tide.ink
    val onVar: Color get() = tide.inkMid
    val outline: Color get() = tide.lineSoft
    val outlineVar: Color get() = tide.line
    val primary: Color get() = tide.primary
    val onPrimary: Color get() = tide.onPrimary
    val pc: Color get() = tide.primaryContainer
    val onPc: Color get() = tide.onPrimaryContainer
    /** Progress track on a primary container. */
    val pcTrack: Color get() = if (tide.isDark) Color(0xFF3B56C9) else Color(0xFFB9C3FF)
    /** Progress indicator on a primary container. */
    val primaryOnPc: Color get() = if (tide.isDark) Color(0xFFDEE1FF) else tide.primary
    val sc: Color get() = tide.secondaryContainer
    val onSc: Color get() = tide.onSecondaryContainer
    val tertiary: Color get() = tide.tertiary
    val tc: Color get() = tide.tertiaryContainer
    val onTc: Color get() = tide.onTertiaryContainer
    val tcSoft: Color get() = tide.tertiarySoft
    val error: Color get() = tide.danger
    val onError: Color get() = tide.onError
    val ec: Color get() = tide.errorContainer
    val onEc: Color get() = tide.onErrorContainer
    val ok: Color get() = tide.synced
    val okc: Color get() = tide.okContainer
    val onOkc: Color get() = tide.onOkContainer
    val inv: Color get() = tide.heroBg
    val onInv: Color get() = tide.heroText
    val invMeta: Color get() = tide.heroMeta
    val invLine: Color get() = tide.heroLine
    val invAhead: Color get() = tide.heroAhead
    val invBehind: Color get() = tide.heroBehind
    val invOk: Color get() = tide.heroSynced
    val invDanger: Color get() = tide.heroDanger
    val ahead: Color get() = tide.ahead
    val behind: Color get() = tide.behind
    val addInk: Color get() = tide.addInk
    val delInk: Color get() = tide.delInk
    val addBg: Color get() = tide.addBg
    val delBg: Color get() = tide.delBg
    val disabled: Color get() = tide.ink.copy(alpha = 0.38f)
    val disabledContainer: Color get() = tide.ink.copy(alpha = 0.12f)
    val scrim: Color get() = Color.Black.copy(alpha = if (tide.isDark) 0.5f else 0.32f)
}

object M3 {
    /** The design palette for the current theme. */
    val colors: M3Colors
        @Composable @ReadOnlyComposable get() = M3Colors(LocalTideColors.current)

    /** Screen gutter: cards and lists sit 16dp from the screen edges. */
    val gutter = 16.dp
    /** Cards, list containers and heroes. */
    val cardRadius = 28.dp
    val cardShape = RoundedCornerShape(28.dp)
}

/** Design text styles. Sans = Roboto (system), mono = Roboto Mono ([TideFonts.Mono]). */
object M3Type {
    private fun sans(size: Int, line: Int, weight: FontWeight = FontWeight.Normal, tracking: Double = 0.0) = TextStyle(
        fontFamily = TideFonts.Sans, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp, letterSpacing = tracking.sp,
    )
    private fun mono(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) = TextStyle(
        fontFamily = TideFonts.Mono, fontWeight = weight, fontSize = size.sp, lineHeight = line.sp,
    )

    /** "gitsync.md" home wordmark — 22/28 600, -0.3. */
    val wordmark = sans(22, 28, FontWeight.SemiBold, -0.3)
    /** Screen bar title, pinned vault name — 22/28 500. */
    val title = sans(22, 28, FontWeight.Medium)
    /** Hero count ("3") — 57/64 500, -1.5. */
    val display = sans(57, 64, FontWeight.Medium, -1.5)
    /** Onboarding headline — 45/52 500, -0.8. */
    val displayMedium = sans(45, 52, FontWeight.Medium, -0.8)
    /** Upgrade headline — 36/44 500, -0.5. */
    val displaySmall = sans(36, 44, FontWeight.Medium, -0.5)
    /** Hero headline ("1 vault needs you.") — 28/34 500, -0.4. */
    val headline = sans(28, 34, FontWeight.Medium, -0.4)
    /** Smaller hero headline ("The remote moved on…", "Keep writing.") — 24/30 500. */
    val headlineSmall = sans(24, 30, FontWeight.Medium)
    /** Dialog title — 24/32 400. */
    val dialogTitle = sans(24, 32)
    /** Bottom sheet title — 24/32 500. */
    val sheetTitle = sans(24, 32, FontWeight.Medium)
    /** Hero count caption ("changes on this phone") — 18/24. */
    val lead = sans(18, 24)
    /** List headline, body large — 16/24. */
    val body = sans(16, 24)
    /** List headline, emphasised (vault names) — 16/24 500. */
    val bodyStrong = sans(16, 24, FontWeight.Medium)
    /** Supporting text, notes, dialog body — 14/20. */
    val bodyMedium = sans(14, 20)
    /** Settings row subtitle, file meta — 13/18. */
    val bodySmall = sans(13, 18)
    /** Step labels — 15/20. */
    val step = sans(15, 20)
    /** Section label ("Changes · 3") — 14/20 600 in primary. */
    val section = sans(14, 20, FontWeight.SemiBold)
    /** Hero kicker ("Ready to commit") — 12/16 600, +0.4. */
    val kicker = sans(12, 16, FontWeight.SemiBold, 0.4)
    /** Status badge ("In sync") — 12/16 600. */
    val badge = sans(12, 16, FontWeight.SemiBold)
    /** Caption, hints ("tap a file to see the diff") — 12/16. */
    val caption = sans(12, 16)
    /** Buttons — 14/20 600 (40dp and smaller). */
    val button = sans(14, 20, FontWeight.SemiBold)
    /** Buttons — 15/20 600 (48dp filled, FAB). */
    val buttonLarge = sans(15, 20, FontWeight.SemiBold)
    /** Buttons — 16/24 600 (52–56dp). */
    val buttonXLarge = sans(16, 24, FontWeight.SemiBold)
    /** Chips — 14/20 500; compact chips 13/18 500. */
    val chip = sans(14, 20, FontWeight.Medium)
    val chipSmall = sans(13, 18, FontWeight.Medium)
    /** Navigation bar label — 12/16 (500, 700 when selected). */
    val navLabel = sans(12, 16, FontWeight.Medium)

    /** Mono meta line ("main · CodyBontecou/VoxVault") — 12/16. */
    val meta = mono(12, 16)
    val metaStrong = mono(12, 16, FontWeight.SemiBold)
    /** Rail labels ("Phone", "GitHub") — mono 11/16. */
    val metaSmall = mono(11, 16)
    /** Tags ("LFS", "M", "INFO", "origin/main") — mono 11/16 600. */
    val tag = mono(11, 16, FontWeight.SemiBold)
    /** Paths and log lines — mono 13/18. */
    val code = mono(13, 18)
    /** Counts ("↑3 to push", "+8 −3") — mono 13/18 600. */
    val count = mono(13, 18, FontWeight.SemiBold)
    /** Branch names in lists — mono 15/22 600. */
    val branch = mono(15, 22, FontWeight.SemiBold)
    /** Current branch headline — mono 24/32 600. */
    val branchHeadline = mono(24, 32, FontWeight.SemiBold)
}

/** Tonal pairs used by badges, avatars, letter tiles and tags (design `badge(kind)`). */
enum class M3Tone { Ahead, Ok, Muted, Warn, Behind }

/** (container, content) for a tone: ahead tc/onTc, ok okc/onOkc, muted cHighest/onVar, warn ec/onEc, behind pc/onPc. */
@Composable
@ReadOnlyComposable
fun M3Tone.colors(): Pair<Color, Color> {
    val c = M3.colors
    return when (this) {
        M3Tone.Ahead -> c.tc to c.onTc
        M3Tone.Ok -> c.okc to c.onOkc
        M3Tone.Muted -> c.cHighest to c.onVar
        M3Tone.Warn -> c.ec to c.onEc
        M3Tone.Behind -> c.pc to c.onPc
    }
}

/** Page gutter: 16dp on both sides. */
fun Modifier.m3Gutter(): Modifier = padding(horizontal = 16.dp)

/**
 * Grouped-list item shape for lazy lists: the first item gets the top 28dp corners, the last the
 * bottom ones, so separate LazyColumn items read as one [M3ListCard].
 */
fun Modifier.m3GroupedItem(index: Int, count: Int, color: Color, radius: Dp = 28.dp): Modifier {
    val shape: Shape = when {
        count <= 1 -> RoundedCornerShape(radius)
        index == 0 -> RoundedCornerShape(topStart = radius, topEnd = radius)
        index == count - 1 -> RoundedCornerShape(bottomStart = radius, bottomEnd = radius)
        else -> RectangleShape
    }
    return clip(shape).background(color, shape)
}

/** 1dp outlineVar line on top of a row: the design's `border-top` between list rows. */
fun Modifier.m3TopDivider(show: Boolean, color: Color): Modifier =
    if (!show) this else drawBehind {
        val stroke = 1.dp.toPx()
        drawLine(color, Offset(0f, stroke / 2), Offset(size.width, stroke / 2), stroke)
    }

// ─── Icons ──────────────────────────────────────────────────────────────────

/** Design icon: a 24dp Material glyph from res/drawable/ic_m3_*. */
@Composable
fun M3Icon(@DrawableRes icon: Int, modifier: Modifier = Modifier, tint: Color = LocalContentColor.current, size: Dp = 24.dp, contentDescription: String? = null) {
    Icon(painterResource(icon), contentDescription = contentDescription, tint = tint, modifier = modifier.size(size))
}

/** Design icon button: 48dp circle, 24dp glyph in onVar (top-bar actions, row actions). */
@Composable
fun M3IconButton(
    @DrawableRes icon: Int,
    contentDescription: String?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = M3.colors.onVar,
    size: Dp = 48.dp,
    iconSize: Dp = 24.dp,
    enabled: Boolean = true,
) {
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.Center,
    ) { M3Icon(icon, tint = tint, size = iconSize) }
}

// ─── Top bars ───────────────────────────────────────────────────────────────

/** "gitsync.md" wordmark: 22/28 600, the ".md" in primary (Home bar, onboarding). */
@Composable
fun M3Wordmark(modifier: Modifier = Modifier, style: TextStyle = M3Type.wordmark) {
    val c = M3.colors
    val brand = stringResource(R.string.app_name)
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = c.on)) { append("gitsync") }
            withStyle(SpanStyle(color = c.primary)) { append(".md") }
        },
        modifier = modifier.semantics { contentDescription = brand; heading() },
        style = style,
    )
}

/** Initials avatar: 32dp tc/onTc circle, 13/600 (Home bar); 48dp/17sp on the Settings account card. */
@Composable
fun M3Avatar(initials: String, modifier: Modifier = Modifier, size: Dp = 32.dp, container: Color = M3.colors.tc, content: Color = M3.colors.onTc) {
    val fontSize = if (size >= 48.dp) 17.sp else 13.sp
    Box(modifier.size(size).background(container, CircleShape), contentAlignment = Alignment.Center) {
        Text(initials, style = M3Type.badge.copy(fontSize = fontSize, lineHeight = fontSize * 1.2f), color = content, maxLines = 1)
    }
}

/**
 * Home top bar: 64dp, "gitsync.md" wordmark start-aligned, then [actions] (48dp icon buttons,
 * e.g. Add a vault) and the signed-in avatar in a 48dp touch target.
 */
@Composable
fun M3HomeTopBar(
    modifier: Modifier = Modifier,
    avatarInitials: String? = null,
    avatarDescription: String? = null,
    onAvatarClick: (() -> Unit)? = null,
    statusBarPadding: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(M3.colors.surface)
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = 20.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        M3Wordmark(Modifier.weight(1f))
        actions()
        if (avatarInitials != null) {
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .then(if (onAvatarClick != null) Modifier.clickable(role = Role.Button, onClick = onAvatarClick) else Modifier)
                    .then(if (avatarDescription != null) Modifier.semantics { contentDescription = avatarDescription } else Modifier),
                contentAlignment = Alignment.Center,
            ) { M3Avatar(avatarInitials) }
        }
    }
}

/**
 * Screen top bar: 64dp, 48dp back (or close) button, start-aligned 22/28 500 title over an
 * optional mono 12/16 subtitle, then trailing [actions] ([M3IconButton] or [M3TopBarTextAction]).
 */
@Composable
fun M3TopBar(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    @DrawableRes backIcon: Int = R.drawable.ic_arrow_back,
    backLabel: String = stringResource(R.string.publication_back),
    statusBarPadding: Boolean = true,
    /** Test tags for the back button and the title text, so device tests can find them. */
    backTestTag: String? = null,
    titleTestTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = M3.colors
    Row(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .then(if (statusBarPadding) Modifier.statusBarsPadding() else Modifier)
            .heightIn(min = 64.dp)
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) M3IconButton(backIcon, backLabel, onBack,
            if (backTestTag != null) Modifier.testTag(backTestTag) else Modifier, tint = c.on)
        Column(Modifier.weight(1f).padding(start = 4.dp)) {
            Text(title, style = M3Type.title, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.then(if (titleTestTag != null) Modifier.testTag(titleTestTag) else Modifier))
            if (subtitle != null) Text(subtitle, style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        actions()
    }
}

/** Top-bar text action ("Commit", "Abort", "Clear"): 40dp, 14/600 in primary, or error when destructive. */
@Composable
fun M3TopBarTextAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, enabled: Boolean = true) {
    val c = M3.colors
    Box(
        modifier
            .padding(end = 8.dp)
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(20.dp))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = M3Type.button, color = if (!enabled) c.disabled else if (destructive) c.error else c.primary) }
}

// ─── Section labels & containers ────────────────────────────────────────────

/**
 * Section label: 14/20 600 in primary, sentence case, margin 20/20/4. With [count] it reads
 * "label · count"; [trailing] is the right-aligned 12sp hint ("tap a file to see the diff").
 */
@Composable
fun M3SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    count: String? = null,
    trailing: String? = null,
    contentPadding: PaddingValues = PaddingValues(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 4.dp),
    color: Color = M3.colors.primary,
) {
    Row(modifier.fillMaxWidth().padding(contentPadding), verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (count != null) "$text · $count" else text,
            style = M3Type.section, color = color,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (trailing != null) Text(trailing, style = M3Type.caption, color = M3.colors.onVar)
    }
}

/** Overload taking an Int count ("Changes · 3"). */
@Composable
fun M3SectionLabel(text: String, count: Int, modifier: Modifier = Modifier, trailing: String? = null) =
    M3SectionLabel(text, modifier, count = count.toString(), trailing = trailing)

/**
 * Grouped list card: 28dp container on cLow (or another container color), clipping its rows.
 * Put [M3ListRow]s inside with `divider = index > 0`.
 */
@Composable
fun M3ListCard(
    modifier: Modifier = Modifier,
    color: Color = M3.colors.cLow,
    radius: Dp = 28.dp,
    border: BorderStroke? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(color, shape)
            .then(if (border != null) Modifier.border(border, shape) else Modifier),
        content = content,
    )
}

/** Section label + grouped list card with the design's margins (label 20/20/4, card 8dp below, 16dp gutter). */
@Composable
fun M3Group(
    title: String?,
    modifier: Modifier = Modifier,
    count: String? = null,
    trailing: String? = null,
    color: Color = M3.colors.cLow,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxWidth()) {
        if (title != null) M3SectionLabel(title, count = count, trailing = trailing)
        M3ListCard(Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp), color = color, content = content)
    }
}

/** Outlined card ("Latest commit", "Resume publishing"): 28dp, 1dp outlineVar, padding 16/20, optional tap. */
@Composable
fun M3OutlinedCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, M3.colors.outlineVar, shape)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(contentPadding),
        content = content,
    )
}

/** Inset list inside a sheet or dialog: surface fill, 16dp (sheets) or 12dp (dialogs) corners. */
@Composable
fun M3InsetList(modifier: Modifier = Modifier, radius: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    M3ListCard(modifier, color = M3.colors.surface, radius = radius, content = content)

/** Inset list row in a dialog: 44dp, mono 12 path start, 12sp effect end ("restore", "−8 lines"). */
@Composable
fun M3InsetRow(text: String, modifier: Modifier = Modifier, trailing: String? = null, trailingColor: Color = M3.colors.onVar, trailingMono: Boolean = false, divider: Boolean = false) {
    val c = M3.colors
    Row(
        modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).heightIn(min = 44.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = M3Type.meta, color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = if (trailingMono) M3Type.metaStrong else M3Type.caption, color = trailingColor, modifier = Modifier.padding(start = 8.dp))
    }
}

/** Standalone 1dp outlineVar divider. */
@Composable
fun M3Divider(modifier: Modifier = Modifier, color: Color = M3.colors.outlineVar) {
    Box(modifier.fillMaxWidth().height(1.dp).background(color))
}

// ─── List rows ──────────────────────────────────────────────────────────────

/**
 * List row: optional leading (avatar, icon tile, letter tile, icon), a 16/24 title over an optional
 * mono 12/16 meta line, then [trailing] (badge, value, chevron, button, switch). 72dp min with a
 * meta line, 56dp without; padding 8/16; gap 16. Tappable and long-pressable; [divider] draws the
 * outlineVar line above (use it on every row but the first); [selected] fills cHighest.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun M3ListRow(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    metaMono: Boolean = true,
    metaStyle: TextStyle? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    onClickLabel: String? = null,
    onLongClickLabel: String? = null,
    divider: Boolean = false,
    selected: Boolean = false,
    enabled: Boolean = true,
    titleStyle: TextStyle = M3Type.body,
    titleColor: Color = Color.Unspecified,
    titleMaxLines: Int = 2,
    minHeight: Dp = if (meta != null) 72.dp else 56.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
) {
    val c = M3.colors
    val clickable = onClick != null || onLongClick != null
    Row(
        modifier
            .fillMaxWidth()
            .then(if (selected) Modifier.background(c.cHighest) else Modifier)
            .m3TopDivider(divider, c.outlineVar)
            .then(
                if (clickable) Modifier.combinedClickable(
                    enabled = enabled, onClickLabel = onClickLabel, role = Role.Button,
                    onLongClickLabel = onLongClickLabel, onLongClick = onLongClick, onClick = onClick ?: {},
                ) else Modifier
            )
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = minHeight)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) CompositionLocalProvider(LocalContentColor provides c.onVar) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, style = titleStyle, color = if (titleColor == Color.Unspecified) c.on else titleColor, maxLines = titleMaxLines, overflow = TextOverflow.Ellipsis)
            if (meta != null) Text(
                meta,
                style = metaStyle ?: if (metaMono) M3Type.meta else M3Type.bodyMedium,
                color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides c.onVar) { trailing() }
    }
}

/**
 * Settings row (Settings, Vault settings, Background Sync): 56dp min, padding 8/16/8/20, optional
 * 24dp onVar icon, 16/24 title over a 13/18 subtitle, then a 13sp value (max 150dp), a switch
 * ([checked] non-null makes the whole row a toggle), [trailing] content or a chevron.
 */
@Composable
fun M3SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    checked: Boolean? = null,
    onCheckedChange: ((Boolean) -> Unit)? = null,
    onClick: (() -> Unit)? = null,
    chevron: Boolean = false,
    destructive: Boolean = false,
    divider: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = M3.colors
    val interaction = when {
        checked != null && onCheckedChange != null ->
            Modifier.toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
        onClick != null -> Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick)
        else -> Modifier
    }
    Row(
        modifier
            .fillMaxWidth()
            .m3TopDivider(divider, c.outlineVar)
            .then(interaction)
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = 56.dp)
            .padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (icon != null) M3Icon(icon, tint = c.onVar)
        Column(Modifier.weight(1f)) {
            Text(title, style = M3Type.body, color = if (destructive) c.error else c.on)
            if (subtitle != null) Text(subtitle, style = M3Type.bodySmall, color = c.onVar)
        }
        if (value != null) Text(
            value, style = M3Type.bodySmall.copy(textAlign = TextAlign.End), color = c.onVar,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp),
        )
        if (trailing != null) CompositionLocalProvider(LocalContentColor provides c.onVar) { trailing() }
        if (checked != null) M3Switch(checked, onCheckedChange = null, enabled = enabled)
        if (chevron) M3Chevron()
    }
}

/** Leading avatar: 40dp circle in a tone, 16/600 initial (vault rows: In sync = Ok, ↑ = Ahead, Paused = Warn). */
@Composable
fun M3LeadingAvatar(text: String, tone: M3Tone, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val (bg, fg) = tone.colors()
    Box(modifier.size(size).background(bg, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, style = M3Type.bodyStrong.copy(fontWeight = FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

/** Leading icon tile: 40dp, 12dp corners, pc/onPc by default (Start-with rows; files use cHigh/onVar, folders pc/onPc). */
@Composable
fun M3LeadingTile(
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    container: Color = M3.colors.pc,
    content: Color = M3.colors.onPc,
    size: Dp = 40.dp,
    iconSize: Dp = 24.dp,
) {
    Box(modifier.size(size).background(container, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        M3Icon(icon, tint = content, size = iconSize)
    }
}

/** Leading change letter: 40dp, 12dp corners, mono 15/600 (A = Ok, M = Ahead, D = Warn). */
@Composable
fun M3LeadingLetter(letter: String, tone: M3Tone, modifier: Modifier = Modifier) {
    val (bg, fg) = tone.colors()
    Box(modifier.size(40.dp).background(bg, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        Text(letter, style = M3Type.branch.copy(fontSize = 15.sp, lineHeight = 20.sp), color = fg)
    }
}

/** Trailing value: mono 12 onVar, single line, max 170dp ("github.com/CodyBontecou", "4"). */
@Composable
fun M3RowValue(text: String, modifier: Modifier = Modifier, mono: Boolean = true, color: Color = M3.colors.onVar, maxWidth: Dp = 170.dp) {
    Text(text, style = if (mono) M3Type.meta else M3Type.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier.widthIn(max = maxWidth))
}

/** Disclosure chevron: 24dp onVar, mirrored in right-to-left layouts by the drawable. */
@Composable
fun M3Chevron(modifier: Modifier = Modifier, tint: Color = M3.colors.onVar) =
    M3Icon(R.drawable.ic_m3_chevron_right, modifier, tint = tint)

/** Diff stat: mono 13/600 "+8" in addInk and "−3" in delInk; either may be null. */
@Composable
fun M3DiffStat(added: String?, removed: String?, modifier: Modifier = Modifier, style: TextStyle = M3Type.count) {
    val c = M3.colors
    Text(
        buildAnnotatedString {
            if (added != null) withStyle(SpanStyle(color = c.addInk)) { append(added) }
            if (added != null && removed != null) append(" ")
            if (removed != null) withStyle(SpanStyle(color = c.delInk)) { append(removed) }
        },
        modifier = modifier, style = style,
    )
}

/** Mono count in a tint ("↑3 to push" on the pinned card, "yours"/"theirs"): mono 13/600. */
@Composable
fun M3CountText(text: String, modifier: Modifier = Modifier, color: Color = M3.colors.tertiary, style: TextStyle = M3Type.count) {
    Text(text, modifier = modifier, style = style, color = color, maxLines = 1)
}

/** Design switch: 52×32 track; on = primary with a 24dp onPrimary thumb, off = cHighest, 2dp outline, 16dp outline thumb. */
@Composable
fun M3Switch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    contentDescription: String? = null,
) {
    val c = M3.colors
    val thumb by animateDpAsState(if (checked) 24.dp else 16.dp, label = "thumb")
    val start by animateDpAsState(if (checked) 24.dp else 6.dp, label = "thumb_offset")
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier
            .size(width = 52.dp, height = 32.dp)
            .clip(shape)
            .background(if (checked) c.primary else c.cHighest, shape)
            .then(if (checked) Modifier else Modifier.border(2.dp, c.outline, shape))
            .then(
                if (onCheckedChange != null) Modifier.toggleable(checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange)
                else Modifier
            )
            .then(if (contentDescription != null) Modifier.semantics { this.contentDescription = contentDescription } else Modifier)
            .alpha(if (enabled) 1f else 0.38f),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(Modifier.offset(x = start).size(thumb).background(if (checked) c.onPrimary else c.outline, CircleShape))
    }
}

/** Radio indicator: 20dp ring (2dp primary when selected, outline otherwise) with a 10dp primary dot. */
@Composable
fun M3RadioIndicator(selected: Boolean, modifier: Modifier = Modifier) {
    val c = M3.colors
    Box(modifier.size(20.dp).border(2.dp, if (selected) c.primary else c.outline, CircleShape), contentAlignment = Alignment.Center) {
        if (selected) Box(Modifier.size(10.dp).background(c.primary, CircleShape))
    }
}

/** Radio row (merge "From", choices in sheets): 56dp min, padding 6/16/6/12, ring, mono 14/600 title over mono 12 meta. */
@Composable
fun M3RadioRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    meta: String? = null,
    mono: Boolean = true,
    divider: Boolean = false,
    enabled: Boolean = true,
) {
    val c = M3.colors
    Row(
        modifier
            .fillMaxWidth()
            .m3TopDivider(divider, c.outlineVar)
            .selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .alpha(if (enabled) 1f else 0.38f)
            .heightIn(min = 56.dp)
            .padding(start = 12.dp, end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        M3RadioIndicator(selected)
        Column(Modifier.weight(1f)) {
            Text(title, style = if (mono) M3Type.metaStrong.copy(fontSize = 14.sp, lineHeight = 20.sp) else M3Type.body, color = c.on)
            if (meta != null) Text(meta, style = M3Type.meta, color = c.onVar)
        }
    }
}

/** Selectable card (conflict sides): cLow, 20dp corners, padding 16; 2dp primary border when selected, 1dp outlineVar otherwise. */
@Composable
fun M3SelectableCard(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = M3.colors
    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .clip(shape)
            .background(c.cLow, shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) c.primary else c.outlineVar, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .padding(16.dp),
        content = content,
    )
}

/** Checkbox row (commit "Included", discard "Stash a copy first"): 52dp min, 18dp primary checkbox, 15sp label, optional trailing. */
@Composable
fun M3CheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    divider: Boolean = false,
    enabled: Boolean = true,
    contentPadding: PaddingValues = PaddingValues(start = 12.dp, end = 16.dp),
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val c = M3.colors
    Row(
        modifier
            .fillMaxWidth()
            .m3TopDivider(divider, c.outlineVar)
            .toggleable(value = checked, enabled = enabled, role = Role.Checkbox, onValueChange = onCheckedChange)
            .heightIn(min = 52.dp)
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Checkbox(
            checked = checked, onCheckedChange = null, enabled = enabled,
            colors = CheckboxDefaults.colors(checkedColor = c.primary, uncheckedColor = c.onVar, checkmarkColor = c.onPrimary),
        )
        Text(label, style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp), color = c.on, modifier = Modifier.weight(1f))
        trailing?.invoke(this)
    }
}

/** Step state for [M3StepRow]. */
enum class M3StepState { Done, Now, Todo }

/** Sync step row ("Upload to origin/main  66%"): 56dp, padding 8/20, 12dp dot (ok fill / 3dp primary ring / outlineVar ring), 15sp label, mono meta. */
@Composable
fun M3StepRow(label: String, state: M3StepState, modifier: Modifier = Modifier, meta: String? = null, divider: Boolean = false) {
    val c = M3.colors
    Row(
        modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        val dot = Modifier.size(12.dp)
        when (state) {
            M3StepState.Done -> Box(dot.background(c.ok, CircleShape))
            M3StepState.Now -> Box(dot.border(3.dp, c.primary, CircleShape))
            M3StepState.Todo -> Box(dot.border(2.dp, c.outlineVar, CircleShape))
        }
        Text(label, style = M3Type.step, color = if (state == M3StepState.Todo) c.onVar else c.on, modifier = Modifier.weight(1f))
        if (meta != null) Text(meta, style = M3Type.meta, color = c.onVar)
    }
}

/** Dot row ("Recent runs"): 52dp, padding 0/20, 10dp status dot, 14sp label, mono 12 time. */
@Composable
fun M3DotRow(label: String, dotColor: Color, modifier: Modifier = Modifier, trailing: String? = null, divider: Boolean = false) {
    val c = M3.colors
    Row(
        modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).heightIn(min = 52.dp).padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(10.dp).background(dotColor, CircleShape))
        Text(label, style = M3Type.bodyMedium, color = c.on, modifier = Modifier.weight(1f))
        if (trailing != null) Text(trailing, style = M3Type.meta, color = c.onVar)
    }
}

// ─── Heroes ─────────────────────────────────────────────────────────────────

/** Hero card fills (design `section` heroes). */
enum class M3HeroStyle { Inverse, PrimaryContainer, ErrorContainer, OkContainer, Highest, PinnedSoft }

private val LocalM3HeroStyle = staticCompositionLocalOf<M3HeroStyle?> { null }

/**
 * Hero card: 28dp corners, 20dp padding. Inverse = dark inv/onInv in both themes (Home, Vault "Ready
 * to commit"); PrimaryContainer = pc/onPc (syncing, current branch); ErrorContainer = ec/onEc (sign-in
 * expired, push rejected); OkContainer = okc/onOkc (health, tracking removed); Highest = cHighest
 * (offline); PinnedSoft = tcSoft (pinned vault). Use [M3HeroKicker], [M3HeroHeadline], [M3HeroMeta],
 * [M3HeroCount], [M3HeroActions] inside; they pick the right ink for the style.
 */
@Composable
fun M3Hero(
    modifier: Modifier = Modifier,
    style: M3HeroStyle = M3HeroStyle.Inverse,
    contentPadding: PaddingValues = PaddingValues(20.dp),
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val (bg, fg) = m3HeroColors(style)
    val shape = RoundedCornerShape(28.dp)
    CompositionLocalProvider(LocalContentColor provides fg, LocalM3HeroStyle provides style) {
        Column(
            modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg, shape)
                .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
                .padding(contentPadding),
            content = content,
        )
    }
}

@Composable
@ReadOnlyComposable
private fun m3HeroColors(style: M3HeroStyle): Pair<Color, Color> {
    val c = M3.colors
    return when (style) {
        M3HeroStyle.Inverse -> c.inv to c.onInv
        M3HeroStyle.PrimaryContainer -> c.pc to c.onPc
        M3HeroStyle.ErrorContainer -> c.ec to c.onEc
        M3HeroStyle.OkContainer -> c.okc to c.onOkc
        M3HeroStyle.Highest -> c.cHighest to c.on
        M3HeroStyle.PinnedSoft -> c.tcSoft to c.on
    }
}

/** Hero kicker ("Ready to commit", "Push didn't go through"): 12/16 600 +0.4; invAhead on Inverse, tertiary on PinnedSoft. */
@Composable
fun M3HeroKicker(text: String, modifier: Modifier = Modifier, color: Color = Color.Unspecified, @DrawableRes icon: Int? = null) {
    val c = M3.colors
    val ink = when {
        color != Color.Unspecified -> color
        LocalM3HeroStyle.current == M3HeroStyle.Inverse -> c.invAhead
        LocalM3HeroStyle.current == M3HeroStyle.PinnedSoft -> c.tertiary
        else -> LocalContentColor.current
    }
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (icon != null) M3Icon(icon, tint = ink, size = 16.dp)
        Text(text, style = M3Type.kicker, color = ink)
    }
}

/**
 * Hero headline: 28/34 500 (or [M3Type.headlineSmall]). [accent] is appended in the accent ink
 * (invAhead on Inverse — "1 vault <needs you.>"; onVar on Highest — "Keep writing. / We'll catch up.").
 */
@Composable
fun M3HeroHeadline(
    text: String,
    modifier: Modifier = Modifier,
    accent: String? = null,
    accentOnNewLine: Boolean = false,
    accentColor: Color = Color.Unspecified,
    style: TextStyle = M3Type.headline,
) {
    val c = M3.colors
    val base = LocalContentColor.current
    val accentInk = when {
        accentColor != Color.Unspecified -> accentColor
        LocalM3HeroStyle.current == M3HeroStyle.Inverse -> c.invAhead
        LocalM3HeroStyle.current == M3HeroStyle.Highest -> c.onVar
        else -> base.copy(alpha = 0.72f)
    }
    Text(
        buildAnnotatedString {
            append(text)
            if (accent != null) {
                append(if (accentOnNewLine) "\n" else " ")
                withStyle(SpanStyle(color = accentInk)) { append(accent) }
            }
        },
        modifier = modifier.semantics { heading() }, style = style, color = base,
    )
}

/** Full translated quantity sentence, retaining subject and accent as separate styles. */
@Composable
fun M3HeroHeadline(phrase: CountedPhrase, modifier: Modifier = Modifier, style: TextStyle = M3Type.headline) {
    val c = M3.colors
    val base = LocalContentColor.current
    val accent = when (LocalM3HeroStyle.current) {
        M3HeroStyle.Inverse -> c.invAhead
        M3HeroStyle.Highest -> c.onVar
        else -> base.copy(alpha = 0.72f)
    }
    Text(buildAnnotatedString {
        withStyle(SpanStyle(color = accent)) { append(phrase.before) }
        append(phrase.value)
        withStyle(SpanStyle(color = accent)) { append(phrase.after) }
    }, modifier.semantics { heading() }, style = style, color = base)
}

/** Hero meta line: mono 12/16, 6dp above; invMeta on Inverse, onVar on Highest/PinnedSoft, content ink otherwise. */
@Composable
fun M3HeroMeta(text: String, modifier: Modifier = Modifier, topPadding: Dp = 6.dp) {
    val c = M3.colors
    val ink = when (LocalM3HeroStyle.current) {
        M3HeroStyle.Inverse -> c.invMeta
        M3HeroStyle.Highest, M3HeroStyle.PinnedSoft -> c.onVar
        else -> LocalContentColor.current
    }
    Text(text, modifier = modifier.padding(top = topPadding), style = M3Type.meta, color = ink)
}

/** Hero count ("3  changes on this phone"): 57/64 500 number baseline-aligned with an 18/24 label, gap 12. */
@Composable
fun M3HeroCount(count: String, label: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(count, style = M3Type.display, color = LocalContentColor.current, modifier = Modifier.alignByBaseline())
        Text(label, style = M3Type.lead, color = LocalContentColor.current, modifier = Modifier.alignByBaseline())
    }
}

/** Keep the metric's actual value and inflected label from one translated template. */
@Composable
fun M3HeroCount(phrase: CountedPhrase, modifier: Modifier = Modifier) {
    Row(modifier.padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        if (phrase.before.isNotBlank()) Text(phrase.before.trimEnd(), style = M3Type.lead,
            color = LocalContentColor.current, modifier = Modifier.alignByBaseline())
        Text(phrase.value, style = M3Type.display, color = LocalContentColor.current, modifier = Modifier.alignByBaseline())
        if (phrase.after.isNotBlank()) Text(phrase.after.trimStart(), style = M3Type.lead,
            color = LocalContentColor.current, modifier = Modifier.alignByBaseline())
    }
}

/** Hero action row: 20dp above, 8dp between (use [M3ButtonStyle.OnHero] / [M3ButtonStyle.OutlinedOnHero] buttons). */
@Composable
fun M3HeroActions(modifier: Modifier = Modifier, topPadding: Dp = 20.dp, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().padding(top = topPadding), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = content)
}

// ─── Badges, tags, pills ────────────────────────────────────────────────────

/** Status badge ("In sync", "↑3", "↓1", "Paused", "needs access"): 12/16 600, padding 4/10, 8dp corners, tonal fill. */
@Composable
fun M3Badge(text: String, tone: M3Tone, modifier: Modifier = Modifier, mono: Boolean = false) {
    val (bg, fg) = tone.colors()
    Text(
        text,
        modifier = modifier.background(bg, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = if (mono) M3Type.metaStrong else M3Type.badge, color = fg, maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
}

/**
 * Tag ("LFS", "M", "INFO", "ERROR", "v0.4", "origin/main"): mono 11/600, padding 2/8, 6dp corners.
 * A [tone] fills it (M = Ahead, ERROR = Warn, BG = Behind, INFO = Muted); null draws the outlined tag.
 */
@Composable
fun M3Tag(text: String, modifier: Modifier = Modifier, tone: M3Tone? = null) {
    val c = M3.colors
    val shape = RoundedCornerShape(6.dp)
    val (bg, fg) = tone?.colors() ?: (Color.Transparent to c.onVar)
    Text(
        text,
        modifier = modifier
            .then(if (tone == null) Modifier.border(1.dp, c.outlineVar, shape) else Modifier.background(bg, shape))
            .padding(horizontal = 8.dp, vertical = 2.dp),
        style = M3Type.tag, color = fg, maxLines = 1,
    )
}

/** State chip on an error hero ("↑2 yours", "↓1 from Mac · 4m ago"): 32dp, surface fill, mono 13/600 in [color], 8dp corners. */
@Composable
fun M3StateChip(text: String, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 32.dp).background(M3.colors.surface, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, style = M3Type.count, color = color, maxLines = 1) }
}

/** Outlined hero pill ("✓ Cloned"): 28dp, 14dp corners, 1dp border and 13/600 text in [color], optional 16dp icon. */
@Composable
fun M3HeroPill(text: String, color: Color, modifier: Modifier = Modifier, @DrawableRes icon: Int? = R.drawable.ic_m3_check) {
    Row(
        modifier.heightIn(min = 28.dp).border(1.dp, color, RoundedCornerShape(14.dp)).padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) M3Icon(icon, tint = color, size = 16.dp)
        Text(text, style = M3Type.badge.copy(fontSize = 13.sp), color = color)
    }
}

// ─── Chips ──────────────────────────────────────────────────────────────────

/**
 * Filter chip: 32dp, 8dp corners, 14/500; selected = sc/onSc with an 18dp check (start padding 8),
 * unselected = 1dp outlineVar, onVar. [compact] is the 13sp/16dp-check variant (merge strategy,
 * new-file templates). [showCheck] = false gives the owner/log chips. Sits in a 48dp touch target.
 */
@Composable
fun M3FilterChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    showCheck: Boolean = true,
    enabled: Boolean = true,
    role: Role = Role.Tab,
) {
    val c = M3.colors
    val shape = RoundedCornerShape(8.dp)
    val withCheck = selected && showCheck
    val horizontal = when { !showCheck -> 14.dp; compact -> 12.dp; else -> 16.dp }
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(
                enabled = enabled, role = role, onClick = onClick,
                indication = null, interactionSource = remember { MutableInteractionSource() },
            )
            .semantics { this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        Row(
            Modifier
                .heightIn(min = 32.dp)
                .clip(shape)
                .then(if (selected) Modifier.background(c.sc, shape) else Modifier.border(1.dp, c.outlineVar, shape))
                .padding(start = if (withCheck) 8.dp else horizontal, end = horizontal)
                .alpha(if (enabled) 1f else 0.38f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp),
        ) {
            val ink = if (selected) c.onSc else c.onVar
            if (withCheck) M3Icon(R.drawable.ic_m3_check, tint = ink, size = if (compact) 16.dp else 18.dp)
            Text(label, style = if (compact) M3Type.chipSmall else M3Type.chip, color = ink, maxLines = 1)
        }
    }
}

/** Owner / log-level chip ("Yours · Orgs · Starred", "All · Warnings · Errors 2"): filter chip without the check, padding 14. */
@Composable
fun M3OwnerChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) =
    M3FilterChip(label, selected, onClick, modifier, showCheck = false, role = Role.Button)

/** Suggestion chip ("vault backup: Nov 1"): 32dp, 1dp outlineVar, 13/500 onVar, tap to apply. */
@Composable
fun M3SuggestionChip(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(8.dp)
    Box(modifier.heightIn(min = 48.dp).clickable(role = Role.Button, onClick = onClick, indication = null, interactionSource = remember { MutableInteractionSource() }), contentAlignment = Alignment.Center) {
        Box(Modifier.heightIn(min = 32.dp).clip(shape).border(1.dp, c.outlineVar, shape).padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
            Text(label, style = M3Type.chipSmall, color = c.onVar, maxLines = 1)
        }
    }
}

/** Static info chip ("✕ Force-push" under "It will never"): 32dp, 1dp outlineVar, 16dp icon, 13sp onVar. */
@Composable
fun M3InfoChip(label: String, modifier: Modifier = Modifier, @DrawableRes icon: Int? = R.drawable.ic_m3_close) {
    val c = M3.colors
    Row(
        modifier.heightIn(min = 32.dp).border(1.dp, c.outlineVar, RoundedCornerShape(8.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) M3Icon(icon, tint = c.onVar, size = 16.dp)
        Text(label, style = M3Type.bodySmall, color = c.onVar)
    }
}

/** Horizontally scrolling chip row, 8dp apart; default padding offsets the chips' 48dp touch targets to the design's 16/16/4. */
@Composable
fun M3ChipRow(
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp),
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(contentPadding),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

// ─── Buttons ────────────────────────────────────────────────────────────────

/**
 * Button fills: Filled = primary; Tonal = sc/onSc; PrimaryContainer = pc/onPc; Outlined = 1dp outline
 * with primary text; Text = primary text; Danger = error/onError; OnHero = onInv fill with inv text
 * (hero "Commit & push"); OutlinedOnHero = invLine border, onInv text (hero "Pull"); OutlinedContent =
 * border and text in the current content color (e.g. "Cancel" on a pc hero).
 */
enum class M3ButtonStyle { Filled, Tonal, PrimaryContainer, Outlined, Text, Danger, OnHero, OutlinedOnHero, OutlinedContent }

/** Button heights (pill shapes): XSmall 32 ("Fetch"), Small 36 (branch "Switch"), Medium 40, Large 48, XLarge 52 (footer), Hero 56 (onboarding, sheet). */
enum class M3ButtonSize(val height: Dp, val horizontalPadding: Dp) {
    XSmall(32.dp, 12.dp), Small(36.dp, 16.dp), Medium(40.dp, 24.dp), Large(48.dp, 20.dp), XLarge(52.dp, 24.dp), Hero(56.dp, 24.dp)
}

/**
 * Design button: pill-shaped (radius = height/2 unless [shape] is given), centered label, optional
 * leading [icon] and a mono [trailingMeta] ("↑3" at 80% opacity). [destructive] turns Filled into
 * error and Outlined/Text into error ink. Use `Modifier.fillMaxWidth()` or `weight(1f)` for wide ones.
 */
@Composable
fun M3Button(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: M3ButtonStyle = M3ButtonStyle.Filled,
    size: M3ButtonSize = M3ButtonSize.Medium,
    @DrawableRes icon: Int? = null,
    trailingMeta: String? = null,
    enabled: Boolean = true,
    loading: Boolean = false,
    destructive: Boolean = false,
    shape: Shape? = null,
) {
    val c = M3.colors
    val content = LocalContentColor.current
    val outlined = style in setOf(M3ButtonStyle.Outlined, M3ButtonStyle.OutlinedOnHero, M3ButtonStyle.OutlinedContent)
    val (fill, ink, stroke) = when (style) {
        M3ButtonStyle.Filled -> Triple(if (destructive) c.error else c.primary, if (destructive) c.onError else c.onPrimary, null)
        M3ButtonStyle.Tonal -> Triple(c.sc, c.onSc, null)
        M3ButtonStyle.PrimaryContainer -> Triple(c.pc, c.onPc, null)
        M3ButtonStyle.Outlined -> Triple(Color.Transparent, if (destructive) c.error else c.primary, c.outline)
        M3ButtonStyle.Text -> Triple(Color.Transparent, if (destructive) c.error else c.primary, null)
        M3ButtonStyle.Danger -> Triple(c.error, c.onError, null)
        M3ButtonStyle.OnHero -> Triple(c.onInv, c.inv, null)
        M3ButtonStyle.OutlinedOnHero -> Triple(Color.Transparent, c.onInv, c.invLine)
        M3ButtonStyle.OutlinedContent -> Triple(Color.Transparent, content, content)
    }
    val filled = fill != Color.Transparent
    val active = enabled && !loading
    val drawFill = if (!enabled && filled) c.disabledContainer else fill
    val drawInk = if (!enabled) c.disabled else ink
    val drawStroke = if (!enabled && stroke != null) c.disabledContainer else stroke
    val buttonShape = shape ?: RoundedCornerShape(size.height / 2)
    val labelStyle = when (size) {
        M3ButtonSize.XSmall -> M3Type.button.copy(fontSize = 13.sp, lineHeight = 18.sp)
        M3ButtonSize.Small, M3ButtonSize.Medium -> M3Type.button
        M3ButtonSize.Large -> if (filled) M3Type.buttonLarge else M3Type.button
        M3ButtonSize.XLarge, M3ButtonSize.Hero -> M3Type.buttonXLarge
    }
    val padding = when {
        style == M3ButtonStyle.Text -> 12.dp
        style == M3ButtonStyle.Tonal && size == M3ButtonSize.Medium -> 16.dp
        size == M3ButtonSize.Medium && outlined -> 16.dp
        else -> size.horizontalPadding
    }
    val iconSize = if (size.height >= 48.dp) 20.dp else 18.dp
    Row(
        modifier
            .heightIn(min = size.height)
            .clip(buttonShape)
            .background(drawFill, buttonShape)
            .then(if (drawStroke != null) Modifier.border(1.dp, drawStroke, buttonShape) else Modifier)
            .clickable(enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = padding),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(18.dp), color = drawInk, strokeWidth = 2.dp)
        } else {
            if (icon != null) {
                M3Icon(icon, tint = drawInk, size = iconSize)
                Spacer(Modifier.width(if (size.height >= 48.dp) 8.dp else 6.dp))
            }
            Text(text, style = labelStyle, color = drawInk, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (trailingMeta != null) {
                Spacer(Modifier.width(10.dp))
                Text(trailingMeta, style = M3Type.count.copy(fontWeight = FontWeight.Medium), color = drawInk.copy(alpha = drawInk.alpha * 0.8f), maxLines = 1)
            }
        }
    }
}

/** Primary filled button (primary/onPrimary pill). */
@Composable
fun M3FilledButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: M3ButtonSize = M3ButtonSize.Large, @DrawableRes icon: Int? = null,
    trailingMeta: String? = null, enabled: Boolean = true, loading: Boolean = false, destructive: Boolean = false) =
    M3Button(text, onClick, modifier, M3ButtonStyle.Filled, size, icon, trailingMeta, enabled, loading, destructive)

/** Tonal button (sc/onSc pill: "Clone", "Copy public key", "Fetch" with size XSmall). */
@Composable
fun M3TonalButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: M3ButtonSize = M3ButtonSize.Medium, @DrawableRes icon: Int? = null, enabled: Boolean = true, loading: Boolean = false) =
    M3Button(text, onClick, modifier, M3ButtonStyle.Tonal, size, icon, null, enabled, loading)

/** Outlined button (1dp outline, primary text — "Review first", "Keep both"; destructive = error text — "Discard changes"). */
@Composable
fun M3OutlinedButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: M3ButtonSize = M3ButtonSize.Large, @DrawableRes icon: Int? = null,
    enabled: Boolean = true, loading: Boolean = false, destructive: Boolean = false) =
    M3Button(text, onClick, modifier, M3ButtonStyle.Outlined, size, icon, null, enabled, loading, destructive)

/** Text button (14/600 primary, 12dp padding — "Not now", "Commit locally", dialog actions). */
@Composable
fun M3TextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, size: M3ButtonSize = M3ButtonSize.Medium, enabled: Boolean = true, destructive: Boolean = false) =
    M3Button(text, onClick, modifier, M3ButtonStyle.Text, size, null, null, enabled, false, destructive)

// ─── FABs ───────────────────────────────────────────────────────────────────

/** Extended FAB fills: Tonal = pc/onPc ("Sync all"), Primary = primary ("Add your first vault"), Flat = disabled, cHighest/onVar, no shadow ("Syncs when online"). */
enum class M3FabStyle { Tonal, Primary, Flat }

/**
 * Extended FAB: 56dp, 16dp corners, padding 16/20, 24dp icon, 15/600 label, gap 10, optional mono
 * 12/500 [meta] at 80% ("↓0 ↑3"); level-3 shadow except Flat. Place it 16dp from the end, 24dp from the bottom.
 */
@Composable
fun M3ExtendedFab(
    text: String,
    @DrawableRes icon: Int,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    meta: String? = null,
    style: M3FabStyle = M3FabStyle.Tonal,
) {
    val c = M3.colors
    val (fill, ink) = when (style) {
        M3FabStyle.Tonal -> c.pc to c.onPc
        M3FabStyle.Primary -> c.primary to c.onPrimary
        M3FabStyle.Flat -> c.cHighest to c.onVar
    }
    val shape = RoundedCornerShape(16.dp)
    val enabled = style != M3FabStyle.Flat && onClick != null
    Row(
        modifier
            .then(if (style == M3FabStyle.Flat) Modifier else Modifier.shadow(6.dp, shape))
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(fill, shape)
            .then(if (enabled) Modifier.clickable(role = Role.Button, onClick = onClick!!) else Modifier)
            .padding(start = 16.dp, end = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        M3Icon(icon, tint = ink)
        Text(text, style = M3Type.buttonLarge, color = ink, maxLines = 1)
        if (meta != null) Text(meta, style = M3Type.meta.copy(fontWeight = FontWeight.Medium), color = ink.copy(alpha = 0.8f), maxLines = 1)
    }
}

/** Square FAB: 56dp, 16dp corners, pc/onPc, level-3 shadow ("New file" on Files). */
@Composable
fun M3Fab(@DrawableRes icon: Int, contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.shadow(6.dp, shape).size(56.dp).clip(shape).background(c.pc, shape)
            .clickable(role = Role.Button, onClick = onClick).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) { M3Icon(icon, tint = c.onPc) }
}

// ─── Segmented control ──────────────────────────────────────────────────────

/**
 * Segmented control (Write/Preview, Changes/Whole file/Preview, SSH key/Token/None, Note/Folder):
 * 40dp, 1dp outline, 20dp corners; the selected segment is sc/onSc 14/600 with an 18dp check,
 * the others transparent 14/500 in on; 1dp outline separators.
 */
@Composable
fun M3Segmented(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    role: Role = Role.Tab,
) {
    val c = M3.colors
    val shape = RoundedCornerShape(20.dp)
    Row(
        // IntrinsicSize.Min bounds the row to its tallest segment, so the fillMaxHeight dividers cannot
        // grow to the parent's max height (in a non-scrolling Column that starved weighted siblings).
        modifier.fillMaxWidth().height(IntrinsicSize.Min).heightIn(min = 40.dp).clip(shape).border(1.dp, c.outline, shape).alpha(if (enabled) 1f else 0.38f),
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            if (index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.outline))
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 40.dp)
                    .then(if (selected) Modifier.background(c.sc) else Modifier)
                    .selectable(selected = selected, enabled = enabled, role = role) { onSelect(index) }
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center,
            ) {
                val ink = if (selected) c.onSc else c.on
                if (selected) {
                    M3Icon(R.drawable.ic_m3_check, tint = ink, size = 18.dp)
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    option, style = M3Type.button.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium),
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

// ─── Notes ──────────────────────────────────────────────────────────────────

/**
 * Note tones (design `note(kind)`): Info = pc/onPc, Warn = ec/onEc, Ahead = tcSoft with a tertiary
 * icon and on text, Ok = okc/onOkc, Muted = cHighest/onVar, Plain = no fill, onVar (the
 * "You can leave this screen" hint).
 */
enum class M3NoteTone { Info, Warn, Ahead, Ok, Muted, Plain }

/** Inline note: padding 12/16, 16dp corners, 20dp icon, 14/20 text, optional 14/600 title and action below. */
@Composable
fun M3Note(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    tone: M3NoteTone = M3NoteTone.Info,
    @DrawableRes icon: Int? = null,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    val c = M3.colors
    val (bg, ink, iconInk) = when (tone) {
        M3NoteTone.Info -> Triple(c.pc, c.onPc, c.onPc)
        M3NoteTone.Warn -> Triple(c.ec, c.onEc, c.onEc)
        M3NoteTone.Ahead -> Triple(c.tcSoft, c.on, c.tertiary)
        M3NoteTone.Ok -> Triple(c.okc, c.onOkc, c.onOkc)
        M3NoteTone.Muted -> Triple(c.cHighest, c.onVar, c.onVar)
        M3NoteTone.Plain -> Triple(Color.Transparent, c.onVar, c.onVar)
    }
    val glyph = icon ?: when (tone) {
        M3NoteTone.Warn -> R.drawable.ic_m3_warning
        M3NoteTone.Ok -> R.drawable.ic_m3_check
        else -> R.drawable.ic_m3_info
    }
    Row(
        modifier.fillMaxWidth().background(bg, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        M3Icon(glyph, tint = iconInk, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (title != null) Text(title, style = M3Type.section, color = ink)
            Text(text, style = M3Type.bodyMedium, color = ink)
            if (action != null) Box(Modifier.padding(top = 6.dp)) { CompositionLocalProvider(LocalContentColor provides ink) { action() } }
        }
    }
}

@Composable
fun M3Note(
    text: String,
    modifier: Modifier = Modifier,
    tone: M3NoteTone = M3NoteTone.Info,
    @DrawableRes icon: Int? = null,
    title: String? = null,
    action: (@Composable () -> Unit)? = null,
) = M3Note(AnnotatedString(text), modifier, tone, icon, title, action)

/** Code block ("! [rejected] main -> main", an SSH public key): padding 10/12, 12dp corners, cHighest, mono 12. */
@Composable
fun M3CodeBlock(text: String, modifier: Modifier = Modifier, color: Color = M3.colors.onVar, lineHeight: Int = 16) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().background(M3.colors.cHighest, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp),
        style = M3Type.meta.copy(lineHeight = lineHeight.sp), color = color,
    )
}

// ─── Text fields ────────────────────────────────────────────────────────────

/**
 * Outlined text field: 56dp, 4dp corners, 1dp outline (2dp primary focused, error when [isError]),
 * floating 12sp label. [mono] uses Roboto Mono 14 (URLs, branch names); [suffix] shows ".md".
 */
@Composable
fun M3TextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    supportingText: String? = null,
    isError: Boolean = false,
    mono: Boolean = false,
    singleLine: Boolean = true,
    minLines: Int = 1,
    enabled: Boolean = true,
    suffix: String? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
) {
    val c = M3.colors
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier.fillMaxWidth(),
        enabled = enabled,
        textStyle = if (mono) M3Type.code.copy(fontSize = 14.sp, lineHeight = 20.sp, color = c.on) else M3Type.body.copy(color = c.on),
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it, style = if (mono) M3Type.code.copy(fontSize = 14.sp) else M3Type.body) } },
        supportingText = supportingText?.let { { Text(it, style = M3Type.caption) } },
        suffix = suffix?.let { { Text(it, style = M3Type.code.copy(fontSize = 14.sp), color = c.onVar) } },
        trailingIcon = trailingIcon,
        isError = isError,
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = keyboardOptions,
        keyboardActions = keyboardActions,
        shape = RoundedCornerShape(4.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Color.Transparent, unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent, errorContainerColor = Color.Transparent,
            focusedBorderColor = c.primary, unfocusedBorderColor = c.outline, disabledBorderColor = c.disabledContainer, errorBorderColor = c.error,
            focusedLabelColor = c.primary, unfocusedLabelColor = c.onVar, errorLabelColor = c.error,
            cursorColor = c.primary, errorCursorColor = c.error,
            focusedTextColor = c.on, unfocusedTextColor = c.on,
            focusedPlaceholderColor = c.onVar, unfocusedPlaceholderColor = c.onVar,
            focusedSupportingTextColor = c.onVar, unfocusedSupportingTextColor = c.onVar, errorSupportingTextColor = c.error,
        ),
    )
}

/** Search field: 56dp, 28dp corners, cHigh, 24dp search icon in onVar, 16sp text. */
@Composable
fun M3SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    trailing: (@Composable () -> Unit)? = null,
) {
    val c = M3.colors
    Row(
        modifier.fillMaxWidth().heightIn(min = 56.dp).background(c.cHigh, RoundedCornerShape(28.dp)).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        M3Icon(R.drawable.ic_m3_search, tint = c.onVar)
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) Text(placeholder, style = M3Type.body, color = c.onVar, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = M3Type.body.copy(color = c.on), cursorBrush = SolidColor(c.primary),
                keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
                modifier = Modifier.fillMaxWidth().semantics { contentDescription = placeholder },
            )
        }
        trailing?.invoke()
    }
}

// ─── Bottom bars ────────────────────────────────────────────────────────────

/**
 * Bottom pinned action bar ("Test & clone", "Run now", "Share diagnostics" + "Copy visible entries"):
 * surface, 1dp outlineVar top border, padding 12/16/20, 8dp between; pads for the navigation bar.
 * Typical content: an [M3FilledButton] with size XLarge and an optional [M3TextButton].
 */
@Composable
fun M3BottomActionBar(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val c = M3.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface)
            .m3TopDivider(true, c.outlineVar)
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

/** Bottom toolbar (Diff: "Discard" … "Open in editor"): 80dp on the c container, padding 0/16, 8dp between. */
@Composable
fun M3BottomToolbar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().background(M3.colors.c).navigationBarsPadding().heightIn(min = 80.dp).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

// ─── Modal host, sheet, dialog ──────────────────────────────────────────────

@Composable
private fun m3SystemBarPadding(): PaddingValues {
    val view = LocalView.current
    val density = LocalDensity.current
    val insets = view.rootWindowInsets?.let { androidx.core.view.WindowInsetsCompat.toWindowInsetsCompat(it, view) }
        ?.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
    return with(density) { PaddingValues(top = (insets?.top ?: 0).toDp(), bottom = (insets?.bottom ?: 0).toDp()) }
}

/** A full-window dialog with the design scrim that keeps the caller's locale, direction and density. */
@Composable
private fun M3ModalHost(
    onDismissRequest: () -> Unit,
    dismissOnClickOutside: Boolean,
    dismissOnBackPress: Boolean,
    alignment: Alignment,
    content: @Composable BoxScope.(bars: PaddingValues) -> Unit,
) {
    val parentContext = LocalContext.current
    val parentConfiguration = LocalConfiguration.current
    val parentDirection = LocalLayoutDirection.current
    val parentDensity = LocalDensity.current
    val tide = LocalTideColors.current
    val bars = m3SystemBarPadding()
    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            dismissOnBackPress = dismissOnBackPress, dismissOnClickOutside = dismissOnClickOutside,
            usePlatformDefaultWidth = false, decorFitsSystemWindows = false,
        ),
    ) {
        CompositionLocalProvider(
            LocalContext provides parentContext,
            LocalConfiguration provides parentConfiguration,
            LocalLayoutDirection provides parentDirection,
            LocalDensity provides parentDensity,
            LocalTideColors provides tide,
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(M3.colors.scrim)
                    .pointerInput(dismissOnClickOutside) { if (dismissOnClickOutside) detectTapGestures { onDismissRequest() } },
                contentAlignment = alignment,
            ) { content(bars) }
        }
    }
}

/**
 * Modal bottom sheet ("Commit 3 files", "Merge into main"): cLow, 28dp top corners, padding 0/20/24,
 * 32×4 outline handle (16 above, 12 below), 24/32 500 title with an optional mono 12 [titleMeta] at
 * the end. The body scrolls; [footer] (primary 56dp button + text button) stays pinned below it.
 */
@Composable
fun M3ModalSheet(
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    titleMeta: String? = null,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    M3ModalHost(onDismissRequest, dismissOnClickOutside, dismissOnBackPress, Alignment.BottomCenter) { bars ->
        M3SheetSurface(
            modifier.statusBarsPadding().padding(top = bars.calculateTopPadding() + 48.dp).imePadding(),
            title, titleMeta, footer, bottomInset = bars.calculateBottomPadding(), content = content,
        )
    }
}

/** The sheet panel itself (for previews and in-layout sheets); [M3ModalSheet] hosts it over a scrim. */
@Composable
fun M3SheetSurface(
    modifier: Modifier = Modifier,
    title: String? = null,
    titleMeta: String? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    bottomInset: Dp = 0.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = M3.colors
    val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    Column(
        modifier
            .widthIn(max = 640.dp)
            .fillMaxWidth()
            .background(c.cLow, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, bottom = 24.dp + bottomInset)
            .semantics { if (title != null) paneTitle = title },
    ) {
        Box(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 12.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(width = 32.dp, height = 4.dp).background(c.outline, RoundedCornerShape(2.dp)))
        }
        if (title != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, style = M3Type.sheetTitle, color = c.on, modifier = Modifier.weight(1f).semantics { heading() })
                if (titleMeta != null) Text(titleMeta, style = M3Type.meta, color = c.onVar, modifier = Modifier.padding(start = 12.dp))
            }
        }
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), content = content)
        if (footer != null) Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp), content = footer)
    }
}

/**
 * M3 dialog ("Discard 3 changes?", "Delete branch?", "New note"): cHigh, 28dp corners, 24dp padding,
 * 24dp from the screen edges; optional centered 24dp [icon] (16 below), 24/32 400 title (centered
 * with an icon), 14/20 onVar [text], extra [content], then right-aligned [actions] 24dp below
 * ([M3DialogAction]); [stackedActions] stacks full-width buttons instead ("Can't switch yet").
 */
@Composable
fun M3Dialog(
    onDismissRequest: () -> Unit,
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color = Color.Unspecified,
    text: String? = null,
    stackedActions: Boolean = false,
    dismissOnClickOutside: Boolean = true,
    dismissOnBackPress: Boolean = true,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    M3ModalHost(onDismissRequest, dismissOnClickOutside, dismissOnBackPress, Alignment.Center) { bars ->
        M3DialogSurface(
            title,
            modifier.padding(top = bars.calculateTopPadding(), bottom = bars.calculateBottomPadding()).statusBarsPadding().navigationBarsPadding()
                .padding(horizontal = 24.dp, vertical = 24.dp).imePadding(),
            icon, iconTint, text, stackedActions, content, actions,
        )
    }
}

/** The dialog panel itself (for previews); [M3Dialog] hosts it over a scrim. */
@Composable
fun M3DialogSurface(
    title: String,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    iconTint: Color = Color.Unspecified,
    text: String? = null,
    stackedActions: Boolean = false,
    content: (@Composable ColumnScope.() -> Unit)? = null,
    actions: @Composable () -> Unit,
) {
    val c = M3.colors
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier
            .widthIn(min = 280.dp, max = 560.dp)
            .fillMaxWidth()
            .background(c.cHigh, shape)
            .pointerInput(Unit) { detectTapGestures { } }
            .padding(24.dp)
            .semantics { paneTitle = title },
    ) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
            if (icon != null) M3Icon(
                icon, tint = if (iconTint == Color.Unspecified) c.primary else iconTint,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(bottom = 16.dp),
            )
            Text(
                title, style = M3Type.dialogTitle, color = c.on,
                textAlign = if (icon != null) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth().semantics { heading() },
            )
            if (text != null) Text(text, style = M3Type.bodyMedium, color = c.onVar, modifier = Modifier.padding(top = 16.dp))
            if (content != null) {
                CompositionLocalProvider(LocalContentColor provides c.onVar, LocalTextStyle provides M3Type.bodyMedium) {
                    Column(Modifier.fillMaxWidth().padding(top = 16.dp), content = content)
                }
            }
        }
        if (stackedActions) {
            Column(Modifier.fillMaxWidth().padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(top = 24.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically,
            ) { actions() }
        }
    }
}

/** Dialog action: text button (40dp, padding 12, 14/600 primary) or, with [filled], a 40dp pill (primary, or error when [destructive]). */
@Composable
fun M3DialogAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, destructive: Boolean = false, filled: Boolean = false, enabled: Boolean = true, loading: Boolean = false) {
    val style = when {
        filled && destructive -> M3ButtonStyle.Danger
        filled -> M3ButtonStyle.Filled
        else -> M3ButtonStyle.Text
    }
    M3Button(text, onClick, modifier, style, M3ButtonSize.Medium, enabled = enabled, loading = loading, destructive = destructive && !filled)
}

// ─── Menus ──────────────────────────────────────────────────────────────────

/** Dropdown menu (long-press file actions): c container, 8dp corners, 8dp vertical padding, 200dp wide. */
@Composable
fun M3DropdownMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    offset: androidx.compose.ui.unit.DpOffset = androidx.compose.ui.unit.DpOffset(0.dp, 0.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = M3.colors
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(surface = c.c),
        shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(8.dp)),
    ) {
        DropdownMenu(expanded = expanded, onDismissRequest = onDismissRequest, modifier = modifier.widthIn(min = 200.dp).background(c.c), offset = offset, content = content)
    }
}

/** Menu item: 48dp, padding 0/12, 20dp icon, 14sp; error ink when [destructive] ("Delete"). */
@Composable
fun M3MenuItem(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, @DrawableRes icon: Int? = null, destructive: Boolean = false, enabled: Boolean = true) {
    val c = M3.colors
    val ink = if (!enabled) c.disabled else if (destructive) c.error else c.on
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (icon != null) M3Icon(icon, tint = ink, size = 20.dp)
        Text(text, style = M3Type.bodyMedium, color = ink)
    }
}

// ─── Progress ───────────────────────────────────────────────────────────────

/** Where a progress bar sits: Surface (cHighest track, primary), PrimaryContainer (pcTrack, primaryOnPc), Hero (invLine, invBehind). */
enum class M3ProgressTone { Surface, PrimaryContainer, Hero }

@Composable
@ReadOnlyComposable
private fun m3ProgressColors(tone: M3ProgressTone): Pair<Color, Color> {
    val c = M3.colors
    return when (tone) {
        M3ProgressTone.Surface -> c.cHighest to c.primary
        M3ProgressTone.PrimaryContainer -> c.pcTrack to c.primaryOnPc
        M3ProgressTone.Hero -> c.invLine to c.invBehind
    }
}

/** Determinate progress bar: 4dp, 2dp corners, [progress] in 0..1. */
@Composable
fun M3ProgressBar(progress: Float, modifier: Modifier = Modifier, tone: M3ProgressTone = M3ProgressTone.Surface, description: String? = null) {
    val (track, bar) = m3ProgressColors(tone)
    val fraction = progress.coerceIn(0f, 1f)
    Box(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(track)
            .semantics { if (description != null) contentDescription = description; progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo(fraction, 0f..1f) },
    ) { Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().clip(RoundedCornerShape(2.dp)).background(bar)) }
}

/** Indeterminate progress bar: 4dp track with a sliding 40% segment. */
@Composable
fun M3IndeterminateProgressBar(modifier: Modifier = Modifier, tone: M3ProgressTone = M3ProgressTone.Surface, description: String? = null) {
    val (track, bar) = m3ProgressColors(tone)
    val transition = rememberInfiniteTransition(label = "m3-indeterminate")
    val head by transition.animateFloat(-0.4f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing), RepeatMode.Restart), label = "head")
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp))
            .semantics { if (description != null) contentDescription = description; progressBarRangeInfo = androidx.compose.ui.semantics.ProgressBarRangeInfo.Indeterminate },
    ) {
        drawRect(track)
        val start = (head.coerceAtLeast(0f)) * size.width
        val end = ((head + 0.4f).coerceAtMost(1f)) * size.width
        if (end > start) {
            val left = if (rtl) size.width - end else start
            drawRoundRect(bar, topLeft = Offset(left, 0f), size = androidx.compose.ui.geometry.Size(end - start, size.height),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(2.dp.toPx()))
        }
    }
}

/** Step progress (conflict "1 of 2"): [count] 4dp segments, 4dp apart; the first [done] in primary, the rest pc. */
@Composable
fun M3StepProgress(count: Int, done: Int, modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(count) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i < done) c.primary else c.pc, RoundedCornerShape(2.dp))) }
    }
}

/** Small spinner (14dp, 2dp stroke) for inline "Cloning repository" lines. */
@Composable
fun M3Spinner(modifier: Modifier = Modifier, color: Color = LocalContentColor.current, size: Dp = 14.dp) {
    CircularProgressIndicator(modifier.size(size), color = color, strokeWidth = 2.dp)
}

// ─── Snackbar ───────────────────────────────────────────────────────────────

/** Snackbar: inv/onInv, 8dp corners, 48dp min, 14/20 message, optional 14/600 action in invBehind; place 16dp from the sides, 24dp from the bottom. */
@Composable
fun M3Snackbar(message: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    val c = M3.colors
    val shape = RoundedCornerShape(8.dp)
    Row(
        modifier
            .fillMaxWidth()
            .shadow(4.dp, shape)
            .background(c.inv, shape)
            .heightIn(min = 48.dp)
            .padding(start = 16.dp, end = if (actionLabel != null) 8.dp else 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(message, style = M3Type.bodyMedium, color = c.onInv, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
        if (actionLabel != null && onAction != null) {
            Box(
                Modifier.heightIn(min = 36.dp).clip(RoundedCornerShape(18.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) { Text(actionLabel, style = M3Type.button, color = c.invBehind) }
        }
    }
}

/** Material [SnackbarHostState] rendered as [M3Snackbar], with the design's 16/24 placement. */
@Composable
fun M3SnackbarHost(hostState: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(hostState, modifier.navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp)) { data ->
        M3Snackbar(data.visuals.message, actionLabel = data.visuals.actionLabel, onAction = { data.performAction() })
    }
}

// ─── Sync rail illustration ─────────────────────────────────────────────────

/** Where a rail node sits: by edge, center, an offset from either end (left edge), or a fraction of the width. */
sealed interface M3RailPosition {
    data object Start : M3RailPosition
    data object End : M3RailPosition
    data object Center : M3RailPosition
    data class FromStart(val offset: Dp) : M3RailPosition
    data class FromEnd(val offset: Dp) : M3RailPosition
    data class Fraction(val fraction: Float, val fromEnd: Boolean = false) : M3RailPosition
}

/** A rail node: a filled dot, or a 2dp ring filled with [fill] (the background behind the rail). */
@Immutable
data class M3RailNode(val position: M3RailPosition, val color: Color, val size: Dp = 14.dp, val ring: Boolean = false, val fill: Color = Color.Transparent)

/** A whole rail: its nodes, the line between the end nodes (inset 6dp), dashed or solid. */
@Immutable
data class M3RailSpec(val nodes: List<M3RailNode>, val lineColor: Color, val dashed: Boolean = false, val height: Dp = 14.dp)

/** Ready-made rails from the boards. [onHero] picks the inverse inks; [background] fills rings. */
enum class M3RailState { Empty, Pending, Incoming, InSync, Onboarding }

/** Builds the boards' rails: Empty (dashed, two rings), Pending (phone filled + up to 3 dots, GitHub ring), Incoming (phone ring, dots and GitHub filled in behind), InSync (all ok), Onboarding (phone dots, center ring, GitHub rings). */
@Composable
@ReadOnlyComposable
fun m3RailSpec(state: M3RailState, onHero: Boolean = true, dots: Int = 3, background: Color = Color.Unspecified): M3RailSpec {
    val c = M3.colors
    val bg = if (background != Color.Unspecified) background else if (onHero) c.inv else c.surface
    val ahead = if (onHero) c.invAhead else c.ahead
    val behind = if (onHero) c.invBehind else c.behind
    val meta = if (onHero) c.invMeta else c.onVar
    val line = if (onHero) c.invLine else c.outlineVar
    val ok = if (onHero) c.invOk else c.ok
    val dotNodes = { color: Color -> (0 until dots.coerceIn(0, 3)).map { M3RailNode(M3RailPosition.FromStart(22.dp + 14.dp * it), color, 8.dp) } }
    return when (state) {
        M3RailState.Empty -> M3RailSpec(
            listOf(M3RailNode(M3RailPosition.Start, meta, ring = true, fill = bg), M3RailNode(M3RailPosition.End, meta, ring = true, fill = bg)),
            line, dashed = true,
        )
        M3RailState.Pending -> M3RailSpec(
            listOf(M3RailNode(M3RailPosition.Start, ahead)) + dotNodes(ahead) + M3RailNode(M3RailPosition.End, behind, ring = true, fill = bg),
            line,
        )
        M3RailState.Incoming -> M3RailSpec(
            listOf(M3RailNode(M3RailPosition.Start, meta, ring = true, fill = bg)) + dotNodes(behind) + M3RailNode(M3RailPosition.End, behind),
            line,
        )
        M3RailState.InSync -> M3RailSpec(listOf(M3RailNode(M3RailPosition.Start, ok), M3RailNode(M3RailPosition.End, ok)), ok)
        M3RailState.Onboarding -> M3RailSpec(
            listOf(
                M3RailNode(M3RailPosition.Start, ahead),
                M3RailNode(M3RailPosition.Fraction(0.24f), ahead, 10.dp),
                M3RailNode(M3RailPosition.Center, c.onInv, 28.dp, ring = true, fill = bg),
                M3RailNode(M3RailPosition.Fraction(0.24f, fromEnd = true), behind, 10.dp, ring = true, fill = bg),
                M3RailNode(M3RailPosition.End, behind, ring = true, fill = bg),
            ),
            line, height = 28.dp,
        )
    }
}

/** Sync rail: a 2dp line between two end nodes with dots along it (phone on the start side, GitHub on the end). Mirrors in RTL. */
@Composable
fun M3SyncRail(spec: M3RailSpec, modifier: Modifier = Modifier, description: String? = null) {
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Canvas(
        modifier.fillMaxWidth().height(spec.height)
            .then(if (description != null) Modifier.semantics { contentDescription = description } else Modifier),
    ) {
        val w = size.width
        val cy = size.height / 2
        val inset = 6.dp.toPx()
        val stroke = 2.dp.toPx()
        fun x(px: Float) = if (rtl) w - px else px
        drawLine(
            spec.lineColor, Offset(x(inset), cy), Offset(x(w - inset), cy), stroke,
            pathEffect = if (spec.dashed) PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())) else null,
        )
        spec.nodes.forEach { node ->
            val d = node.size.toPx()
            val center = when (val p = node.position) {
                M3RailPosition.Start -> d / 2
                M3RailPosition.End -> w - d / 2
                M3RailPosition.Center -> w / 2
                is M3RailPosition.FromStart -> p.offset.toPx() + d / 2
                is M3RailPosition.FromEnd -> w - p.offset.toPx() - d / 2
                is M3RailPosition.Fraction -> if (p.fromEnd) w - p.fraction * w - d / 2 else p.fraction * w + d / 2
            }
            val o = Offset(x(center), cy)
            if (node.ring) {
                if (node.fill != Color.Transparent) drawCircle(node.fill, d / 2, o)
                drawCircle(node.color, d / 2 - stroke / 2, o, style = Stroke(stroke))
            } else drawCircle(node.color, d / 2, o)
        }
    }
}

/**
 * Phone ↔ GitHub illustration (onboarding, empty Home, pinned vault, cloning, upgrade): mono 11 end
 * labels above the rail (or inline at its ends with [labelsInline]), the rail, and an optional
 * centered mono [caption] below ("nothing to sync", "03:14 · pulled 2 · pushed 1"). [exchangeGlyph]
 * draws the upgrade variant: filled dot, line, "⇄", line, ring.
 */
@Composable
fun M3SyncIllustration(
    startLabel: String,
    endLabel: String,
    spec: M3RailSpec,
    modifier: Modifier = Modifier,
    onHero: Boolean = true,
    labelsInline: Boolean = false,
    caption: String? = null,
    captionColor: Color = Color.Unspecified,
    exchangeGlyph: String? = null,
    description: String? = null,
) {
    val c = M3.colors
    val labelInk = if (onHero) c.invMeta else c.onVar
    Column(modifier.fillMaxWidth().then(if (description != null) Modifier.semantics(mergeDescendants = true) { contentDescription = description } else Modifier)) {
        if (exchangeGlyph != null) {
            Row(Modifier.fillMaxWidth()) {
                Text(startLabel, style = M3Type.metaSmall, color = labelInk, modifier = Modifier.weight(1f))
                Text(endLabel, style = M3Type.metaSmall, color = labelInk)
            }
            Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val ahead = if (onHero) c.invAhead else c.ahead
                val behind = if (onHero) c.invBehind else c.behind
                val line = if (onHero) c.invLine else c.outlineVar
                Box(Modifier.size(14.dp).background(ahead, CircleShape))
                Box(Modifier.weight(1f).height(2.dp).background(line))
                Text(exchangeGlyph, style = M3Type.meta, color = behind)
                Box(Modifier.weight(1f).height(2.dp).background(line))
                Box(Modifier.size(14.dp).border(2.dp, behind, CircleShape))
            }
        } else if (labelsInline) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(startLabel, style = M3Type.metaSmall, color = labelInk)
                M3SyncRail(spec, Modifier.weight(1f))
                Text(endLabel, style = M3Type.metaSmall, color = labelInk)
            }
        } else {
            Row(Modifier.fillMaxWidth()) {
                Text(startLabel, style = M3Type.metaSmall, color = labelInk, modifier = Modifier.weight(1f))
                Text(endLabel, style = M3Type.metaSmall, color = labelInk)
            }
            M3SyncRail(spec, Modifier.padding(top = if (spec.height > 14.dp) 10.dp else 6.dp))
        }
        if (caption != null) Text(
            caption, style = if (exchangeGlyph != null) M3Type.meta else M3Type.metaSmall,
            color = if (captionColor != Color.Unspecified) captionColor else if (exchangeGlyph != null && onHero) c.onInv else labelInk,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = if (exchangeGlyph != null) 10.dp else 8.dp),
        )
    }
}

/** Convenience: [M3SyncIllustration] for one of the ready-made [M3RailState]s. */
@Composable
fun M3SyncIllustration(
    startLabel: String,
    endLabel: String,
    state: M3RailState,
    modifier: Modifier = Modifier,
    onHero: Boolean = true,
    dots: Int = 3,
    background: Color = Color.Unspecified,
    labelsInline: Boolean = false,
    caption: String? = null,
    description: String? = null,
) = M3SyncIllustration(startLabel, endLabel, m3RailSpec(state, onHero, dots, background), modifier, onHero, labelsInline, caption, description = description)

// ─── Navigation ─────────────────────────────────────────────────────────────

/** Navigation indicator: 64×32, 16dp corners, sc/onSc when selected, 24dp icon, error count badge at x=36. */
@Composable
private fun M3NavIndicator(@DrawableRes icon: Int, selected: Boolean, badge: String?) {
    val c = M3.colors
    Box(
        Modifier.size(width = 64.dp, height = 32.dp).then(if (selected) Modifier.background(c.sc, RoundedCornerShape(16.dp)) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        M3Icon(icon, tint = if (selected) c.onSc else c.onVar)
        if (!badge.isNullOrEmpty()) {
            Box(
                Modifier.align(Alignment.TopStart).offset(x = 36.dp).heightIn(min = 16.dp).widthIn(min = 16.dp)
                    .background(c.error, RoundedCornerShape(8.dp)).padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center,
            ) { Text(badge, style = M3Type.badge.copy(fontSize = 11.sp, lineHeight = 16.sp), color = c.onError) }
        }
    }
}

/** Navigation bar: 80dp on the c container, items share the width (pads for the system navigation bar). */
@Composable
fun M3NavigationBar(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(modifier.fillMaxWidth().background(M3.colors.c).navigationBarsPadding().height(80.dp), content = content)
}

/** Navigation bar item: indicator over a 12/16 label (700 on when selected, 500 onVar otherwise), 4dp gap. */
@Composable
fun RowScope.M3NavigationBarItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val c = M3.colors
    Column(
        modifier.weight(1f).fillMaxHeight()
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
    ) {
        M3NavIndicator(icon, selected, badge)
        Text(
            label, style = M3Type.navLabel.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium),
            color = if (selected) c.on else c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

private const val RAIL_LABEL_MAX_SCALE = 1.3f

/** Navigation rail item (tablets, unfolded): the bar item's indicator and label in a 72dp column. */
@Composable
fun M3NavigationRailItem(
    label: String,
    @DrawableRes icon: Int,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
    width: Dp = 72.dp,
) {
    val c = M3.colors
    Column(
        modifier.width(width).clip(RoundedCornerShape(16.dp))
            .selectable(selected = selected, role = Role.Tab, onClick = onClick)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        M3NavIndicator(icon, selected, badge)
        // The rail is a fixed 72–88dp column: past 1.3× font scale the label stops growing (the icon
        // already identifies the place) and may wrap to two lines, instead of ellipsizing to "Cha…".
        val fontScale = LocalDensity.current.fontScale
        val shrink = if (fontScale > RAIL_LABEL_MAX_SCALE) RAIL_LABEL_MAX_SCALE / fontScale else 1f
        Text(
            label, style = M3Type.navLabel.copy(fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                fontSize = M3Type.navLabel.fontSize * shrink, lineHeight = M3Type.navLabel.lineHeight * shrink),
            color = if (selected) c.on else c.onVar, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** Navigation rail: 80dp column on c, optional [header] (e.g. [M3Fab]) and [footer], items 12dp apart. */
@Composable
fun M3NavigationRail(
    modifier: Modifier = Modifier,
    header: (@Composable ColumnScope.() -> Unit)? = null,
    footer: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier.width(80.dp).fillMaxHeight().background(M3.colors.c).statusBarsPadding().navigationBarsPadding().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (header != null) { header(); Spacer(Modifier.height(16.dp)) }
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
        footer?.invoke(this)
    }
}

/** The vault's label for each tab ("Changes", "Files", "History", "Git"). */
@Composable
fun VaultTab.m3Label(): String = stringResource(
    when (this) {
        VaultTab.CHANGES -> R.string.ui_changes_bbd4b6a8
        VaultTab.FILES -> R.string.tide_vault_files_section
        VaultTab.HISTORY -> R.string.history_screen
        VaultTab.GIT -> R.string.tide_vault_git
    }
)

/** The 4-tab vault navigation bar (Changes · Files · History · Git) with the change count on Changes. */
@Composable
fun M3VaultNavigationBar(selected: VaultTab, onSelect: (VaultTab) -> Unit, modifier: Modifier = Modifier, changes: Int? = null) {
    M3NavigationBar(modifier.testTag("vault-navigation")) {
        VaultTab.values().forEach { tab ->
            M3NavigationBarItem(
                tab.m3Label(), tab.icon, tab == selected, onClick = { if (tab != selected) onSelect(tab) },
                modifier = Modifier.testTag(tab.tag),
                badge = if (tab == VaultTab.CHANGES && changes != null && changes > 0) (if (changes > 99) "99+" else "$changes") else null,
            )
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Composable
private fun PreviewHome() {
    val c = M3.colors
    Column(Modifier.background(c.surface)) {
        M3HomeTopBar(avatarInitials = "CB", statusBarPadding = false) { M3IconButton(R.drawable.ic_m3_add, androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.add_title), {}) }
        M3Hero(Modifier.m3Gutter().padding(top = 8.dp)) {
            M3HeroHeadline(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_1_vault_c39776f1), accent = "needs you.")
            M3HeroMeta(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_4_in_sync_checked_2_min_ago_a1f1ab5e))
        }
        M3ChipRow {
            M3FilterChip(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_all_5_8907f196), true, {})
            M3FilterChip(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_attention_1_fa1ea5dc), false, {})
            M3FilterChip(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.m3home_filter_pinned), false, {})
        }
        M3Hero(Modifier.m3Gutter().padding(top = 12.dp), style = M3HeroStyle.PinnedSoft, contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 18.dp)) {
            M3HeroKicker(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.m3home_filter_pinned), icon = R.drawable.ic_m3_pin)
            Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_voxvault_b8d22790), style = M3Type.title, modifier = Modifier.weight(1f))
                M3CountText(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_3_to_push_ac87b7cb))
            }
            Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_main_codybontecou_voxvault_c727a0dc), style = M3Type.meta, color = c.onVar)
            M3SyncIllustration("Phone", "GitHub", M3RailState.Pending, Modifier.padding(top = 16.dp), onHero = false, background = c.tcSoft)
        }
        M3ListCard(Modifier.m3Gutter().padding(top = 12.dp)) {
            M3ListRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_notes_ab5aa970), meta = "main · 12m ago", titleStyle = M3Type.bodyStrong, leading = { M3LeadingAvatar("n", M3Tone.Ok) }, trailing = { M3Badge(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_in_sync), M3Tone.Ok) }, onClick = {})
            M3ListRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_voxvault_b8d22790), meta = "main · last synced 2h ago", titleStyle = M3Type.bodyStrong, divider = true, leading = { M3LeadingAvatar("V", M3Tone.Ahead) }, trailing = { M3Badge(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_3_held_f454ab2b), M3Tone.Ahead) }, onClick = {}, onLongClick = {})
            M3ListRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_health_md_f72119f5), meta = "main · last synced 2h ago", titleStyle = M3Type.bodyStrong, divider = true, leading = { M3LeadingAvatar("h", M3Tone.Warn) }, trailing = { M3Badge(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.m3home_auth_paused), M3Tone.Warn) })
        }
        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.CenterEnd) { M3ExtendedFab(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.adaptive_sync_all), R.drawable.ic_m3_sync, {}, meta = "↓0 ↑3") }
    }
}

@Preview(name = "M3Kit · Home", widthDp = 412, heightDp = 760)
@Composable
private fun PreviewM3Home() = SyncMdTheme(darkTheme = false) { PreviewHome() }

@Preview(name = "M3Kit · Home · dark", widthDp = 412, heightDp = 760)
@Composable
private fun PreviewM3HomeDark() = SyncMdTheme(darkTheme = true) { PreviewHome() }

@Preview(name = "M3Kit · Vault", widthDp = 412, heightDp = 860)
@Composable
private fun PreviewM3Vault() = SyncMdTheme(darkTheme = false) {
    val c = M3.colors
    Column(Modifier.background(c.surface)) {
        M3TopBar(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_voxvault_b8d22790), subtitle = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_main_codybontecou_voxvault_c727a0dc), onBack = {}, statusBarPadding = false) {
            M3IconButton(R.drawable.ic_m3_sync, androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.files_m3_check_for_changes), {})
            M3IconButton(R.drawable.ic_m3_more_vert, androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_vault_settings), {})
        }
        M3Hero(Modifier.m3Gutter().padding(top = 8.dp)) {
            M3HeroKicker(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.tide_vault_eyebrow_ready_to_commit))
            M3HeroCount("3", androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_changes_on_this_phone_6359d3d5))
            M3HeroMeta(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_remote_has_no_new_commits_fetched_2m_ago_bdaa4b95), topPadding = 2.dp)
            M3HeroActions {
                M3Button(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.shortcut_m3_commit_short), {}, Modifier.weight(1f), M3ButtonStyle.OnHero, M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_upward)
                M3Button(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.pull_screen), {}, style = M3ButtonStyle.OutlinedOnHero, size = M3ButtonSize.Large, icon = R.drawable.ic_m3_arrow_downward)
            }
        }
        M3SectionLabel(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.diff_m3_changes), count = "3", trailing = "tap a file to see the diff")
        M3ListCard(Modifier.m3Gutter().padding(top = 8.dp)) {
            M3ListRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_2026_11_01_md_4381fb10), meta = "daily/", leading = { M3LeadingLetter("A", M3Tone.Ok) }, trailing = { M3DiffStat("+42", null) }, onClick = {})
            M3ListRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_voice_memo_pipeline_md_2a5a57d3), meta = "ideas/", divider = true, leading = { M3LeadingLetter("M", M3Tone.Ahead) }, trailing = { M3DiffStat("+8", "−3") }, onClick = {})
        }
        M3Note(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_leave_note), Modifier.m3Gutter().padding(top = 12.dp), tone = M3NoteTone.Plain)
        M3Note(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_3_changes_aren_t_committed_yet_commit_or_s_d878e782), Modifier.m3Gutter().padding(top = 8.dp), tone = M3NoteTone.Ahead)
        Spacer(Modifier.weight(1f))
        M3VaultNavigationBar(VaultTab.CHANGES, {}, changes = 3)
    }
}

@Preview(name = "M3Kit · Controls", widthDp = 412, heightDp = 900)
@Composable
private fun PreviewM3Controls() = SyncMdTheme(darkTheme = true) {
    val c = M3.colors
    Column(Modifier.background(c.surface).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Segmented(listOf("Write", "Preview"), 0, {})
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3FilledButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.shortcut_m3_commit_short), {}, trailingMeta = "↑3")
            M3TonalButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.diagnostics_operation_clone), {})
            M3TextButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.settings_m3_not_now), {})
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            M3OutlinedButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.adaptive_review_first), {}, Modifier.weight(1f))
            M3OutlinedButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.git_m3_discard), {}, Modifier.weight(1f), destructive = true)
        }
        M3ListCard {
            M3SettingsRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.settings_m3_vault_include), subtitle = stringResource(R.string.settings_m3_vault_include_sub, "main", "origin", "main"), checked = true, onCheckedChange = {})
            M3SettingsRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.settings_m3_vault_pin), checked = false, onCheckedChange = {}, divider = true)
            M3SettingsRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.settings_m3_vault_display_name), value = "VoxVault", onClick = {}, divider = true)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            M3Tag("LFS"); M3Tag("M", tone = M3Tone.Ahead); M3Tag(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.settings_m3_debug_error), tone = M3Tone.Warn); M3Badge(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.paywall_pill_needs_access), M3Tone.Warn); M3Badge("↓1", M3Tone.Behind)
        }
        M3Hero(style = M3HeroStyle.PrimaryContainer) {
            Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_pushing), style = M3Type.headline)
            Text(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_2_of_3_commits_sent_5591e38a), style = M3Type.bodyMedium, modifier = Modifier.padding(top = 2.dp))
            M3ProgressBar(0.66f, Modifier.padding(top = 16.dp), tone = M3ProgressTone.PrimaryContainer)
        }
        M3IndeterminateProgressBar()
        M3Hero(contentPadding = PaddingValues(horizontal = 20.dp, vertical = 28.dp)) {
            M3SyncIllustration("this phone", "GitHub", M3RailState.Onboarding)
        }
        M3Snackbar("Support email copied", actionLabel = stringResource(android.R.string.ok), onAction = {})
        M3ExtendedFab(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.m3home_offline_fab), R.drawable.ic_m3_cloud_off, null, meta = "↑3", style = M3FabStyle.Flat)
    }
}

@Preview(name = "M3Kit · Dialog & sheet", widthDp = 412, heightDp = 820)
@Composable
private fun PreviewM3Overlays() = SyncMdTheme(darkTheme = false) {
    Column(Modifier.background(M3.colors.scrim).padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        M3DialogSurface(
            androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_discard_3_changes_d2ad52aa), Modifier.padding(horizontal = 24.dp), icon = R.drawable.ic_m3_delete, iconTint = M3.colors.error,
            text = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_these_files_go_back_to_how_they_were_in_a4_02e3cd66),
            content = {
                M3InsetList(radius = 12.dp) {
                    M3InsetRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_daily_2026_11_01_md_c8c1afcc), trailing = "delete new file")
                    M3InsetRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_old_inbox_md_66b84b5b), trailing = "restore", divider = true)
                }
            },
        ) {
            M3DialogAction(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_keep_them), {})
            M3DialogAction(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_discard_3_95e06aed), {}, destructive = true, filled = true)
        }
        M3SheetSurface(title = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_commit_3_files_959a8528), titleMeta = "VoxVault → main", footer = {
            M3FilledButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.shortcut_m3_commit_short), {}, Modifier.fillMaxWidth(), size = M3ButtonSize.Hero, trailingMeta = "↑3")
            M3TextButton(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_commit_locally), {}, Modifier.fillMaxWidth())
        }) {
            M3TextField("Add Nov 1 daily note", {}, androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_message), Modifier.padding(top = 20.dp), singleLine = false)
            M3SectionLabel(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.vault_m3_included), contentPadding = PaddingValues(start = 4.dp, end = 4.dp, top = 20.dp, bottom = 4.dp))
            M3InsetList {
                M3CheckboxRow(androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_daily_2026_11_01_md_c8c1afcc), true, {}, trailing = { M3DiffStat("+42", null, style = M3Type.metaStrong) })
            }
        }
    }
}
