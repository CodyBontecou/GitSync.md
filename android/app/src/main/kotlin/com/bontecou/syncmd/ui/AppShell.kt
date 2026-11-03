package com.bontecou.syncmd.ui

import com.bontecou.syncmd.ui.util.displayIdentity
import com.bontecou.syncmd.ui.util.asString

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.bontecou.syncmd.onboarding.GuideTarget
import com.bontecou.syncmd.R
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import com.bontecou.syncmd.ui.util.awaitResumedNavigationEntry
import com.bontecou.syncmd.ui.util.onResumedSource
import androidx.navigation.NavController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.bontecou.syncmd.storage.CloneStorage
import com.bontecou.syncmd.ui.screens.CloneScreen
import com.bontecou.syncmd.ui.screens.FilesScreen
import com.bontecou.syncmd.ui.screens.FileEditorScreen
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
 * Defer transition-time taps until their source resumes. Leaving that source cancels pending taps.
 */
fun NavController.navigateSafe(route: String, builder: (androidx.navigation.NavOptionsBuilder.() -> Unit)? = null) {
    onResumedSource {
        if (builder != null) navigate(route, builder) else navigate(route)
    }
}

fun NavController.popBackStackSafe(): Boolean {
    // True means a Back request was accepted; a later source change may cancel it.
    if (previousBackStackEntry == null) return false
    return onResumedSource { popBackStack() }
}

/** Completing publication removes its route; a delayed storage callback cannot redirect a later screen. */
fun NavController.openPublishedRepository(settings: SettingsViewModel, path: String) {
    val source = currentBackStackEntry
    settings.setRepositoryPath(path) {
        if (currentBackStackEntry === source && source?.destination?.route == Routes.PUBLISH) {
            navigateSafe(Routes.VAULT) { popUpTo(Routes.PUBLISH) { inclusive = true } }
        }
    }
}

// ─── Routes ──────────────────────────────────────────────────────────────────
object Routes {
    const val REPOS        = "repos"
    const val VAULT        = "vault"
    const val GIT          = "git"
    const val SETTINGS     = "settings"
    const val LOGIN        = "login"
    const val ACCOUNTS = "accounts"
    const val STORAGE = "storage"
    const val SSH_TRUST = "ssh-trust"
    const val LFS_ENDPOINTS = "lfs-endpoints"
    const val DIAGNOSTICS = "diagnostics"
    const val TOUR = "app-tour"
    const val NOTES = "release-notes"
    const val MOVE = "move-repository"
    const val ACCOUNT_LOGIN = "account-login"
    const val REPO_PICKER  = "repo_picker"
    /** clone/{repoFullName} — repoFullName is URI-encoded (e.g. "owner%2Frepo") */
    const val CLONE        = "clone"
    /** diff/{filePath} — filePath is URI-encoded */
    const val DIFF         = "diff"
    const val REMOTE_CLONE = "remote-clone"
    const val HISTORY_CLONE = "history-clone"
    const val REBASE = "rebase"
    const val REMOTE_MERGE = "remote-merge"
    const val LFS = "lfs"
    const val CONNECTION = "connection"
    const val AUTHOR = "author"
    const val UPSTREAM = "upstream"
    const val FILES        = "files"
    const val EDITOR       = "editor"
    const val LOCAL        = "local-repositories"
    const val PUBLISH = "publish-folder"
    const val PULL_ALL = "pull-all"
    const val REPOSITORY_ACTIONS = "repository-actions"
    const val CALLBACK = "callback"
    const val OBSIDIAN = "obsidian"
    const val PUSH_SYNC = "push-sync"
    const val BACKGROUND_SYNC = "background-sync"
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
    com.bontecou.syncmd.ui.components.RepositoryTrustGates()
    val navController   = rememberNavController()
    val githubViewModel : GitHubViewModel = hiltViewModel()
    val context         = LocalContext.current

