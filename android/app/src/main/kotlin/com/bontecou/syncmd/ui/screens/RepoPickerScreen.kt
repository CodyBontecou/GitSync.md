package com.bontecou.syncmd.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.ui.theme.BBadge
import com.bontecou.syncmd.ui.theme.BBadgeStyle
import com.bontecou.syncmd.ui.theme.BCard
import com.bontecou.syncmd.ui.theme.BDivider
import com.bontecou.syncmd.ui.theme.BEmptyState
import com.bontecou.syncmd.ui.theme.BLoading
import com.bontecou.syncmd.ui.theme.BSmallActionButton
import com.bontecou.syncmd.ui.theme.BSectionHeader
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

@Composable
fun RepoPickerScreen(
    onRepoSelected: (GitHubRepo) -> Unit,
    onNavigateBack: () -> Unit,
    viewModel: GitHubViewModel = hiltViewModel()
) {
    val isLoggedIn    by viewModel.isLoggedIn.collectAsState()
    val user          by viewModel.user.collectAsState()
    val filteredRepos by viewModel.filteredRepos.collectAsState()
    val searchQuery   by viewModel.searchQuery.collectAsState()
    val isLoading     by viewModel.isLoading.collectAsState()
    val error         by viewModel.error.collectAsState()
    val bc            = LocalBrutalColors.current

    if (!isLoggedIn) {
        LoginScreen(viewModel = viewModel)
        return
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {

            // ── Header ────────────────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    BSmallActionButton(title = "← Back", onClick = onNavigateBack)
                    BSmallActionButton(title = "Refresh", onClick = { viewModel.loadUserAndRepos() })
                }

                Spacer(Modifier.height(8.dp))

                user?.login?.let { login ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = "@$login",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                letterSpacing = 1.sp,
                                color = bc.accent,
                            )
                        )
                        Text(
                            text = "· ${filteredRepos.size} repos",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.textMid)
                        )
                    }
                }
            }

            BDivider()

            // ── Search ────────────────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(bc.surface)
                    .border(1.dp, bc.borderSoft)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(text = "⌕", style = TextStyle(fontSize = 16.sp, color = bc.textFaint))
                Box(modifier = Modifier.weight(1f)) {
                    if (searchQuery.isEmpty()) {
                        Text(
                            text = "Search repositories…",
                            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint)
                        )
                    }
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = viewModel::onSearchQueryChanged,
                        textStyle = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.text),
                        singleLine = true,
                        cursorBrush = SolidColor(bc.text),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (searchQuery.isNotEmpty()) {
                    Text(
                        text = "✕",
                        style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textMid),
                        modifier = Modifier.clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { viewModel.onSearchQueryChanged("") }
                    )
                }
            }

            BDivider()

            // ── Error ─────────────────────────────────────────────────────
            if (error != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(bc.error.copy(alpha = 0.08f))
                        .border(1.dp, bc.error.copy(alpha = 0.4f))
                        .padding(12.dp)
                ) {
                    Text(text = error!!, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = bc.error))
                }
            }

            // ── Content ───────────────────────────────────────────────────
            when {
                isLoading -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BLoading(text = "Loading repos")
                    }
                }

                filteredRepos.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BEmptyState(
                            title    = "No Repositories",
                            subtitle = if (searchQuery.isNotBlank())
                                "No repositories match \"$searchQuery\""
                            else
                                "No repositories found for this account.",
                        )
                    }
                }

                else -> {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filteredRepos, key = { it.id }) { repo ->
                            RepoPickerRow(repo = repo, onClick = { onRepoSelected(repo) })
                            BDivider()
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoPickerRow(repo: GitHubRepo, onClick: () -> Unit) {
    val bc = LocalBrutalColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onClick,
            )
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                if (repo.isPrivate) {
                    Text(text = "🔒", style = TextStyle(fontSize = 12.sp))
                }
                Text(
                    text = repo.fullName,
                    style = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp,
                        color = bc.text,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (!repo.description.isNullOrBlank()) {
                Text(
                    text = repo.description!!,
                    style = TextStyle(fontFamily = FontFamily.Default, fontSize = 13.sp, color = bc.textMid),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                repo.language?.let { lang ->
                    Text(text = lang, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = bc.accent))
                }
                Text(
                    text = "⎇ ${repo.defaultBranch}",
                    style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = bc.textFaint)
                )
                if (repo.stargazersCount > 0) {
                    Text(text = "★ ${repo.stargazersCount}", style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = bc.textFaint))
                }
                repo.updatedAt?.let { iso ->
                    formatRelativeDate(iso)?.let { rel ->
                        Text(text = rel, style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = bc.textFaint))
                    }
                }
            }
        }

        Text(
            text = "→",
            style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 14.sp, color = bc.textFaint),
            modifier = Modifier.padding(top = 2.dp),
        )
    }
}

private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)

private fun formatRelativeDate(iso: String): String? {
    val date: Date = try { isoFmt.parse(iso) ?: return null } catch (_: Exception) { return null }
    val diff = System.currentTimeMillis() - date.time
    return when {
        diff < TimeUnit.MINUTES.toMillis(2)  -> "just now"
        diff < TimeUnit.HOURS.toMillis(1)    -> "${TimeUnit.MILLISECONDS.toMinutes(diff)}m ago"
        diff < TimeUnit.DAYS.toMillis(1)     -> "${TimeUnit.MILLISECONDS.toHours(diff)}h ago"
        diff < TimeUnit.DAYS.toMillis(7)     -> "${TimeUnit.MILLISECONDS.toDays(diff)}d ago"
        diff < TimeUnit.DAYS.toMillis(30)    -> "${TimeUnit.MILLISECONDS.toDays(diff) / 7}w ago"
        diff < TimeUnit.DAYS.toMillis(365)   -> "${TimeUnit.MILLISECONDS.toDays(diff) / 30}mo ago"
        else                                 -> "${TimeUnit.MILLISECONDS.toDays(diff) / 365}y ago"
    }
}
