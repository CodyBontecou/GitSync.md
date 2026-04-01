package com.bontecou.syncmd.ui.theme

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.annotation.DrawableRes
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

// ─── Brutal Colors ─────────────────────────────────────────────────────────
data class BrutalColors(
    val bg: Color,
    val surface: Color,
    val border: Color,
    val borderSoft: Color,
    val text: Color,
    val textMid: Color,
    val textFaint: Color,
    val accent: Color,
    val error: Color,
    val success: Color,
    val warning: Color,
)

val LocalBrutalColors = compositionLocalOf {
    BrutalColors(
        bg         = BrutalBgLight,
        surface    = BrutalSurfaceLight,
        border     = BrutalBorderLight,
        borderSoft = BrutalBorderSoftLight,
        text       = BrutalTextLight,
        textMid    = BrutalTextMidLight,
        textFaint  = BrutalTextFaintLight,
        accent     = BrutalAccentLight,
        error      = BrutalErrorLight,
        success    = BrutalSuccessLight,
        warning    = BrutalWarningLight,
    )
}

// ─── Badge Styles ────────────────────────────────────────────────────────────
enum class BBadgeStyle { DEFAULT, ACCENT, SUCCESS, WARNING, ERROR }

fun BrutalColors.badgeBg(style: BBadgeStyle): Color = when (style) {
    BBadgeStyle.DEFAULT -> surface
    BBadgeStyle.ACCENT  -> accent.copy(alpha = 0.10f)
    BBadgeStyle.SUCCESS -> success.copy(alpha = 0.10f)
    BBadgeStyle.WARNING -> warning.copy(alpha = 0.10f)
    BBadgeStyle.ERROR   -> error.copy(alpha = 0.10f)
}

fun BrutalColors.badgeFg(style: BBadgeStyle): Color = when (style) {
    BBadgeStyle.DEFAULT -> textMid
    BBadgeStyle.ACCENT  -> accent
    BBadgeStyle.SUCCESS -> success
    BBadgeStyle.WARNING -> warning
    BBadgeStyle.ERROR   -> error
}

fun BrutalColors.badgeBorder(style: BBadgeStyle): Color = when (style) {
    BBadgeStyle.DEFAULT -> borderSoft
    BBadgeStyle.ACCENT  -> accent.copy(alpha = 0.30f)
    BBadgeStyle.SUCCESS -> success.copy(alpha = 0.30f)
    BBadgeStyle.WARNING -> warning.copy(alpha = 0.30f)
    BBadgeStyle.ERROR   -> error.copy(alpha = 0.30f)
}

// ─── BCard ───────────────────────────────────────────────────────────────────
/**
 * Sharp-edged card with hard 1dp border — the core brutalist container.
 */
@Composable
fun BCard(
    modifier: Modifier = Modifier,
    bg: Color = LocalBrutalColors.current.bg,
    bc: BrutalColors = LocalBrutalColors.current,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(bg)
            .border(1.dp, bc.border)
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
    Box(
        modifier = Modifier
            .background(bc.badgeBg(style))
            .border(1.dp, bc.badgeBorder(style))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = text.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = bc.badgeFg(style),
            )
        )
    }
}

// ─── BSectionHeader ──────────────────────────────────────────────────────────
@Composable
fun BSectionHeader(
    title: String,
    subtitle: String? = null,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(13.dp)
                    .background(bc.text)
            )
            Text(
                text = title.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = bc.text,
                )
            )
        }
        if (subtitle != null) {
            Text(
                text = subtitle,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = bc.text,
                ),
                modifier = Modifier.padding(start = 11.dp),
            )
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
            Text(
                text = label.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Medium,
                    fontSize = 12.sp,
                    letterSpacing = 2.sp,
                    color = bc.text,
                )
            )
            Box(Modifier.weight(1f).height(1.dp).background(bc.border))
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(bc.border)
        )
    }
}

// ─── BPrimaryButton ─────────────────────────────────────────────────────────
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
    val alpha = if (isDisabled || isLoading) 0.3f else 1f
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bc.text.copy(alpha = alpha))
            .clickable(
                enabled = !isDisabled && !isLoading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = bc.bg,
                strokeWidth = 2.dp,
            )
        } else {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                leadingIconRes?.let { iconRes ->
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = null,
                        tint = bc.bg,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Text(
                    text = title.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp,
                        letterSpacing = 2.sp,
                        color = bc.bg,
                    )
                )
            }
        }
    }
}

// ─── BSecondaryButton ────────────────────────────────────────────────────────
@Composable
fun BSecondaryButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    isDisabled: Boolean = false,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bc.bg)
            .border(1.dp, bc.border)
            .clickable(
                enabled = !isDisabled && !isLoading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = bc.text,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = title.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                    color = if (isDisabled) bc.textFaint else bc.text,
                )
            )
        }
    }
}

// ─── BGhostButton ────────────────────────────────────────────────────────────
@Composable
fun BGhostButton(
    title: String,
    modifier: Modifier = Modifier,
    color: Color = LocalBrutalColors.current.text,
    onClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .padding(vertical = 14.dp)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = title.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                letterSpacing = 1.sp,
                color = color,
            )
        )
    }
}

// ─── BDestructiveButton ──────────────────────────────────────────────────────
@Composable
fun BDestructiveButton(
    title: String,
    modifier: Modifier = Modifier,
    isLoading: Boolean = false,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .background(bc.error.copy(alpha = 0.08f))
            .border(1.dp, bc.error.copy(alpha = 0.5f))
            .clickable(
                enabled = !isLoading,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = bc.error,
                strokeWidth = 2.dp,
            )
        } else {
            Text(
                text = title.uppercase(),
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    letterSpacing = 2.sp,
                    color = bc.error,
                )
            )
        }
    }
}

