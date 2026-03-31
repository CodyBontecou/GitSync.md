package com.bontecou.syncmd.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
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
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// ─── Route constants ─────────────────────────────────────────────────────────

private object Routes {
    const val STATUS = "status"
    const val COMMIT = "commit"
    const val BRANCH = "branch"
    const val HISTORY = "history"
    const val CONFLICT = "conflict"
    const val SETTINGS = "settings"
    const val LOGIN = "github_login"
    const val REPO_PICKER = "github_repo_picker"
}

/**
 * Main app shell.
 *
 * Navigation graph:
 *   • Bottom-nav routes (status / commit / branch / history / conflict / settings)
 *   • github_login      — GitHub OAuth login page
 *   • github_repo_picker — browse & pick a GitHub repo after login
 */
@Composable
fun AppShell() {
    val navController = rememberNavController()
    val settingsViewModel: SettingsViewModel = hiltViewModel()
    val githubViewModel: GitHubViewModel = hiltViewModel()

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val isLoggedIn by githubViewModel.isLoggedIn.collectAsState()

    var currentRoute by remember { mutableStateOf(Routes.STATUS) }

    // When the OAuth callback fires (token stored by MainActivity), automatically
    // navigate from LoginScreen → RepoPicker if we're currently on the login screen.
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
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            // Hide the bottom nav on full-screen login / repo-picker flows
            val showNav = currentRoute !in listOf(Routes.LOGIN, Routes.REPO_PICKER)
            if (showNav) {
                BottomNav(currentRoute = currentRoute) { item ->
                    currentRoute = item.route
                    navController.navigate(item.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = Routes.STATUS,
                modifier = Modifier.fillMaxSize()
            ) {
                // ── GitHub OAuth login ──────────────────────────────────────
                composable(Routes.LOGIN) {
                    currentRoute = Routes.LOGIN
                    LoginScreen(viewModel = githubViewModel)
                }

                // ── GitHub repo picker ──────────────────────────────────────
                composable(Routes.REPO_PICKER) {
                    currentRoute = Routes.REPO_PICKER
                    RepoPickerScreen(
                        viewModel = githubViewModel,
                        onRepoSelected = { repo ->
                            // Add the GitHub repo to saved repos
                            settingsViewModel.addRepository(
                                name = repo.name,
                                path = "github://${repo.fullName}",
                                alias = repo.fullName
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

                // ── Main bottom-nav screens ────────────────────────────────
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
                            if (isLoggedIn) {
                                githubViewModel.loadUserAndRepos()
                            }
                            currentRoute = Routes.REPO_PICKER
                            navController.navigate(Routes.REPO_PICKER)
                        }
                    )
                }
            }
        }
    }
}

// ─── Bottom navigation ────────────────────────────────────────────────────────

@Composable
private fun BottomNav(
    currentRoute: String,
    onNavigate: (BottomNavItem) -> Unit
) {
    val items = listOf(
        BottomNavItem(Routes.STATUS, "Status", Icons.Default.CheckCircle),
        BottomNavItem(Routes.COMMIT, "Commit", Icons.Default.Edit),
        BottomNavItem(Routes.BRANCH, "Branch", Icons.Default.Refresh),
        BottomNavItem(Routes.HISTORY, "History", Icons.Default.Info),
        BottomNavItem(Routes.CONFLICT, "Merge", Icons.Default.Delete),
        BottomNavItem(Routes.SETTINGS, "Settings", Icons.Default.Settings)
    )

    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
    ) {
        items.forEach { item ->
            NavigationBarItem(
                selected = currentRoute == item.route,
                onClick = { onNavigate(item) },
                icon = {
                    Icon(imageVector = item.icon, contentDescription = item.label)
                },
                label = {
                    Text(text = item.label, fontSize = 10.sp)
                }
            )
        }
    }
}

data class BottomNavItem(
    val route: String,
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector
)
