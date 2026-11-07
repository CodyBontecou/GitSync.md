package com.bontecou.syncmd.ui.components

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.git.CloneTransferPhase
import com.bontecou.syncmd.services.git.TransferProgressUpdate
import com.bontecou.syncmd.storage.RepositoryLocations
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Button
import com.bontecou.syncmd.ui.theme.M3ButtonSize
import com.bontecou.syncmd.ui.theme.M3ButtonStyle
import com.bontecou.syncmd.ui.theme.M3Dialog
import com.bontecou.syncmd.ui.theme.M3DialogAction
import com.bontecou.syncmd.ui.theme.M3Hero
import com.bontecou.syncmd.ui.theme.M3HeroPill
import com.bontecou.syncmd.ui.theme.M3IndeterminateProgressBar
import com.bontecou.syncmd.ui.theme.M3LeadingTile
import com.bontecou.syncmd.ui.theme.M3ListCard
import com.bontecou.syncmd.ui.theme.M3Note
import com.bontecou.syncmd.ui.theme.M3NoteTone
import com.bontecou.syncmd.ui.theme.M3ProgressBar
import com.bontecou.syncmd.ui.theme.M3ProgressTone
import com.bontecou.syncmd.ui.theme.M3RailState
import com.bontecou.syncmd.ui.theme.M3Spinner
import com.bontecou.syncmd.ui.theme.M3SyncRail
import com.bontecou.syncmd.ui.theme.M3TonalButton
import com.bontecou.syncmd.ui.theme.M3TextButton
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.m3RailSpec
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

// ═══ Add-a-vault parts (M3) ══════════════════════════════════════════════════
// Shared by Add a vault, Pick a repo, Any Git host, Cloning, Local repositories and Publish a folder.

// ─── Formatting ─────────────────────────────────────────────────────────────

/** "4.0 MB", "18 KB", "210 MB", "1.2 GB" (decimal units, one decimal under 10). */
fun addVaultSize(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble(); var unit = 0
    while (value >= 1000 && unit < units.lastIndex) { value /= 1000; unit++ }
    return when {
        unit == 0 -> "$bytes B"
        value < 10 -> String.format(Locale.US, "%.1f %s", value, units[unit])
        else -> "${value.roundToInt()} ${units[unit]}"
    }
}

/** "1.8 / 4.0 MB": both in the larger number's unit. */
fun addVaultSizePair(done: Long, total: Long): String {
    val totalText = addVaultSize(total)
    val unit = totalText.substringAfter(' ')
    val factor = when (unit) { "KB" -> 1e3; "MB" -> 1e6; "GB" -> 1e9; "TB" -> 1e12; else -> 1.0 }
    val doneValue = done / factor
    val doneText = if (unit == "B") done.toString() else if (doneValue < 10) String.format(Locale.US, "%.1f", doneValue) else doneValue.roundToInt().toString()
    return "$doneText / $totalText"
}

private val isoFormats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX").map {
    SimpleDateFormat(it, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
}

fun parseGitHubTime(iso: String?): Long? = iso?.let { text -> isoFormats.firstNotNullOfOrNull { runCatching { it.parse(text)?.time }.getOrNull() } }

/** "2h ago", "3d ago", "1w ago", "8mo ago": the design's compact age. */
@Composable
fun addVaultAge(millis: Long?, now: Long = System.currentTimeMillis()): String? {
    millis ?: return null
    val minutes = ((now - millis) / 60_000).coerceAtLeast(0)
    return when {
        minutes < 1 -> stringResource(R.string.add_ago_now)
        minutes < 60 -> stringResource(R.string.add_ago_minutes, minutes.toInt())
        minutes < 60 * 24 -> stringResource(R.string.add_ago_hours, (minutes / 60).toInt())
        minutes < 60 * 24 * 7 -> stringResource(R.string.add_ago_days, (minutes / (60 * 24)).toInt())
        minutes < 60 * 24 * 30 -> stringResource(R.string.add_ago_weeks, (minutes / (60 * 24 * 7)).toInt())
        minutes < 60 * 24 * 365 -> stringResource(R.string.add_ago_months, (minutes / (60 * 24 * 30)).toInt())
        else -> stringResource(R.string.add_ago_years, (minutes / (60 * 24 * 365)).toInt())
    }
}

/**
 * A device path the way the design writes it: "Documents / GitSync.md / reading-vault", or
 * "App storage / VoxVault" inside the app's own folders. [separator] is " / " or " › ".
 */
