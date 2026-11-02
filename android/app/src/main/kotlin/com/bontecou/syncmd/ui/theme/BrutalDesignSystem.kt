package com.bontecou.syncmd.ui.theme

import androidx.annotation.DrawableRes
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// ─── GitSync.md legacy primitives ────────────────────────────────────────────
// The B* components keep their original API but render in Tide: paper ground,
// white rounded cards, ink and outline pills, Geist / Geist Mono, sentence-case
// labels. Mirrors iOS BrutalDesignSystem.swift on the Tide redesign.

// ─── Brutal Colors (Tide palette behind the legacy names; see Theme.kt) ─────
data class BrutalColors(
    /** Screen background — Tide paper. */
    val bg: Color,
    /** Wells, inputs and segmented tracks. */
    val surface: Color,
    /** Hairline separators and outlines. */
    val border: Color,
    val borderSoft: Color,
    val text: Color,
    val textMid: Color,
    val textFaint: Color,
    /** Remote / pull blue. */
    val accent: Color,
    val error: Color,
    val success: Color,
    /** Local / push orange. */
    val warning: Color,
)

val LocalBrutalColors = compositionLocalOf {
    BrutalColors(
        bg         = TideLight.paper,
        surface    = TideLight.well,
        border     = TideLight.line,
        borderSoft = TideLight.lineSoft,
        text       = TideLight.ink,
        textMid    = TideLight.inkMid,
        textFaint  = TideLight.inkFaint,
        accent     = TideLight.behind,
        error      = TideLight.danger,
        success    = TideLight.synced,
        warning    = TideLight.ahead,
    )
}

// ─── Badge Styles ────────────────────────────────────────────────────────────
enum class BBadgeStyle { DEFAULT, ACCENT, SUCCESS, WARNING, ERROR }

fun BrutalColors.badgeBg(style: BBadgeStyle): Color = when (style) {
    BBadgeStyle.DEFAULT -> surface
    else -> badgeFg(style).copy(alpha = 0.12f)
}

fun BrutalColors.badgeFg(style: BBadgeStyle): Color = when (style) {
    BBadgeStyle.DEFAULT -> textMid
    BBadgeStyle.ACCENT  -> accent
    BBadgeStyle.SUCCESS -> success
    BBadgeStyle.WARNING -> warning
    BBadgeStyle.ERROR   -> error
}

fun BrutalColors.badgeBorder(style: BBadgeStyle): Color = Color.Transparent

// ─── BCard ───────────────────────────────────────────────────────────────────
/** White rounded card on paper — renders as a TideCard. */
@Composable
fun BCard(
    modifier: Modifier = Modifier,
    bg: Color = Tide.colors.card,
    bc: BrutalColors = LocalBrutalColors.current,
    content: @Composable () -> Unit,
) {
    // Callers that asked for the old screen background get a card instead.
    val fill = if (bg == bc.bg) Tide.colors.card else bg
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(Tide.cardShape)
            .background(fill, Tide.cardShape)
    ) {
        content()
    }
}

// ─── BBadge ──────────────────────────────────────────────────────────────────
@Composable
fun BBadge(
    text: String,
    style: BBadgeStyle = BBadgeStyle.DEFAULT,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Text(
        text = text,
        style = TideType.metaStrong,
        color = bc.badgeFg(style),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(bc.badgeBg(style), RoundedCornerShape(10.dp))
            .padding(horizontal = 9.dp, vertical = 5.dp),
    )
}

// ─── BSectionHeader ──────────────────────────────────────────────────────────
@Composable
fun BSectionHeader(
    title: String,
    subtitle: String? = null,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TideSectionLabel(title)
        if (subtitle != null) {
            Text(text = subtitle, style = TideType.sans(14.sp), color = bc.textMid)
        }
    }
}

