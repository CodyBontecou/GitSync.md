package com.bontecou.syncmd.ui

import android.net.Uri
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
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.screens.CloneScreen
import com.bontecou.syncmd.ui.screens.DiffScreen
import com.bontecou.syncmd.ui.screens.GitScreen
import com.bontecou.syncmd.ui.screens.LoginScreen
import com.bontecou.syncmd.ui.screens.RepoPickerScreen
import com.bontecou.syncmd.ui.screens.ReposScreen
import com.bontecou.syncmd.ui.screens.SettingsScreen
import com.bontecou.syncmd.ui.screens.VaultScreen
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel

/**
 * Navigate only when the current back-stack entry is fully RESUMED.
 * Prevents double-navigation when the user taps faster than the
 * navigation animation completes (classic white-screen bug).
 */
fun NavController.navigateSafe(route: String, builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null) {
    val current = currentBackStackEntry
    if (current == null || current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        if (builder != null) navigate(route, builder) else navigate(route)
    }
}

fun NavController.popBackStackSafe(): Boolean {
    val current = currentBackStackEntry
    return if (current == null || current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
        popBackStack()
    } else {
        false
    }
}

// ─── Routes ──────────────────────────────────────────────────────────────────
object Routes {
    const val REPOS        = "repos"
    const val VAULT        = "vault"
    const val GIT          = "git"
    const val SETTINGS     = "settings"
    const val LOGIN        = "login"
    const val REPO_PICKER  = "repo_picker"
    /** clone/{repoFullName} — repoFullName is URI-encoded (e.g. "owner%2Frepo") */
    const val CLONE        = "clone"
    /** diff/{filePath} — filePath is URI-encoded */
    const val DIFF         = "diff"
}

/**
 * Root navigation shell.
 *
 * Mirrors iOS NavigationStack structure:
 *   repos  →  vault  →  git (sheet equivalent)
 *                    →  settings
 *   repos  →  login  →  repo_picker  →  clone
 *
 * Gitsync.md is now a paid-up-front app on Google Play, so every installed
 * copy has full access. No in-app purchase SDK or repository-limit gate is enforced here.
 */
@Composable
fun AppShell(settingsViewModel: SettingsViewModel) {
    val navController   = rememberNavController()
    val githubViewModel : GitHubViewModel = hiltViewModel()
    val context         = LocalContext.current

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val appSettings        by settingsViewModel.appSettings.collectAsState()
    val isLoggedIn         by githubViewModel.isLoggedIn.collectAsState()
    val bc                 = LocalBrutalColors.current

    // After OAuth callback, keep the user on Login so they can choose
    // download location before continuing.
    LaunchedEffect(isLoggedIn) {
        if (isLoggedIn) {
            val current = navController.currentBackStackEntry?.destination?.route
            if (current == Routes.LOGIN) {
                githubViewModel.onOAuthSuccess()
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
                    settingsViewModel   = settingsViewModel,
                    githubViewModel     = githubViewModel,
                    onRepoRemoved       = { settingsViewModel.removeRepository(it) },
                    onRepoSelected      = { repoPath ->
                        val resolvedPath = if (repoPath.startsWith("github://")) {
                            val relPath = repoPath.removePrefix("github://")
                            CloneStorage.defaultCloneBaseDir(context) + "/" + relPath
                        } else {
                            repoPath
                        }
                        settingsViewModel.setRepositoryPath(resolvedPath)
                        navController.navigateSafe(Routes.VAULT)
                    },
                    onAddRepo = {
                        if (isLoggedIn) {
                            githubViewModel.loadUserAndRepos()
                            navController.navigateSafe(Routes.REPO_PICKER)
                        } else {
                            navController.navigateSafe(Routes.LOGIN)
                        }
                    },
                    onNavigateToSettings = {
                        navController.navigateSafe(Routes.SETTINGS)
                    },
                )
            }

            // ── Vault (single repo overview) ──────────────────────────────
            composable(Routes.VAULT) {
                VaultScreen(
                    repositoryPath  = selectedRepository,
                    showDebugInfo   = appSettings.showDebugInfo,
                    onOpenGit       = { navController.navigateSafe(Routes.GIT) },
                    onOpenDiff      = { filePath ->
                        val encoded = Uri.encode(filePath)
                        navController.navigateSafe("${Routes.DIFF}/$encoded")
                    },
                    onNavigateBack  = { navController.popBackStackSafe() },
                )
            }

            // ── Git control (all git operations) ─────────────────────────
            composable(Routes.GIT) {
                GitScreen(
                    repositoryPath = selectedRepository,
                    showDebugInfo  = appSettings.showDebugInfo,
                    onNavigateBack = { navController.popBackStackSafe() },
                    onOpenDiff     = { filePath ->
                        val encoded = Uri.encode(filePath)
                        navController.navigateSafe("${Routes.DIFF}/$encoded")
                    },
                )
            }

            // ── File diff viewer ──────────────────────────────────────────
            composable(
                route     = "${Routes.DIFF}/{filePath}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            ) { backStackEntry ->
                val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                DiffScreen(
                    repositoryPath = selectedRepository,
                    filePath       = filePath,
                    onNavigateBack = { navController.popBackStackSafe() },
                )
            }

            // ── Settings ──────────────────────────────────────────────────
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel          = settingsViewModel,
                    onNavigateBack     = { navController.popBackStackSafe() },
                    onNavigateToLogin  = { navController.navigateSafe(Routes.LOGIN) },
                    onNavigateToRepoPicker = {
                        if (isLoggedIn) githubViewModel.loadUserAndRepos()
                        navController.navigateSafe(Routes.REPO_PICKER)
                    },
                )
            }

            // ── GitHub OAuth login ────────────────────────────────────────
            composable(Routes.LOGIN) {
                LoginScreen(
                    viewModel         = githubViewModel,
                    settingsViewModel = settingsViewModel,
                    onNavigateBack    = { navController.popBackStackSafe() },
                    onContinueAfterLogin = {
                        if (!navController.popBackStackSafe()) {
                            navController.navigateSafe(Routes.REPOS) {
                                popUpTo(Routes.REPOS) { inclusive = false }
                            }
                        }
                    },
                )
            }

            // ── GitHub repo picker ────────────────────────────────────────
            composable(Routes.REPO_PICKER) {
                RepoPickerScreen(
                    viewModel      = githubViewModel,
                    onRepoSelected = { repo ->
                        val encoded = Uri.encode(repo.fullName)
                        navController.navigateSafe("${Routes.CLONE}/$encoded")
                    },
                    onNavigateBack = { navController.popBackStackSafe() },
                )
            }

            // ── Clone (performed at add-time, matches iOS) ────────────────
            composable(
                route     = "${Routes.CLONE}/{repoFullName}",
                arguments = listOf(navArgument("repoFullName") { type = NavType.StringType }),
            ) { backStackEntry ->
                val repoFullName = backStackEntry.arguments?.getString("repoFullName") ?: ""
                CloneScreen(
                    repoFullName      = repoFullName,
                    settingsViewModel = settingsViewModel,
                    onCloneSuccess    = { /* Paid-up-front app: no repo usage tracking. */ },
                    onSuccess         = {
                        navController.navigateSafe(Routes.REPOS) {
                            popUpTo(Routes.REPOS) { inclusive = false }
                        }
                    },
                    onCancel          = { navController.popBackStackSafe() },
                )
            }
        }
    }
}
