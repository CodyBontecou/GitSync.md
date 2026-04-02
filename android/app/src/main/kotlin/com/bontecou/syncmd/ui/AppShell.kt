package com.bontecou.syncmd.ui

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.navigation.NavController
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bontecou.syncmd.billing.PurchaseManager
import com.bontecou.syncmd.billing.PurchaseViewModel
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.screens.CloneScreen
import com.bontecou.syncmd.ui.screens.DiffScreen
import com.bontecou.syncmd.ui.screens.GitScreen
import com.bontecou.syncmd.ui.screens.LoginScreen
import com.bontecou.syncmd.ui.screens.PaywallScreen
import com.bontecou.syncmd.ui.screens.RepoPickerScreen
import com.bontecou.syncmd.ui.screens.ReposScreen
import com.bontecou.syncmd.ui.screens.SettingsScreen
import com.bontecou.syncmd.ui.screens.VaultScreen
import com.bontecou.syncmd.ui.theme.LocalBrutalColors
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.launch

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
    const val PAYWALL      = "paywall"
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
 * Paywall gates (mirrors iOS PurchaseManager logic):
 *
 *   Gate 1 — ADD REPOSITORY button in ReposScreen:
 *     Allow free navigation only when BOTH conditions hold:
 *       1. The user is currently under the live repo-count limit.
 *       2. The "repos ever added" count (persisted across reinstalls via Auto Backup)
 *          is also under the limit, meaning the free slot has not been consumed.
 *     Otherwise refresh purchase status and show paywall if not unlocked.
 *
 *   Gate 2 — Repo selection in RepoPickerScreen:
 *     If the selected identifier has never been seen before AND the free-slot budget
 *     is exhausted, require a purchase before navigating to CloneScreen.
 *     Re-adding a previously seen repo (after delete or reinstall) is always free.
 */
@Composable
fun AppShell(settingsViewModel: SettingsViewModel) {
    val navController     = rememberNavController()
    val githubViewModel   : GitHubViewModel  = hiltViewModel()
    val purchaseViewModel : PurchaseViewModel = hiltViewModel()
    val context           = LocalContext.current
    val scope             = rememberCoroutineScope()

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val appSettings        by settingsViewModel.appSettings.collectAsState()
    val isLoggedIn         by githubViewModel.isLoggedIn.collectAsState()
    val isUnlocked         by purchaseViewModel.isUnlocked.collectAsState()
    val bc                 = LocalBrutalColors.current

    // Repo that is waiting for purchase before we navigate to CloneScreen.
    // Set in Gate 2 when the user taps a repo but isn't yet unlocked.
    var pendingRepoToClone by remember { mutableStateOf<String?>(null) }

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

    // After a successful purchase while paywalled, complete the pending navigation.
    LaunchedEffect(isUnlocked) {
        val pending = pendingRepoToClone
        if (isUnlocked && pending != null) {
            pendingRepoToClone = null
            val encoded = Uri.encode(pending)
            navController.navigateSafe("${Routes.CLONE}/$encoded") {
                // Pop the paywall off the back stack
                popUpTo(Routes.PAYWALL) { inclusive = true }
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
                val allRepos by settingsViewModel.allRepositories.collectAsState()
                val seenRepoIdentifiers = purchaseViewModel.seenRepoIdentifiers()

                ReposScreen(
                    settingsViewModel   = settingsViewModel,
                    githubViewModel     = githubViewModel,
                    seenRepoIdentifiers = seenRepoIdentifiers,
                    isUnlocked          = isUnlocked,
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
                    onGhostRepoSelected = { repoFullName ->
                        fun navigateToClone() {
                            val encoded = Uri.encode(repoFullName)
                            navController.navigateSafe("${Routes.CLONE}/$encoded")
                        }

                        if (allRepos.size >= PurchaseManager.FREE_REPO_LIMIT) {
                            scope.launch {
                                purchaseViewModel.refreshStatusNow()
                                if (purchaseViewModel.isUnlocked.value) {
                                    navigateToClone()
                                } else {
                                    navController.navigateSafe(Routes.PAYWALL)
                                }
                            }
                        } else {
                            navigateToClone()
                        }
                    },
                    onNavigateToPaywall = {
                        navController.navigateSafe(Routes.PAYWALL)
                    },
                    onAddRepo = {
                        // ── Gate 1 ──────────────────────────────────────────────────────
                        // Allow free access only when BOTH:
                        //   1. Live repo count is under the limit.
                        //   2. The Auto Backup-persisted "repos ever added" count is also
                        //      under the limit (prevents bypass via in-app repo deletion).
                        val underCurrentLimit = allRepos.size < PurchaseManager.FREE_REPO_LIMIT
                        val freeSlotAvailable =
                            purchaseViewModel.uniqueReposEverAdded < PurchaseManager.FREE_REPO_LIMIT

                        if (underCurrentLimit && freeSlotAvailable) {
                            // Still within the free tier — navigate directly.
                            if (isLoggedIn) {
                                githubViewModel.loadUserAndRepos()
                                navController.navigateSafe(Routes.REPO_PICKER)
                            } else {
                                navController.navigateSafe(Routes.LOGIN)
                            }
                        } else {
                            // May have exceeded the free tier — refresh then decide.
                            scope.launch {
                                purchaseViewModel.refreshStatusNow()
                                if (purchaseViewModel.isUnlocked.value) {
                                    if (isLoggedIn) {
                                        githubViewModel.loadUserAndRepos()
                                        navController.navigateSafe(Routes.REPO_PICKER)
                                    } else {
                                        navController.navigateSafe(Routes.LOGIN)
                                    }
                                } else {
                                    navController.navigateSafe(Routes.PAYWALL)
                                }
                            }
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
                        // ── Gate 2 ──────────────────────────────────────────────────────
                        // Before navigating to CloneScreen, check whether this specific
                        // identifier is brand-new AND the free-slot budget is exhausted.
                        // Re-adding a previously cloned repo is always free.
                        val identifier = repo.fullName.trim().lowercase()
                        if (!isUnlocked && purchaseViewModel.isNewRepoIdentifier(identifier)) {
                            // Free slot exhausted and this is a new repo — require purchase.
                            pendingRepoToClone = repo.fullName
                            navController.navigateSafe(Routes.PAYWALL)
                        } else {
                            // Unlocked, known repo, or free slot still available — clone directly.
                            val encoded = Uri.encode(repo.fullName)
                            navController.navigateSafe("${Routes.CLONE}/$encoded")
                        }
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
                    onCloneSuccess    = { fullName ->
                        purchaseViewModel.recordRepoAdded(fullName.trim().lowercase())
                    },
                    onSuccess         = {
                        navController.navigateSafe(Routes.REPOS) {
                            popUpTo(Routes.REPOS) { inclusive = false }
                        }
                    },
                    onCancel          = { navController.popBackStackSafe() },
                )
            }

            // ── Paywall ───────────────────────────────────────────────────
            composable(Routes.PAYWALL) {
                PaywallScreen(
                    purchaseViewModel = purchaseViewModel,
                    onDismiss         = { navController.popBackStackSafe() },
                )
            }
        }
    }
}
