package com.bontecou.syncmd

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.LocalGitRepository
import com.bontecou.syncmd.services.git.SampleRepository
import com.bontecou.syncmd.ui.screens.RemoteCloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class RemoteConnectionTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val context get() = object : android.content.ContextWrapper(app) {
        override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
    }
    private val prefs get() = context.getSharedPreferences("sync_md_prefs", Context.MODE_PRIVATE)
    private val securePrefs get() = context.getSharedPreferences("repository_credentials", Context.MODE_PRIVATE)
    private val store get() = RepositoryCredentialStore(context)
    private lateinit var workspace: File
    private val ownedPaths = mutableListOf<String>()
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val nativeDaemons = mutableListOf<org.eclipse.jgit.transport.Daemon>()
    private val authPreferenceFiles = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
    private var original: Map<String, *> = emptyMap<String, Any>()
    @Before fun setup() {
        workspace = File(context.filesDir, "remote-connection-${UUID.randomUUID()}").apply { mkdirs() }
        original = prefs.all.toMap()
    }
    @After fun cleanup() {
        val jobs = mutableListOf<kotlinx.coroutines.Job>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            models.forEach { jobs += it.viewModelScope.coroutineContext[kotlinx.coroutines.Job]!!.children.toList() }
            models.forEach { ViewModelStore().apply { put("vm", it); clear() } }
        }
        runBlocking { kotlinx.coroutines.withTimeout(15_000) { jobs.forEach { it.join() } } }
        nativeDaemons.forEach { it.stopAndWait() }
        ownedPaths.forEach { store.remove(it); File(it).deleteRecursively() }
        assertTrue(workspace.deleteRecursively())
        authPreferenceFiles.forEach { context.deleteSharedPreferences(it) }
        val editor = prefs.edit().clear()
        original.forEach { (key, value) -> when (value) {
            is String -> editor.putString(key, value)
            is Int -> editor.putInt(key, value)
            is Long -> editor.putLong(key, value)
            is Boolean -> editor.putBoolean(key, value)
            is Float -> editor.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") editor.putStringSet(key, value as Set<String>) }
        } }
        editor.commit()
    }
    private fun own(name: String): String = File(workspace, name).canonicalPath.also { ownedPaths += it }
    private fun recordId(path: String) = MessageDigest.getInstance("SHA-256").digest(File(path).canonicalPath.toByteArray()).joinToString("") { "%02x".format(it) }

    @Test fun fileCloneFormUsesLocalAccessAndRestoresAnonymousHistoryDefaults() {
        val source = SampleRepository.openOrCreate(File(workspace, "local source 名 #?"))
        Git.open(source).use { it.push().setPushAll().call() }
        val remote = File(source.parentFile, "sample-remote.git")
        val url = com.bontecou.syncmd.services.git.GitRemote.parse(remote.toURI().toASCIIString()).url
        val sourceHead = File(remote, "HEAD").readBytes()
        val sourceRefs = Git.open(remote).use { it.repository.refDatabase.getRefsByPrefix("refs/").associate { ref -> ref.name to ref.objectId.name } }
        val vm = RemoteCloneViewModel(LocalGitRepository(), store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var navigated = false
        compose.setContent { SyncMdTheme(darkTheme = false) { RemoteCloneScreen(settings, {}, { navigated = true }, vm) } }
        compose.onNodeWithTag("remote-auth-basic").performScrollTo().performClick()
        field("remote-url", url)
        openOptions()
        field("remote-name", "Local clone fixture")
        field("remote-branch", "sample-branch")
        // A local file address switches the sign-in to "None" and disables every network credential.
        compose.onNodeWithTag("remote-auth-basic").assertIsNotEnabled()
        compose.onNodeWithTag("remote-auth-ssh").assertIsNotEnabled()
        compose.onNodeWithTag("remote-normalized-url", useUnmergedTree = true).assertTextContains(context.getString(R.string.add_remote_file), substring = true)
        compose.onNodeWithTag("remote-file-hint").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("remote-auth-public").performScrollTo().assertIsSelected().assertTextContains(context.getString(R.string.add_auth_none)).performClick()
        capture("file-clone-light.png")
        compose.onNodeWithTag("remote-clone").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null }
        val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
        ownedPaths += target
        compose.waitUntil(10_000) { navigated }
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(target, url))
        val saved = settings.allRepositories.value.single { it.path == target }
        Git.open(File(target)).use {
            assertEquals("sample-branch", it.repository.branch)
            assertEquals(url, it.repository.config.getString("remote", "origin", "url"))
            assertFalse(File(it.repository.directory, "objects/info/alternates").exists())
        }
        assertArrayEquals(sourceHead, File(remote, "HEAD").readBytes())
        assertEquals(sourceRefs, Git.open(remote).use { it.repository.refDatabase.getRefsByPrefix("refs/").associate { ref -> ref.name to ref.objectId.name } })
        val historyVm = RemoteCloneViewModel(LocalGitRepository(), store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { historyVm.loadHistory(com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry(saved)) }
        compose.waitUntil(10_000) { historyVm.history.value.defaults != null || historyVm.history.value.error != null }
        assertNull(historyVm.history.value.error)
        val defaults = historyVm.history.value.defaults!!
        assertEquals(url, defaults.url)
        assertEquals("sample-branch", defaults.branch)
        assertEquals(RemoteCloneViewModel.HistoryAuth.PUBLIC, defaults.auth)
        assertNull(defaults.accountId)
    }

    @Test fun filePolicyRejectsNetworkCredentialsWithoutChangingTheSavedLocalBinding() {
        val path = own("local-policy")
        val url = File(own("local-source")).toURI().toASCIIString()
        store.save(path, url, Credentials.None)
        val version = store.recordVersion(path)
        for (creds in listOf(Credentials.Basic("writer", "fixture-secret"), Credentials.Pat("fixture-secret"), sshFixture())) {
            assertTrue(runCatching { store.save(path, url, creds) }.isFailure)
            assertEquals(version, store.recordVersion(path))
        }
        for (choice in RepositoryCredentialStore.Choice.values().filter { it !in listOf(RepositoryCredentialStore.Choice.PUBLIC, RepositoryCredentialStore.Choice.PUBLIC_KEEP) }) {
            assertTrue(runCatching { store.updateConnection(path, url, choice, Credentials.Basic("writer", "fixture-secret"), version, "fixture-account") { error("Must not commit") } }.isFailure)
            assertEquals(version, store.recordVersion(path))
        }
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(path, url))
    }

    @Test fun localSourceAccessIsCheckedBeforeClonePersistenceAndAgainForLaterTransport() {
        val source = SampleRepository.openOrCreate(File(workspace, "access-source"))
        Git.open(source).use { it.push().setPushAll().call() }
        val remote = File(source.parentFile, "sample-remote.git").canonicalFile
        val url = com.bontecou.syncmd.services.git.GitRemote.parse(remote.toURI().toASCIIString()).url
        val policy = com.bontecou.syncmd.storage.RepositoryLocations(app)
        val blocked = java.util.concurrent.atomic.AtomicBoolean(true)
        var calls = 0
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> {
                calls++; return local.clone(url, path, creds, branch)
            }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val before = securePrefs.all.toMap()
        com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.installAccessCheck { path ->
            if (path == remote.path && blocked.get()) error("Fixture source access removed")
            policy.requireRepositoryAccess(path)
        }
        try {
            vm.clone(url, "Local source", "sample-branch", false, "", "")
            compose.waitUntil(15_000) { !vm.state.value.busy && vm.state.value.error != null }
            assertEquals(context.getString(R.string.file_clone_failure), vm.state.value.error)
            assertEquals(0, calls)
            assertEquals(before, securePrefs.all)
            blocked.set(false)
            vm.clone(url, "Local source", "sample-branch", false, "", "")
            compose.waitUntil(15_000) { vm.state.value.path != null || !vm.state.value.busy && vm.state.value.error != null }
            val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
            ownedPaths += target
            assertEquals(1, calls)
            val version = store.recordVersion(target)
            blocked.set(true)
            Git.open(File(target)).use {
                assertTrue(runCatching { com.bontecou.syncmd.services.git.RemoteAuthentication.provider(it.repository, "origin", resolver = { _, _ -> Credentials.None }) }.isFailure)
            }
            assertEquals(version, store.recordVersion(target))
            assertSame(Credentials.None, store.resolve(target, url))
        } finally {
            blocked.set(false)
            com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.installAccessCheck(policy::requireRepositoryAccess)
            assertTrue(vm.cancelRecovery())
        }
    }

    @Test fun nativeGitCredentialPolicyIsPublicAndCannotBeReplacedWithSecrets() {
        val path = own("native-policy")
        val url = "git://github.com:443/team/notes.git"
        store.save(path, url, Credentials.None)
        val version = store.recordVersion(path)
        for (creds in listOf(Credentials.Basic("writer", "fixture-secret"), Credentials.Pat("fixture-secret"), sshFixture())) {
            assertTrue(runCatching { store.save(path, url, creds) }.isFailure)
            assertEquals(version, store.recordVersion(path))
        }
        for (choice in RepositoryCredentialStore.Choice.values().filter { it !in listOf(RepositoryCredentialStore.Choice.PUBLIC, RepositoryCredentialStore.Choice.PUBLIC_KEEP) }) {
            assertTrue(runCatching { store.updateConnection(path, url, choice, Credentials.Basic("writer", "fixture-secret"), version, "fixture-account") { error("Must not commit") } }.isFailure)
            assertEquals(version, store.recordVersion(path))
        }
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(path, url))
    }

    @Test fun nativeGitFormRequiresPublicSelectionAndRegistersAnAnonymousSelectedBranchClone() {
        val source = SampleRepository.openOrCreate(File(workspace, "native-source"))
        val remote = File(source.parentFile, "sample-remote.git")
        Git.open(source).use { it.push().setPushAll().call() }
        val daemon = org.eclipse.jgit.transport.Daemon(java.net.InetSocketAddress("127.0.0.1", 0))
        daemon.setRepositoryResolver(org.eclipse.jgit.transport.resolver.FileResolver<org.eclipse.jgit.transport.DaemonClient>(remote.parentFile, true))
        nativeDaemons += daemon
        daemon.start()
        val url = "git://127.0.0.1:${daemon.address.port}/sample-remote.git"
        var calls = 0
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> {
                calls++
                assertSame(Credentials.None, creds)
                return local.clone(url, path, creds, branch)
            }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var navigated = false
        compose.setContent { SyncMdTheme(darkTheme = false) { RemoteCloneScreen(settings, {}, { navigated = true }, vm) } }
        compose.onNodeWithTag("remote-auth-basic").performScrollTo().performClick()
        field("remote-url", url)
        openOptions()
        field("remote-name", "Native Git fixture")
        field("remote-branch", "sample-branch")
        // git:// switches the sign-in to "None" and disables every network credential.
        compose.onNodeWithTag("remote-auth-basic").assertIsNotEnabled()
        compose.onNodeWithTag("remote-auth-ssh").assertIsNotEnabled()
        compose.onNodeWithTag("remote-auth-public").assertIsSelected()
        compose.onNodeWithTag("remote-native-public-hint").performScrollTo().assertIsDisplayed()
        capture("native-git-light.png")
        // Calls bypassing the UI must also fail before any network or saved-policy mutation.
        vm.clone(url, "Native", "", true, "writer", "fixture-secret")
        assertTrue(vm.state.value.error.orEmpty().contains("public access"))
        assertEquals(0, calls)
        compose.onNodeWithTag("remote-auth-public").performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null }
        val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
        ownedPaths += target
        compose.waitUntil(10_000) { navigated }
        assertEquals(1, calls)
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(target, url))
        assertTrue(settings.allRepositories.value.any { it.path == target })
        Git.open(File(target)).use {
            assertEquals("sample-branch", it.repository.branch)
            assertEquals(url, it.repository.config.getString("remote", "origin", "url"))
        }
    }

    @Test fun credentialsAreEncryptedReloadedAndBoundToTheRepositoryAddress() {
        val path = own("first")
        val auth = Credentials.Basic("fixture-user", "fixture-private-token")
        store.save(path, "https://git.example/team/notes.git", auth)
        assertFalse(securePrefs.getString(recordId(path), "")!!.contains("fixture-private-token"))
        assertEquals(auth, RepositoryCredentialStore(context).resolve(path, "https://git.example/team/notes.git"))
        assertTrue(runCatching { store.resolve(path, "https://different.example/team/notes.git") }.isFailure)
        assertTrue(runCatching { store.resolve(path, "https://git.example/other/repo.git") }.isFailure)
        assertNull(store.resolve(own("unconfigured"), "https://git.example/team/notes.git"))
    }
    @Test fun anonymousPolicySurvivesReloadAndSwappedCiphertextIsRejected() {
        val first = own("first")
        val second = own("second")
        val url = "https://github.com/team/notes.git"
        store.save(first, url, Credentials.None)
        store.save(second, url, Credentials.Basic("writer", "fixture-token"))
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(first, url))
        securePrefs.edit().putString(recordId(first), securePrefs.getString(recordId(second), null)).commit()
        assertTrue(runCatching { store.resolve(first, url) }.isFailure)
        assertEquals(Credentials.Basic("writer", "fixture-token"), store.resolve(second, url))
    }
    private fun field(tag: String, value: String) {
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performTextReplacement(value)
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
    }

    /** Name and branch sit behind the collapsed "Name, branch and folder" row. */
    private fun openOptions() {
        compose.waitForIdle()
        if (compose.onAllNodesWithTag("remote-name").fetchSemanticsNodes().isEmpty())
            compose.onNodeWithTag("remote-options").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("remote-name").fetchSemanticsNodes().size == 1 }
    }

    /**
     * "Test & clone" first runs `git ls-remote` against the real address, which a synthetic host
     * (git.example, github.com/fixture) cannot answer. Forms using one prove the button is ready and
     * then hand the same values to the clone it would start; real-endpoint forms press the button.
     */
    private fun assertCloneReady() {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("remote-clone").assertIsEnabled()
    }

    private fun capture(name: String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.waitForIdle()
        val evidence = File(context.getExternalFilesDir(null), "parity-evidence").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(evidence, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }

    @Test fun httpFormClonesThroughRealLoopbackTransportWithExplicitBasicCredentials() {
        val sample=SampleRepository.openOrCreate(File(workspace,"http-source"))
        val remote=File(sample.parentFile,"sample-remote.git")
        val source=File(workspace,"http-lfs-source")
        val asset=ByteArray(4096){(it%233).toByte()}
        val oid=MessageDigest.getInstance("SHA-256").digest(asset).joinToString(""){"%02x".format(it)}
        val pointer=com.bontecou.syncmd.services.git.lfs.LfsPointer(oid,asset.size.toLong())
        Git.cloneRepository().setURI(remote.toURI().toString()).setDirectory(source).call().use {
            it.checkout().setCreateBranch(true).setName("sample-branch").call()
            File(source,".gitattributes").writeText("*.bin filter=lfs diff=lfs merge=lfs -text\n")
            File(source,"http-lfs.bin").writeBytes(pointer.bytes())
            it.add().addFilepattern(".").call();it.commit().setMessage("HTTP LFS fixture").setAuthor("Fixture","fixture@example.invalid").call()
            it.push().setPushAll().call()
        }
        LoopbackHttpGitFixture(remote,mapOf(oid to asset)).use { server ->
            assertTrue(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("127.0.0.1"))
            assertFalse(android.security.NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted("git.example"))
            val vm=RemoteCloneViewModel(LocalGitRepository(),store,context,com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models+=it }
            val settings=SettingsViewModel(context).also { models+=it };var returned=false
            compose.setContent { SyncMdTheme { RemoteCloneScreen(settings,{}, { returned=true },vm) } }
            field("remote-url",server.url);openOptions();field("remote-name","HTTP fixture");field("remote-branch","sample-branch")
            compose.onNodeWithTag("remote-http-warning").performScrollTo().assertIsDisplayed()
            capture("http-clone-light.png")
            compose.onNodeWithTag("remote-auth-basic").performScrollTo().performClick()
            field("remote-username","fixture-user");field("remote-secret","fixture-password")
            compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
            compose.waitUntil(20_000) { vm.state.value.path!=null || vm.state.value.error!=null }
            val target=vm.state.value.path ?: error(vm.state.value.error.orEmpty());ownedPaths+=target
            compose.waitUntil(10_000) { returned }
            assertEquals(Credentials.Basic("fixture-user","fixture-password"),store.resolve(target,server.url))
            assertTrue(runCatching { store.resolve(target,server.url.replace("http:","https:")) }.isFailure)
            Git.open(File(target)).use { assertEquals("sample-branch",it.repository.branch);assertEquals(server.url,it.repository.config.getString("remote","origin","url")) }
            assertTrue("HTTP LFS object was not hydrated",asset.contentEquals(File(target,"http-lfs.bin").readBytes()))
            assertTrue(runBlocking { LocalGitRepository().getStatus(target).getOrThrow().isEmpty() })
            assertEquals(1,server.lfsDownloads.get())
            assertTrue(server.authenticated.get()>0);assertEquals(0,server.unexpected.get())
        }
    }

    @Test fun httpPoliciesAreSchemeBoundAndCannotUseAutomaticGithubAccountAccess() {
        val path=own("http-policy");val url="http://github.com:443/fixture/notes.git"
        store.save(path,url,Credentials.Basic("writer","fixture-secret"));val version=store.recordVersion(path)
        assertEquals(Credentials.Basic("writer","fixture-secret"),store.resolve(path,url))
        assertTrue(runCatching { store.resolve(path,url.replace("http:","https:")) }.isFailure)
        assertTrue(runCatching { store.updateConnection(path,url,RepositoryCredentialStore.Choice.GITHUB,null,version,"fixture") { error("Must not commit") } }.isFailure)
        assertEquals(version,store.recordVersion(path))
    }

    @Test fun httpConnectionSaveKeepsDataAndStoresOnlyTheExplicitEndpoint() {
        val root=editableRepository();val index=File(root,".git/index").readBytes();val notes=File(root,"notes.md").readBytes()
        val vm=editorModel();var returned=false;showEditor(root,vm){returned=true}
        val url="http://git.example:8080/team/notes.git"
        field("connection-url",url)
        compose.onNodeWithTag("connection-http-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("connection-auth-REPLACE").performScrollTo().performClick()
        field("connection-username","fixture-writer");field("connection-secret","fixture-http-secret")
        capture("http-connection-light.png")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000){returned || vm.state.value.error!=null};assertNull(vm.state.value.error);assertTrue(returned)
        assertArrayEquals(index,File(root,".git/index").readBytes());assertArrayEquals(notes,File(root,"notes.md").readBytes())
        assertEquals(Credentials.Basic("fixture-writer","fixture-http-secret"),store.resolve(root.path,url))
        Git.open(root).use { assertEquals(url,it.repository.config.getString("remote","origin","url")) }
    }

    @Test fun shorthandClonePreviewsAndPersistsOnlyTheCanonicalGithubEndpoint() {
        val source = SampleRepository.openOrCreate(File(workspace, "shorthand-source"))
        Git.open(source).use { it.push().setPushAll().call() }
        val remote = File(source.parentFile, "sample-remote.git")
        val canonical = "https://github.com/fixture/%E7%AC%94%E8%AE%B0.git"
        val requested = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> {
                requested.set(url)
                assertEquals(canonical, url)
                return local.clone(remote.toURI().toASCIIString(), path, Credentials.None, branch).onSuccess {
                    Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
            }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var returned = false
        compose.setContent { SyncMdTheme(darkTheme = false) { RemoteCloneScreen(settings, {}, { returned = true }, vm) } }
        field("remote-url", "fixture/笔记")
        openOptions()
        field("remote-name", "Shorthand fixture")
        field("remote-branch", "sample-branch")
        // The form previews the GitHub endpoint the shorthand resolves to.
        compose.onNodeWithTag("remote-normalized-url", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("${context.getString(R.string.add_remote_https)} · github.com · fixture/笔记")
        capture("remote-shorthand-light.png")
        compose.onNodeWithTag("remote-auth-basic").performScrollTo().performClick()
        field("remote-username", "fixture-writer")
        field("remote-secret", "fixture-shorthand-secret")
        assertCloneReady()
        compose.runOnIdle { vm.clone("fixture/笔记", "Shorthand fixture", "sample-branch", true, "fixture-writer", "fixture-shorthand-secret") }
        try { compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("Shorthand unfinished: busy=${vm.state.value.busy}; requested=${requested.get()!=null}; submit_nodes=${compose.onAllNodesWithTag("remote-clone").fetchSemanticsNodes().size}; location_reviews=${compose.onAllNodesWithTag("folder-location-review").fetchSemanticsNodes().size}",failure)
        }
        val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
        ownedPaths += target
        compose.waitUntil(10_000) { returned }
        assertEquals(canonical, requested.get())
        assertEquals(Credentials.Basic("fixture-writer", "fixture-shorthand-secret"), RepositoryCredentialStore(context).resolve(target, canonical))
        assertFalse(securePrefs.getString(recordId(target), "")!!.contains("fixture-shorthand-secret"))
        assertTrue(runCatching { store.resolve(target, "https://github.com/fixture/other.git") }.isFailure)
        Git.open(File(target)).use {
            assertEquals(canonical, it.repository.config.getString("remote", "origin", "url"))
            assertEquals("sample-branch", it.repository.branch)
        }
        assertEquals(canonical, settings.allRepositories.value.single { it.path == target }.remoteUrl)
    }

    @Test fun storedRelativeHistoryIsNotReinterpretedAndMalformedInputCannotEchoInlineSecrets() {
        val prefix = "connection-relative-${UUID.randomUUID()}-"
        val isolated = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = prefix + name; authPreferenceFiles += scoped
                return context.getSharedPreferences(scoped, mode)
            }
        }
        val auth = com.bontecou.syncmd.services.github.GitHubAuthManager(isolated)
        assertTrue(auth.saveValidatedToken("fixture-relative-history-token"))
        assertNotNull(auth.defaultAccountId())
        var calls = 0
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> { calls++; error("No network expected") }
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> { calls++; error("No network expected") }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, auth).also { models += it }
        vm.clone("https://writer:synthetic-inline-secret@bad host/repo", "Invalid", "", false, "", "")
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("synthetic-inline-secret"))
        val source = own("relative-history")
        val entry = com.bontecou.syncmd.ui.viewmodels.RepositoryHistoryEntry(com.bontecou.syncmd.ui.viewmodels.SavedRepository("Relative fixture", source, remoteUrl = "fixture/notes"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync { vm.loadHistory(entry) }
        compose.waitUntil(10_000) { !vm.history.value.busy }
        assertNotNull(vm.history.value.error)
        assertNull(vm.history.value.defaults)
        assertNull(store.recordVersion(source))
        assertEquals(0, calls)
    }

    @Test fun formClonesSelectedBranchAndRegistersOnlyAfterEncryptedPolicyIsSaved() {
        val sourceWorkspace = File(workspace, "source")
        SampleRepository.openOrCreate(sourceWorkspace)
        val remoteFile = File(sourceWorkspace, "sample-remote.git")
        Git.open(File(sourceWorkspace, "sample-repository")).use { it.push().setPushAll().call() }
        val url = "https://git.example/team/private-notes.git"
        var requestedUrl = ""
        var requestedCredentials: Credentials? = null
        val local = LocalGitRepository()
        val fixtureGit = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = clone(url, path, creds, null)
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> {
                requestedUrl = url; requestedCredentials = creds
                return local.clone(remoteFile.toURI().toString(), path, Credentials.None, branch).onSuccess {
                    Git.open(File(path)).use { git -> git.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
            }
        }
        val vm = RemoteCloneViewModel(fixtureGit, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var navigated = false
        compose.setContent { SyncMdTheme(darkTheme = false) { RemoteCloneScreen(settings, {}, { navigated = true }, vm) } }
        field("remote-url", url)
        openOptions()
        field("remote-name", "Private HTTPS fixture")
        field("remote-branch", "sample-branch")
        compose.onNodeWithTag("remote-auth-basic").performScrollTo().assertTextContains(context.getString(R.string.add_auth_token)).performClick()
        field("remote-username", "fixture-user")
        field("remote-secret", "fixture-password")
        capture("remote-clone-light.png")
        assertCloneReady()
        compose.runOnIdle { vm.clone(url, "Private HTTPS fixture", "sample-branch", true, "fixture-user", "fixture-password") }
        compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null }
        val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
        ownedPaths += target
        compose.waitUntil(10_000) { navigated }
        assertEquals(url, requestedUrl)
        assertEquals(Credentials.Basic("fixture-user", "fixture-password"), requestedCredentials)
        assertEquals(requestedCredentials, RepositoryCredentialStore(context).resolve(target, url))
        assertTrue(settings.allRepositories.value.any { it.path == target && it.name == "Private HTTPS fixture" })
        Git.open(File(target)).use { git ->
            assertEquals("sample-branch", git.repository.branch)
            assertTrue(git.status().call().isClean)
            assertEquals(url, git.repository.config.getString("remote", "origin", "url"))
        }
    }

    @Test fun formValidatesWithoutNetworkingAndHasAnExplicitAnonymousMode() {
        var calls = 0
        val local = LocalGitRepository()
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> { calls++; error("Must validate before network") }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        compose.setContent { SyncMdTheme(darkTheme = true) { RemoteCloneScreen(settings, {}, {}, vm) } }
        val inline = "https://user:secret@git.example/team/notes.git"
        field("remote-url", inline)
        openOptions()
        field("remote-name", "Notes")
        // An address with inline credentials never parses: no endpoint preview and no way to start the clone.
        compose.onNodeWithTag("remote-normalized-url", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag("remote-auth-public").performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").assertIsNotEnabled()
        // The clone the button would start rejects it before any network call.
        compose.runOnIdle { vm.clone(inline, "Notes", "", false, "", "") }
        compose.waitUntil(5_000) { vm.state.value.error != null }
        assertTrue(vm.state.value.error.orEmpty().contains("credentials separately"))
        assertFalse(vm.state.value.error.orEmpty().contains("secret"))
        compose.onNodeWithText("credentials separately", substring = true).assertExists()
        assertEquals(0, calls)
        compose.onNodeWithText(context.getString(R.string.add_none_hint)).assertExists()
        capture("remote-clone-dark.png")
    }

    private fun sshFixture(): Credentials.Ssh {
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        return Credentials.Ssh(assets.open("ssh-fixture/identity").bufferedReader().use { it.readText() }, "android-fixture-passphrase",
            assets.open("ssh-fixture/identity.pub").bufferedReader().use { it.readText() })
    }

    @Test fun sshKeysAreEncryptedReloadedAndCannotMoveAcrossPathsOrEndpoints() {
        val first = own("ssh-first")
        val second = own("ssh-second")
        val url = "ssh://git@git.example:2222/team/notes.git"
        val key = sshFixture()
        store.save(first, url, key)
        assertEquals(key, RepositoryCredentialStore(context).resolve(first, url))
        val raw = securePrefs.getString(recordId(first), "")!!
        assertFalse(raw.contains("PRIVATE KEY"))
        assertFalse(raw.contains(key.passphrase))
        assertTrue(store.summary(first)!!.usesSshKey)
        for (address in listOf("ssh://git@other.example:2222/team/notes.git", "ssh://git@git.example:2223/team/notes.git", "ssh://other@git.example:2222/team/notes.git", "ssh://git@git.example:2222/other.git"))
            assertTrue(runCatching { store.resolve(first, address) }.isFailure)
        securePrefs.edit().putString(recordId(second), raw).commit()
        assertTrue(runCatching { store.resolve(second, url) }.isFailure)
        assertTrue(runCatching { store.save(first, url, key.copy(passphrase = "wrong")) }.isFailure)
        assertEquals(key, store.resolve(first, url))
    }

    @Test fun sshCloneFormPublishesWithEncryptedKeyAndKeepsSecretsHidden() {
        val sourceWorkspace = File(workspace, "ssh-source")
        SampleRepository.openOrCreate(sourceWorkspace)
        val remote = File(sourceWorkspace, "sample-remote.git")
        val key = sshFixture()
        val url = "ssh://git@git.example:2222/team/notes.git"
        val local = LocalGitRepository()
        val requested = java.util.concurrent.atomic.AtomicReference<Credentials?>(null)
        val fixture = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials) = clone(url, path, creds, null)
            override suspend fun clone(url: String, path: String, creds: Credentials, branch: String?): Result<Unit> {
                requested.set(creds)
                return local.clone(remote.toURI().toString(), path, Credentials.None, branch).onSuccess {
                    Git.open(File(path)).use { git -> git.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
            }
        }
        val vm = RemoteCloneViewModel(fixture, store, context, com.bontecou.syncmd.services.github.GitHubAuthManager(context)).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var returned = false
        compose.setContent { SyncMdTheme(darkTheme = true) { RemoteCloneScreen(settings, {}, { returned = true }, vm) } }
        val alias = url.replaceFirst("ssh://", "git+ssh://")
        field("remote-url", alias)
        compose.onNodeWithTag("remote-normalized-url", useUnmergedTree = true).performScrollTo()
            .assertTextEquals("${context.getString(R.string.add_remote_ssh)} · git.example · team/notes")
        capture("remote-ssh-alias-dark.png")
        openOptions()
        field("remote-name", "SSH fixture")
        // An SSH address selects the SSH key sign-in; "Use another…" takes the imported key instead of this phone's.
        compose.onNodeWithTag("remote-auth-ssh").performScrollTo().assertIsSelected().performClick()
        compose.onNodeWithTag("remote-auth-basic").assertIsNotSelected()
        compose.onNodeWithTag("remote-key-other").performScrollTo().performClick()
        field("ssh-private-key", key.privateKey)
        field("ssh-passphrase", key.passphrase)
        field("ssh-public-key", key.publicKey)
        compose.onNodeWithText(context.getString(R.string.ssh_show_private_key)).assertExists()
        compose.onNodeWithText(key.privateKey.trim().lines().first(), substring = true).assertDoesNotExist()
        assertCloneReady()
        compose.runOnIdle { vm.clone(alias, "SSH fixture", "", false, "", "", ssh = Credentials.Ssh(key.privateKey, key.passphrase, key.publicKey)) }
        try { compose.waitUntil(15_000) { vm.state.value.path != null || vm.state.value.error != null } }
        catch (failure: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("SSH clone unfinished: busy=${vm.state.value.busy}; requested=${requested.get()!=null}; submit_nodes=${compose.onAllNodesWithTag("remote-clone").fetchSemanticsNodes().size}; location_reviews=${compose.onAllNodesWithTag("folder-location-review").fetchSemanticsNodes().size}",failure)
        }
        val target = vm.state.value.path ?: error(vm.state.value.error.orEmpty())
        ownedPaths += target
        compose.waitUntil(10_000) { returned }
        assertEquals(key, requested.get())
        assertEquals(key, RepositoryCredentialStore(context).resolve(target, url))
        assertTrue(settings.allRepositories.value.any { it.path == target })
    }

    private fun editableRepository(separatePush: Boolean = false): File {
        val root = SampleRepository.openOrCreate(File(workspace, "editor-source"))
        ownedPaths += root.path
        Git.open(root).use { git ->
            git.repository.config.apply {
                setString("remote", "origin", "url", "https://old.example/team/notes.git")
                if (separatePush) setString("remote", "origin", "pushurl", "https://push.example/team/notes.git")
                save()
            }
            git.add().addFilepattern("notes.md").call()
        }
        store.save(root.path, "https://old.example/team/notes.git", Credentials.Basic("writer", "old-fixture-token"))
        val index = prefs.getInt("repository_count", 0)
        prefs.edit().putInt("repository_count", index + 1).putString("repo_${index}_name", "Connection fixture")
            .putString("repo_${index}_path", root.path).putString("selected_repository", root.path).commit()
        return root
    }
    private fun editorModel(auth: com.bontecou.syncmd.services.github.GitHubAuthManager = com.bontecou.syncmd.services.github.GitHubAuthManager(context)) =
        com.bontecou.syncmd.ui.viewmodels.ConnectionViewModel(store, auth).also { models += it }
    private fun showEditor(root: File, vm: com.bontecou.syncmd.ui.viewmodels.ConnectionViewModel, onBack: () -> Unit = {}) {
        val settings = SettingsViewModel(context).also { models += it }
        compose.setContent { SyncMdTheme(darkTheme = false) { com.bontecou.syncmd.ui.screens.ConnectionScreen(root.path, settings, onBack, vm) } }
        compose.waitUntil(10_000) { vm.state.value.plan != null && !vm.state.value.busy }
        compose.waitForIdle()
    }

    @Test fun connectionEditorSwitchesToLocalAccessWithoutChangingFilesIndexHeadOrRefs() {
        val root = editableRepository(separatePush = true)
        val remote = File(root.parentFile, "sample-remote.git")
        val url = com.bontecou.syncmd.services.git.GitRemote.parse(remote.toURI().toASCIIString()).url
        val index = File(root, ".git/index").readBytes()
        val notes = File(root, "notes.md").readBytes()
        val head = File(root, ".git/HEAD").readBytes()
        val refs = Git.open(root).use { it.repository.refDatabase.getRefsByPrefix("refs/").associate { ref -> ref.name to ref.objectId.name } }
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        field("connection-url", url)
        compose.onNodeWithTag("connection-url").assertTextContains("Repository URL")
        compose.onNodeWithTag("connection-auth-REPLACE").assertIsNotEnabled()
        compose.onNodeWithTag("connection-auth-SSH").assertIsNotEnabled()
        compose.onNodeWithTag("connection-save").assertIsNotEnabled()
        compose.onNodeWithTag("connection-auth-PUBLIC").performScrollTo().assertTextContains("Local access").performClick()
        capture("file-connection-light.png")
        compose.onNodeWithTag("connection-save").performScrollTo().assertIsEnabled().performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(root.path, url))
        assertArrayEquals(index, File(root, ".git/index").readBytes())
        assertArrayEquals(notes, File(root, "notes.md").readBytes())
        assertArrayEquals(head, File(root, ".git/HEAD").readBytes())
        Git.open(root).use {
            assertEquals(url, it.repository.config.getString("remote", "origin", "url"))
            assertEquals(url, it.repository.config.getString("remote", "origin", "pushurl"))
            assertEquals(refs, it.repository.refDatabase.getRefsByPrefix("refs/").associate { ref -> ref.name to ref.objectId.name })
        }
    }

    @Test fun connectionEditorSwitchesToSshWithoutChangingIndexOrWorkingFiles() {
        val root = editableRepository()
        val beforeIndex = File(root, ".git/index").readBytes()
        val beforeNotes = File(root, "notes.md").readBytes()
        val key = sshFixture()
        val url = "ssh://git@git.example:2222/team/notes.git"
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        field("connection-url", url.replaceFirst("ssh://", "git+ssh://"))
        compose.onNodeWithTag("connection-normalized-url", useUnmergedTree = true).performScrollTo().assertTextContains("Repository address: $url")
        compose.onNodeWithTag("connection-auth-SSH").performScrollTo().performClick()
        field("ssh-private-key", key.privateKey)
        field("ssh-passphrase", key.passphrase)
        field("ssh-public-key", key.publicKey)
        capture("ssh-connection-light.png")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertEquals(key, RepositoryCredentialStore(context).resolve(root.path, url))
        assertArrayEquals(beforeIndex, File(root, ".git/index").readBytes())
        assertArrayEquals(beforeNotes, File(root, "notes.md").readBytes())
        Git.open(root).use { assertEquals(url, it.repository.config.getString("remote", "origin", "url")) }
    }

    @Test fun sshPolicyMovesWithVaultAndConfigFailureRestoresTheOldEncryptedRecord() {
        val movePrefs = "ssh-relocation-${UUID.randomUUID()}".also { authPreferenceFiles += it }
        val isolatedStore = RepositoryCredentialStore(object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int) = context.getSharedPreferences(movePrefs, mode)
        })
        val source = own("ssh-move-source")
        val destination = own("ssh-move-destination")
        val retained = own("ssh-move-retained")
        val url = "git@git.example:team/notes.git"
        val key = sshFixture()
        isolatedStore.save(source, url, key)
        val version = isolatedStore.recordVersion(source)
        assertTrue(runCatching { isolatedStore.updateConnection(source, url, RepositoryCredentialStore.Choice.SSH,
            key.copy(publicKey = ""), version) { error("Fixture config write failure") } }.isFailure)
        assertEquals(version, isolatedStore.recordVersion(source))
        assertEquals(key, isolatedStore.resolve(source, url))
        val moveId = UUID.randomUUID().toString()
        isolatedStore.relocate(moveId, source, destination, retained, version, null, null)
        assertNull(isolatedStore.resolve(source, url))
        assertEquals(key, isolatedStore.resolve(destination, url))
        assertEquals(key, isolatedStore.resolve(retained, url))
        isolatedStore.finishRelocation(moveId)
    }

    @Test fun editorReplacesAddressAndTokenWithoutChangingLocalData() {
        val root = editableRepository()
        val indexBefore = File(root, ".git/index").readBytes()
        val notesBefore = File(root, "notes.md").readText()
        val head = Git.open(root).use { it.repository.resolve("HEAD") }
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        field("connection-url", "https://new.example/team/notes.git")
        compose.onNodeWithTag("connection-save").assertIsNotEnabled()
        compose.onNodeWithTag("connection-auth-REPLACE").performScrollTo().performClick()
        field("connection-username", "new-writer")
        field("connection-secret", "new-fixture-token")
        compose.onNodeWithTag("connection-save").performScrollTo()
        capture("connection-editor-light.png")
        compose.onNodeWithTag("connection-save").performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertEquals(Credentials.Basic("new-writer", "new-fixture-token"), RepositoryCredentialStore(context).resolve(root.path, "https://new.example/team/notes.git"))
        Git.open(root).use { git ->
            assertEquals("https://new.example/team/notes.git", git.repository.config.getString("remote", "origin", "url"))
            assertEquals("https://new.example/team/notes.git", git.repository.config.getString("remote", "origin", "pushurl"))
            assertEquals(head, git.repository.resolve("HEAD"))
        }
        assertArrayEquals(indexBefore, File(root, ".git/index").readBytes())
        assertEquals(notesBefore, File(root, "notes.md").readText())
    }

    @Test fun editorForgetsCredentialsOnlyAfterConfirmationAndPreservesSeparatePushAddress() {
        val root = editableRepository(separatePush = true)
        val configBefore = File(root, ".git/config").readBytes()
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        compose.onNodeWithText("Remove saved credentials", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertEquals(Credentials.Basic("writer", "old-fixture-token"), store.resolve(root.path, "https://old.example/team/notes.git"))
        compose.onNodeWithText("Remove saved credentials", substring = false).performScrollTo().performClick()
        compose.onNodeWithText("Remove credentials", substring = false).performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(root.path, "https://old.example/team/notes.git"))
        assertSame(Credentials.None, RepositoryCredentialStore(context).resolve(root.path, "https://push.example/team/notes.git"))
        assertArrayEquals(configBefore, File(root, ".git/config").readBytes())
    }

    @Test fun failedConfigCommitRestoresTheExactEncryptedCredentialRecord() = runBlocking {
        val root = editableRepository()
        val service = com.bontecou.syncmd.services.repositories.RepositoryConnection()
        val plan = service.inspect(root.path)
        val before = securePrefs.getString(recordId(root.path), null)
        val version = store.recordVersion(root.path)
        val result = runCatching { service.save(plan, "https://new.example/team/notes.git") { commit ->
            store.updateConnection(root.path, "https://new.example/team/notes.git", RepositoryCredentialStore.Choice.REPLACE,
                Credentials.Basic("new-writer", "new-fixture-token"), version) {
                File(root, ".git/config").appendText("\n# external edit while saving\n")
                commit()
            }
        } }
        assertTrue(result.isFailure)
        assertEquals(before, securePrefs.getString(recordId(root.path), null))
        assertEquals(Credentials.Basic("writer", "old-fixture-token"), store.resolve(root.path, "https://old.example/team/notes.git"))
        assertTrue(File(root, ".git/config").readText().contains("# external edit while saving"))
        assertFalse(File(root, ".git/config").readText().contains("https://new.example"))
        assertFalse(File(root, ".git/config.lock").exists())
    }

    @Test fun credentialsChangedByAnotherEditorAreNotOverwritten() {
        val root = editableRepository()
        val configBefore = File(root, ".git/config").readBytes()
        val vm = editorModel()
        showEditor(root, vm)
        store.save(root.path, "https://old.example/team/notes.git", Credentials.Basic("other-editor", "other-fixture-token"))
        compose.onNodeWithTag("connection-auth-REPLACE").performScrollTo().performClick()
        field("connection-username", "stale-editor")
        field("connection-secret", "stale-fixture-token")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { vm.state.value.error != null }
        assertTrue(vm.state.value.error?.resolve(app).orEmpty().contains("credentials changed"))
        assertEquals(Credentials.Basic("other-editor", "other-fixture-token"), store.resolve(root.path, "https://old.example/team/notes.git"))
        assertArrayEquals(configBefore, File(root, ".git/config").readBytes())
        compose.onNodeWithText("Reload connection", substring = false).assertExists()
    }

    @Test fun interruptedPolicyUpdateFailsClosedAndCanBeRepairedInTheEditor() {
        val root = editableRepository()
        store.save(root.path, "https://new.example/team/notes.git", Credentials.Basic("new-writer", "uncommitted-fixture-token"))
        assertTrue(runCatching { store.resolve(root.path, "https://old.example/team/notes.git") }.isFailure)
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        compose.onNodeWithTag("connection-auth-KEEP").assertIsNotEnabled()
        compose.onNodeWithTag("connection-auth-REPLACE").performScrollTo().performClick()
        field("connection-username", "repaired-writer")
        field("connection-secret", "repaired-fixture-token")
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertEquals(Credentials.Basic("repaired-writer", "repaired-fixture-token"), store.resolve(root.path, "https://old.example/team/notes.git"))
        // Simulate interruption after persisting an account policy but before config commit.
        store.updateConnection(root.path, "https://github.com/fixture/notes.git", RepositoryCredentialStore.Choice.GITHUB, null,
            store.recordVersion(root.path), "fixture-account") {}
        assertTrue(runCatching { store.resolve(root.path, "https://old.example/team/notes.git") }.isFailure)
        assertEquals(Credentials.Pat("fixture-token"), store.resolve(root.path, "https://github.com/fixture/notes.git") { "fixture-token" })
    }

    @Test fun checkedConnectionSaveWorksOnAnAndroidMediaClone() = runBlocking {
        val source = SampleRepository.openOrCreate(File(workspace, "media-source"))
        val base = com.bontecou.syncmd.storage.CloneStorage.obsidianCompatibleCloneBaseDir(context) ?: error("Media storage unavailable")
        val target = File(base, "connection-test-${UUID.randomUUID()}")
        ownedPaths += target.path
        val remote = File(source.parentFile, "sample-remote.git")
        com.bontecou.syncmd.services.repositories.SafeCloneService(LocalGitRepository()).clone(remote.toURI().toString(), target.path, Credentials.None).getOrThrow()
        val head = Git.open(target).use { it.repository.resolve("HEAD") }
        val service = com.bontecou.syncmd.services.repositories.RepositoryConnection()
        val plan = service.inspect(target.path)
        service.save(plan, "https://git.example/team/media.git") { commit ->
            store.updateConnection(target.path, "https://git.example/team/media.git", RepositoryCredentialStore.Choice.PUBLIC, null, null, commit = commit)
        }
        assertSame(Credentials.None, store.resolve(target.path, "https://git.example/team/media.git"))
        Git.open(target).use { git ->
            assertEquals(head, git.repository.resolve("HEAD"))
            assertEquals("https://git.example/team/media.git", git.repository.config.getString("remote", "origin", "url"))
            assertTrue(git.status().call().isClean)
        }
    }

    @Test fun keepingSavedCredentialsDoesNotExposeOrRequireTheTokenAgain() {
        val root = editableRepository()
        val vm = editorModel()
        var returned = false
        showEditor(root, vm) { returned = true }
        compose.onNodeWithTag("connection-auth-KEEP").assertIsSelected()
        compose.onNodeWithTag("connection-secret").assertDoesNotExist()
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertEquals(Credentials.Basic("writer", "old-fixture-token"), RepositoryCredentialStore(context).resolve(root.path, "https://old.example/team/notes.git"))
    }

    @Test fun GithubAccountSelectionRequiresGithubAndReplacesTheOldStoredSecret() {
        val root = editableRepository()
        val prefix = "connection-account-${UUID.randomUUID()}-"
        val accountContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences {
                val scoped = prefix + name
                authPreferenceFiles += scoped
                return context.getSharedPreferences(scoped, mode)
            }
        }
        val auth = com.bontecou.syncmd.services.github.GitHubAuthManager(accountContext)
        assertTrue(auth.saveValidatedToken("fixture-account-token"))
        val vm = editorModel(auth)
        var returned = false
        showEditor(root, vm) { returned = true }
        compose.onNodeWithTag("connection-auth-GITHUB").assertIsNotEnabled()
        field("connection-url", "fixture/notes.git")
        compose.onNodeWithTag("connection-normalized-url", useUnmergedTree = true).performScrollTo().assertTextContains("Repository address: https://github.com/fixture/notes.git")
        capture("connection-shorthand-light.png")
        compose.onNodeWithTag("connection-auth-GITHUB").performScrollTo().performClick()
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || vm.state.value.error != null }
        assertNull(vm.state.value.error)
        assertTrue(returned)
        assertEquals(Credentials.Pat("fixture-account-token"), auth.repositoryCredentials(root.path, "https://github.com/fixture/notes.git", store))
        Git.open(root).use { git ->
            val provider = com.bontecou.syncmd.services.git.RemoteAuthentication.provider(git.repository, "origin", token = { "fixture-account-token" }, resolver = { path, url -> auth.repositoryCredentials(path, url, store) })
            val password = org.eclipse.jgit.transport.CredentialItem.Password()
            assertTrue(provider.get(org.eclipse.jgit.transport.URIish("https://github.com/fixture/notes.git"), org.eclipse.jgit.transport.CredentialItem.Username(), password))
            assertEquals("fixture-account-token", String(password.value))
        }
        assertTrue(store.summary(root.path)!!.usesGithubAccount)
        assertFalse(securePrefs.getString(recordId(root.path), "")!!.contains("fixture-account-token"))
        assertTrue(runCatching { store.resolve(root.path, "https://github.com/fixture/different.git") }.isFailure)
    }

    @Test fun failedPreferenceCommitRestoresInMemoryAndPersistedCredentialsBeforeConfigWrite() = runBlocking {
        val root = editableRepository()
        val originalRecord = securePrefs.getString(recordId(root.path), null)
        val config = File(root, ".git/config").readBytes()
        var failNext = true
        val failingPreferences = object : android.content.SharedPreferences by securePrefs {
            override fun edit(): android.content.SharedPreferences.Editor {
                val delegate = securePrefs.edit()
                return object : android.content.SharedPreferences.Editor by delegate {
                    override fun putString(key: String, value: String?): android.content.SharedPreferences.Editor {
                        delegate.putString(key, value)
                        return this
                    }
                    override fun commit(): Boolean {
                        val committed = delegate.commit()
                        if (failNext) { failNext = false; return false }
                        return committed
                    }
                }
            }
        }
        val faultContext = object : android.content.ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences =
                if (name == "repository_credentials") failingPreferences else super.getSharedPreferences(name, mode)
        }
        val faultStore = RepositoryCredentialStore(faultContext)
        val service = com.bontecou.syncmd.services.repositories.RepositoryConnection()
        var configCalled = false
        val result = runCatching { service.save(service.inspect(root.path), "https://new.example/team/notes.git") { commit ->
            faultStore.updateConnection(root.path, "https://new.example/team/notes.git", RepositoryCredentialStore.Choice.REPLACE,
                Credentials.Basic("new-writer", "new-fixture-token"), store.recordVersion(root.path)) { configCalled = true; commit() }
        } }
        assertTrue(result.isFailure)
        assertFalse(configCalled)
        assertEquals(originalRecord, securePrefs.getString(recordId(root.path), null))
        assertEquals(Credentials.Basic("writer", "old-fixture-token"), RepositoryCredentialStore(context).resolve(root.path, "https://old.example/team/notes.git"))
        assertArrayEquals(config, File(root, ".git/config").readBytes())
    }
}