    val selectedRepository by settingsViewModel.selectedRepository.collectAsState()
    val appSettings        by settingsViewModel.appSettings.collectAsState()
    val isLoggedIn         by githubViewModel.isLoggedIn.collectAsState()
    val bc                 = LocalBrutalColors.current
    val repositoryError by settingsViewModel.errorMessage.collectAsState()
    val sessionError by githubViewModel.authManager.sessionError.collectAsState()
    val navigationEntry by navController.currentBackStackEntryAsState()
    val guide: com.bontecou.syncmd.ui.viewmodels.AppGuideViewModel = hiltViewModel()
    val pullAll: com.bontecou.syncmd.ui.viewmodels.PullAllViewModel = hiltViewModel()
    val pullAllRequest by com.bontecou.syncmd.automation.PullAllRequests.pending.collectAsState()
    val repositoryActions: com.bontecou.syncmd.ui.viewmodels.RepositoryActionsViewModel = hiltViewModel()
    val repositoryActionState by repositoryActions.state.collectAsState()
    val shortcutRequest by com.bontecou.syncmd.automation.RepositoryShortcutRequests.pending.collectAsState()
    val callbacks: com.bontecou.syncmd.ui.viewmodels.RepositoryCallbackViewModel = hiltViewModel()
    val callbackIncoming by com.bontecou.syncmd.automation.RepositoryCallbackRequests.pending.collectAsState()
    val obsidian: com.bontecou.syncmd.ui.viewmodels.ObsidianViewModel = hiltViewModel()
    val obsidianState by obsidian.state.collectAsState()
    val obsidianIncoming by com.bontecou.syncmd.obsidian.ObsidianRequests.pending.collectAsState()
    val pushSync: com.bontecou.syncmd.ui.viewmodels.PushSyncViewModel = hiltViewModel()
    val pushSyncState by pushSync.state.collectAsState()
    val backgroundSync: com.bontecou.syncmd.ui.viewmodels.BackgroundSyncViewModel = hiltViewModel()
    val pushLinkCallback by com.bontecou.syncmd.push.PushLinkCallbacks.pending.collectAsState()
    val pushTap by com.bontecou.syncmd.push.PushSyncRequests.pending.collectAsState()
    val registeredRepositories by settingsViewModel.allRepositories.collectAsState()
    val guideState by guide.state.collectAsState()
    if (!guideState.ready) {
        // Tide loading gate: paper ground with the quiet three-dot pulse instead of a bar.
        Box(Modifier.fillMaxSize().background(com.bontecou.syncmd.ui.theme.Tide.colors.paper), contentAlignment = Alignment.Center) {
            com.bontecou.syncmd.ui.theme.TidePulse(color = com.bontecou.syncmd.ui.theme.Tide.colors.inkFaint)
        }
        return
    }
    if (!guideState.progress.completed) {
        com.bontecou.syncmd.ui.screens.AppTourScreen(guideState.progress.page, guideState.busy, guideState.error,
            replay = false, onPage = guide::page, onFinish = guide::complete)
        return
    }
    LaunchedEffect(guideState.progress.pending) {
        val target = guideState.progress.pending ?: return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (guide.state.value.progress.pending != target) return@LaunchedEffect
        when (target) {
            GuideTarget.HOME -> Unit
            GuideTarget.LOGIN -> navController.navigateSafe(Routes.LOGIN)
            GuideTarget.URL -> navController.navigateSafe(Routes.REMOTE_CLONE)
            GuideTarget.STORAGE -> navController.navigateSafe(Routes.STORAGE)
            GuideTarget.SAMPLE -> settingsViewModel.openSampleRepository { navController.navigateSafe(Routes.VAULT) }
        }
        guide.consumeTarget()
    }

