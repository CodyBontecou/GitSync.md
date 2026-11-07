package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.github.GitHubAuthManager
import com.bontecou.syncmd.services.publication.*
import com.bontecou.syncmd.storage.AndroidFolderPublications
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.github.GitHubUser
import com.bontecou.syncmd.services.repositories.RepositoryDiscovery
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.UUID

/** Native durability/access fixtures; never an external account or GitHub publication. */
class FolderPublicationJournalDeviceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun record(root: File): FolderPublicationRecord {
        File(root, "note.md").writeText("Reviewed bytes\r\n")
        val review = FolderPublicationGit().inspect(root.canonicalPath)
        return FolderPublicationRecord(FolderPublicationInput(UUID.randomUUID().toString(), review, review.suggestedPaths,
            "Fixture", "fixture@example.invalid", "Reviewed initial commit"), null, PublicationAccount(17, "fixture"), "reviewed")
    }
    private suspend fun fixture(action: suspend (Context, File) -> Unit) {
        val id = UUID.randomUUID().toString()
        val workspace = File(context.filesDir, "publication-journal-native-$id").canonicalFile.apply { mkdir() }
        val privateRoot = File(context.noBackupFilesDir, "publication-journal-native-$id").canonicalFile.apply { mkdir() }
        val prefix = "publication-journal-$id-"
        val isolated = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir() = privateRoot
            override fun getSharedPreferences(name: String, mode: Int) = super.getSharedPreferences(prefix + name, mode)
        }
        try { action(isolated, workspace) }
        finally {
            workspace.deleteRecursively(); privateRoot.deleteRecursively()
            listOf("sync_md_prefs", "github_session", "repository_locations", "repository_credentials").forEach { context.deleteSharedPreferences(prefix + it) }
        }
    }

    @Test fun privateJournalPersistsWithNativeDirectoryFsyncAndRejectsCorruption() = runBlocking {
        fixture { host, root ->
            val original = record(root); val store = AndroidFolderPublications.privateStore(host)
            store.insert(original)
            assertEquals(listOf(original), AndroidFolderPublications.privateStore(host).load())
            val journal = File(host.noBackupFilesDir, "folder-publications/progress.json")
            assertTrue(journal.isFile); assertFalse(File(root, ".git").exists())
            journal.writeText("{unreadable-preserve-this")
            assertTrue(runCatching { store.load() }.exceptionOrNull() is FolderPublicationJournalException)
            assertTrue(runCatching { store.replace(original, original.copy(isPrivate = false)) }.isFailure)
            assertEquals("{unreadable-preserve-this", journal.readText())
        }
    }

    @Test fun originalFolderIdentityIsRequiredEvenWhenTheSamePathIsReused() = runBlocking {
        fixture { host, root ->
            val original = record(root)
            val runtime = AndroidFolderPublications(host, GitHubAuthManager(host))
            runtime.requireAccess(original)
            val retained = File(root.parentFile, root.name + "-retained")
            try {
                assertTrue(root.renameTo(retained)); assertTrue(root.mkdir())
                File(root, "note.md").writeText("Unrelated replacement")
                assertTrue(runCatching { runtime.requireAccess(original) }.isFailure)
                assertEquals("Unrelated replacement", File(root, "note.md").readText())
                assertEquals("Reviewed bytes\r\n", File(retained, "note.md").readText())
            } finally { root.deleteRecursively(); assertTrue(retained.renameTo(root)) }
        }
    }

    @Test fun missingGrantAndSymlinkNeverFallBackToAnAccessibleSavedPath() = runBlocking {
        fixture { host, root ->
            val original = record(root)
            val runtime = AndroidFolderPublications(host, GitHubAuthManager(host))
            assertTrue(runCatching { runtime.requireAccess(original.copy(folderUri = "content://fixture/tree/expired")) }.isFailure)
            val alias = File(root.parentFile, root.name + "-alias")
            try {
                Files.createSymbolicLink(alias.toPath(), root.toPath())
                val changed = original.copy(input = original.input.copy(review = original.input.review.copy(path = alias.path)))
                assertTrue(runCatching { runtime.requireAccess(changed) }.isFailure)
                val beginFailure = runCatching { runtime.begin(com.bontecou.syncmd.storage.RepositoryFolder(
                    com.bontecou.syncmd.storage.RepositoryLocations.APP, alias.path, "Alias"), "github:17") }.exceptionOrNull()
                assertTrue(beginFailure?.message.orEmpty().contains("symbolic links"))
            } finally { Files.deleteIfExists(alias.toPath()) }
            assertFalse(File(root, ".git").exists())
        }
    }

    @Test fun interruptedOwnedPreparationResumesThroughANewAndroidHost() = runBlocking {
        fixture { host, root ->
            val original = record(root); val store = AndroidFolderPublications.privateStore(host); store.insert(original)
            val preparing = original.copy(phase = FolderPublicationPhase.PREPARING); store.replace(original, preparing)
            val prepared = FolderPublicationGit().prepare(original.input)
            // Model a process stopping after Git persisted the commit, before private progress saved it.
            val publisher = object : FolderPublicationPublisher {
                override suspend fun account(token: String) = original.account
                override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote = error("No creation expected")
                override suspend fun repository(account: PublicationAccount, name: String, token: String): PublicationRemote = error("No network expected")
                override suspend fun references(remote: PublicationRemote, token: String): List<PublicationRef> = error("No network expected")
                override suspend fun branch(remote: PublicationRemote, name: String, token: String): String? = error("No network expected")
            }
            val renewed = AndroidFolderPublications(host, GitHubAuthManager(host), publisher = publisher)
            val loaded = renewed.coordinator.recover().single()
            val resumed = renewed.coordinator.prepare(loaded, "fixture-only-token")
            assertEquals(prepared, resumed.prepared)
            assertEquals(FolderPublicationPhase.PREPARED, resumed.phase)
            assertEquals("Reviewed bytes\r\n", File(root, "note.md").readText())
            assertEquals(listOf(resumed), AndroidFolderPublications.privateStore(host).load())
        }
    }

    @Test fun realLocalPublicationRegistersEncryptedAccountAndClearsGuardOnlyAfterDurableCompletion() = runBlocking {
        fixture { host, workspace ->
            var root = File(workspace, "folder").apply { mkdir() }
            val bare = File(workspace, "remote.git")
            Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
            val original = record(root)
            val remote = PublicationRemote(92, original.account, original.repositoryName, true,
                "https://github.com/fixture/reviewed", "https://github.com/fixture/reviewed.git")
            val service = FolderPublicationGit(transport = { repo, actual ->
                check(actual == remote.cloneUrl)
                val delegate = Transport.open(repo, URIish(bare.toURI().toString()))
                object : Transport(repo, URIish(actual)) {
                    override fun openFetch(): FetchConnection = delegate.openFetch()
                    override fun openPush(): PushConnection = delegate.openPush()
                    override fun close() = delegate.close()
                }
            })
            var posts = 0
            val publisher = object : FolderPublicationPublisher {
                override suspend fun account(token: String) = original.account
                override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote { posts++; return remote }
                override suspend fun repository(account: PublicationAccount, name: String, token: String) = remote
                override suspend fun references(remote: PublicationRemote, token: String): List<PublicationRef> = Git.open(bare).use { git ->
                    git.repository.refDatabase.getRefsByPrefix("refs/").map { PublicationRef(it.name, it.objectId.name, "commit") }
                }
                override suspend fun branch(remote: PublicationRemote, name: String, token: String): String? = Git.open(bare).use { it.repository.resolve("refs/heads/$name")?.name }
            }
            val auth = GitHubAuthManager(host)
            assertTrue(auth.saveValidatedToken("native-fixture-publication-token", GitHubUser("fixture", "Fixture", "fixture@example.invalid", null, 17)))
            var runtime = AndroidFolderPublications(host, auth, git = service, publisher = publisher)
            runtime.store.insert(original)
            val settings = SettingsViewModel(host)
            try {
                var ready = runtime.coordinator.prepare(original, runtime.token(original))
                assertTrue(runCatching { RepositoryDiscovery().inspect(root.path) }.exceptionOrNull() is FolderPublicationOverlap)
                val relocated = File(workspace, "moved-folder")
                assertTrue(root.renameTo(relocated)); root = relocated
                File(root, "note.md").writeText("Newer staged bytes before recovery")
                Git.open(root).use { it.add().addFilepattern("note.md").call() }
                val index = File(root, ".git/index").readBytes()
                // New host represents reloading private progress; reconnection itself has no API effects.
                runtime = AndroidFolderPublications(host, auth, git = service, publisher = publisher)
                val loaded = runtime.coordinator.recover().single()
                ready = runtime.reconnect(loaded, com.bontecou.syncmd.storage.RepositoryFolder(
                    com.bontecou.syncmd.storage.RepositoryLocations.APP, root.path, root.name))
                assertEquals(original.id, ready.id); assertEquals(0, posts)
                assertEquals(root.path, ready.input.review.path)
                val published = runtime.coordinator.push(runtime.coordinator.create(ready, runtime.token(ready)), runtime.token(ready))
                assertArrayEquals(index, File(root, ".git/index").readBytes())
                assertEquals("Newer staged bytes before recovery", File(root, "note.md").readText())
                assertTrue(runCatching { RepositoryDiscovery().inspect(root.path) }.isFailure)
                val completed = runtime.coordinator.complete(published, runtime.token(published)) {
                    settings.registerPublication(it, "github:17")
                    // A crash after this registration is recoverable without duplicating the catalog entry.
                    settings.registerPublication(it, "github:17")
                    assertTrue(File(root, ".git/folder-publication.json").isFile)
                }
                assertEquals(FolderPublicationPhase.COMPLETED, completed.phase)
                assertEquals(1, posts); assertEquals(1, settings.allRepositories.value.size)
                val binding = RepositoryCredentialStore(host).summary(root.path)!!
                assertEquals("github:17", binding.accountId); assertTrue(binding.usesGithubAccount)
                assertEquals(remote.cloneUrl, binding.url)
                assertFalse(File(root, ".git/folder-publication.json").exists())
                Git.open(root).use { assertNull(it.repository.config.getString("gitsync", null, "pendingPublication")) }
                assertEquals("main", RepositoryDiscovery().inspect(root.path).branch)
                File(root, "note.md").writeText("Newer work after publication")
                Git.open(root).use { git -> git.add().addFilepattern("note.md").call(); git.commit().setMessage("Later local commit").call() }
                assertEquals(completed, runtime.coordinator.complete(completed, "") { error("Registration must not repeat") })
                assertEquals("Newer work after publication", File(root, "note.md").readText())
                assertEquals(1, settings.allRepositories.value.size)
                assertFalse(File(host.noBackupFilesDir, "folder-publications/progress.json").readText().contains("native-fixture-publication-token"))
            } finally {
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    androidx.lifecycle.ViewModelStore().apply { put("publication-settings", settings); clear() }
                }
            }
        }
    }

    @Test fun copiedPublicationReconnectionRequiresFreshAccessAndRejectsRegisteredDestination() = runBlocking {
        fixture { host, workspace ->
            val originalRoot = File(workspace, "original").apply { mkdir() }
            val original = record(originalRoot)
            val runtime = AndroidFolderPublications(host, GitHubAuthManager(host))
            runtime.store.insert(original)
            val preparing = original.copy(phase = FolderPublicationPhase.PREPARING)
            runtime.store.replace(original, preparing)
            val ready = preparing.copy(phase = FolderPublicationPhase.PREPARED, prepared = FolderPublicationGit().prepare(original.input))
            runtime.store.replace(preparing, ready)
            val destination = File(workspace, "copied"); assertTrue(originalRoot.copyRecursively(destination))
            val selected = com.bontecou.syncmd.storage.RepositoryFolder(
                com.bontecou.syncmd.storage.RepositoryLocations.APP, destination.path, destination.name)
            val marker = File(originalRoot, ".git/folder-publication.json").readBytes()
            assertTrue(runCatching { runtime.reconnect(ready, selected.copy(uri = "content://fixture/tree/revoked")) }.isFailure)
            val prefs = host.getSharedPreferences("sync_md_prefs", 0)
            assertTrue(prefs.edit().putString("repo_0_path", destination.path).commit())
            assertTrue(runCatching { runtime.reconnect(ready, selected) }.isFailure)
            assertEquals(listOf(ready), runtime.store.load())
            assertTrue(prefs.edit().remove("repo_0_path").commit())
            val rebound = runtime.reconnect(ready, selected)
            assertEquals(destination.path, rebound.input.review.path)
            assertNotEquals(ready.input.review.identity, rebound.input.review.identity)
            assertEquals(ready.prepared, rebound.prepared)
            assertArrayEquals(marker, File(originalRoot, ".git/folder-publication.json").readBytes())
            assertTrue(runCatching { RepositoryDiscovery().inspect(originalRoot.path) }.exceptionOrNull() is FolderPublicationOverlap)
            assertTrue(runCatching { RepositoryDiscovery().inspect(destination.path) }.exceptionOrNull() is FolderPublicationOverlap)
            assertEquals(listOf(rebound), AndroidFolderPublications.privateStore(host).load())
            runtime.requireAccess(rebound)
        }
    }

    @Test fun publicationBindingNeverOverwritesAnotherCredentialPolicyAndIsIdempotentAfterRestart() = runBlocking {
        fixture { host, root ->
            val credentials = RepositoryCredentialStore(host)
            val url = "https://github.com/fixture/reviewed.git"; val workflow = UUID.randomUUID().toString()
            credentials.save(root.path, url, com.bontecou.syncmd.data.models.Credentials.Basic("fixture", "preserved-fixture-secret"))
            val version = credentials.recordVersion(root.path)
            assertTrue(runCatching { credentials.bindPublication(workflow, root.path, url, "github:17") }.isFailure)
            assertEquals(version, credentials.recordVersion(root.path))
            assertEquals(com.bontecou.syncmd.data.models.Credentials.Basic("fixture", "preserved-fixture-secret"), credentials.resolve(root.path, url))
            credentials.remove(root.path)
            credentials.bindPublication(workflow, root.path, url, "github:17")
            val saved = credentials.recordVersion(root.path)
            RepositoryCredentialStore(host).bindPublication(workflow, root.path, url, "github:17")
            assertEquals(saved, credentials.recordVersion(root.path))
            assertTrue(runCatching { credentials.bindPublication(UUID.randomUUID().toString(), root.path, url, "github:17") }.isFailure)
            assertTrue(runCatching { credentials.bindPublication(workflow, root.path, url, "github:18") }.isFailure)
            assertEquals(saved, credentials.recordVersion(root.path))
            assertFalse(host.getSharedPreferences("repository_credentials", 0).all.values.any { it.toString().contains("preserved-fixture-secret") })
        }
    }

    @Test fun beginningReviewPinsChosenAccountDespiteBrowsingSwitchAndExcludesSensitiveDefaults() = runBlocking {
        fixture { host, root ->
            File(root, "note.md").writeText("Reviewed")
            File(root, ".env").writeText("FAKE_SECRET=excluded")
            val auth = GitHubAuthManager(host)
            assertTrue(auth.saveValidatedToken("selected-fixture-token", GitHubUser("fixture", "Selected Author", "selected@example.invalid", null, 17)))
            assertTrue(auth.saveValidatedToken("browsing-fixture-token", GitHubUser("other", "Other Author", "other@example.invalid", null, 18)))
            val api = object : FolderPublicationPublisher {
                override suspend fun account(token: String): PublicationAccount {
                    assertEquals("selected-fixture-token", token); return PublicationAccount(17, "fixture")
                }
                override suspend fun create(account: PublicationAccount, name: String, isPrivate: Boolean, token: String): PublicationRemote = error("Review must not create")
                override suspend fun repository(account: PublicationAccount, name: String, token: String): PublicationRemote = error("Review must not reconcile")
                override suspend fun references(remote: PublicationRemote, token: String): List<PublicationRef> = error("Review must not inspect refs")
                override suspend fun branch(remote: PublicationRemote, name: String, token: String): String? = error("Review must not inspect refs")
            }
            val runtime = AndroidFolderPublications(host, auth, publisher = api)
            val catalog = host.getSharedPreferences("sync_md_prefs", 0)
            assertTrue(catalog.edit().putInt("repository_count", 1).putString("repo_0_path", root.path).commit())
            val alreadyAdded = runCatching { runtime.begin(com.bontecou.syncmd.storage.RepositoryFolder(
                com.bontecou.syncmd.storage.RepositoryLocations.APP, root.path, "Reviewed notes"), "github:17") }.exceptionOrNull()
            assertTrue(alreadyAdded?.message.orEmpty().contains("registered repository"))
            assertFalse(File(root, ".git").exists()); assertTrue(runtime.store.load().isEmpty())
            assertTrue(catalog.edit().remove("repo_0_path").remove("repository_count").commit())
            val review = runtime.begin(com.bontecou.syncmd.storage.RepositoryFolder(com.bontecou.syncmd.storage.RepositoryLocations.APP,
                root.path, "Reviewed notes"), "github:17")
            assertEquals(PublicationAccount(17, "fixture"), review.account)
            assertEquals("Selected Author", review.input.authorName)
            assertEquals(setOf("note.md"), review.input.selected)
            assertTrue(review.isPrivate); assertFalse(File(root, ".git").exists())
            assertEquals("selected-fixture-token", runtime.token(review))
            assertEquals("github:18", auth.activeAccountId())
            assertEquals(listOf(review), AndroidFolderPublications.privateStore(host).load())
            assertFalse(File(host.noBackupFilesDir, "folder-publications/progress.json").readText().contains("selected-fixture-token"))
        }
    }
}
