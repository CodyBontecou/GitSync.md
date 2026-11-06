package com.bontecou.syncmd.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.syncmd.R
import com.bontecou.syncmd.onboarding.GuideTarget
import com.bontecou.syncmd.ui.components.AddVaultIconTile
import com.bontecou.syncmd.ui.components.AddVaultProgress
import com.bontecou.syncmd.ui.components.AddVaultTextButton
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.SyncRail
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideHairline
import com.bontecou.syncmd.ui.theme.TideHero
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TidePrimaryButton
import com.bontecou.syncmd.ui.theme.TideSyncState
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun AppTourScreen(page: Int, busy: Boolean, error: Boolean, replay: Boolean, onPage: (Int) -> Unit,
    onFinish: (GuideTarget) -> Unit, onClose: () -> Unit = {}) {
    val pager = rememberPagerState(initialPage = page, pageCount = { 4 })
    var synchronized by remember { mutableStateOf(false) }
    LaunchedEffect(page) {
        synchronized = false
        if (pager.currentPage != page) pager.scrollToPage(page)
        synchronized = true
    }
    LaunchedEffect(pager.settledPage, synchronized) { if (synchronized && pager.settledPage != page) onPage(pager.settledPage) }
    BackHandler(page > 0) { onPage(page - 1) }
    val c = Tide.colors
    Scaffold(modifier = Modifier.testTag("app-tour"), containerColor = c.paper, topBar = {
        TideTopBar(
            modifier = Modifier.testTag("tour-header"),
            onBack = if (page > 0 || replay) ({ if (page > 0) onPage(page - 1) else onClose() }) else null,
            backLabel = stringResource(R.string.guide_back),
            backModifier = Modifier.testTag("tour-back"),
            center = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    com.bontecou.syncmd.ui.theme.TideWordmark(size = 18.sp)
                    Text(stringResource(R.string.guide_title), style = TideType.sans(14.sp, FontWeight.Medium), color = c.inkMid,
                        modifier = Modifier.testTag("tour-title"))
                }
            },
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            if (busy) AddVaultProgress(Modifier.padding(horizontal = Tide.gutter))
            HorizontalPager(state = pager, userScrollEnabled = !busy, modifier = Modifier.weight(1f).testTag("tour-pages")) { index ->
                TourPage(index = index, busy = busy, error = error, replay = replay, onFinish = onFinish)
            }
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = Tide.gutter, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                TourPageDots(current = page, count = 4, modifier = Modifier.padding(bottom = 6.dp))
                if (page < 3) TidePrimaryButton(stringResource(R.string.guide_continue), onClick = { onPage(page + 1) }, enabled = !busy,
                    modifier = Modifier.testTag("tour-next"))
                AddVaultTextButton(
                    stringResource(if (replay && page == 3) R.string.guide_done else R.string.guide_skip),
                    onClick = { if (replay) onClose() else onFinish(GuideTarget.HOME) },
                    enabled = !busy,
                    color = c.inkMid,
                    modifier = Modifier.fillMaxWidth().testTag("tour-skip"),
                )
            }
        }
    }
}

private val tourTitles = listOf(R.string.guide_clone_title, R.string.guide_edit_title, R.string.guide_git_title, R.string.guide_start_title)
private val tourBodies = listOf(R.string.guide_clone_body, R.string.guide_edit_body, R.string.guide_git_body, R.string.guide_start_body)

/** One tour page: mono step eyebrow, vignette, display headline, body, and the start choices on the last page. */
@Composable
private fun TourPage(index: Int, busy: Boolean, error: Boolean, replay: Boolean, onFinish: (GuideTarget) -> Unit) {
    val c = Tide.colors
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = Tide.gutter, vertical = 12.dp)) {
        Text(stringResource(R.string.guide_step, index + 1), style = TideType.section, color = c.primary,
            modifier = Modifier.padding(start = 4.dp).testTag("tour-step-$index"))
        if (index < 3) TourVignette(index, Modifier.padding(top = 16.dp))
        Text(stringResource(tourTitles[index]), style = TideType.display(36.sp).copy(lineHeight = 44.sp), color = c.ink,
            modifier = Modifier.padding(start = 4.dp, top = 22.dp).testTag("tour-page-title-$index"))
        Text(stringResource(tourBodies[index]), style = TideType.sans(16.sp).copy(lineHeight = 23.sp), color = c.inkMid,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp, top = 12.dp).testTag("tour-page-body-$index"))
        if (error) TideBanner(stringResource(R.string.guide_save_error), Modifier.padding(top = 18.dp), tone = TideBannerTone.DANGER)
        if (index == 3 && !replay) {
            // M3 board "10 · Onboarding": GitHub as the filled action, the other ways in as a list.
            TidePrimaryButton(stringResource(R.string.guide_login), onClick = { onFinish(GuideTarget.LOGIN) }, enabled = !busy,
                icon = painterResource(R.drawable.ic_github), modifier = Modifier.padding(top = 22.dp).testTag("tour-start-LOGIN"))
            TideCard(Modifier.padding(top = 12.dp), padding = 0.dp) {
                val choices = listOf(GuideTarget.URL to R.string.guide_url,
                    GuideTarget.SAMPLE to R.string.guide_sample, GuideTarget.STORAGE to R.string.guide_storage,
                    GuideTarget.HOME to R.string.guide_home)
                choices.forEachIndexed { i, (target, label) ->
                    if (i > 0) TideHairline()
                    TourChoiceRow(target, stringResource(label), enabled = !busy) { onFinish(target) }
                }
            }
        }
    }
}

