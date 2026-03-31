package com.bontecou.syncmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bontecou.syncmd.ui.screens.BranchScreen
import com.bontecou.syncmd.ui.screens.CommitScreen
import com.bontecou.syncmd.ui.screens.ConflictScreen
import com.bontecou.syncmd.ui.screens.HistoryScreen
import com.bontecou.syncmd.ui.screens.LoginScreen
import com.bontecou.syncmd.ui.screens.RepoPickerScreen
import com.bontecou.syncmd.ui.screens.RepositoryEmptyState
import com.bontecou.syncmd.ui.screens.SettingsScreen
import com.bontecou.syncmd.ui.screens.StatusScreen
import com.bontecou.syncmd.ui.theme.BrutalColors
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// ─── Route constants ─────────────────────────────────────────────────────────
private object Routes {
    const val STATUS   = "status"
    const val COMMIT   = "commit"
    const val BRANCH   = "branch"
    const val HISTORY  = "history"
    const val CONFLICT = "conflict"
    const val SETTINGS = "settings"
    const val LOGIN    = "github_login"
    const val REPO_PICKER = "github_repo_picker"
}

@Composable
fun AppShell() {
    val navController      = rememberNavController()
    val settingsViewModel  : SettingsViewModel = hiltViewModel()
    val githubViewModel    : GitHubViewModel   = hiltViewModel()

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val isLoggedIn         by githubViewModel.isLoggedIn.collectAsState()
    val bc                 = LocalBrutalColors.current

    var currentRoute by remember { mutableStateOf(Routes.STATUS) }

    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn && currentRoute == Routes.LOGIN) {
            githubViewModel.onOAuthSuccess()
            navController.navigate(Routes.REPO_PICKER) {
                popUpTo(Routes.LOGIN) { inclusive = true }
            }
            currentRoute = Routes.REPO_PICKER
        }
    }

    Scaffold(
        modifier      = Modifier.fillMaxSize(),
        containerColor = bc.bg,
        topBar = {
            BrutalTopBar(currentRoute = currentRoute, bc = bc)
        },
        bottomBar = {
            val showNav = currentRoute !in listOf(Routes.LOGIN, Routes.REPO_PICKER)
            if (showNav) {
                BrutalBottomNav(
                    currentRoute = currentRoute,
                    bc           = bc,
                    onNavigate   = { item ->
                        currentRoute = item.route
                        navController.navigate(item.route) {
                            popUpTo(navController.graph.startDestinationId) { saveState = true }
                            launchSingleTop = true
                            restoreState    = true
                        }
                    }
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController    = navController,
                startDestination = Routes.STATUS,
                modifier         = Modifier.fillMaxSize(),
            ) {
                composable(Routes.LOGIN) {
                    currentRoute = Routes.LOGIN
                    LoginScreen(viewModel = githubViewModel)
                }

                composable(Routes.REPO_PICKER) {
                    currentRoute = Routes.REPO_PICKER
                    RepoPickerScreen(
                        viewModel        = githubViewModel,
                        onRepoSelected   = { repo ->
                            settingsViewModel.addRepository(
                                name  = repo.name,
                                path  = "github://${repo.fullName}",
                                alias = repo.fullName,
                            )
                            currentRoute = Routes.SETTINGS
                            navController.navigate(Routes.SETTINGS) {
                                popUpTo(Routes.REPO_PICKER) { inclusive = true }
                            }
                        },
                        onNavigateBack = {
                            currentRoute = Routes.SETTINGS
                            navController.navigate(Routes.SETTINGS) {
                                popUpTo(Routes.REPO_PICKER) { inclusive = true }
                            }
                        }
                    )
                }

                composable(Routes.STATUS) {
                    currentRoute = Routes.STATUS
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = Routes.SETTINGS
                                navController.navigate(Routes.SETTINGS)
                            },
                            onConnectGitHub = {
                                if (isLoggedIn) {
                                    githubViewModel.loadUserAndRepos()
                                    currentRoute = Routes.REPO_PICKER
                                    navController.navigate(Routes.REPO_PICKER)
                                } else {
                                    currentRoute = Routes.LOGIN
                                    navController.navigate(Routes.LOGIN)
                                }
                            }
                        )
                    } else {
                        StatusScreen(repositoryPath = selectedRepository)
                    }
                }

                composable(Routes.COMMIT) {
                    currentRoute = Routes.COMMIT
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = Routes.SETTINGS
                                navController.navigate(Routes.SETTINGS)
                            },
                            onConnectGitHub = {
                                if (isLoggedIn) {
                                    githubViewModel.loadUserAndRepos()
                                    currentRoute = Routes.REPO_PICKER
                                    navController.navigate(Routes.REPO_PICKER)
                                } else {
                                    currentRoute = Routes.LOGIN
                                    navController.navigate(Routes.LOGIN)
                                }
                            }
                        )
                    } else {
                        CommitScreen(repositoryPath = selectedRepository)
                    }
                }

                composable(Routes.BRANCH) {
                    currentRoute = Routes.BRANCH
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = Routes.SETTINGS
                                navController.navigate(Routes.SETTINGS)
                            },
                            onConnectGitHub = {
                                if (isLoggedIn) {
                                    githubViewModel.loadUserAndRepos()
                                    currentRoute = Routes.REPO_PICKER
                                    navController.navigate(Routes.REPO_PICKER)
                                } else {
                                    currentRoute = Routes.LOGIN
                                    navController.navigate(Routes.LOGIN)
                                }
                            }
                        )
                    } else {
                        BranchScreen(repositoryPath = selectedRepository)
                    }
                }

                composable(Routes.HISTORY) {
                    currentRoute = Routes.HISTORY
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = Routes.SETTINGS
                                navController.navigate(Routes.SETTINGS)
                            },
                            onConnectGitHub = {
                                if (isLoggedIn) {
                                    githubViewModel.loadUserAndRepos()
                                    currentRoute = Routes.REPO_PICKER
                                    navController.navigate(Routes.REPO_PICKER)
                                } else {
                                    currentRoute = Routes.LOGIN
                                    navController.navigate(Routes.LOGIN)
                                }
                            }
                        )
                    } else {
                        HistoryScreen(repositoryPath = selectedRepository)
                    }
                }

                composable(Routes.CONFLICT) {
                    currentRoute = Routes.CONFLICT
                    if (selectedRepository.isBlank()) {
                        RepositoryEmptyState(
                            onNavigateToSettings = {
                                currentRoute = Routes.SETTINGS
                                navController.navigate(Routes.SETTINGS)
                            },
                            onConnectGitHub = {
                                if (isLoggedIn) {
                                    githubViewModel.loadUserAndRepos()
                                    currentRoute = Routes.REPO_PICKER
                                    navController.navigate(Routes.REPO_PICKER)
                                } else {
                                    currentRoute = Routes.LOGIN
                                    navController.navigate(Routes.LOGIN)
                                }
                            }
                        )
                    } else {
                        ConflictScreen(repositoryPath = selectedRepository)
                    }
                }

                composable(Routes.SETTINGS) {
                    currentRoute = Routes.SETTINGS
                    SettingsScreen(
                        onNavigateToLogin = {
                            currentRoute = Routes.LOGIN
                            navController.navigate(Routes.LOGIN)
                        },
                        onNavigateToRepoPicker = {
                            if (isLoggedIn) githubViewModel.loadUserAndRepos()
                            currentRoute = Routes.REPO_PICKER
                            navController.navigate(Routes.REPO_PICKER)
                        }
                    )
                }
            }
        }
    }
}