fun addVaultFriendlyPath(context: Context, path: String, separator: String = " / "): String {
    val app = context.getString(R.string.vault_l10n_app_storage)
    @Suppress("DEPRECATION")
    val bases = buildList {
        add(runCatching { context.filesDir.canonicalPath }.getOrNull() to app)
        context.externalMediaDirs.firstOrNull()?.let { add(runCatching { it.canonicalPath }.getOrNull() to app) }
        context.getExternalFilesDir(null)?.let { add(runCatching { it.canonicalPath }.getOrNull() to app) }
        add("/storage/emulated/0" to null)
        add("/sdcard" to null)
    }
    val clean = path.trimEnd('/')
    for ((base, label) in bases) {
        if (base == null || !(clean == base || clean.startsWith("$base/"))) continue
        val rest = clean.removePrefix(base).trim('/').split('/').filter { it.isNotEmpty() }
        return (listOfNotNull(label) + rest).joinToString(separator).ifEmpty { label ?: clean }
    }
    return clean.trim('/').split('/').joinToString(separator)
}

// ─── Folder access + picker ─────────────────────────────────────────────────

/** Board LocalClones "access": Allow local folder access → Android's all-files setting. */
@Composable
fun AddVaultAccessDialog(onDismiss: () -> Unit, onEnable: () -> Unit) {
    val c = M3.colors
    M3Dialog(onDismissRequest = onDismiss, title = stringResource(R.string.add_access_title), icon = R.drawable.ic_m3_folder,
        text = stringResource(R.string.add_access_body), modifier = Modifier.testTag("add-access-dialog"),
        content = {
            Text(stringResource(R.string.add_access_note), style = M3Type.bodySmall, color = c.onVar,
                modifier = Modifier.fillMaxWidth().background(c.surface, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp))
        }) {
        M3DialogAction(stringResource(R.string.add_access_cancel), onDismiss)
        M3Button(stringResource(R.string.add_access_enable), onEnable, style = M3ButtonStyle.Filled, size = M3ButtonSize.Medium,
            modifier = Modifier.testTag("add-access-enable"))
    }
}

/**
 * The system folder picker behind the storage-access explanation. Returns the launcher; it shows
 * [AddVaultAccessDialog] first when shared storage access is off. Permission alone never selects a folder.
 */
@Composable
fun rememberAddVaultFolderPicker(onFolder: (Uri) -> Unit, onError: (String) -> Unit, initialUri: Uri? = null, requireAccess: Boolean = true): () -> Unit {
    val context = LocalContext.current
    var explain by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> uri?.let(onFolder) }
    fun openPicker() {
        val initial = initialUri?.let { if (DocumentsContract.isTreeUri(it)) DocumentsContract.buildDocumentUriUsingTree(it, DocumentsContract.getTreeDocumentId(it)) else it }
        runCatching { picker.launch(initial) }.onFailure { onError(context.getString(R.string.folder_picker_unavailable)) }
    }
    val afterSettings = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else onError(context.getString(R.string.folder_access_not_enabled))
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else onError(context.getString(R.string.folder_access_not_enabled))
    }
    if (explain) AddVaultAccessDialog(onDismiss = { explain = false }, onEnable = {
        explain = false
        if (Build.VERSION.SDK_INT >= 30) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))
            runCatching { afterSettings.launch(intent) }.recoverCatching { afterSettings.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
                .onFailure { onError(context.getString(R.string.folder_settings_unavailable)) }
        } else permissions.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
    })
    return { if (!requireAccess || RepositoryLocations.hasSharedStorageAccess(context)) openPicker() else explain = true }
}

// ─── Clone location (board Cloning "ready" / "failed") ──────────────────────

/** Null means the saved default; [RepositoryLocations.APP] chooses app storage for just this clone. */
@Composable
fun AddVaultLocationCard(selection: String?, onSelection: (String?) -> Unit, repoName: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val c = M3.colors
    val context = LocalContext.current
    val locations = remember(context) { RepositoryLocations(context) }
    val scope = rememberCoroutineScope()
    var error by remember { mutableStateOf<String?>(null) }
    var saving by remember { mutableStateOf(false) }
    val base = runCatching { locations.summary(selection).path }.getOrNull()
    val label = base?.let { addVaultFriendlyPath(context, "$it/$repoName") } ?: stringResource(R.string.clone_location_reconnect)
    val choose = rememberAddVaultFolderPicker(onFolder = { uri ->
        saving = true; error = null
        scope.launch {
            try { onSelection(withContext(Dispatchers.IO) { locations.rememberFolder(uri) }.uri) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { error = e.message ?: context.getString(R.string.folder_unusable) }
            finally { saving = false }
        }
    }, onError = { error = it }, initialUri = runCatching { locations.selectedFolders().firstOrNull()?.uri?.let(Uri::parse) }.getOrNull())
    M3ListCard(modifier.testTag("add-clone-location")) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                M3LeadingTile(R.drawable.ic_m3_folder)
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.add_clone_location), style = M3Type.bodyStrong.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = c.on)
                    Text(label, style = M3Type.meta, color = c.onVar, modifier = Modifier.testTag("clone-location-path"))
                }
            }
            Spacer(Modifier.height(14.dp))
            M3TonalButton(stringResource(R.string.add_clone_choose_folder), onClick = choose, icon = R.drawable.ic_m3_folder,
                enabled = enabled && !saving, loading = saving, modifier = Modifier.testTag("device-folder-picker"))
            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (selection != RepositoryLocations.APP) M3TextButton(stringResource(R.string.add_clone_app_storage),
                    onClick = { onSelection(RepositoryLocations.APP); error = null }, enabled = enabled && !saving, modifier = Modifier.testTag("clone-location-app"))
                if (selection != null) M3TextButton(stringResource(R.string.add_clone_saved_default),
                    onClick = { onSelection(null); error = null }, enabled = enabled && !saving, modifier = Modifier.testTag("clone-location-default"))
            }
            error?.let { M3Note(it, Modifier.padding(top = 8.dp), tone = M3NoteTone.Warn) }
        }
    }
}