// ─── BSmallActionButton ──────────────────────────────────────────────────────
@Composable
fun BSmallActionButton(
    title: String,
    isDestructive: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    val fg = if (isDestructive) bc.error else bc.text
    val borderColor = if (isDestructive) bc.error.copy(alpha = 0.4f) else bc.border
    Box(
        modifier = Modifier
            .border(1.dp, if (enabled) borderColor else borderColor.copy(alpha = 0.3f))
            .clickable(
                enabled = enabled,
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 9.dp, vertical = 6.dp)
    ) {
        Text(
            text = title.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = if (enabled) fg else fg.copy(alpha = 0.4f),
            )
        )
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

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp,
                letterSpacing = 2.sp,
                color = bc.text,
            )
        )

        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 16.sp,
                color = bc.text,
            ),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            singleLine = true,
            keyboardOptions = keyboardOptions,
            cursorBrush = SolidColor(bc.text),
            modifier = Modifier
                .fillMaxWidth()
                .background(bc.surface)
                .border(if (isFocused) 2.dp else 1.dp, bc.border)
                .onFocusChanged { isFocused = it.isFocused }
                .padding(horizontal = 12.dp, vertical = 13.dp),
        ) { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 16.sp,
                            color = bc.textFaint,
                        )
                    )
                }
                innerTextField()
            }
        }
    }
}

// ─── BActionRow ──────────────────────────────────────────────────────────────
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
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            text = icon,
            style = TextStyle(fontSize = 20.sp),
            modifier = Modifier.width(32.dp),
        )

        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                    color = bc.text,
                )
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = bc.textMid,
                    )
                )
            }
        }

        if (badge != null && badge > 0) {
            BBadge(text = "$badge", style = badgeStyle)
        } else {
            Text(
                text = "→",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = bc.textFaint,
                )
            )
        }
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
            .padding(vertical = 13.dp)
            .then(
                if (onClick != null) Modifier.clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = onClick,
                ) else Modifier
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = FontFamily.Default,
                    fontWeight = FontWeight.Medium,
                    fontSize = 16.sp,
                    color = if (destructive) bc.error else bc.text,
                )
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = bc.textMid,
                    )
                )
            }
        }

        if (badgeText != null) {
            BBadge(text = badgeText, style = badgeStyle)
        }
        if (value != null) {
            Text(
                text = value,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = bc.textMid,
                )
            )
        }
        if (showArrow) {
            Text(
                text = "→",
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    color = bc.textFaint,
                )
            )
        }
    }
}

// ─── BMonoRow ────────────────────────────────────────────────────────────────
@Composable
fun BMonoRow(
    key: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = LocalBrutalColors.current.text,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = key.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                letterSpacing = 1.sp,
                color = bc.text,
            )
        )
        Spacer(Modifier.weight(1f))
        Text(
            text = value,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 15.sp,
                color = valueColor,
            )
        )
    }
}

// ─── BSpineHeader ────────────────────────────────────────────────────────────
@Composable
fun BSpineHeader(
    title: String,
    subtitle: String? = null,
    modifier: Modifier = Modifier,
    bc: BrutalColors = LocalBrutalColors.current,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = title,
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Black,
                fontSize = 40.sp,
                letterSpacing = (-1).sp,
                color = bc.text,
            )
        )
        if (subtitle != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.width(20.dp).height(1.dp).background(bc.border))
                Text(
                    text = subtitle.uppercase(),
                    style = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Medium,
                        fontSize = 12.sp,
                        letterSpacing = 2.sp,
                        color = bc.textMid,
                    )
                )
            }
        }
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
            .height(3.dp)
            .background(bc.text.copy(alpha = 0.12f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(animatedProgress)
                .height(3.dp)
                .background(bc.text)
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
        modifier = modifier.padding(horizontal = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text(
            text = "—",
            style = TextStyle(
                fontFamily = FontFamily.Default,
                fontWeight = FontWeight.Black,
                fontSize = 72.sp,
                color = bc.text,
            ),
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Text(
            text = title.uppercase(),
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                letterSpacing = 2.sp,
                color = bc.text,
            ),
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = subtitle,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 15.sp,
                color = bc.textMid,
            ),
            modifier = Modifier.padding(bottom = if (note != null) 10.dp else 28.dp),
        )
        if (note != null) {
            Text(
                text = note,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 11.sp,
                    letterSpacing = 1.5.sp,
                    color = bc.accent,
                ),
                modifier = Modifier
                    .padding(bottom = 28.dp)
                    .then(
                        if (onNoteClick != null) Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                            onClick = onNoteClick,
                        ) else Modifier
                    ),
            )
        }
        if (actionTitle != null && onAction != null) {
            BPrimaryButton(
                title = actionTitle,
                modifier = Modifier.width(220.dp),
                onClick = onAction,
            )
        }
    }
}

// ─── BLoading ────────────────────────────────────────────────────────────────
@Composable
fun BLoading(
    text: String = "Loading",
    bc: BrutalColors = LocalBrutalColors.current,
) {
    var dotCount by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(400)
            dotCount = (dotCount + 1) % 4
        }
    }

    Text(
        text = (text + ".".repeat(dotCount)).uppercase(),
        style = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Medium,
            fontSize = 14.sp,
            letterSpacing = 2.sp,
            color = bc.text,
        )
    )
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
        Text(text = icon, style = TextStyle(fontSize = 12.sp, color = bc.textMid))
        Text(
            text = text,
            style = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Medium,
                fontSize = 13.sp,
                color = bc.textMid,
            )
        )
    }
}
