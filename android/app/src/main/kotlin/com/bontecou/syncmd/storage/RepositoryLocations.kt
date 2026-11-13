package com.bontecou.syncmd.storage

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import androidx.annotation.StringRes
import androidx.core.content.ContextCompat
import com.bontecou.syncmd.R
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.util.UUID

data class RepositoryFolder(val uri: String, val path: String, val name: String)

/** User-selected device folders. A content URI is never treated as an ordinary filesystem path. */
class RepositoryLocations(private val context: Context) {
    companion object {
        const val APP = "app-storage"
        private val lock = Any()
        fun hasSharedStorageAccess(context: Context): Boolean = if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
            else ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }
    private fun text(@StringRes id: Int): String = context.getString(id)
    private val prefs = context.getSharedPreferences("repository_locations", Context.MODE_PRIVATE)
    private fun records(): List<RepositoryFolder> {
        val items = JSONArray(prefs.getString("folders", "[]"))
        return (0 until items.length()).map { index -> items.getJSONObject(index).let {
            RepositoryFolder(it.getString("uri"), it.getString("path"), it.getString("name"))
        } }
    }
    fun defaultSelection(): String = prefs.getString("default", APP) ?: APP
    fun summary(selection: String? = null): RepositoryFolder = synchronized(lock) {
        val selected = selection ?: defaultSelection()
        if (selected == APP) return@synchronized RepositoryFolder(APP, CloneStorage.appDefaultCloneBaseDir(context), text(R.string.vault_l10n_app_storage))
        records().firstOrNull { it.uri == selected } ?: error(text(R.string.vault_l10n_folder_repository_choose_again))
    }
    fun resolve(selection: String? = null): RepositoryFolder = synchronized(lock) {
        val folder = summary(selection)
        if (folder.uri == APP) return@synchronized folder
        val uri = Uri.parse(folder.uri)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) {
            text(R.string.vault_l10n_folder_access_removed)
        }
        val physical = deviceFolder(uri)
        require(physical.canonicalPath == folder.path) { text(R.string.vault_l10n_storage_location_changed) }
        requireAccess(physical)
        folder
    }
    /** Call only with the tree returned by Android's folder picker. */
    fun rememberFolder(uri: Uri): RepositoryFolder = rememberFolder(uri, probe = true)
    /** Publication review must not create temporary files in a folder before its journal exists. */
    fun rememberPublicationFolder(uri: Uri): RepositoryFolder = rememberFolder(uri, probe = false)
    private fun rememberFolder(uri: Uri, probe: Boolean): RepositoryFolder = synchronized(lock) {
        val file = deviceFolder(uri)
        if (probe) requireAccess(file) else {
            require(CloneStorage.isWithinAppWritableRoots(context, file.path) || hasSharedStorageAccess(context)) { text(R.string.vault_l10n_enable_shared_storage_before) }
            require(file.isDirectory && !Files.isSymbolicLink(file.toPath())) { text(R.string.vault_l10n_folder_unavailable) }
        }
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission }) { text(R.string.vault_l10n_folder_retain_access_failed) }
        val folder = RepositoryFolder(uri.toString(), file.canonicalPath, file.name.ifBlank { text(R.string.vault_l10n_device_storage) })
        val previous = prefs.getString("folders", "[]")
        val updated = listOf(folder) + records().filterNot { it.uri == folder.uri }
        val items = JSONArray()
        updated.forEach { items.put(JSONObject().put("uri", it.uri).put("path", it.path).put("name", it.name)) }
        try { check(prefs.edit().putString("folders", items.toString()).commit()) { text(R.string.vault_l10n_folder_access_save_failed) }
        } catch (e: Exception) { check(prefs.edit().putString("folders", previous).commit()); throw e }
        folder
    }
    fun setDefault(selection: String) = synchronized(lock) {
        resolve(selection)
        val previous = defaultSelection()
        if (!prefs.edit().putString("default", selection).commit()) {
            check(prefs.edit().putString("default", previous).commit())
            error(text(R.string.vault_l10n_default_location_save_failed))
        }
    }
    fun selectedFolders(): List<RepositoryFolder> = synchronized(lock) { records() }

    /** Read-only publication binding check: an old path alone never restores an expired tree grant. */
    fun requireFolderBinding(uri: String, path: String) = synchronized(lock) {
        val saved = records().singleOrNull { it.uri == uri } ?: error(text(R.string.vault_l10n_publication_folder_choose_again))
        val parsed = Uri.parse(uri)
        require(context.contentResolver.persistedUriPermissions.any { it.uri == parsed && it.isReadPermission && it.isWritePermission }) {
            text(R.string.vault_l10n_publication_access_removed)
        }
        require(saved.path == File(path).canonicalPath && deviceFolder(parsed).canonicalPath == saved.path) {
            text(R.string.vault_l10n_publication_grant_different)
        }
        requireRepositoryAccess(path)
    }

    /** Recheck saved grants for reads and writes, including already-added working copies. */
    fun requireRepositoryAccess(path: String) = synchronized(lock) {
        if (CloneStorage.isWithinAppWritableRoots(context, path)) return@synchronized
        require(hasSharedStorageAccess(context)) { text(R.string.vault_l10n_shared_storage_removed) }
        val canonical = File(path).canonicalPath
        val candidates = records().filter { canonical == it.path || canonical.startsWith(it.path + "/") }
        require(candidates.any { folder ->
            val uri = Uri.parse(folder.uri)
            context.contentResolver.persistedUriPermissions.any { it.uri == uri && it.isReadPermission && it.isWritePermission } &&
                runCatching { deviceFolder(uri).canonicalPath == folder.path }.getOrDefault(false)
        }) { text(R.string.vault_l10n_repository_folder_choose_again) }
    }

    private fun deviceFolder(uri: Uri): File {
        require(uri.scheme == "content" && uri.authority == "com.android.externalstorage.documents" && DocumentsContract.isTreeUri(uri)) {
            text(R.string.vault_l10n_folder_cloud_provider)
        }
        val id = DocumentsContract.getTreeDocumentId(uri).split(':', limit = 2)
        require(id.size == 2 && id[0].isNotBlank()) { text(R.string.vault_l10n_folder_no_volume) }
        // DocumentsUI can return its home: alias instead of primary:Documents/….
        // Treat Documents as a checked path component, so aliases cannot bypass link checks.
        val documents = id[0] == "home"
        val segments = (if (documents) listOf(Environment.DIRECTORY_DOCUMENTS) else emptyList()) +
            id[1].split('/').filter { it.isNotEmpty() }
        require(segments.none { it == "." || it == ".." || it == ".git" || it.contains('\\') || it.contains('\u0000') }) { text(R.string.vault_l10n_folder_outside_git_metadata) }
        val storage = context.getSystemService(StorageManager::class.java)
        val volume = storage.storageVolumes.firstOrNull { if (documents || id[0].equals("primary", true)) it.isPrimary else it.uuid?.equals(id[0], true) == true }
            ?: error(text(R.string.vault_l10n_volume_unavailable_reconnect))
        val root = if (Build.VERSION.SDK_INT >= 30) volume.directory else if (volume.isPrimary) Environment.getExternalStorageDirectory()
            else volume.uuid?.takeIf { it.matches(Regex("[A-Za-z0-9-]+")) }?.let { File("/storage/$it") }
        require(root != null && root.isDirectory) { text(R.string.vault_l10n_volume_unavailable) }
        val base = root.canonicalFile
        var current = base
        for (part in segments) {
            current = File(current, part)
            require(!Files.isSymbolicLink(current.toPath())) { text(R.string.vault_l10n_folder_no_links) }
        }
        val result = current.canonicalFile
        require(result == base || result.path.startsWith(base.path + "/")) { text(R.string.vault_l10n_folder_outside_volume) }
        return result
    }
    private fun requireAccess(folder: File) {
        require(CloneStorage.isWithinAppWritableRoots(context, folder.path) || hasSharedStorageAccess(context)) {
            text(R.string.vault_l10n_enable_shared_storage)
        }
        require(folder.isDirectory && !Files.isSymbolicLink(folder.toPath())) { text(R.string.vault_l10n_folder_unavailable) }
        val probe = File(folder, ".gitsync-access-${UUID.randomUUID()}")
        val renamed = File(folder, ".gitsync-access-${UUID.randomUUID()}")
        var ownsProbe = false
        var ownsRenamed = false
        try {
            check(probe.createNewFile())
            ownsProbe = true
            FileOutputStream(probe).use { it.write("GitSync.md folder access".toByteArray()); it.fd.sync() }
            Files.move(probe.toPath(), renamed.toPath())
            ownsRenamed = true
            check(renamed.readText() == "GitSync.md folder access")
            check(renamed.delete())
        } catch (e: Exception) { throw IllegalStateException(text(R.string.vault_l10n_folder_not_writable))
        } finally { if (ownsProbe) probe.delete(); if (ownsRenamed) renamed.delete() }
    }
}