// ─── Clone progress ─────────────────────────────────────────────────────────

/** "Receiving objects", "Resolving deltas"… for the current JGit task. */
@Composable
fun addVaultTaskLabel(progress: TransferProgressUpdate?): String = stringResource(when (progress?.phase) {
    CloneTransferPhase.RECEIVING -> R.string.add_clone_receiving
    CloneTransferPhase.RESOLVING -> R.string.add_clone_resolving
    CloneTransferPhase.CHECKOUT -> R.string.add_clone_checkout
    null -> R.string.add_clone_preparing
    else -> R.string.add_clone_receiving
})

/** "Receiving objects · 1.8 / 4.0 MB" (or "· 1.8 MB" when the total is unknown). */
@Composable
fun addVaultProgressLine(progress: TransferProgressUpdate?, totalBytes: Long?): String {
    val task = addVaultTaskLabel(progress)
    val received = progress?.bytesOnDisk?.takeIf { it > 0 }
    return when {
        received != null && totalBytes != null && totalBytes > 0 -> stringResource(R.string.add_clone_bytes, task, addVaultSizePair(received.coerceAtMost(totalBytes), totalBytes))
        received != null -> stringResource(R.string.add_clone_bytes, task, addVaultSize(received))
        else -> task
    }
}

/** The receive task's share (resolving and checkout keep the bar full while they finish). */
fun addVaultFraction(progress: TransferProgressUpdate?): Float? = when (progress?.phase) {
    null -> null
    CloneTransferPhase.RECEIVING -> progress.fraction
    else -> progress.fraction?.let { 0.9f + it * 0.1f } ?: 1f
}

/**
 * Board Cloning hero: inverse card with "owner/" over the repo name, a "Cloned" pill when done,
 * the meta line, the phone → GitHub rail and, while cloning, a progress bar and its task line.
 */
@Composable
fun AddVaultCloneHero(fullName: String, meta: String?, cloned: Boolean, cloning: Boolean, progress: TransferProgressUpdate?, totalBytes: Long?, modifier: Modifier = Modifier) {
    val c = M3.colors
    val owner = fullName.substringBeforeLast('/', "")
    val name = fullName.substringAfterLast('/')
    M3Hero(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("add-clone-hero")) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                if (owner.isNotEmpty()) Text("$owner/", style = M3Type.meta, color = c.invMeta)
                Text(name, style = M3Type.headline, color = c.onInv, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (cloned) M3HeroPill(stringResource(R.string.add_clone_cloned), c.invOk)
        }
        if (meta != null) Text(meta, style = M3Type.meta, color = c.invMeta, modifier = Modifier.padding(top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(top = 20.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.add_rail_phone), style = M3Type.metaSmall, color = c.invMeta)
            M3SyncRail(m3RailSpec(if (cloned) M3RailState.InSync else M3RailState.Incoming, onHero = true), Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(stringResource(R.string.add_rail_github), style = M3Type.metaSmall, color = c.invMeta)
        }
        if (cloning) {
            val fraction = addVaultFraction(progress)
            Spacer(Modifier.height(20.dp))
            if (fraction != null) M3ProgressBar(fraction, tone = M3ProgressTone.Hero, description = stringResource(R.string.add_clone_busy))
            else M3IndeterminateProgressBar(tone = M3ProgressTone.Hero, description = stringResource(R.string.add_clone_busy))
            Row(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(addVaultProgressLine(progress, totalBytes), style = M3Type.meta, color = c.invMeta, modifier = Modifier.weight(1f).testTag("add-clone-progress-line"))
                if (fraction != null) Text(stringResource(R.string.add_clone_percent, (fraction * 100).roundToInt()), style = M3Type.meta, color = c.invMeta)
            }
            Row(Modifier.padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                M3Spinner(color = c.onInv, size = 14.dp)
                Text(stringResource(R.string.add_clone_busy), style = M3Type.bodyMedium, color = c.onInv)
            }
        }
    }
}