// ─── Brutal Top Bar ───────────────────────────────────────────────────────────
@Composable
private fun BrutalTopBar(currentRoute: String, bc: BrutalColors) {
    val title = when (currentRoute) {
        Routes.STATUS   -> "SYNC.MD"
        Routes.COMMIT   -> "COMMIT"
        Routes.BRANCH   -> "BRANCHES"
        Routes.HISTORY  -> "HISTORY"
        Routes.CONFLICT -> "CONFLICTS"
        Routes.SETTINGS -> "SETTINGS"
        else             -> "SYNC.MD"
    }

    Column(modifier = Modifier.fillMaxWidth().background(bc.bg)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = title,
                style = TextStyle(
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Black,
                    fontSize = 14.sp,
                    letterSpacing = 3.sp,
                    color = bc.text,
                )
            )
        }
        // Bottom border
        Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))
    }
}

// ─── Brutal Bottom Nav ────────────────────────────────────────────────────────
private data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: ImageVector,
)

@Composable
private fun BrutalBottomNav(
    currentRoute: String,
    bc: BrutalColors,
    onNavigate: (BottomNavItem) -> Unit,
) {
    val items = listOf(
        BottomNavItem(Routes.STATUS,   "Status",    Icons.Default.CheckCircle),
        BottomNavItem(Routes.COMMIT,   "Commit",    Icons.Default.Edit),
        BottomNavItem(Routes.BRANCH,   "Branch",    Icons.Default.Refresh),
        BottomNavItem(Routes.HISTORY,  "History",   Icons.Default.Info),
        BottomNavItem(Routes.CONFLICT, "Merge",     Icons.Default.Delete),
        BottomNavItem(Routes.SETTINGS, "Settings",  Icons.Default.Settings),
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(bc.bg)
    ) {
        // 1dp top border
        Box(Modifier.fillMaxWidth().height(1.dp).background(bc.border))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .navigationBarsPadding(),
        ) {
            items.forEach { item ->
                val isSelected = currentRoute == item.route
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() },
                        ) { onNavigate(item) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = item.icon,
                        contentDescription = item.label,
                        modifier = Modifier.size(20.dp),
                        tint = if (isSelected) bc.text else bc.textFaint,
                    )
                    Text(
                        text = item.label.uppercase(),
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontSize = 8.sp,
                            letterSpacing = 0.5.sp,
                            color = if (isSelected) bc.text else bc.textFaint,
                        )
                    )
                }
            }
        }
    }
}