    LaunchedEffect(pullAllRequest) {
        val request = pullAllRequest ?: return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!com.bontecou.syncmd.automation.PullAllRequests.consume(request)) return@LaunchedEffect
        if (navController.currentDestination?.route != Routes.PULL_ALL) navController.navigateSafe(Routes.PULL_ALL)
        pullAll.refreshBackground()
    }
    LaunchedEffect(shortcutRequest, repositoryActionState.ready) {
        val request = shortcutRequest ?: return@LaunchedEffect
        if (!repositoryActionState.ready) return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!com.bontecou.syncmd.automation.RepositoryShortcutRequests.consume(request)) return@LaunchedEffect
        if (navController.currentDestination?.route != Routes.REPOSITORY_ACTIONS) navController.navigateSafe(Routes.REPOSITORY_ACTIONS)
        repositoryActions.runShortcut(request.id)
    }

    LaunchedEffect(callbackIncoming) {
        val incoming = callbackIncoming ?: return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!com.bontecou.syncmd.automation.RepositoryCallbackRequests.consume(incoming)) return@LaunchedEffect
        callbacks.accept(incoming)
        if (navController.currentDestination?.route != Routes.CALLBACK) navController.navigateSafe(Routes.CALLBACK)
    }

    LaunchedEffect(obsidianIncoming, obsidianState.ready) {
        val incoming = obsidianIncoming ?: return@LaunchedEffect
        if (!obsidianState.ready) return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!com.bontecou.syncmd.obsidian.ObsidianRequests.consume(incoming)) return@LaunchedEffect
        obsidian.accept(incoming)
        if (navController.currentDestination?.route != Routes.OBSIDIAN) navController.navigateSafe(Routes.OBSIDIAN)
    }

    LaunchedEffect(registeredRepositories, pushSyncState.enabled) {
        if (pushSyncState.enabled) com.bontecou.syncmd.push.PushSyncJobs.maintain(context)
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.bontecou.syncmd.background.BackgroundSyncJobs.schedule(context) }
    }
    LaunchedEffect(pushLinkCallback, pushTap, pushSyncState.ready, pushSyncState.busy) {
        if (!pushSyncState.ready || pushSyncState.busy) return@LaunchedEffect
        val callback = pushLinkCallback; val tap = pushTap
        if (callback == null && tap == null) return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!pushSync.state.value.ready || pushSync.state.value.busy) return@LaunchedEffect
        if (callback != null && com.bontecou.syncmd.push.PushLinkCallbacks.consume(callback)) pushSync.acceptCallback(callback)
        else if (tap != null && com.bontecou.syncmd.push.PushSyncRequests.consume(tap)) pushSync.pull(tap)
        if (navController.currentDestination?.route != Routes.PUSH_SYNC) navController.navigateSafe(Routes.PUSH_SYNC)
    }

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
                        settingsViewModel.setRepositoryPath(resolvedPath) { navController.navigateSafe(Routes.VAULT) }
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
                    onManageRepositories = { navController.navigateSafe(Routes.LOCAL) },
                    onCloneFromUrl = { navController.navigateSafe(Routes.REMOTE_CLONE) },
                    onPublishFolder = { navController.navigateSafe(Routes.PUBLISH) },
                    onPullAll = { navController.navigateSafe(Routes.PULL_ALL); pullAll.open() },
                    onRepositoryActions = { navController.navigateSafe(Routes.REPOSITORY_ACTIONS) },
                )
            }

            composable(Routes.PUBLISH) {
                com.bontecou.syncmd.ui.screens.FolderPublicationScreen(
                    onBack = { navController.popBackStackSafe() },
                    onManageAccounts = { navController.navigateSafe(Routes.ACCOUNTS) },
                    register = { settingsViewModel.registerPublication(it, "github:${it.account.id}") },
                    onReady = { path -> navController.openPublishedRepository(settingsViewModel, path) })
            }
            composable(Routes.PULL_ALL) {
                com.bontecou.syncmd.ui.screens.PullAllScreen(pullAll, onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.REPOSITORY_ACTIONS) {
                com.bontecou.syncmd.ui.screens.RepositoryActionsScreen(repositoryActions, onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.CALLBACK) {
                com.bontecou.syncmd.ui.screens.RepositoryCallbackScreen(callbacks, onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.OBSIDIAN) {
                com.bontecou.syncmd.ui.screens.ObsidianScreen(obsidian, onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.PUSH_SYNC) {
                com.bontecou.syncmd.ui.screens.PushSyncScreen(pushSync, onBack = { navController.popBackStackSafe() }, onOpenRepository = { path ->
                    val source = navController.currentBackStackEntry
                    settingsViewModel.setRepositoryPath(path) {
                        if (navController.currentBackStackEntry === source && source?.destination?.route == Routes.PUSH_SYNC) navController.navigateSafe(Routes.VAULT)
                    }
                })
            }
            composable(Routes.BACKGROUND_SYNC) {
                com.bontecou.syncmd.ui.screens.BackgroundSyncScreen(backgroundSync, onBack = { navController.popBackStackSafe() }, onOpenRepository = { path ->
                    val source = navController.currentBackStackEntry
                    settingsViewModel.setRepositoryPath(path) {
                        if (navController.currentBackStackEntry === source && source?.destination?.route == Routes.BACKGROUND_SYNC) navController.navigateSafe(Routes.VAULT)
                    }
                })
            }
            composable(Routes.REMOTE_CLONE) {
                com.bontecou.syncmd.ui.screens.RemoteCloneScreen(settingsViewModel,
                    onBack = { navController.popBackStackSafe() },
                    onSuccess = { navController.navigateSafe(Routes.REPOS) { popUpTo(Routes.REPOS) { inclusive = false } } },
                    onReviewLfs = { path -> navController.navigateSafe("${Routes.LFS}/${Uri.encode(path)}") { popUpTo(Routes.REPOS) { inclusive = false } } })
            }
            composable("${Routes.HISTORY_CLONE}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                val history by settingsViewModel.repositoryHistory.collectAsState()
                val previous = com.bontecou.syncmd.ui.util.reviewedHistoryClone(entry.savedStateHandle, entry.arguments?.getString("path").orEmpty(), history)
                if (previous != null) com.bontecou.syncmd.ui.screens.RemoteCloneScreen(settingsViewModel,
                    onBack = { navController.popBackStackSafe() },
                    onSuccess = { navController.navigateSafe(Routes.REPOS) { popUpTo(Routes.REPOS) { inclusive = false } } }, historyEntry = previous, onReviewLfs = { path -> navController.navigateSafe("${Routes.LFS}/${Uri.encode(path)}") { popUpTo(Routes.REPOS) { inclusive = false } } })
                else Column(Modifier.fillMaxSize().background(com.bontecou.syncmd.ui.theme.Tide.colors.paper)) {
                    com.bontecou.syncmd.ui.components.WrappingTopBar(
                        title = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.ui_this_history_entry_is_unavailable_c2c9f4e0),
                        backLabel = androidx.compose.ui.res.stringResource(com.bontecou.syncmd.R.string.publication_back),
                        onBack = { navController.popBackStackSafe() })
                }
            }

            composable("${Routes.CONNECTION}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.ConnectionScreen(entry.arguments?.getString("path").orEmpty(), settingsViewModel,
                    onBack = { navController.popBackStackSafe() },
                    onManageAccounts = { navController.navigateSafe(Routes.ACCOUNTS) },
                    onEditAuthor = { navController.navigateSafe("${Routes.AUTHOR}/${Uri.encode(entry.arguments?.getString("path").orEmpty())}") },
                    onEditUpstream = { navController.navigateSafe("${Routes.UPSTREAM}/${Uri.encode(entry.arguments?.getString("path").orEmpty())}") })
            }

            composable("${Routes.UPSTREAM}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.UpstreamScreen(entry.arguments?.getString("path").orEmpty(),onBack = { navController.popBackStackSafe() })
            }

            composable("${Routes.AUTHOR}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.AuthorScreen(entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStackSafe() })
            }

            composable(Routes.LOCAL) {
                com.bontecou.syncmd.ui.screens.RepositoryManagementScreen(
                    viewModel = settingsViewModel,
                    onNavigateBack = { navController.popBackStackSafe() },
                    onOpenRepository = { path -> settingsViewModel.setRepositoryPath(path) { navController.navigateSafe(Routes.VAULT) } },
                    onEditConnection = { path -> navController.navigateSafe("${Routes.CONNECTION}/${Uri.encode(path)}") },
                    onMoveRepository = { path -> navController.navigateSafe("${Routes.MOVE}?path=${Uri.encode(path)}") },
                    onRestoreMoveRecovery = { id -> navController.navigateSafe("${Routes.MOVE}?recovery=$id") },
                    onCloneRepository = { path -> navController.navigateSafe("${Routes.HISTORY_CLONE}/${Uri.encode(path)}") },
                )
            }

            // ── Vault (single repo overview) ──────────────────────────────
            composable(Routes.VAULT) {
                VaultScreen(
                    repositoryPath  = selectedRepository,
                    showDebugInfo   = appSettings.showDebugInfo,
                    repositoryName = registeredRepositories.firstOrNull { it.path == selectedRepository }?.displayIdentity()?.name,
                    repositoryOwner = registeredRepositories.firstOrNull { it.path == selectedRepository }?.displayIdentity()?.owner,
                    remoteUrl = registeredRepositories.firstOrNull { it.path == selectedRepository }?.remoteUrl,
                    onOpenConnection = { navController.navigateSafe("${Routes.CONNECTION}/${Uri.encode(selectedRepository)}") },
                    onEditAuthor = { navController.navigateSafe("${Routes.AUTHOR}/${Uri.encode(selectedRepository)}") },
                    onMoveRepository = { navController.navigateSafe("${Routes.MOVE}?path=${Uri.encode(selectedRepository)}") },
                    onOpenRemoteMerge = { navController.navigateSafe("${Routes.REMOTE_MERGE}/${Uri.encode(selectedRepository)}") },
                    onOpenGit       = { navController.navigateSafe(Routes.GIT) },
                    onOpenFiles     = { navController.navigateSafe(Routes.FILES) },
                    onOpenDiff      = { filePath ->
                        val encoded = Uri.encode(filePath)
                        navController.navigateSafe("${Routes.DIFF}/$encoded")
                    },
                    onNavigateBack  = { navController.popBackStackSafe() },
                )
            }

            // ── Git control (all git operations) ─────────────────────────
            composable("${Routes.LFS}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.LfsScreen(entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStackSafe() })
            }
            composable("${Routes.REBASE}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.RebaseScreen(entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStackSafe() })
            }
            composable("${Routes.REMOTE_MERGE}/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.RemoteMergeScreen(entry.arguments?.getString("path").orEmpty(), onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.GIT) {
                GitScreen(
                    repositoryPath = selectedRepository,
                    showDebugInfo  = appSettings.showDebugInfo,
                    onNavigateBack = { navController.popBackStackSafe() },
                    onOpenLfs = { navController.navigateSafe("${Routes.LFS}/${Uri.encode(selectedRepository)}") },
                    onOpenRebase = { navController.navigateSafe("${Routes.REBASE}/${Uri.encode(selectedRepository)}") },
                    onOpenRemoteMerge = { navController.navigateSafe("${Routes.REMOTE_MERGE}/${Uri.encode(selectedRepository)}") },
                    onOpenDiff     = { filePath, staged ->
                        val encoded = Uri.encode(filePath)
                        navController.navigateSafe("${Routes.DIFF}/$encoded?staged=$staged")
                    },
                )
            }

            // ── File diff viewer ──────────────────────────────────────────
            composable(
                route     = "${Routes.DIFF}/{filePath}?staged={staged}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType },
                    navArgument("staged") { type = NavType.StringType; defaultValue = "auto" }),
            ) { backStackEntry ->
                val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                DiffScreen(
                    repositoryPath = selectedRepository,
                    filePath       = filePath,
                    staged = backStackEntry.arguments?.getString("staged")?.toBooleanStrictOrNull(),
                    onNavigateBack = { navController.popBackStackSafe() },
                )
            }

            composable(
                route = "${Routes.FILES}?directory={directory}",
                arguments = listOf(navArgument("directory") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
                FilesScreen(
                    repositoryPath = selectedRepository,
                    directory = entry.arguments?.getString("directory").orEmpty(),
                    onOpenDirectory = { navController.navigateSafe("${Routes.FILES}?directory=${Uri.encode(it)}") },
                    onOpenFile = { navController.navigateSafe("${Routes.EDITOR}/${Uri.encode(it)}") },
                    onNavigateBack = { navController.popBackStackSafe() },
                )
            }
            composable(
                route = "${Routes.EDITOR}/{filePath}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            ) { entry ->
                FileEditorScreen(
                    repositoryPath = selectedRepository,
                    filePath = entry.arguments?.getString("filePath").orEmpty(),
                    onNavigateBack = { navController.popBackStackSafe() },
                )
            }

            // ── Settings ──────────────────────────────────────────────────
            composable(Routes.SETTINGS) {
                SettingsScreen(
                    viewModel          = settingsViewModel,
                    onNavigateBack     = { navController.popBackStackSafe() },
                    onNavigateToLogin  = { navController.navigateSafe(Routes.LOGIN) },
                    onManageAccounts = { navController.navigateSafe(Routes.ACCOUNTS) },
                    githubViewModel = githubViewModel,
                    onObsidian = { obsidian.reload(); navController.navigateSafe(Routes.OBSIDIAN) },
                    onPushSync = { navController.navigateSafe(Routes.PUSH_SYNC) },
                    onBackgroundSync = { backgroundSync.reload(); navController.navigateSafe(Routes.BACKGROUND_SYNC) },
                    onManageLfsEndpoints = { navController.navigateSafe(Routes.LFS_ENDPOINTS) },
                    onDiagnostics = { navController.navigateSafe(Routes.DIAGNOSTICS) },
                    onTour = { navController.navigateSafe(Routes.TOUR) },
                    onReleaseNotes = { guide.notesSeen(); navController.navigateSafe(Routes.NOTES) },
                    onManageSshTrust = { navController.navigateSafe(Routes.SSH_TRUST) },
                    onManageStorage = { navController.navigateSafe(Routes.STORAGE) },
                    onNavigateToRepoPicker = {
                        if (isLoggedIn) githubViewModel.loadUserAndRepos()
                        navController.navigateSafe(Routes.REPO_PICKER)
                    },
                )
            }

            composable(Routes.ACCOUNTS) {
                com.bontecou.syncmd.ui.screens.AccountsScreen(githubViewModel.authManager,
                    onBack = { navController.popBackStackSafe() },
                    onAddAccount = { navController.navigateSafe(Routes.ACCOUNT_LOGIN) })
            }
            composable(Routes.LFS_ENDPOINTS) {
                com.bontecou.syncmd.ui.screens.LfsEndpointsScreen(onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.DIAGNOSTICS) {
                com.bontecou.syncmd.ui.screens.DiagnosticsScreen(onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.TOUR) {
                var page by rememberSaveable { mutableStateOf(0) }
                com.bontecou.syncmd.ui.screens.AppTourScreen(page, false, false, replay = true, onPage = { page = it },
                    onFinish = { navController.popBackStackSafe() }, onClose = { navController.popBackStackSafe() })
            }
            composable(Routes.NOTES) {
                com.bontecou.syncmd.ui.screens.ReleaseNotesScreen(onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.SSH_TRUST) {
                com.bontecou.syncmd.ui.screens.SshTrustScreen(onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.STORAGE) {
                com.bontecou.syncmd.ui.screens.StorageScreen(onBack = { navController.popBackStackSafe() })
            }
            composable("${Routes.MOVE}?path={path}&recovery={recovery}", arguments = listOf(
                navArgument("path") { type = NavType.StringType; defaultValue = "" },
                navArgument("recovery") { type = NavType.StringType; defaultValue = "" })) { entry ->
                com.bontecou.syncmd.ui.screens.MoveRepositoryScreen(entry.arguments?.getString("path").orEmpty(),
                    entry.arguments?.getString("recovery")?.takeIf { it.isNotEmpty() }, settingsViewModel, onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.ACCOUNT_LOGIN) {
                LoginScreen(viewModel = githubViewModel, settingsViewModel = settingsViewModel,
                    addingAccount = true, onNavigateBack = { navController.popBackStackSafe() },
                    onContinueAfterLogin = { navController.popBackStackSafe() })
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
                    settingsViewModel = settingsViewModel,
                    viewModel      = githubViewModel,
                    onManageAccounts = { navController.navigateSafe(Routes.ACCOUNTS) },
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
                    onReviewLfs       = { path -> navController.navigateSafe("${Routes.LFS}/${Uri.encode(path)}") { popUpTo(Routes.REPOS) { inclusive = false } } },
                )
            }
        }
    }
    if (guideState.error) {
        Box(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), contentAlignment = Alignment.BottomCenter) {
            com.bontecou.syncmd.ui.theme.TideToast(stringResource(R.string.guide_save_error),
                style = com.bontecou.syncmd.ui.theme.TideToastStyle.DANGER)
        }
    }
    if (guideState.progress.showNotes && sessionError == null && repositoryError == null && navigationEntry?.destination?.route != Routes.NOTES) {
        com.bontecou.syncmd.ui.theme.TideDialog(onDismissRequest = guide::notesSeen,
            title = stringResource(R.string.notes_notice_title),
            content = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                com.bontecou.syncmd.ui.screens.ReleaseNotesContent()
            } },
            confirmButton = { com.bontecou.syncmd.ui.theme.BPrimaryButton(stringResource(R.string.notes_acknowledge),
                Modifier.heightIn(min = 48.dp).testTag("notes-acknowledge"), onClick = guide::notesSeen) })
    }
    if (sessionError != null && navigationEntry?.destination?.route != Routes.LOGIN) {
        com.bontecou.syncmd.ui.theme.TideDialog(onDismissRequest = githubViewModel.authManager::clearSessionError,
            icon = { com.bontecou.syncmd.ui.theme.TideModalIcon(tint = com.bontecou.syncmd.ui.theme.Tide.colors.ahead) },
            title = stringResource(R.string.ui_github_sign_in_a2e50311),
            text = { Text(sessionError?.asString().orEmpty()) },
            confirmButton = { com.bontecou.syncmd.ui.theme.BPrimaryButton(stringResource(R.string.ui_ok_565339bc),
                onClick = githubViewModel.authManager::clearSessionError) })
    }
    if (repositoryError != null && navigationEntry?.destination?.route != Routes.LOCAL && navigationEntry?.destination?.route?.startsWith(Routes.MOVE) != true) {
        com.bontecou.syncmd.ui.theme.TideDialog(
            onDismissRequest = settingsViewModel::clearError,
            icon = { com.bontecou.syncmd.ui.theme.TideModalIcon() },
            title = stringResource(R.string.ui_repository_operation_failed_f1152917),
            text = { Text(repositoryError.orEmpty()) },
            confirmButton = { com.bontecou.syncmd.ui.theme.BPrimaryButton(stringResource(R.string.ui_ok_565339bc),
                onClick = settingsViewModel::clearError) },
        )
    }

}
