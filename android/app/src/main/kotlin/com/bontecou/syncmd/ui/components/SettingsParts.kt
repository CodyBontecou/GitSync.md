package com.bontecou.syncmd.ui.components

import android.content.Context
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.diagnostics.AppDiagnostics
import com.bontecou.syncmd.security.DefaultAuthorStore
import com.bontecou.syncmd.storage.VaultPreferences
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3Type
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

// Settings-area pieces the M3 kit doesn't have (boards 15–18, 21 and the settings pages).

/** App singletons the settings screens read directly. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SettingsEntryPoint {
    fun defaultAuthor(): DefaultAuthorStore
    fun diagnostics(): AppDiagnostics
    fun vaultPreferences(): VaultPreferences
}

fun Context.settingsEntryPoint(): SettingsEntryPoint = EntryPointAccessors.fromApplication(applicationContext, SettingsEntryPoint::class.java)

/** Plain hint under a card ("Phone storage and SD cards work…"): 20dp onVar icon, 13/18 onVar text, 4dp padding. */
@Composable
fun SettingsHint(text: String, modifier: Modifier = Modifier, @DrawableRes icon: Int = R.drawable.ic_m3_info) {
    val c = M3.colors
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 12.dp).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        M3Icon(icon, tint = c.onVar, size = 20.dp)
        Text(text, style = M3Type.bodySmall, color = c.onVar, modifier = Modifier.weight(1f))
    }
}

/** Intro paragraph under a top bar: 14/20 onVar, margin 4/20/0. */
@Composable
fun SettingsIntro(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp), style = M3Type.bodyMedium, color = M3.colors.onVar)
}

/** Status line with an 8dp dot ("● This phone is registered for notifications"): mono 12 onVar, margin 12/20. */
@Composable
fun SettingsDotLine(text: String, dot: Color, modifier: Modifier = Modifier, ink: Color = M3.colors.onVar) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(Modifier.size(8.dp).background(dot, CircleShape))
        Text(text, style = M3Type.meta, color = ink)
    }
}

/** Folder card (Storage): 28dp cLow, padding 16/20, a dotted 13/600 label, the path in mono 13 (always LTR), optional action. */
@Composable
fun SettingsFolderCard(label: String, labelColor: Color, path: String?, modifier: Modifier = Modifier, pathModifier: Modifier = Modifier,
    action: (@Composable ColumnScope.() -> Unit)? = null) {
    val c = M3.colors
    val shape = RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp).background(c.cLow, shape).padding(horizontal = 20.dp, vertical = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(8.dp).background(labelColor, CircleShape))
            Text(label, style = M3Type.bodySmall.copy(fontWeight = FontWeight.SemiBold), color = labelColor)
        }
        if (path != null) Text(path, pathModifier.padding(top = 6.dp), style = M3Type.code.copy(textDirection = TextDirection.Ltr), color = c.on)
        if (action != null) Column(Modifier.padding(top = 14.dp), content = action)
    }
}

/** A plain 28dp cLow card with the screen margins (8dp above, 16dp gutters). */
@Composable
fun SettingsCard(modifier: Modifier = Modifier, top: androidx.compose.ui.unit.Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    Column(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = top).background(M3.colors.cLow, shape), content = content)
}

/** Section heading with a trailing text action ("Vaults on this phone · Reload"). */
@Composable
fun SettingsSectionWithAction(title: String, action: String, onAction: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    @DrawableRes icon: Int? = R.drawable.ic_m3_refresh) {
    Row(modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f).semantics { heading() }, style = M3Type.section, color = M3.colors.primary)
        com.bontecou.syncmd.ui.theme.M3Button(action, onAction, style = com.bontecou.syncmd.ui.theme.M3ButtonStyle.Text, icon = icon, enabled = enabled)
    }
}

/** Centered mono footnote ("GitSync.md 3.4 (412)", "Saving closes this page. Nothing is committed."). */
@Composable
fun SettingsFootnote(text: String, modifier: Modifier = Modifier, mono: Boolean = false) {
    Text(text, modifier.fillMaxWidth(), style = (if (mono) M3Type.meta else M3Type.caption).copy(textAlign = TextAlign.Center), color = M3.colors.onVar)
}

/** Row of static chips that wraps ("It will never", "Push Sync never"). */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun SettingsChipFlow(modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    androidx.compose.foundation.layout.FlowRow(modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
}

/** "14m ago" / "3d ago" with the app's translated plurals; "just now" under a minute. */
@Composable
fun relativeAgo(time: Long, now: Long = System.currentTimeMillis()): String {
    val minutes = ((now - time).coerceAtLeast(0) / 60_000).toInt()
    return when {
        minutes < 1 -> stringResource(R.string.account_updated_just_now)
        minutes < 60 -> pluralStringResource(R.plurals.account_updated_minutes, minutes, minutes)
        minutes < 60 * 24 -> (minutes / 60).let { pluralStringResource(R.plurals.account_updated_hours, it, it) }
        minutes < 60 * 24 * 7 -> (minutes / (60 * 24)).let { pluralStringResource(R.plurals.account_updated_days, it, it) }
        minutes < 60 * 24 * 30 -> (minutes / (60 * 24 * 7)).let { pluralStringResource(R.plurals.account_updated_weeks, it, it) }
        minutes < 60 * 24 * 365 -> (minutes / (60 * 24 * 30)).let { pluralStringResource(R.plurals.account_updated_months, it, it) }
        else -> (minutes / (60 * 24 * 365)).let { pluralStringResource(R.plurals.account_updated_years, it, it) }
    }
}

/**
 * A folder as the design writes it: "Documents › Obsidian › VoxVault" for shared storage,
 * "App storage › notes" for the app's private clones, the raw path otherwise.
 */
@Composable
fun folderCrumbs(path: String?): String {
    val context = LocalContext.current
    val appStorage = stringResource(R.string.settings_m3_app_storage)
    return remember(path, appStorage) { folderCrumbs(context, path, appStorage) }
}

fun folderCrumbs(context: Context, path: String?, appStorage: String): String {
    if (path.isNullOrBlank()) return ""
    val managed = runCatching { com.bontecou.syncmd.storage.CloneStorage.managedCloneRoots(context).map { java.io.File(it).canonicalPath } }.getOrDefault(emptyList()) +
        listOfNotNull(context.filesDir?.canonicalPath)
    managed.firstOrNull { path == it || path.startsWith("$it/") }?.let { root ->
        val rest = path.removePrefix(root).trim('/').split('/').filter { it.isNotEmpty() }
        return (listOf(appStorage) + rest.takeLast(1)).joinToString(" › ")
    }
    val prefixes = listOf("/storage/emulated/0/", "/sdcard/")
    val relative = prefixes.firstOrNull { path.startsWith(it) }?.let { path.removePrefix(it) } ?: return path
    return relative.trim('/').split('/').filter { it.isNotEmpty() }.joinToString(" › ")
}

@Composable
private fun <T> remember(a: Any?, b: Any?, calc: () -> T): T = androidx.compose.runtime.remember(a, b) { calc() }

/** A one-line row inside a [SettingsCard] that only shows a label and a value ("App · GitSync.md"). */
@Composable
fun SettingsValueRow(label: String, value: String, modifier: Modifier = Modifier, divider: Boolean = false) =
    com.bontecou.syncmd.ui.theme.M3SettingsRow(label, modifier, value = value, divider = divider)

/** Empty card body ("Add or clone a vault into a device folder first…"). */
@Composable
fun SettingsEmptyText(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 20.dp, vertical = 16.dp), style = M3Type.bodyMedium.copy(fontSize = 14.sp),
        color = M3.colors.onVar)
}
