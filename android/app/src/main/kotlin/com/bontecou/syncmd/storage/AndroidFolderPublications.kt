package com.bontecou.syncmd.storage

import android.content.Context
import android.system.Os
import android.system.OsConstants
import androidx.annotation.StringRes
import com.bontecou.syncmd.R
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.publication.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.BasicFileAttributes
import java.util.UUID

/** Android owns URI/volume access and durability; the core workflow never interprets a content URI. */
class AndroidFolderPublications(
    private val context: Context,
    private val auth: GitHubAuthManager,
    val store: FolderPublicationStore = privateStore(context),
    private val git: FolderPublicationGitOperations = FolderPublicationGit(),
    private val publisher: FolderPublicationPublisher = GitHubFolderPublisher()
) {
    private val locations = RepositoryLocations(context)
    val coordinator = FolderPublicationCoordinator(store, git, publisher, ::requireAccess)

    suspend fun begin(folder: RepositoryFolder, accountId: String): FolderPublicationRecord = withContext(Dispatchers.IO) {
        val selected = File(folder.path)
        require(selected.isAbsolute && selected.canonicalFile == selected.absoluteFile && !Files.isSymbolicLink(selected.toPath())) {
            text(R.string.vault_l10n_publication_original_no_links)
        }
        val path = selected.canonicalPath
        requireUnregistered(path)
        coordinator.withAvailableFolder(path) {
            val uri = folder.uri.takeUnless { it == RepositoryLocations.APP }
            requireBinding(path, uri)
            val summary = auth.accountSummary(accountId) ?: error(text(R.string.vault_l10n_publication_select_account))
            val account = publisher.account(auth.tokenForAccount(accountId))
            require(summary.id == "github:${account.id}" && summary.login?.equals(account.login, true) == true) {
                text(R.string.vault_l10n_publication_verify_account)
            }
            val review = git.inspect(path)
            val author = auth.authorForAccount(summary.id)
            val suggestedName = folder.name.replace(Regex("[^A-Za-z0-9_.-]"), "-").take(100)
                .takeUnless { it.isBlank() || it == "." || it == ".." } ?: "notes"
            val input = FolderPublicationInput(UUID.randomUUID().toString(), review, review.suggestedPaths,
                author.first, author.second, "Initial commit")
            FolderPublicationRecord(input, uri, account, suggestedName).also { requireAccess(it); store.insert(it) }
        }
    }

    suspend fun reconnect(expected: FolderPublicationRecord, folder: RepositoryFolder): FolderPublicationRecord = withContext(Dispatchers.IO) {
        val selected = File(folder.path)
        require(selected.isAbsolute && selected.canonicalFile == selected.absoluteFile && !Files.isSymbolicLink(selected.toPath())) {
            text(R.string.vault_l10n_publication_no_links)
        }
        if (selected.path != expected.input.review.path) requireUnregistered(selected.path)
        coordinator.reconnect(expected, folder.uri.takeUnless { it == RepositoryLocations.APP }, selected.path)
    }

    private fun requireUnregistered(path: String) {
        // Reject even stale catalog entries before any initial commit or rebinding can occur.
        val catalog = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE).all
        val registered = catalog.filterKeys { it.matches(Regex("repo_[0-9]+_path")) }.values.map { value ->
            require(value is String && value.isNotBlank()) { text(R.string.vault_l10n_publication_settings_repair) }
            value
        }.filterNot { it.startsWith("github://") }.map { File(it).canonicalPath }
        require(registered.none { it == path || it.startsWith("$path/") || path.startsWith("$it/") }) {
            text(R.string.vault_l10n_publication_overlaps)
        }
    }

    fun token(record: FolderPublicationRecord): String {
        val id = "github:${record.account.id}"
        val summary = auth.accountSummary(id) ?: error(text(R.string.vault_l10n_publication_sign_in))
        require(summary.connected && summary.login?.equals(record.account.login, true) == true) { text(R.string.vault_l10n_publication_reconnect_account) }
        return auth.tokenForAccount(id)
    }

    fun requireAccess(record: FolderPublicationRecord) {
        val path = record.input.review.path
        requireBinding(path, record.folderUri)
        val root = File(path)
        require(root.canonicalFile == root.absoluteFile && Files.isDirectory(root.toPath(), NOFOLLOW_LINKS)) {
            text(R.string.vault_l10n_publication_original_unavailable)
        }
        val identity = Files.readAttributes(root.toPath(), BasicFileAttributes::class.java, NOFOLLOW_LINKS).fileKey()?.toString()
        require(identity != null && identity == record.input.review.identity) {
            text(R.string.vault_l10n_publication_folder_different)
        }
    }

    private fun requireBinding(path: String, uri: String?) {
        if (uri == null) require(CloneStorage.isWithinAppWritableRoots(context, path)) {
            text(R.string.vault_l10n_publication_device_folder_again)
        } else locations.requireFolderBinding(uri, path)
        locations.requireRepositoryAccess(path)
    }

    /** Localized user-facing copy for callers that hold this runtime but no Context of their own. */
    internal fun text(@StringRes id: Int, vararg args: Any): String = context.getString(id, *args)

    companion object {
        fun privateStore(context: Context): FolderPublicationStore = FolderPublicationStore(
            File(context.noBackupFilesDir, "folder-publications").canonicalFile,
            syncDirectory = { directory ->
                val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
                try { Os.fsync(fd) } finally { Os.close(fd) }
            }
        )
    }
}
