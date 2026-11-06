package com.bontecou.syncmd.ui.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3IconButton
import com.bontecou.syncmd.ui.theme.M3SnackbarHost
import com.bontecou.syncmd.ui.theme.M3TopBar
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.TideFonts
import com.bontecou.syncmd.ui.theme.m3TopDivider
import java.io.File

// ═══ Git area parts ════════════════════════════════════════════════════════
// Shared pieces of the Material 3 Git tab, its deeper screens (Branches, Stashes,
// Tags, Remote, Rebase, Manage vault) and the conflict screen. Kit gaps live here,
// not in M3Kit, so the Git area never collides with other areas.

/** Where the Git tab and its screens can send the user; AppShell maps each to a route. */
sealed interface GitDestination {
    data object Branches : GitDestination
    data object Stashes : GitDestination
    data object Tags : GitDestination
    data object Remote : GitDestination
    data object Rebase : GitDestination
    data object Upstream : GitDestination
    data object Author : GitDestination
    data object Conflicts : GitDestination
    data object ManageVault : GitDestination
    /** The vault top bar's More: Vault settings (vaultSettings/{repo}). */
    data object VaultSettings : GitDestination
    data object LargeFiles : GitDestination
    data object Connection : GitDestination
    data object MoveRepository : GitDestination
    /** Back to the vault's Changes tab, where the commit sheet lives. */
    data object Changes : GitDestination
    data class Editor(val filePath: String) : GitDestination
}

/**
 * A Git screen: the design top bar (back, 22/28 title, mono subtitle, actions), a scrolling body
 * and an optional pinned [bottomBar]; [snackbar] floats over the body.
 */
@Composable
fun GitPage(
    title: String,
    subtitle: String?,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    scrollTag: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
    snackbar: SnackbarHostState? = null,
    bottomBar: (@Composable () -> Unit)? = null,
    bottomSpace: Dp = 40.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = M3.colors
    Box(modifier.fillMaxSize().background(c.surface)) {
        Column(Modifier.fillMaxSize()) {
            M3TopBar(title, subtitle = subtitle, onBack = onBack, actions = actions)
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())
                    .then(if (scrollTag != null) Modifier.testTag(scrollTag) else Modifier),
            ) {
                content()
                Spacer(Modifier.height(bottomSpace))
            }
            if (bottomBar != null) bottomBar() else Spacer(Modifier.navigationBarsPadding())
        }
        if (snackbar != null) M3SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

/** Hero section with any container pair (the kit has no tertiary-container hero). */
@Composable
fun GitToneHero(
    container: Color,
    content: Color,
    modifier: Modifier = Modifier,
    padding: Dp = 20.dp,
    inset: Boolean = true,
    body: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    CompositionLocalProvider(LocalContentColor provides content) {
        Column(modifier.then(if (inset) Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp) else Modifier).fillMaxWidth().clip(shape).background(container, shape).padding(padding), content = body)
    }
}

/** Hero kicker: 12/16 600 +0.4. */
@Composable
fun GitKicker(text: String, color: Color = LocalContentColor.current, modifier: Modifier = Modifier) =
    Text(text, modifier, style = M3Type.kicker, color = color)

/** Hero title used by the Git boards: 26/32 500. */
@Composable
fun GitHeroTitle(text: String, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) =
    Text(text, modifier.padding(top = 4.dp), style = M3Type.headline.copy(fontSize = 26.sp, lineHeight = 32.sp, letterSpacing = 0.sp), color = color)

/** Mono 13/18 line in a hero ("↑3 yours · ↓2 from remote · fetched 2m ago"). */
@Composable
fun GitHeroMono(text: AnnotatedString, modifier: Modifier = Modifier, color: Color = LocalContentColor.current) =
    Text(text, modifier.padding(top = 10.dp), style = M3Type.code, color = color)

/** Section label used inside sheets: 14/20 600 primary, margin 16/4/4. */
@Composable
fun GitSheetLabel(text: String, modifier: Modifier = Modifier, bottom: Dp = 4.dp) =
    Text(text, modifier.padding(start = 4.dp, end = 4.dp, top = 16.dp, bottom = bottom).semantics { heading() }, style = M3Type.section, color = M3.colors.primary)

/** Rounded block on the surface color inside a sheet or dialog ("Reviewed plan", dirty file list). */
@Composable
fun GitInsetBlock(modifier: Modifier = Modifier, radius: Dp = 16.dp, padding: Boolean = true, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(radius)
    Column(
        modifier.fillMaxWidth().clip(shape).background(M3.colors.surface, shape)
            .then(if (padding) Modifier.padding(horizontal = 16.dp, vertical = 12.dp) else Modifier),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        content = content,
    )
}

