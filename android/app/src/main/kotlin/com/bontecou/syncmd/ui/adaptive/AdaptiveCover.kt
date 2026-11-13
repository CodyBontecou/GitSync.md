package com.bontecou.syncmd.ui.adaptive

import com.bontecou.syncmd.ui.util.quantityPhrase
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.data.models.GitStatusEntry
import com.bontecou.syncmd.domain.repository.PullRepository
import com.bontecou.syncmd.services.git.FileLineCount
import com.bontecou.syncmd.services.git.VaultInsights
import com.bontecou.syncmd.ui.components.relativeAgo
import com.bontecou.syncmd.ui.components.rememberMinuteClock
import com.bontecou.syncmd.ui.screens.HomeVault
import com.bontecou.syncmd.ui.screens.changeBadge
import com.bontecou.syncmd.ui.theme.M3
import com.bontecou.syncmd.ui.theme.M3Avatar
import com.bontecou.syncmd.ui.theme.M3Icon
import com.bontecou.syncmd.ui.theme.M3Tone
import com.bontecou.syncmd.ui.theme.M3Type
import com.bontecou.syncmd.ui.theme.colors
import com.bontecou.syncmd.ui.util.displayIdentity
import com.bontecou.syncmd.ui.viewmodels.HomeViewModel
import com.bontecou.syncmd.ui.viewmodels.SavedRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Reads the changed files of a vault that needs attention, for the cover card's mini list. */
@HiltViewModel
class CoverViewModel @Inject constructor(private val pull: PullRepository) : ViewModel() {
    suspend fun files(path: String): List<Pair<GitStatusEntry, FileLineCount?>> = withContext(Dispatchers.IO) {
        val status = pull.getStatus(path).getOrNull() ?: return@withContext emptyList()
        val counts = runCatching { VaultInsights.overview(path).lineCounts }.getOrDefault(emptyMap())
        status.allChanges.distinctBy { it.filePath }.sortedBy { it.filePath }.map { it to counts[it.filePath] }
    }
}

/**
 * Home on a foldable's cover display (board FoldCover): the mono wordmark and avatar, "1 vault
 * needs you.", each vault that needs you as an expanded card (↑N to push, the count, its files),
 * the vaults in sync grouped in one card, the unfold tip, and Sync all ↓N ↑N beside Add.
 */
@Composable
fun CoverHome(
    repositories: List<SavedRepository>,
    avatarInitials: String?,
    onOpenVault: (String) -> Unit,
    onAddVault: () -> Unit,
    onAccount: () -> Unit,
    home: HomeViewModel = hiltViewModel(),
    cover: CoverViewModel = hiltViewModel(),
) {
    val vaults = rememberAdaptiveVaults(repositories, home)
    val syncAll by home.syncAll.collectAsState()
    val online by home.online.collectAsState()
    val now = rememberMinuteClock()
    val attention = vaults.filter { it.needsAttention }
    val cards = attention.map { v ->
        key(v.repo.path) {
            val files by produceState(emptyList<Pair<GitStatusEntry, FileLineCount?>>(), v.repo.path, v.loaded) { value = cover.files(v.repo.path) }
            coverCard(v, files)
        }
    }
    CoverHomeContent(
        attentionCount = attention.size,
        total = vaults.size,
        inSyncCount = vaults.count { it.inSync },
        checked = vaults.mapNotNull { it.loaded?.checkedAt ?: it.loaded?.lastSyncAt }.maxOrNull()?.let { relativeAgo(it, now, longMinutes = false) },
        cards = cards,
        inSync = vaults.filter { !it.needsAttention }.map { v ->
            CoverRowUi(v.repo.path, v.name, v.loaded?.lastSyncAt?.let { relativeAgo(it, now) } ?: stringResource(R.string.m3home_meta_loading), v.inSync)
        },
        behind = vaults.sumOf { it.behind },
        ahead = vaults.sumOf { it.ahead },
        syncing = syncAll.busy,
        syncProgress = if (syncAll.busy) stringResource(R.string.m3home_syncing_meta, syncAll.done, syncAll.total) else null,
        canSync = online && !syncAll.busy,
        avatarInitials = avatarInitials,
        onOpenVault = onOpenVault,
        onSyncAll = home::syncAll,
        onAddVault = onAddVault,
        onAccount = onAccount,
    )
}

