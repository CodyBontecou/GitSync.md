package com.bontecou.syncmd.ui

import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavType
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.bontecou.syncmd.ui.components.GitDestination
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.ui.util.onResumedSource
import com.bontecou.syncmd.ui.util.displayIdentity

/** Routes for the Material 3 Git area: every one takes the working copy's path. */
object GitRoutes {
    const val BRANCHES = "git-branches"
    const val STASHES = "git-stashes"
    const val TAGS = "git-tags"
    const val REMOTE = "git-remote"
    const val CONFLICT = "git-conflict"
    const val MANAGE = "git-manage"
    /** The LFS area registers this route in AppShell (Routes.LARGE_FILES). */
    const val LARGE_FILES = Routes.LARGE_FILES
}

/** Open a Git destination for [path]. */
fun NavController.openGit(destination: GitDestination, path: String) {
    val encoded = Uri.encode(path)
    when (destination) {
        GitDestination.Branches -> navigateSafe("${GitRoutes.BRANCHES}/$encoded")
        GitDestination.Stashes -> navigateSafe("${GitRoutes.STASHES}/$encoded")
        GitDestination.Tags -> navigateSafe("${GitRoutes.TAGS}/$encoded")
        GitDestination.Remote -> navigateSafe("${GitRoutes.REMOTE}/$encoded")
        GitDestination.Conflicts -> navigateSafe("${GitRoutes.CONFLICT}/$encoded") { launchSingleTop = true }
        GitDestination.ManageVault -> navigateSafe("${GitRoutes.MANAGE}/$encoded")
        GitDestination.VaultSettings -> navigateSafe("${Routes.VAULT_SETTINGS}/$encoded")
        GitDestination.LargeFiles -> navigateSafe("${GitRoutes.LARGE_FILES}/$encoded")
        GitDestination.Rebase -> navigateSafe("${Routes.REBASE}/$encoded")
        GitDestination.Upstream -> navigateSafe("${Routes.UPSTREAM}/$encoded")
        GitDestination.Author -> navigateSafe("${Routes.AUTHOR}/$encoded")
        GitDestination.Connection -> navigateSafe("${Routes.CONNECTION}/$encoded")
        GitDestination.MoveRepository -> navigateSafe("${Routes.MOVE}?path=$encoded")
        GitDestination.Changes -> onResumedSource { if (!popBackStack(Routes.VAULT, inclusive = false)) navigate(Routes.VAULT) }
        is GitDestination.Editor -> navigateSafe("${Routes.EDITOR}/${Uri.encode(destination.filePath)}")
    }
}

private fun NavGraphBuilder.pathRoute(route: String, content: @Composable (String) -> Unit) =
    composable("$route/{path}", arguments = listOf(navArgument("path") { type = NavType.StringType })) { entry ->
        content(entry.arguments?.getString("path").orEmpty())
    }

/**
 * The Git area's screens. [vaultName] resolves a working copy's display name for the top bars;
 * [settings] owns repository registration (Manage vault removes, deletes and untracks through it).
 */
fun NavGraphBuilder.gitRoutes(navController: NavController, settings: SettingsViewModel, vaultName: (String) -> String) {
    val back = { navController.popBackStackSafe() }
    pathRoute(GitRoutes.BRANCHES) { path ->
        com.bontecou.syncmd.ui.screens.GitBranchesScreen(path, vaultName(path), onBack = { back() }, onNavigate = { navController.openGit(it, path) })
    }
    pathRoute(GitRoutes.STASHES) { path -> com.bontecou.syncmd.ui.screens.GitStashesScreen(path, vaultName(path), onBack = { back() }) }
    pathRoute(GitRoutes.TAGS) { path -> com.bontecou.syncmd.ui.screens.GitTagsScreen(path, vaultName(path), onBack = { back() }) }
    pathRoute(GitRoutes.REMOTE) { path ->
        com.bontecou.syncmd.ui.screens.GitRemoteScreen(path, vaultName(path), onBack = { back() }, onNavigate = { navController.openGit(it, path) })
    }
    pathRoute(GitRoutes.CONFLICT) { path ->
        com.bontecou.syncmd.ui.screens.ConflictScreen(path, vaultName(path), onBack = { back() }, onNavigate = { navController.openGit(it, path) })
    }
    pathRoute(GitRoutes.MANAGE) { path ->
        com.bontecou.syncmd.ui.screens.GitManageVaultScreen(path, settings, onBack = { back() },
            onOpenVault = { navController.openGit(GitDestination.Changes, path) },
            onNavigate = { navController.openGit(it, path) },
            onRemoved = { navController.onResumedSource { if (!navController.popBackStack(Routes.REPOS, inclusive = false)) navController.navigate(Routes.REPOS) } })
    }
}

/** The display name for a registered working copy, or its folder name. */
@Composable
fun rememberVaultName(settings: SettingsViewModel): (String) -> String {
    val repositories by settings.allRepositories.collectAsState()
    return { path -> repositories.firstOrNull { it.path == path }?.displayIdentity()?.name?.takeIf { it.isNotBlank() } ?: path.substringAfterLast('/') }
}
