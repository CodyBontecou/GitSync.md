package com.bontecou.syncmd.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bontecou.syncmd.ui.screens.GitScreen
import com.bontecou.syncmd.ui.screens.LoginScreen
import com.bontecou.syncmd.ui.screens.RepoPickerScreen
import com.bontecou.syncmd.ui.screens.ReposScreen
import com.bontecou.syncmd.ui.screens.SettingsScreen
import com.bontecou.syncmd.ui.screens.VaultScreen
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

// ─── Routes ──────────────────────────────────────────────────────────────────
object Routes {
    const val REPOS        = "repos"
    const val VAULT        = "vault"
    const val GIT          = "git"
    const val SETTINGS     = "settings"
    const val LOGIN        = "login"
    const val REPO_PICKER  = "repo_picker"
}

/**
 * Root navigation shell.
 *
 * Matches iOS NavigationStack structure:
 *   repos  →  vault  →  git (sheet equivalent)
 *                    →  settings
 *   repos  →  login  →  repo_picker
 */
@Composable
fun AppShell(settingsViewModel: SettingsViewModel) {
    val navController   = rememberNavController()
    val githubViewModel : GitHubViewModel = hiltViewModel()
    val context           = LocalContext.current

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val appSettings        by settingsViewModel.appSettings.collectAsState()
    val isLoggedIn         by githubViewModel.isLoggedIn.collectAsState()
    val bc                 = LocalBrutalColors.current

    // After OAuth callback — auto-navigate from Login → RepoPicker
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            val back = navController.previousBackStackEntry?.destination?.route
            if (back == Routes.LOGIN) {
                githubViewModel.onOAuthSuccess()
                navController.navigate(Routes.REPO_PICKER) {
                    popUpTo(Routes.LOGIN) { inclusive = true }
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(bc.bg)
    ) {
        NavHost(
            navController    = navController,
            startDestination = Routes.REPOS,
            modifier         = Modifier.fillMaxSize(),
        ) {
            // ── Repo list (home) ──────────────────────────────────────────
            composable(Routes.REPOS) {
                ReposScreen(
                    settingsViewModel  = settingsViewModel,
                    githubViewModel    = githubViewModel,
                    onRepoRemoved      = { settingsViewModel.removeRepository(it) },
                    onRepoSelected     = { repoPath ->
                        val resolvedPath = if (repoPath.startsWith("github://")) {
                            val relPath = repoPath.removePrefix("github://")
                            context.filesDir.absolutePath + "/repos/" + relPath
                        } else {
                            repoPath
                        }
                        settingsViewModel.setRepositoryPath(resolvedPath)
                        navController.navigate(Routes.VAULT)
                    },
                    onAddRepo          = {
                        if (isLoggedIn) {
                            githubViewModel.loadUserAndRepos()
                            navController.navigate(Routes.REPO_PICKER)
                        } else {
                            navController.navigate(Routes.LOGIN)
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigate(Routes.SETTINGS)
                    },
                )
            }

            // ── Vault (single repo overview) ──────────────────────────────
            composable(Routes.VAULT) {
                VaultScreen(
                    repositoryPath  = selectedRepository,
                    showDebugInfo   = appSettings.showDebugInfo,
                    onOpenGit       = { navController.navigate(Routes.GIT) },
                    onNavigateBack  = { navController.popBackStack() },
                )
            }

            // ── Git control (all git operations) ─────────────────────────
            composable(Routes.GIT) {
                GitScreen(
                    repositoryPath = selectedRepository,
                    showDebugInfo  = appSettings.showDebugInfo,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── Settings ──────────────────────────────────────────────────
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel          = settingsViewModel,
                    onNavigateBack     = { navController.popBackStack() },
                    onNavigateToLogin  = { navController.navigate(Routes.LOGIN) },
                    onNavigateToRepoPicker = {
                        if (isLoggedIn) githubViewModel.loadUserAndRepos()
                        navController.navigate(Routes.REPO_PICKER)
                    },
                )
            }

            // ── GitHub OAuth login ────────────────────────────────────────
            composable(Routes.LOGIN) {
                LoginScreen(
                    viewModel      = githubViewModel,
                    onNavigateBack = { navController.popBackStack() },
                )
            }

            // ── GitHub repo picker ────────────────────────────────────────
            composable(Routes.REPO_PICKER) {
                RepoPickerScreen(
                    viewModel      = githubViewModel,
                    onRepoSelected = { repo ->
                        settingsViewModel.addRepository(
                            name  = repo.name,
                            path  = "github://${repo.fullName}",
                            alias = repo.fullName,
                        )
                        navController.navigate(Routes.REPOS) {
                            popUpTo(Routes.REPOS) { inclusive = false }
                        }
                    },
                    onNavigateBack = { navController.popBackStack() },
                )
            }
        }
    }
}