@Composable
private fun coverCard(v: HomeVault, files: List<Pair<GitStatusEntry, FileLineCount?>>): CoverCardUi {
    val s = v.loaded
    val identity = v.repo.displayIdentity()
    val badge = when {
        s == null || !s.safe || v.failed -> stringResource(R.string.m3home_badge_needs_you)
        s.ahead > 0 && s.behind > 0 -> stringResource(R.string.m3home_badge_both, s.behind, s.ahead)
        s.ahead > 0 -> stringResource(R.string.m3home_badge_to_push, s.ahead)
        s.behind > 0 -> stringResource(R.string.m3home_badge_to_pull, s.behind)
        s.changes > 0 -> pluralStringResource(R.plurals.m3home_badge_edited, s.changes, s.changes)
        else -> null
    }
    val eyebrow = listOfNotNull(s?.branch?.ifBlank { null }, identity.owner?.let { "$it/${identity.name}" }).joinToString(" · ")
    return CoverCardUi(v.repo.path, v.name, eyebrow, badge, s?.changes ?: 0, files.map { (entry, count) ->
        val (letter, tone) = changeBadge(entry)
        ChangeRowUi(entry.filePath, letter, tone, count?.added, count?.removed, entry.isConflicted)
    })
}

data class CoverCardUi(val path: String, val name: String, val eyebrow: String, val badge: String?, val changes: Int, val files: List<ChangeRowUi>)
data class CoverRowUi(val path: String, val name: String, val whenText: String, val inSync: Boolean)

/** Stateless cover Home. */
@Composable
fun CoverHomeContent(
    attentionCount: Int,
    total: Int,
    inSyncCount: Int,
    checked: String?,
    cards: List<CoverCardUi>,
    inSync: List<CoverRowUi>,
    behind: Int,
    ahead: Int,
    syncing: Boolean,
    syncProgress: String?,
    canSync: Boolean,
    avatarInitials: String?,
    onOpenVault: (String) -> Unit,
    onSyncAll: () -> Unit,
    onAddVault: () -> Unit,
    onAccount: () -> Unit,
) {
    val c = M3.colors
    Column(Modifier.fillMaxSize().background(c.surface).statusBarsPadding().navigationBarsPadding().testTag("cover-home")) {
        Row(Modifier.fillMaxWidth().height(64.dp).padding(start = 20.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Wordmark(M3Type.metaStrong.copy(fontSize = 15.sp), Modifier.weight(1f))
            val label = stringResource(R.string.adaptive_account)
            Box(Modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onAccount).semantics { contentDescription = label }
                .testTag("open-settings"), contentAlignment = Alignment.Center) {
                if (avatarInitials != null) M3Avatar(avatarInitials, size = 32.dp)
                else M3Icon(R.drawable.ic_m3_settings, tint = c.onVar)
            }
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.padding(start = 4.dp, end = 4.dp, top = 4.dp)) {
                val headline = if (attentionCount > 0)
                    quantityPhrase(R.plurals.m3home_headline_attention_accent, attentionCount,
                        pluralStringResource(R.plurals.m3home_headline_attention, attentionCount, attentionCount)).text
                else pluralStringResource(R.plurals.m3home_headline_ready, total, total) + " " + stringResource(R.string.m3home_headline_ready_accent)
                Text(headline, style = M3Type.headline.copy(lineHeight = 36.sp, letterSpacing = (-0.3).sp), color = c.on, modifier = Modifier.testTag("cover-headline"))
                Text(listOfNotNull(stringResource(R.string.m3home_meta_in_sync, inSyncCount),
                    checked?.let { stringResource(R.string.m3home_meta_checked, it) } ?: stringResource(R.string.m3home_meta_never_checked)).joinToString(" · "),
                    Modifier.padding(top = 2.dp), style = M3Type.meta, color = c.onVar)
            }
            cards.forEach { card -> AttentionCard(card) { onOpenVault(card.path) } }
            if (inSync.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.cLow).padding(vertical = 4.dp)) {
                inSync.forEachIndexed { index, row ->
                    if (index > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.outlineVar))
                    Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(role = Role.Button) { onOpenVault(row.path) }
                        .padding(horizontal = 18.dp).testTag("cover-vault-${row.path}"),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(row.name, Modifier.weight(1f), style = M3Type.body.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
                            color = c.on, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (row.inSync) M3Icon(R.drawable.ic_m3_check, tint = c.ok, size = 16.dp)
                        Text(row.whenText, Modifier.padding(start = 6.dp), style = M3Type.meta, color = c.onVar, maxLines = 1)
                    }
                }
            }
            val tipShape = RoundedCornerShape(20.dp)
            Row(Modifier.fillMaxWidth().border(1.dp, c.outlineVar, tipShape).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                M3Icon(R.drawable.ic_m3_vertical_split, tint = c.onVar)
                Text(stringResource(R.string.adaptive_cover_unfold), style = M3Type.bodyMedium, color = c.onVar)
            }
            Box(Modifier.height(8.dp))
        }
        Row(Modifier.fillMaxWidth().background(c.surface).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val shape = RoundedCornerShape(16.dp)
            Row(Modifier.weight(1f).height(56.dp).shadow(3.dp, shape).clip(shape).background(c.pc)
                .clickable(enabled = canSync, role = Role.Button, onClick = onSyncAll).testTag("cover-sync-all"),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally)) {
                if (syncing) com.bontecou.syncmd.ui.theme.M3Spinner(color = c.onPc, size = 20.dp) else M3Icon(R.drawable.ic_m3_sync, tint = c.onPc)
                Text(stringResource(if (syncing) R.string.m3home_syncing else R.string.m3home_sync_all), style = M3Type.buttonXLarge, color = c.onPc)
                Text(syncProgress ?: stringResource(R.string.m3home_sync_all_meta, behind, ahead),
                    style = M3Type.meta.copy(fontWeight = FontWeight.Medium, textDirection = TextDirection.Ltr), color = c.onPc.copy(alpha = 0.8f))
            }
            val add = stringResource(R.string.m3home_add_vault)
            Box(Modifier.size(56.dp).clip(shape).background(c.sc).clickable(role = Role.Button, onClick = onAddVault)
                .semantics { contentDescription = add }.testTag("cover-add"), contentAlignment = Alignment.Center) {
                M3Icon(R.drawable.ic_m3_add, tint = c.onSc)
            }
        }
    }
}

