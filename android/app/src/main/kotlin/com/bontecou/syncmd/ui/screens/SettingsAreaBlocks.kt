package com.bontecou.syncmd.ui.screens
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import com.bontecou.syncmd.ui.util.asString
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.BuildConfig
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.theme.BSecondaryButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideConfirmDialog
import com.bontecou.syncmd.ui.theme.TidePulse
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.theme.tideSwitchColors
import com.bontecou.syncmd.ui.util.FilesAppLauncher
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// The pre-M3 settings-area building blocks, still used by detail screens outside the settings area (Accounts).

// ─── Shared settings-area building blocks (used by the detail screens) ──────

internal enum class SettingsAccessory { NONE, CHEVRON, EXTERNAL }

/** M3 list section: a primary sentence-case header over a 28dp tonal container. */
@Composable
internal fun SettingsAreaSection(
    title: String?,
    modifier: Modifier = Modifier,
    footer: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Tide.colors
    Column(modifier.fillMaxWidth()) {
        if (title != null) {
            Text(
                title,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).semantics { heading() },
                style = TideType.section,
                color = c.primary,
            )
        }
        val shape = RoundedCornerShape(Tide.radiusCard)
        Column(Modifier.fillMaxWidth().clip(shape).background(c.card, shape), content = content)
        if (footer != null) {
            Text(footer, style = TideType.sans(12.sp).copy(lineHeight = 16.sp), color = c.inkMid,
                modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp))
        }
    }
}

/** Hairline between rows in a grouped card, inset 18dp like iOS. */
@Composable
internal fun SettingsAreaSeparator() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tide.colors.line))
}

/** Label/value row (iOS AppSettingsRow): sentence-case title, optional subtitle, mono value, accessory. */
@Composable
internal fun SettingsAreaRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleIsPath: Boolean = false,
    value: String? = null,
    valueIsMono: Boolean = true,
    valueTint: Color = Tide.colors.inkMid,
    titleTint: Color = Tide.colors.ink,
    accessory: SettingsAccessory = SettingsAccessory.NONE,
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
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (leading != null) androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.inkMid) { leading() }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = TideType.body, color = titleTint)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = if (subtitleIsPath) TideType.mono(12.sp).copy(textDirection = TextDirection.Ltr) else TideType.sans(14.sp).copy(lineHeight = 20.sp),
                    color = c.inkMid,
                )
            }
        }
        if (value != null) {
            Text(
                value,
                style = if (valueIsMono) TideType.mono(12.sp, if (valueTint == c.synced) FontWeight.SemiBold else FontWeight.Normal)
                    else TideType.sans(14.sp),
                color = valueTint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 180.dp),
            )
        }
        trailing?.invoke(this)
        when (accessory) {
            SettingsAccessory.CHEVRON -> TideChevron()
            SettingsAccessory.EXTERNAL -> Icon(painterResource(R.drawable.ic_m3_open_in_new), contentDescription = null, tint = c.inkMid, modifier = Modifier.size(20.dp))
            SettingsAccessory.NONE -> Unit
        }
    }
}

/** Toggle row: the whole row is the switch; the label (and optional subtitle) is its text. */
@Composable
internal fun SettingsAreaSwitchRow(
    label: String,
    checked: Boolean,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    tag: String? = null,
    padded: Boolean = true,
    onChange: (Boolean) -> Unit,
) {
    val c = Tide.colors
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = if (padded) 20.dp else 0.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = TideType.body, color = c.ink)
            if (subtitle != null) Text(subtitle, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = c.inkMid)
        }
        Switch(checked, onCheckedChange = null, enabled = enabled, colors = tideSwitchColors())
    }
}

internal enum class SettingsPillStyle { INK, OUTLINE, DANGER_OUTLINE, BEHIND, DANGER_TEXT, TEXT }

/** Compact pill that wraps long translations instead of clipping; 48dp minimum target. */
@Composable
internal fun SettingsAreaPill(
    title: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: SettingsPillStyle = SettingsPillStyle.OUTLINE,
    enabled: Boolean = true,
    minHeight: Dp = 48.dp,
) {
    val c = Tide.colors
    val (fill, outline, tint) = when (style) {
        SettingsPillStyle.INK -> Triple(c.primary, null, c.onPrimary)
        SettingsPillStyle.OUTLINE -> Triple(null, c.lineSoft, c.primary)
        SettingsPillStyle.DANGER_OUTLINE -> Triple(null, c.lineSoft, c.danger)
        SettingsPillStyle.BEHIND -> Triple(null, c.lineSoft, c.primary)
        SettingsPillStyle.DANGER_TEXT -> Triple(null, null, c.danger)
        SettingsPillStyle.TEXT -> Triple(null, null, c.primary)
    }
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = minHeight)
            .clip(CircleShape)
            .then(if (fill != null) Modifier.background(fill, CircleShape) else Modifier)
            .then(if (outline != null) Modifier.border(1.dp, outline, CircleShape) else Modifier)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(title, style = TideType.sans(14.sp, FontWeight.SemiBold), color = tint, textAlign = TextAlign.Center)
    }
}

/** Ink circle with the account's initial (no avatar loading on Android). */
@Composable
internal fun SettingsAreaAvatar(name: String, size: Dp = 46.dp) {
    val c = Tide.colors
    Box(Modifier.size(size).background(c.tertiaryContainer, CircleShape), contentAlignment = Alignment.Center) {
        Text(
            name.trim().removePrefix("@").firstOrNull()?.uppercaseChar()?.toString() ?: "?",
            style = TideType.sans((size.value * 0.4f).sp, FontWeight.SemiBold),
            color = c.onTertiaryContainer,
        )
    }
}

/** Thin ink progress line on a quiet well. */
@Composable
internal fun SettingsAreaProgress(modifier: Modifier = Modifier) {
    LinearProgressIndicator(
        modifier.fillMaxWidth().clip(CircleShape),
        color = Tide.colors.primary,
        trackColor = Tide.colors.primaryContainer,
    )
}

/** Tinted inline message (notice/error/warning) on paper. */
@Composable
internal fun SettingsAreaNotice(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Tide.colors.ink,
    textModifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val c = Tide.colors
    val shape = RoundedCornerShape(16.dp)
    val neutral = tint == c.ink
    val (fill, ink) = if (neutral) c.well to c.inkMid else (c.containerFor(tint) ?: (tint.copy(alpha = 0.12f) to tint))
    Column(
        modifier.fillMaxWidth()
            .background(fill, shape)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text, textModifier, style = TideType.sans(14.sp).copy(lineHeight = 20.sp), color = ink)
        action?.invoke(this)
    }
}
