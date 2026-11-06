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
import androidx.compose.runtime.remember
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
import androidx.compose.foundation.layout.Row
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
    const val HISTORY = "history"
    /** Board routes other screens link to: largeFiles/{repo} (URI-encoded vault path), lfsEndpoints, sshHosts. */
    const val LARGE_FILES = "largeFiles"
    const val LFS_ENDPOINTS_M3 = "lfsEndpoints"
    const val SSH_HOSTS = "sshHosts"
    /** Vault settings for one vault: `vaultSettings/{repo}` (repo = Uri-encoded working-copy path). */
    const val VAULT_SETTINGS = "vaultSettings"
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
        com.bontecou.syncmd.ui.screens.OnboardingScreen(guideState.busy, guideState.error, onFinish = guide::complete)
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

    // Keep widgets and per-vault launcher shortcuts in step with the vault list.
    LaunchedEffect(registeredRepositories) { com.bontecou.syncmd.automation.VaultWidgets.refresh(context) }
    // Launcher shortcuts, widget rows and "See changes" open a vault (or Add a vault) in the app.
    val vaultShortcut by com.bontecou.syncmd.automation.VaultShortcutRequests.pending.collectAsState()
    LaunchedEffect(vaultShortcut) {
        val request = vaultShortcut ?: return@LaunchedEffect
        navController.awaitResumedNavigationEntry()
        if (!com.bontecou.syncmd.automation.VaultShortcutRequests.consume(request)) return@LaunchedEffect
        val path = request.path
        when (request.action) {
            com.bontecou.syncmd.automation.VaultShortcutActivity.ADD ->
                if (isLoggedIn) { githubViewModel.loadUserAndRepos(); navController.navigateSafe(Routes.REPO_PICKER) } else navController.navigateSafe(Routes.LOGIN)
            com.bontecou.syncmd.automation.VaultShortcutActivity.HOME -> navController.popBackStack(Routes.REPOS, inclusive = false)
            else -> if (path != null) settingsViewModel.setRepositoryPath(path) {
                navController.navigateSafe(Routes.VAULT)
                if (request.action == com.bontecou.syncmd.automation.VaultShortcutActivity.CHANGES)
                    navController.navigateSafe(Routes.HISTORY) { popUpTo(Routes.VAULT); launchSingleTop = true }
            }
        }
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

    val adaptive = com.bontecou.syncmd.ui.util.rememberAdaptiveInfo()
    val selectedIdentity = registeredRepositories.firstOrNull { it.path == selectedRepository }?.displayIdentity()
    // Changes · Files · History · Git are siblings under the vault: switching keeps one vault entry below them.
    val openTab: (com.bontecou.syncmd.ui.components.VaultTab) -> Unit = { tab ->
        when (tab) {
            com.bontecou.syncmd.ui.components.VaultTab.CHANGES -> navController.onResumedSource {
                if (!navController.popBackStack(Routes.VAULT, inclusive = false)) navController.navigate(Routes.VAULT)
            }
            com.bontecou.syncmd.ui.components.VaultTab.FILES -> navController.navigateSafe(Routes.FILES) { popUpTo(Routes.VAULT); launchSingleTop = true }
            com.bontecou.syncmd.ui.components.VaultTab.HISTORY -> navController.navigateSafe(Routes.HISTORY) { popUpTo(Routes.VAULT); launchSingleTop = true }
            com.bontecou.syncmd.ui.components.VaultTab.GIT -> navController.navigateSafe(Routes.GIT) { popUpTo(Routes.VAULT); launchSingleTop = true }
        }
    }
    val openPullAll: () -> Unit = { navController.navigateSafe(Routes.PULL_ALL); pullAll.open() }
    val openSettings: () -> Unit = { navController.navigateSafe(Routes.SETTINGS) }
    val railFrame: @Composable (com.bontecou.syncmd.ui.components.VaultTab?, @Composable () -> Unit) -> Unit = { tab, content ->
        com.bontecou.syncmd.ui.components.VaultRailFrame(adaptive, tab, openTab, openPullAll, openSettings, content)
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
                    signInIssue = sessionError?.asString(),
                    onSignIn = { githubViewModel.authManager.clearSessionError(); navController.navigateSafe(Routes.LOGIN) },
                    onDismissSignInIssue = githubViewModel.authManager::clearSessionError,
                    onRenewSignIn = { navController.navigateSafe(Routes.ACCOUNT_LOGIN) },
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
                // List-detail on tablets and foldables: the file picked in Changes opens beside the list.
                var selectedFile by rememberSaveable(selectedRepository) { mutableStateOf<String?>(null) }
                // Bumped when the commit sheet closes: staging or committing changes which layer the pane should show.
                var detailRevision by remember { mutableStateOf(0) }
                val twoPane = adaptive.isMedium
                val openEditor: (String) -> Unit = { navController.navigateSafe("${Routes.EDITOR}/${Uri.encode(it)}") }
                val vault: @Composable () -> Unit = {
                    VaultScreen(
                        repositoryPath  = selectedRepository,
                        showDebugInfo   = appSettings.showDebugInfo,
                        repositoryName = selectedIdentity?.name,
                        repositoryOwner = selectedIdentity?.owner,
                        remoteUrl = registeredRepositories.firstOrNull { it.path == selectedRepository }?.remoteUrl,
                        onOpenConnection = { navController.navigateSafe("${Routes.CONNECTION}/${Uri.encode(selectedRepository)}") },
                        onEditAuthor = { navController.navigateSafe("${Routes.AUTHOR}/${Uri.encode(selectedRepository)}") },
                        onMoveRepository = { navController.navigateSafe("${Routes.MOVE}?path=${Uri.encode(selectedRepository)}") },
                        onOpenRemoteMerge = { navController.navigateSafe("${Routes.REMOTE_MERGE}/${Uri.encode(selectedRepository)}") },
                        onOpenGit       = { navController.navigateSafe(Routes.GIT) },
                        onOpenFiles     = { navController.navigateSafe(Routes.FILES) },
                        onOpenDiff      = { filePath ->
                            if (twoPane) selectedFile = filePath
                            else navController.navigateSafe("${Routes.DIFF}/${Uri.encode(filePath)}")
                        },
                        onNavigateBack  = { navController.popBackStackSafe() },
                        showNavigation = !twoPane,
                        onSelectTab = openTab,
                        selectedFile = selectedFile.takeIf { twoPane },
                        onCommitSheetClosed = { detailRevision++ },
                        onOpenVaultSettings = { navController.navigateSafe("${Routes.VAULT_SETTINGS}/${Uri.encode(selectedRepository)}") },
                    )
                }
                val detail: @Composable () -> Unit = {
                    val file = selectedFile
                    if (file == null) com.bontecou.syncmd.ui.components.VaultDetailPlaceholder()
                    else androidx.compose.runtime.key(file, detailRevision) {
                        DiffScreen(
                            repositoryPath = selectedRepository,
                            filePath = file,
                            onNavigateBack = { selectedFile = null },
                            onOpenEditor = { openEditor(file) },
                            showBack = false,
                            onOpenEditorFor = openEditor,
                        )
                    }
                }
                when {
                    !twoPane -> vault()
                    // Half-opened like a laptop: the diff above the fold, the vault and its commit actions below.
                    adaptive.isTabletop -> railFrame(com.bontecou.syncmd.ui.components.VaultTab.CHANGES) {
                        com.bontecou.syncmd.ui.util.HingeAwareColumn(adaptive, top = detail, bottom = vault)
                    }
                    adaptive.isExpanded -> railFrame(com.bontecou.syncmd.ui.components.VaultTab.CHANGES) {
                        androidx.compose.foundation.layout.Row(Modifier.fillMaxSize()) {
                            com.bontecou.syncmd.ui.components.VaultListPane(registeredRepositories, selectedRepository,
                                onSelect = { path -> selectedFile = null; settingsViewModel.setRepositoryPath(path) {} })
                            Box(Modifier.weight(1f)) {
                                com.bontecou.syncmd.ui.util.HingeAwareRow(adaptive, 380.dp, first = vault, second = detail)
                            }
                        }
                    }
                    else -> railFrame(com.bontecou.syncmd.ui.components.VaultTab.CHANGES) {
                        com.bontecou.syncmd.ui.util.HingeAwareRow(adaptive, 360.dp, first = vault, second = detail)
                    }
                }
            }

            composable(Routes.HISTORY) {
                railFrame(com.bontecou.syncmd.ui.components.VaultTab.HISTORY) {
                    com.bontecou.syncmd.ui.screens.HistoryScreen(
                        repositoryPath = selectedRepository,
                        repositoryName = selectedIdentity?.name ?: selectedRepository.substringAfterLast('/'),
                        onNavigateBack = { navController.popBackStackSafe() },
                        showNavigation = !adaptive.isMedium,
                        onSelectTab = openTab,
                        repositoryOwner = selectedIdentity?.owner,
                        onOpenVaultSettings = { navController.navigateSafe("${Routes.VAULT_SETTINGS}/${Uri.encode(selectedRepository)}") },
                    )
                }
            }

            // ── Vault settings (placeholder until the Vault settings board lands) ──
            composable("${Routes.VAULT_SETTINGS}/{repo}", arguments = listOf(navArgument("repo") { type = NavType.StringType })) { entry ->
                val repo = entry.arguments?.getString("repo").orEmpty()
                val context = androidx.compose.ui.platform.LocalContext.current
                val remote = registeredRepositories.firstOrNull { it.path == repo }?.remoteUrl
                com.bontecou.syncmd.ui.screens.VaultSettingsPlaceholderScreen(
                    repositoryName = selectedIdentity?.name?.takeIf { repo == selectedRepository } ?: repo.substringAfterLast('/'),
                    onBack = { navController.popBackStackSafe() },
                    onOpenInFiles = { com.bontecou.syncmd.ui.util.FilesAppLauncher.openFolder(context, repo) },
                    onCopyUrl = remote?.takeIf { it.isNotBlank() }?.let { url -> {
                        val shareable = com.bontecou.syncmd.ui.screens.shareableRemoteUrl(url)
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText(shareable, shareable))
                    } },
                    onOpenConnection = { navController.navigateSafe("${Routes.CONNECTION}/${Uri.encode(repo)}") },
                    onEditAuthor = { navController.navigateSafe("${Routes.AUTHOR}/${Uri.encode(repo)}") },
                    onMoveRepository = { navController.navigateSafe("${Routes.MOVE}?path=${Uri.encode(repo)}") },
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
              railFrame(com.bontecou.syncmd.ui.components.VaultTab.GIT) {
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
                    showNavigation = !adaptive.isMedium,
                    onSelectTab = openTab,
                )
              }
            }

            // ── File diff viewer ──────────────────────────────────────────
            composable(
                route     = "${Routes.DIFF}/{filePath}?staged={staged}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType },
                    navArgument("staged") { type = NavType.StringType; defaultValue = "auto" }),
            ) { backStackEntry ->
                val filePath = backStackEntry.arguments?.getString("filePath") ?: ""
                val diff: @Composable () -> Unit = {
                    DiffScreen(
                        repositoryPath = selectedRepository,
                        filePath       = filePath,
                        staged = backStackEntry.arguments?.getString("staged")?.toBooleanStrictOrNull(),
                        onNavigateBack = { navController.popBackStackSafe() },
                        onOpenEditor = { navController.navigateSafe("${Routes.EDITOR}/${Uri.encode(filePath)}") },
                        onOpenEditorFor = { navController.navigateSafe("${Routes.EDITOR}/${Uri.encode(it)}") },
                    )
                }
                railFrame(null) {
                    if (adaptive.isTabletop) {
                        // Tabletop: read the diff above the fold, commit with the Git tools below it.
                        com.bontecou.syncmd.ui.util.HingeAwareColumn(adaptive, top = diff, bottom = {
                            GitScreen(
                                repositoryPath = selectedRepository,
                                showDebugInfo = appSettings.showDebugInfo,
                                onNavigateBack = { navController.popBackStackSafe() },
                                onOpenLfs = { navController.navigateSafe("${Routes.LFS}/${Uri.encode(selectedRepository)}") },
                                onOpenRebase = { navController.navigateSafe("${Routes.REBASE}/${Uri.encode(selectedRepository)}") },
                                onOpenRemoteMerge = { navController.navigateSafe("${Routes.REMOTE_MERGE}/${Uri.encode(selectedRepository)}") },
                                onOpenDiff = { path, staged -> navController.navigateSafe("${Routes.DIFF}/${Uri.encode(path)}?staged=$staged") },
                            )
                        })
                    } else diff()
                }
            }

            composable(
                route = "${Routes.FILES}?directory={directory}",
                arguments = listOf(navArgument("directory") { type = NavType.StringType; defaultValue = "" }),
            ) { entry ->
              railFrame(com.bontecou.syncmd.ui.components.VaultTab.FILES) {
                FilesScreen(
                    repositoryPath = selectedRepository,
                    directory = entry.arguments?.getString("directory").orEmpty(),
                    onOpenDirectory = { navController.navigateSafe("${Routes.FILES}?directory=${Uri.encode(it)}") },
                    onOpenFile = { navController.navigateSafe("${Routes.EDITOR}/${Uri.encode(it)}") },
                    onNavigateBack = { navController.popBackStackSafe() },
                    showNavigation = !adaptive.isMedium,
                    onSelectTab = openTab,
                    vaultName = selectedIdentity?.name,
                    onOpenHistory = { navController.navigateSafe("${com.bontecou.syncmd.ui.screens.FILE_HISTORY_ROUTE}/${Uri.encode(it)}") },
                )
              }
            }
            composable(
                route = "${com.bontecou.syncmd.ui.screens.FILE_HISTORY_ROUTE}/{filePath}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            ) { entry ->
                com.bontecou.syncmd.ui.screens.FileHistoryScreen(selectedRepository, entry.arguments?.getString("filePath").orEmpty(),
                    onNavigateBack = { navController.popBackStackSafe() })
            }
            composable(
                route = "${Routes.EDITOR}/{filePath}",
                arguments = listOf(navArgument("filePath") { type = NavType.StringType }),
            ) { entry ->
              railFrame(com.bontecou.syncmd.ui.components.VaultTab.FILES) {
                FileEditorScreen(
                    repositoryPath = selectedRepository,
                    filePath = entry.arguments?.getString("filePath").orEmpty(),
                    onNavigateBack = { navController.popBackStackSafe() },
                    vaultName = selectedIdentity?.name,
                )
              }
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
            composable(Routes.LFS_ENDPOINTS_M3) {
                com.bontecou.syncmd.ui.screens.LfsEndpointsScreen(onBack = { navController.popBackStackSafe() })
            }
            composable(Routes.SSH_HOSTS) {
                com.bontecou.syncmd.ui.screens.SshTrustScreen(onBack = { navController.popBackStackSafe() })
            }
            composable("${Routes.LARGE_FILES}/{repo}", arguments = listOf(navArgument("repo") { type = NavType.StringType })) { entry ->
                com.bontecou.syncmd.ui.screens.LfsScreen(entry.arguments?.getString("repo").orEmpty(), onBack = { navController.popBackStackSafe() })
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
    // Home shows a session problem inline (board "24 · Sign-in expired"); elsewhere it stays a dialog.
    if (sessionError != null && navigationEntry?.destination?.route != Routes.LOGIN && navigationEntry?.destination?.route != Routes.REPOS) {
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