// ─── BDivider ────────────────────────────────────────────────────────────────
@Composable
fun BDivider(
    label: String? = null,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    if (label != null) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Box(Modifier.weight(1f).height(1.dp).background(bc.border))
            TideSectionLabel(label)
            Box(Modifier.weight(1f).height(1.dp).background(bc.border))
        }
    } else {
        Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
    }
}

// ─── Pill buttons ────────────────────────────────────────────────────────────

@Composable
private fun BPill(
    title: String,
    modifier: Modifier,
    fill: Color?,
    outline: Color?,
    tint: Color,
    enabled: Boolean,
    isLoading: Boolean,
    dimmed: Float,
    @DrawableRes iconRes: Int? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
            .then(if (outline != null) Modifier.border(1.dp, outline, CircleShape) else Modifier)
            .clickable(enabled = enabled && !isLoading, role = Role.Button, onClick = onClick)
            .alpha(dimmed)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), color = tint, strokeWidth = 2.dp)
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                iconRes?.let {
                    Icon(painterResource(it), contentDescription = null, tint = tint, modifier = Modifier.size(18.dp))
                }
                Text(text = title, textAlign = TextAlign.Center, style = TideType.button, color = tint)
            }
        }
    }
}

/** Solid ink pill — the one right action. */
@Composable
fun BPrimaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isDisabled: Boolean = false,
    @DrawableRes leadingIconRes: Int? = null,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    BPill(
        title, modifier,
        fill = bc.text.copy(alpha = if (isDisabled) 0.3f else 1f), outline = null, tint = bc.bg,
        enabled = !isDisabled, isLoading = isLoading, dimmed = 1f, iconRes = leadingIconRes, onClick = onClick,
    )
}

/** Outline pill. */
@Composable
fun BSecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isDisabled: Boolean = false,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    BPill(
        title, modifier,
        fill = null, outline = bc.borderSoft, tint = bc.text,
        enabled = !isDisabled, isLoading = isLoading, dimmed = if (isDisabled) 0.4f else 1f, onClick = onClick,
    )
}

/** Text-only action with a 48dp hit target. */
@Composable
fun BGhostButton(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = LocalBrutalColors.current.text,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .clip(CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = title, style = TideType.sans(15.sp, FontWeight.Medium), color = color)
    }
}

/** Solid danger pill. */
@Composable
fun BDestructiveButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    BPill(
        title, modifier,
        fill = bc.error, outline = null, tint = Color.White,
        enabled = true, isLoading = isLoading, dimmed = 1f, onClick = onClick,
    )
}

/** Compact outline pill for row-level actions. */
@Composable
fun BSmallActionButton(
    title: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    val fg = if (isDestructive) bc.error else bc.text
    Box(
        modifier = Modifier
            .heightIn(min = 36.dp)
            .clip(CircleShape)
            .border(1.dp, if (isDestructive) bc.error.copy(alpha = 0.35f) else bc.borderSoft, CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = title, style = TideType.sans(13.sp, FontWeight.SemiBold), color = fg, maxLines = 1)
    }
}

// ─── BTextField ──────────────────────────────────────────────────────────────
@Composable
fun BTextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    isPassword: Boolean = false,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    var isFocused by remember { mutableStateOf(false) }
    val tide = Tide.colors

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TideSectionLabel(label)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TideType.sans(16.sp, FontWeight.Medium).copy(color = bc.text),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(bc.text),
            modifier = Modifier
                .fillMaxWidth()
                .background(tide.card, Tide.fieldShape)
                .border(if (isFocused) 1.5.dp else 1.dp, if (isFocused) bc.text else bc.border, Tide.fieldShape)
                .onFocusChanged { isFocused = it.isFocused }
                .padding(horizontal = 16.dp, vertical = 15.dp),
        ) { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(text = placeholder, style = TideType.sans(16.sp), color = bc.textFaint)
                }
                innerTextField()
            }
        }
    }
}