@Composable
private fun TourChoiceRow(target: GuideTarget, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = Tide.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 60.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .alpha(if (enabled) 1f else 0.45f)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("tour-start-${target.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(40.dp).background(c.primaryContainer, androidx.compose.foundation.shape.RoundedCornerShape(12.dp)).clearAndSetSemantics {},
            contentAlignment = Alignment.Center) {
            com.bontecou.syncmd.ui.theme.TideIcon(when (target) {
                GuideTarget.URL -> R.drawable.ic_m3_link
                GuideTarget.SAMPLE -> R.drawable.ic_m3_file
                GuideTarget.STORAGE -> R.drawable.ic_m3_storage
                else -> R.drawable.ic_m3_arrow_upward
            }, tint = c.onPrimaryContainer)
        }
        Text(label, style = TideType.body, color = c.ink, modifier = Modifier.weight(1f))
        com.bontecou.syncmd.ui.theme.TideChevron()
    }
}

/** Page dots: the current page is a wider ink capsule. Decorative; the step eyebrow carries the position. */
@Composable
private fun TourPageDots(current: Int, count: Int, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Row(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            val width by animateDpAsState(if (i == current) 22.dp else 7.dp, label = "dot$i")
            Box(Modifier.height(8.dp).width(width).background(if (i == current) c.primary else c.line, CircleShape))
        }
    }
}

/** Small illustrative vignettes per page, built from Tide parts (rail, vault card, file rows). */
@Composable
private fun TourVignette(index: Int, modifier: Modifier = Modifier) {
    val c = Tide.colors
    Box(modifier.fillMaxWidth().clearAndSetSemantics {}) {
        when (index) {
            0 -> TideHero {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("notes", style = TideType.title, color = c.heroText, modifier = Modifier.weight(1f))
                    Text("main", style = TideType.meta, color = c.heroMeta)
                }
                Spacer(Modifier.height(18.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tide_onboarding_rail_device), style = TideType.mono(11.sp), color = c.heroMeta)
                    SyncRail(TideSyncState.InSync, Modifier.weight(1f).padding(horizontal = 10.dp), onHero = true)
                    Text("GitHub", style = TideType.mono(11.sp), color = c.heroMeta)
                }
            }
            1 -> TideCard(padding = 0.dp) {
                listOf("daily/2026-11-02.md" to true, "ideas.md" to true, "README.md" to false).forEachIndexed { i, (path, edited) ->
                    if (i > 0) TideHairline(Modifier.padding(start = 16.dp))
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 13.dp), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).background(if (edited) c.ahead else c.line, CircleShape))
                        Text(path, style = TideType.mono(13.sp), color = c.ink, modifier = Modifier.weight(1f))
                        if (edited) Text("M", style = TideType.metaStrong, color = c.ahead)
                    }
                }
            }
            else -> TideCard {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("notes", style = TideType.title, color = c.ink, modifier = Modifier.weight(1f))
                    TidePill("3 ↑", c.ahead, c.ahead.copy(alpha = 0.12f))
                }
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.tide_onboarding_rail_device), style = TideType.mono(11.sp), color = c.inkFaint)
                    SyncRail(TideSyncState.Ahead(3), Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text("GitHub", style = TideType.mono(11.sp), color = c.inkFaint)
                }
            }
        }
    }
}

// ─── Previews ───────────────────────────────────────────────────────────────

@Preview(name = "Tour · page 1", heightDp = 800) @Composable
private fun TourFirstPreview() = SyncMdTheme(darkTheme = false) { AppTourScreen(0, busy = false, error = false, replay = false, onPage = {}, onFinish = {}) }

@Preview(name = "Tour · page 2 · dark", heightDp = 800) @Composable
private fun TourEditPreview() = SyncMdTheme(darkTheme = true) { AppTourScreen(1, busy = false, error = false, replay = false, onPage = {}, onFinish = {}) }

@Preview(name = "Tour · page 3 · saving error", heightDp = 800) @Composable
private fun TourGitPreview() = SyncMdTheme(darkTheme = false) { AppTourScreen(2, busy = false, error = true, replay = false, onPage = {}, onFinish = {}) }

@Preview(name = "Tour · choices · busy · dark", heightDp = 900) @Composable
private fun TourStartPreview() = SyncMdTheme(darkTheme = true) { AppTourScreen(3, busy = true, error = false, replay = false, onPage = {}, onFinish = {}) }
