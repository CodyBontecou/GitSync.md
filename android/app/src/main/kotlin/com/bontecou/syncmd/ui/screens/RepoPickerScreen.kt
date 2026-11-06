package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bontecou.syncmd.ui.util.asString
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.ui.components.AddVaultBusyLine
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.theme.Tide
import com.bontecou.syncmd.ui.theme.TideBanner
import com.bontecou.syncmd.ui.theme.TideBannerTone
import com.bontecou.syncmd.ui.theme.TideCard
import com.bontecou.syncmd.ui.theme.TideChevron
import com.bontecou.syncmd.ui.theme.TideCircleButton
import com.bontecou.syncmd.ui.theme.TideOutlineButton
import com.bontecou.syncmd.ui.theme.TidePill
import com.bontecou.syncmd.ui.theme.TideTopBar
import com.bontecou.syncmd.ui.theme.TideType
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun RepoPickerScreen(
    settingsViewModel: SettingsViewModel,
    onRepoSelected: (GitHubRepo) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: GitHubViewModel = hiltViewModel(),
    onManageAccounts: () -> Unit = {},
) {
    val isLoggedIn    by viewModel.isLoggedIn.collectAsState()
    val user          by viewModel.user.collectAsState()
    val filteredRepos by viewModel.filteredRepos.collectAsState()
    val searchQuery   by viewModel.searchQuery.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error = viewModel.error.collectAsState().value?.asString()

    if (!isLoggedIn) {
        LoginScreen(
            viewModel = viewModel,
            settingsViewModel = settingsViewModel,
            onContinueAfterLogin = onNavigateBack,
        )
        return
    }

    RepoPickerContent(
        login = user?.login,
        repos = filteredRepos,
        searchQuery = searchQuery,
        isLoading = isLoading,
        error = error,
        onSearch = viewModel::onSearchQueryChanged,
        onRefresh = { viewModel.loadUserAndRepos(forceRefresh = true) },
        onBack = onNavigateBack,
        onManageAccounts = onManageAccounts,
        onRepoSelected = onRepoSelected,
    )
}