/** Board 13's bottom status: "Cloning reading-vault  1.8 / 4.0 MB", a bar, "→ Documents › GitSync.md › reading-vault". */
@Composable
fun AddVaultCloneStatusBar(name: String, progress: TransferProgressUpdate?, totalBytes: Long?, destination: String?, modifier: Modifier = Modifier) {
    val c = M3.colors
    val fraction = addVaultFraction(progress)
    val received = progress?.bytesOnDisk?.takeIf { it > 0 }
    Column(
        modifier.fillMaxWidth().background(c.inv, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }.testTag("add-clone-status"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.add_cloning_name, name), style = M3Type.button, color = c.onInv, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            val amount = when {
                received != null && totalBytes != null && totalBytes > 0 -> addVaultSizePair(received.coerceAtMost(totalBytes), totalBytes)
                received != null -> addVaultSize(received)
                else -> null
            }
            if (amount != null) Text(amount, style = M3Type.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, fontSize = M3Type.button.fontSize), color = c.onInv)
        }
        Spacer(Modifier.height(10.dp))
        if (fraction != null) M3ProgressBar(fraction, tone = M3ProgressTone.Hero) else M3IndeterminateProgressBar(tone = M3ProgressTone.Hero)
        if (destination != null) Text(stringResource(R.string.add_clone_to, destination), style = M3Type.metaSmall, color = c.invMeta,
            modifier = Modifier.padding(top = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A short busy line: 16dp ring spinner and onVar text ("Finishing up"). */
@Composable
fun AddVaultBusy(text: String, modifier: Modifier = Modifier) {
    val c = M3.colors
    Row(modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        M3Spinner(color = c.onVar, size = 16.dp)
        Text(text, style = M3Type.body.copy(fontSize = M3Type.buttonLarge.fontSize), color = c.onVar)
    }
}

/** The 40dp pc/onPc icon tile + two-line row used by Add a vault and Local repositories ("way" rows). */
@Composable
fun AddVaultWayRow(title: String, subtitle: String, icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, divider: Boolean = false, enabled: Boolean = true) {
    com.bontecou.syncmd.ui.theme.M3ListRow(title, modifier, meta = subtitle, metaMono = false, divider = divider, enabled = enabled,
        leading = { M3LeadingTile(icon) }, trailing = { com.bontecou.syncmd.ui.theme.M3Chevron() }, onClick = onClick, minHeight = 76.dp)
}

/** Inline error/success row inside a card: 20dp icon + 14sp text (board Cloning "failed", LocalClones "empty"). */
@Composable
fun AddVaultInlineIssue(text: String, modifier: Modifier = Modifier, icon: Int = R.drawable.ic_m3_error, tint: androidx.compose.ui.graphics.Color = M3.colors.error) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        com.bontecou.syncmd.ui.theme.M3Icon(icon, tint = tint, size = 20.dp, modifier = Modifier.padding(top = 1.dp))
        Text(text, style = M3Type.bodyMedium, color = M3.colors.on, modifier = Modifier.weight(1f))
    }
}

/** The design's mono code chip ("Documents/.trash: not readable, skipped"). */
@Composable
fun AddVaultCodeNote(text: String, modifier: Modifier = Modifier) {
    val c = M3.colors
    Text(text, style = M3Type.meta, color = c.onVar,
        modifier = modifier.fillMaxWidth().background(c.cHighest, RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 10.dp))
}

/** 3-segment step line (PublishFolder, Vault check). */
@Composable
fun AddVaultStepBars(step: Int, modifier: Modifier = Modifier, track: androidx.compose.ui.graphics.Color = M3.colors.cHighest) {
    val c = M3.colors
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(3) { i -> Box(Modifier.weight(1f).height(4.dp).background(if (i < step) c.primary else track, RoundedCornerShape(2.dp))) }
    }
}

/** Initials avatar for an account ("CB"). */
fun addVaultInitials(name: String?, login: String?): String {
    val source = name?.takeIf { it.isNotBlank() } ?: login.orEmpty()
    val words = source.split(Regex("[\\s_-]+")).filter { it.isNotEmpty() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}".uppercase()
        words.size == 1 -> words[0].take(1).uppercase()
        else -> "?"
    }
}