/** 40dp round error icon button ("Delete branch", "Drop stash", "Delete tag"). */
@Composable
fun GitDeleteButton(contentDescription: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) =
    M3IconButton(R.drawable.ic_m3_delete, contentDescription, onClick, modifier, tint = M3.colors.error, size = 40.dp, enabled = enabled)

/** 48dp circle of ec/onEc with a glyph, above a sheet title ("Remove Git tracking?"). */
@Composable
fun GitDangerBadge(@DrawableRes icon: Int, modifier: Modifier = Modifier) {
    val c = M3.colors
    Box(modifier.size(48.dp).background(c.ec, CircleShape), contentAlignment = Alignment.Center) { M3Icon(icon, tint = c.onEc) }
}

/** Icon + two-line row inside a card list (Remote "Update", Manage vault). */
@Composable
fun GitActionRow(
    title: String,
    @DrawableRes icon: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleMono: Boolean = false,
    subtitleColor: Color = M3.colors.onVar,
    titleColor: Color = M3.colors.on,
    iconTint: Color = M3.colors.onVar,
    value: String? = null,
    chevron: Boolean = false,
    divider: Boolean = false,
    enabled: Boolean = true,
    minHeight: Dp = 64.dp,
    trailing: (@Composable RowScope.() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    val c = M3.colors
    Row(
        modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar)
            .then(if (onClick != null) Modifier.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else Modifier)
            .heightIn(min = minHeight).padding(start = 20.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        M3Icon(icon, tint = if (enabled) iconTint else c.disabled)
        Column(Modifier.weight(1f)) {
            Text(title, style = M3Type.body, color = if (enabled) titleColor else c.disabled)
            if (subtitle != null) Text(subtitle, style = if (subtitleMono) M3Type.meta else M3Type.bodySmall, color = if (enabled) subtitleColor else c.disabled)
        }
        if (value != null) Text(value, Modifier.widthIn(max = 170.dp), style = M3Type.meta, color = c.onVar, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (trailing != null) trailing()
        if (chevron) M3Icon(R.drawable.ic_m3_chevron_right, tint = c.onVar)
    }
}

/** "Label · body" legend row ("Apply — Brings the changes back…"). */
@Composable
fun GitLegendRow(name: String, text: String, nameColor: Color, divider: Boolean) {
    val c = M3.colors
    Row(Modifier.fillMaxWidth().m3TopDivider(divider, c.outlineVar).padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(name, Modifier.size(width = 52.dp, height = 20.dp), style = M3Type.button, color = nameColor)
        Text(text, Modifier.weight(1f), style = M3Type.bodyMedium, color = c.onVar)
    }
}

/** Plain info line (no fill) with the 20dp info glyph: margin 12/16, padding 12/16. */
@Composable
fun GitPlainNote(text: AnnotatedString, modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(R.drawable.ic_m3_info, tint = c.onVar, size = 20.dp)
        Text(text, style = M3Type.bodyMedium, color = c.onVar)
    }
}

@Composable
fun GitPlainNote(text: String, modifier: Modifier = Modifier) = GitPlainNote(AnnotatedString(text), modifier)

/** Mono span for names inside body copy ("Deletes draft/spring on this phone."). */
@Composable
fun gitMonoInline(template: String, vararg names: String, ink: Color = M3.colors.on): AnnotatedString = buildAnnotatedString {
    // [template] holds %1$s… placeholders; each is replaced by a mono span in [ink].
    var rest = template
    val pattern = Regex("%(\\d+)\\\$[sd]")
    while (true) {
        val match = pattern.find(rest) ?: break
        append(rest.substring(0, match.range.first))
        val index = match.groupValues[1].toInt() - 1
        withStyle(SpanStyle(fontFamily = TideFonts.Mono, color = ink)) { append(names.getOrElse(index) { "" }) }
        rest = rest.substring(match.range.last + 1)
    }
    append(rest)
}

/** Ahead/behind summary ("vs origin/main · 3 ahead · 0 behind") with the ahead count in 600. */
@Composable
fun gitAheadBehind(prefix: String, ahead: Int, behind: Int): AnnotatedString = buildAnnotatedString {
    append(prefix); append(" · ")
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(stringResource(R.string.git_m3_ahead, ahead)) }
    append(" · "); append(stringResource(R.string.git_m3_behind, behind))
}