/** Stateless picker: header, search, then loading / error / empty / no-matches / list. */
@Composable
private fun RepoPickerContent(
    login: String?,
    repos: List<GitHubRepo>,
    searchQuery: String,
    isLoading: Boolean,
    error: String?,
    onSearch: (String) -> Unit,
    onRefresh: () -> Unit,
    onBack: () -> Unit,
    onManageAccounts: () -> Unit,
    onRepoSelected: (GitHubRepo) -> Unit,
) {
    val c = Tide.colors
    Box(Modifier.fillMaxSize().background(c.paper)) {
        Column(Modifier.fillMaxSize()) {
            TideTopBar(
                onBack = onBack,
                backLabel = stringResource(R.string.publication_back),
                center = { com.bontecou.syncmd.ui.theme.TideTopBarTitle(stringResource(R.string.tide_onboarding_picker_title)) },
                actions = {
                    TideCircleButton(stringResource(R.string.ui_github_accounts_653cf0c5), onManageAccounts) {
                        com.bontecou.syncmd.ui.theme.TideIcon(R.drawable.ic_m3_person, tint = c.inkMid)
                    }
                    TideCircleButton(stringResource(R.string.ui_refresh_0e916101), onRefresh) {
                        com.bontecou.syncmd.ui.theme.TideIcon(R.drawable.ic_m3_sync, tint = c.inkMid)
                    }
                },
            )

            Column(Modifier.fillMaxWidth().padding(horizontal = Tide.gutter), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RepoSearchField(searchQuery, onSearch, Modifier.padding(top = 4.dp))
                if (login != null) RepositoryPickerIdentity(login, repos.size, Modifier.padding(start = 4.dp, top = 4.dp))
                if (error != null) TideBanner(error, tone = TideBannerTone.DANGER)
            }

            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        AddVaultBusyLine(stringResource(R.string.ui_loading_repos_2f4f4a93))
                    }
                }

                repos.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        if (searchQuery.isNotBlank()) BEmptyState(
                            title    = stringResource(R.string.tide_onboarding_picker_no_matches),
                            subtitle = stringResource(R.string.account_no_matching_repositories, searchQuery),
                        ) else BEmptyState(
                            title    = stringResource(R.string.ui_no_repositories_a6e9ff91),
                            subtitle = stringResource(R.string.account_no_repositories_found),
                            actionTitle = stringResource(R.string.ui_refresh_0e916101),
                            onAction = onRefresh,
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = Tide.gutter, end = Tide.gutter, top = 16.dp, bottom = 24.dp),
                    ) {
                        itemsIndexed(repos, key = { _, it -> it.id }) { index, repo ->
                            val r = Tide.radiusCard
                            val shape = androidx.compose.foundation.shape.RoundedCornerShape(
                                topStart = if (index == 0) r else 0.dp, topEnd = if (index == 0) r else 0.dp,
                                bottomStart = if (index == repos.lastIndex) r else 0.dp, bottomEnd = if (index == repos.lastIndex) r else 0.dp)
                            Column(Modifier.fillMaxWidth().clip(shape).background(c.card)) {
                                if (index > 0) com.bontecou.syncmd.ui.theme.TideHairline()
                                RepoPickerRow(repo = repo, onClick = { onRepoSelected(repo) })
                            }
                        }
                        item { Box(Modifier.navigationBarsPadding()) }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoSearchField(query: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    val c = Tide.colors
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .background(c.containerHigh, CircleShape)
            .border(if (focused) 2.dp else 0.dp, if (focused) c.primary else Color.Transparent, CircleShape)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        com.bontecou.syncmd.ui.theme.TideIcon(R.drawable.ic_m3_search, tint = c.inkMid)
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(stringResource(R.string.ui_search_repositories_243865a1), style = TideType.body, color = c.inkMid)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                textStyle = TideType.body.copy(color = c.ink),
                singleLine = true,
                cursorBrush = SolidColor(c.primary),
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
        }
        if (query.isNotEmpty()) {
            TideCircleButton(stringResource(R.string.tide_onboarding_picker_clear), { onQuery("") }, size = 40.dp, filled = false) {
                com.bontecou.syncmd.ui.theme.TideIcon(R.drawable.ic_m3_close, tint = c.inkMid, size = 20.dp)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepoPickerRow(repo: GitHubRepo, onClick: () -> Unit) {
    val c = Tide.colors
    TideCard(padding = 16.dp, radius = 0.dp, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = repo.name,
                    style = TideType.sans(16.sp, FontWeight.Medium).copy(textDirection = TextDirection.Ltr),
                    color = c.ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${repo.ownerLogin} · ⎇ ${repo.defaultBranch}",
                    style = TideType.meta.copy(textDirection = TextDirection.Ltr),
                    color = c.inkFaint,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!repo.description.isNullOrBlank() && repo.description != "null") {
                    Text(
                        text = repo.description,
                        style = TideType.sans(13.sp).copy(lineHeight = 18.sp),
                        color = c.inkMid,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (repo.isPrivate) TidePill(stringResource(R.string.publication_private_label), c.inkMid, c.well)
                    repo.language?.let { TidePill(it, c.behind, c.behind.copy(alpha = 0.10f)) }
                    if (repo.stargazersCount > 0) Text("★ ${repo.stargazersCount}", style = TideType.meta, color = c.inkFaint,
                        modifier = Modifier.padding(vertical = 5.dp))
                    repo.updatedAt?.let { iso ->
                        formatRelativeDate(iso)?.let { rel -> Text(rel, style = TideType.meta, color = c.inkFaint, modifier = Modifier.padding(vertical = 5.dp)) }
                    }
                }
            }
            TideChevron()
        }
    }
}

private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

@Composable
private fun formatRelativeDate(iso: String): String? {
    val date: Date = try { isoFmt.parse(iso) ?: return null } catch (_: Exception) { return null }
    val diff = System.currentTimeMillis() - date.time
    val days = TimeUnit.MILLISECONDS.toDays(diff).toInt()
    if (diff < TimeUnit.MINUTES.toMillis(2)) return stringResource(R.string.account_updated_just_now)
    val (id, count) = when {
        diff < TimeUnit.HOURS.toMillis(1)    -> R.plurals.account_updated_minutes to TimeUnit.MILLISECONDS.toMinutes(diff).toInt()
        diff < TimeUnit.DAYS.toMillis(1)     -> R.plurals.account_updated_hours to TimeUnit.MILLISECONDS.toHours(diff).toInt()
        diff < TimeUnit.DAYS.toMillis(7)     -> R.plurals.account_updated_days to days
        diff < TimeUnit.DAYS.toMillis(30)    -> R.plurals.account_updated_weeks to days / 7
        diff < TimeUnit.DAYS.toMillis(365)   -> R.plurals.account_updated_months to days / 30
        else                                 -> R.plurals.account_updated_years to days / 365
    }
    return pluralStringResource(id, count, count)
}

// ─── Previews ───────────────────────────────────────────────────────────────

private val previewRepos = listOf(
    GitHubRepo(1, "notes", "octocat/notes", "Daily notes and an Obsidian vault", true, "", "main", "2026-10-30T10:00:00Z", "octocat", "Markdown", 4),
    GitHubRepo(2, "garden", "octocat/garden", null, false, "", "trunk", null, "octocat", null, 0),
)

@Composable
private fun PickerPreview(repos: List<GitHubRepo>, query: String = "", loading: Boolean = false, error: String? = null) =
    RepoPickerContent("octocat", repos, query, loading, error, {}, {}, {}, {}, {})

@Preview(name = "Picker · list", heightDp = 760) @Composable
private fun PickerListPreview() = SyncMdTheme(darkTheme = false) { PickerPreview(previewRepos) }

@Preview(name = "Picker · list · dark", heightDp = 760) @Composable
private fun PickerListDarkPreview() = SyncMdTheme(darkTheme = true) { PickerPreview(previewRepos, query = "no") }

@Preview(name = "Picker · loading", heightDp = 600) @Composable
private fun PickerLoadingPreview() = SyncMdTheme(darkTheme = false) { PickerPreview(emptyList(), loading = true) }

@Preview(name = "Picker · empty + error", heightDp = 600) @Composable
private fun PickerEmptyPreview() = SyncMdTheme(darkTheme = false) { PickerPreview(emptyList(), error = "GitHub rate limit reached.") }

@Preview(name = "Picker · no matches · dark", heightDp = 600) @Composable
private fun PickerNoMatchPreview() = SyncMdTheme(darkTheme = true) { PickerPreview(emptyList(), query = "zzz") }