// ─── BActionRow (icon + title + subtitle + chevron) ─────────────────────────
@Composable
fun BActionRow(
    icon: String,
    title: String,
    subtitle: String? = null,
    badge: Int? = null,
    badgeStyle: BBadgeStyle = BBadgeStyle.ACCENT,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            Modifier.size(28.dp).background(bc.surface, RoundedCornerShape(8.dp)),
            contentAlignment = Alignment.Center,
        ) { Text(text = icon, style = TideType.sans(14.sp), color = bc.text) }

        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = TideType.body, color = bc.text)
            if (subtitle != null) Text(text = subtitle, style = TideType.mono(11.sp), color = bc.textFaint)
        }

        if (badge != null && badge > 0) BBadge(text = "$badge", style = badgeStyle) else TideChevron()
    }
}

// ─── BCardRow ────────────────────────────────────────────────────────────────
@Composable
fun BCardRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    badgeText: String? = null,
    badgeStyle: BBadgeStyle = BBadgeStyle.DEFAULT,
    showArrow: Boolean = false,
    destructive: Boolean = false,
    onClick: (() -> Unit)? = null,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = TideType.body, color = if (destructive) bc.error else bc.text)
            if (subtitle != null) Text(text = subtitle, style = TideType.mono(11.sp), color = bc.textFaint)
        }
        if (badgeText != null) BBadge(text = badgeText, style = badgeStyle)
        if (value != null) Text(text = value, style = TideType.mono(13.sp), color = bc.textMid)
        if (showArrow) TideChevron()
    }
}

// ─── BMonoRow (key / value) ─────────────────────────────────────────────────
@Composable
fun BMonoRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalBrutalColors.current.textMid,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = key, style = TideType.body, color = bc.text)
        Text(
            text = value,
            style = TideType.mono(13.sp),
            color = valueColor,
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1f),
        )
    }
}

// ─── BSpineHeader (big left-aligned title) ──────────────────────────────────
@Composable
fun BSpineHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (subtitle != null) Text(text = subtitle, style = TideType.meta, color = bc.textFaint)
        Text(
            text = title,
            style = TideType.display(36.sp),
            color = bc.text,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
    }
}

// ─── BProgressBar ────────────────────────────────────────────────────────────
@Composable
fun BProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = tween(durationMillis = 300, easing = LinearEasing),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(bc.border, CircleShape)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(4.dp)
                .background(bc.text, CircleShape)
        )
    }
}

// ─── BEmptyState ─────────────────────────────────────────────────────────────
@Composable
fun BEmptyState(
    title: String,
    subtitle: String,
    note: String? = null,
    onNoteClick: (() -> Unit)? = null,
    actionTitle: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Column(
        modifier = modifier.padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = title,
            style = TideType.display(30.sp),
            color = bc.text,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = 10.dp).semantics { heading() },
        )
        Text(
            text = subtitle,
            style = TideType.copy,
            color = bc.textMid,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(bottom = if (note != null) 12.dp else 28.dp),
        )
        if (note != null) {
            Text(
                text = note,
                style = TideType.sans(14.sp, FontWeight.Medium),
                color = bc.accent,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .then(if (onNoteClick != null) Modifier.clickable(role = Role.Button, onClick = onNoteClick) else Modifier),
            )
        }
        if (actionTitle != null && onAction != null) {
            BPrimaryButton(title = actionTitle, modifier = Modifier.widthIn(max = 260.dp), onClick = onAction)
        }
    }
}

// ─── BLoading ────────────────────────────────────────────────────────────────
@Composable
fun BLoading(
    text: String = "Loading",
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), color = bc.text, strokeWidth = 2.dp)
        Text(text = text, style = TideType.sans(15.sp, FontWeight.Medium), color = bc.textMid)
    }
}

// ─── BMetaChip ───────────────────────────────────────────────────────────────
@Composable
fun BMetaChip(
    icon: String,
    text: String,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = icon, style = TideType.mono(12.sp), color = bc.textMid)
        Text(text = text, style = TideType.mono(12.sp), color = bc.textMid)
    }
}