/** tc card: name over "main · owner/repo", the ↑N badge on surface, the count and up to four files. */
@Composable
private fun AttentionCard(card: CoverCardUi, onClick: () -> Unit) {
    val c = M3.colors
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.tc).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 18.dp, vertical = 16.dp).testTag("cover-attention-${card.path}"),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(card.name, style = M3Type.lead.copy(fontWeight = FontWeight.SemiBold), color = c.onTc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (card.eyebrow.isNotEmpty()) Text(card.eyebrow, style = M3Type.metaSmall.copy(textDirection = TextDirection.Ltr), color = c.onTc,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (card.badge != null) Text(card.badge, Modifier.background(c.surface, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 4.dp),
                style = M3Type.metaStrong, color = c.ahead, maxLines = 1)
        }
        val changePhrase = changesOnDevice(false, card.changes)
        if (card.changes > 0) Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(changePhrase.value, style = M3Type.displaySmall.copy(fontSize = 40.sp, lineHeight = 44.sp, letterSpacing = 0.sp), color = c.onTc)
            Text(changePhrase.label, Modifier.padding(bottom = 6.dp), style = M3Type.bodyMedium, color = c.onTc)
        }
        if (card.files.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            card.files.take(4).forEach { f ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    val (bg, fg) = if (f.tone == M3Tone.Ahead) c.surface to c.onTc else f.tone.colors()
                    Box(Modifier.size(22.dp).background(bg, RoundedCornerShape(7.dp)), contentAlignment = Alignment.Center) {
                        Text(f.letter, style = M3Type.tag, color = fg)
                    }
                    Text(f.path, Modifier.weight(1f), style = M3Type.bodySmall, color = c.onTc, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(f.added?.takeIf { it > 0 }?.let { "+$it" }, f.removed?.takeIf { it > 0 }?.let { "−$it" }).joinToString(" "),
                        style = M3Type.tag.copy(textDirection = TextDirection.Ltr), color = c.onTc)
                }
            }
        }
    }
}