/** "2m ago", "3h ago", "4d ago", then a short date ("Oct 29"). */
fun gitAgo(context: Context, timeMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (timeMs == null || timeMs <= 0) return ""
    val minutes = ((now - timeMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 1 -> context.getString(R.string.git_m3_ago_now)
        minutes < 60 -> context.getString(R.string.git_m3_ago_minutes, minutes.toInt())
        minutes < 60 * 24 -> context.getString(R.string.git_m3_ago_hours, (minutes / 60).toInt())
        minutes < 60 * 24 * 7 -> context.getString(R.string.git_m3_ago_days, (minutes / (60 * 24)).toInt())
        else -> gitShortDate(context, timeMs)
    }
}

/** Compact age without "ago" for list metadata ("20m", "1h", "Oct 31"). */
fun gitAge(context: Context, timeMs: Long?, now: Long = System.currentTimeMillis()): String {
    if (timeMs == null || timeMs <= 0) return ""
    val minutes = ((now - timeMs) / 60_000L).coerceAtLeast(0)
    return when {
        minutes < 60 -> context.getString(R.string.git_m3_age_minutes, minutes.toInt().coerceAtLeast(1))
        minutes < 60 * 24 -> context.getString(R.string.git_m3_age_hours, (minutes / 60).toInt())
        minutes < 60 * 24 * 7 -> context.getString(R.string.git_m3_age_days, (minutes / (60 * 24)).toInt())
        else -> gitShortDate(context, timeMs)
    }
}

/** Localized "Oct 29". */
fun gitShortDate(context: Context, timeMs: Long): String {
    val locale = context.resources.configuration.locales[0]
    val pattern = android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd")
    return java.text.SimpleDateFormat(pattern, locale).format(java.util.Date(timeMs))
}

/** "Documents › Obsidian › VoxVault" for a folder on shared storage; app folders read "App storage › …". */
fun gitFolderTrail(context: Context, path: String): String {
    if (path.isBlank()) return ""
    val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
    val bases = buildList {
        add(context.filesDir.canonicalPath to context.getString(R.string.vault_l10n_app_storage))
        context.getExternalFilesDir(null)?.let { add(it.canonicalPath to context.getString(R.string.vault_l10n_app_external_storage)) }
        add("/storage/emulated/0" to null)
        add("/sdcard" to null)
    }
    val base = bases.firstOrNull { canonical.startsWith(it.first + "/") }
    val parts = buildList {
        base?.second?.let(::add)
        addAll((if (base != null) canonical.removePrefix(base.first + "/") else canonical.trimStart('/')).split('/').filter { it.isNotBlank() })
    }
    return parts.joinToString(" › ")
}

/** Remote without scheme, credentials or ".git" ("github.com/CodyBontecou/VoxVault"). */
fun gitRemoteDisplay(url: String?): String? {
    val raw = url?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    val scp = Regex("^[^@/]+@([^:/]+):(.+)$").matchEntire(raw)
    val text = if (scp != null) "${scp.groupValues[1]}/${scp.groupValues[2]}"
    else raw.replace(Regex("^[a-zA-Z][a-zA-Z0-9+.-]*://"), "").replace(Regex("^[^@/]*@"), "")
    return text.removeSuffix("/").removeSuffix(".git")
}

/** "github.com/CodyBontecou" — host and owner only, for the Git tab's Remote row. */
fun gitRemoteOwner(url: String?): String? = gitRemoteDisplay(url)?.split('/')?.take(2)?.joinToString("/")

/** True for a GitHub remote: its copy reads "GitHub"; other hosts read "the remote". */
fun gitIsGitHub(url: String?): Boolean = url?.contains("github.com", ignoreCase = true) == true

/** The incoming side's name: "GitHub" for a GitHub remote, the host otherwise. */
@Composable
fun gitRemoteName(url: String?): String = when {
    gitIsGitHub(url) -> stringResource(R.string.git_m3_github)
    else -> gitRemoteDisplay(url)?.substringBefore('/') ?: stringResource(R.string.git_m3_the_remote)
}

/** Small tonal pill (12/16 600, padding 4/10, 8dp corners). */
@Composable
fun GitStatePill(text: String, container: Color, content: Color, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(text, modifier.background(container, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
        style = if (mono) M3Type.metaStrong else M3Type.badge, color = content, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Outlined chip pill for "light" tags; filled pc for "annotated". */
@Composable
fun GitKindTag(text: String, filled: Boolean, modifier: Modifier = Modifier) {
    val c = M3.colors
    val shape = RoundedCornerShape(6.dp)
    Text(text,
        modifier.then(if (filled) Modifier.background(c.pc, shape) else Modifier.border(1.dp, c.outlineVar, shape)).padding(horizontal = 8.dp, vertical = 2.dp),
        style = TextStyle(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold), color = if (filled) c.onPc else c.onVar)
}

/** Version/commit dot: filled (ours) or ringed (theirs) in [color]. */
@Composable
fun GitSideDot(color: Color, ring: Boolean, modifier: Modifier = Modifier, size: Dp = 12.dp) {
    Box(modifier.size(size).then(if (ring) Modifier.border(2.dp, color, CircleShape) else Modifier.background(color, CircleShape)))
}

/** The folder of a working copy, as the Git tab and Manage vault show it. */
@Composable
fun rememberGitFolderTrail(path: String): String = gitFolderTrail(LocalContext.current, path)
