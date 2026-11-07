package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.net.Uri
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.*
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.ui.screens.AccountsScreen
import com.bontecou.syncmd.ui.screens.AuthorScreen
import com.bontecou.syncmd.ui.screens.LoginScreen
import com.bontecou.syncmd.ui.screens.ConnectionScreen
import com.bontecou.syncmd.ui.screens.RemoteCloneScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.AuthorViewModel
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import com.bontecou.syncmd.ui.viewmodels.ConnectionViewModel
import com.bontecou.syncmd.ui.viewmodels.RemoteCloneViewModel
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Fake accounts, isolated encrypted preferences, and local Git fixtures. No real sign-in or external pushes. */
class GitHubAccountsTest {
    @get:Rule val compose = createComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val names = mutableSetOf<String>()
    private lateinit var context: Context
    private lateinit var workspace: File
    private lateinit var auth: GitHubAuthManager
    private lateinit var credentials: RepositoryCredentialStore
    private val models = mutableListOf<androidx.lifecycle.ViewModel>()
    private val externalPaths = mutableListOf<String>()
    private val a = GitHubUser("alpha", "Alpha Author", "alpha@example.invalid", null, 101)
    private val b = GitHubUser("beta", "Beta Author", "beta@example.invalid", null, 202)
    private val url = "https://github.com/fixture/notes.git"
    @Before fun setup() {
        val prefix = "github-accounts-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(app) {
            override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val scoped = prefix + name; names += scoped
                return app.getSharedPreferences(scoped, mode)
            }
        }
        workspace = File(app.filesDir, prefix).apply { mkdirs() }
        auth = GitHubAuthManager(context); credentials = RepositoryCredentialStore(context)
    }
    @After fun cleanup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ViewModelStore().apply { models.forEachIndexed { i, vm -> put("vm-$i", vm) }; clear() } }
        externalPaths.forEach { credentials.remove(it); File(it).deleteRecursively() }
        names.forEach { app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit() }
        workspace.deleteRecursively()
    }
    private fun addBoth() {
        assertTrue(auth.saveValidatedToken("alpha-fixture-token", a))
        assertTrue(auth.saveValidatedToken("beta-fixture-token", b))
    }
    private fun bind(path: String, account: String) = credentials.updateConnection(path, url,
        RepositoryCredentialStore.Choice.GITHUB, null, credentials.recordVersion(path), account) {}
    private fun token(path: String, manager: GitHubAuthManager = auth, store: RepositoryCredentialStore = credentials) =
        manager.repositoryCredentials(path, url, store)
    private fun capture(name: String) {
        val folder = File(app.filesDir, "parity-evidence").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun repositoryBindingSurvivesSwitchRemovalReauthenticationAndRestart() {
        addBoth()
        val path = File(workspace, "bound-repository").path
        bind(path, "github:101")
        assertEquals(Credentials.Pat("alpha-fixture-token"), token(path))
        assertEquals("beta-fixture-token", auth.getToken())
        assertEquals("alpha-fixture-token", auth.defaultToken())
        val restarted = GitHubAuthManager(context)
        assertEquals(Credentials.Pat("alpha-fixture-token"), token(path, restarted, RepositoryCredentialStore(context)))
        assertTrue(restarted.removeAccount("github:101"))
        assertTrue(runCatching { token(path, restarted) }.isFailure)
        assertEquals("beta-fixture-token", restarted.getToken())
        assertTrue(runCatching { restarted.defaultToken() }.isFailure)
        assertEquals("Alpha Author" to "alpha@example.invalid", restarted.authorForAccount("github:101"))
        assertTrue(restarted.saveValidatedToken("alpha-renewed-fixture-token", a.copy(login = "alpha-renamed")))
        assertEquals(Credentials.Pat("alpha-renewed-fixture-token"), token(path, restarted))
        assertEquals(2, restarted.accounts.value.size)
        assertTrue(restarted.switchAccount("github:202"))
        assertEquals(Credentials.Pat("alpha-renewed-fixture-token"), token(path, GitHubAuthManager(context)))
        val raw = context.getSharedPreferences("github_session", 0).getString("session", "")!!
        assertFalse(raw.contains("fixture-token"))
    }
    @Test fun provisionalOAuthReferenceMergesIntoStableIdAndRenewsExistingAccount() {
        assertTrue(auth.saveValidatedToken("first-alpha-token", a))
        assertTrue(auth.saveValidatedToken("beta-fixture-token", b))
        val state = Uri.parse(auth.createOAuthLoginUrl(1_000_000)!!).getQueryParameter("state")!!
        assertTrue(auth.acceptOAuthCallback(Uri.parse("syncmd://auth?state=$state&token=renewed-alpha-token"), 1_000_001))
        val provisional = auth.captureActiveAccount()
        val path = File(workspace, "oauth-reference").path
        bind(path, provisional.id)
        assertTrue(auth.cacheUserProfile(a.login, a.name, a.avatarUrl, a.email, provisional.token, a.id))
        assertEquals("github:101", auth.activeAccountId())
        assertEquals(2, auth.accounts.value.size)
        assertEquals("github:101", auth.accountSummary(provisional.id)!!.id)
        assertTrue(auth.switchAccount("github:202"))
        assertEquals(Credentials.Pat("renewed-alpha-token"), token(path, GitHubAuthManager(context)))
        assertEquals("renewed-alpha-token", auth.defaultToken())
    }
    @Test fun encryptedV1SessionMigratesWithoutLosingThePendingCallbackOrOriginalAccount() {
        // Initialize the test encryption key, then encode the actual old on-disk schema.
        auth.saveValidatedToken("key-initialization-fixture")
        val keyStore = java.security.KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val key = keyStore.getKey("gitsync.github.session.v1", null) as javax.crypto.SecretKey
        val cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(javax.crypto.Cipher.ENCRYPT_MODE, key); updateAAD("gitsync/github/session/v1".toByteArray())
        }
        val json = org.json.JSONObject().put("version", 1).put("token", "v1-alpha-token").put("state", "v1-pending-state").put("createdAt", 1_000_000)
        val data = cipher.doFinal(json.toString().toByteArray())
        val record = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP) + ":" + android.util.Base64.encodeToString(data, android.util.Base64.NO_WRAP)
        context.getSharedPreferences("github_session", 0).edit().putString("session", record).commit()
        val cacheOwner = java.security.MessageDigest.getInstance("SHA-256").digest("v1-alpha-token".toByteArray()).joinToString("") { "%02x".format(it) }
        context.getSharedPreferences("sync_md_prefs", 0).edit().putString("github_cache_owner", cacheOwner)
            .putString("github_login", "alpha").putString("github_name", "Alpha Author").putString("github_email", "alpha@example.invalid").commit()
        val migrated = GitHubAuthManager(context)
        val original = migrated.defaultAccountId()!!
        assertEquals("v1-alpha-token", migrated.tokenForAccount(original))
        assertEquals("Alpha Author" to "alpha@example.invalid", migrated.authorForAccount(original))
        assertTrue(migrated.needsAccountVerification())
        assertEquals(2, GitHubSessionStore(context).read()!!.version)
        assertTrue(migrated.acceptOAuthCallback(Uri.parse("syncmd://auth?state=v1-pending-state&token=beta-fixture-token"), 1_000_001))
        assertEquals("v1-alpha-token", migrated.defaultToken())
        assertEquals("beta-fixture-token", migrated.getToken())
        assertEquals(2, GitHubAuthManager(context).accounts.value.count { it.connected })
    }
    @Test fun legacyDefaultStaysWithOriginalAccountAndPublicAccessNeverFallsBack() {
        context.getSharedPreferences("sync_md_prefs", 0).edit().putString("github_token", "legacy-alpha-token").commit()
        auth = GitHubAuthManager(context)
        val oldId = auth.defaultAccountId()!!
        assertTrue(auth.cacheUserProfile(a.login, a.name, null, a.email, "legacy-alpha-token", a.id))
        assertTrue(auth.saveValidatedToken("beta-fixture-token", b))
        assertEquals("github:101", auth.accountSummary(oldId)!!.id)
        assertEquals("legacy-alpha-token", auth.defaultToken())
        val path = File(workspace, "anonymous").path
        credentials.save(path, url, Credentials.None)
        assertSame(Credentials.None, token(path))
        assertSame(Credentials.None, auth.repositoryCredentials(path, "https://other.example/push.git", credentials))
        assertTrue(auth.signOut())
        assertNull(auth.getToken())
        assertEquals("legacy-alpha-token", auth.defaultToken())
        assertNull(GitHubAuthManager(context).getToken())
    }
    @Test fun failedAccountRemovalAndSwitchPreserveEncryptedAndPublishedState() {
        addBoth()
        val real = context.getSharedPreferences("github_session", 0)
        var fail = false
        val scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                if (name != "github_session") return super.getSharedPreferences(name, mode)
                return object : SharedPreferences by real {
                    override fun edit(): SharedPreferences.Editor {
                        val delegate = real.edit()
                        return object : SharedPreferences.Editor by delegate {
                            override fun putString(key: String, value: String?): SharedPreferences.Editor { delegate.putString(key, value); return this }
                            override fun commit(): Boolean { val ok = delegate.commit(); return if (fail) { fail = false; false } else ok }
                        }
                    }
                }
            }
        }
        val manager = GitHubAuthManager(scoped)
        val before = real.getString("session", null)
        fail = true
        assertFalse(manager.switchAccount("github:101"))
        assertEquals(before, real.getString("session", null))
        assertEquals("beta-fixture-token", manager.getToken())
        fail = true
        assertFalse(manager.removeAccount("github:101"))
        assertEquals(before, real.getString("session", null))
        assertEquals("alpha-fixture-token", manager.tokenForAccount("github:101"))
    }
    @Test fun validatedPatCanRecoverUnreadableStorageWithoutAssigningLegacyRepositoriesToTheNewAccount() {
        context.getSharedPreferences("github_session", 0).edit().putString("session", "unreadable-fixture-session").commit()
        val manager = GitHubAuthManager(context)
        assertNull(manager.getToken())
        assertTrue(manager.saveValidatedToken("beta-fixture-token", b))
        assertEquals("beta-fixture-token", manager.captureActiveAccount().token)
        assertTrue(runCatching { manager.defaultToken() }.isFailure)
        val path = File(workspace, "recovered-binding").path
        bind(path, "github:202")
        assertEquals(Credentials.Pat("beta-fixture-token"), token(path, manager))
        val restarted = GitHubAuthManager(context)
        assertTrue(runCatching { restarted.defaultToken() }.isFailure)
        assertEquals("beta-fixture-token", restarted.tokenForAccount("github:202"))
    }
    @Test fun clonePublicationPinsCapturedAccountDespiteBrowsingSwitch() = runBlocking {
        addBoth(); assertTrue(auth.switchAccount("github:101"))
        val captured = auth.captureActiveAccount()
        val source = SampleRepository.openOrCreate(File(workspace, "source"))
        val target = File(workspace, "published")
        SafeCloneService(LocalGitRepository()).clone(File(source.parentFile, "sample-remote.git").toURI().toString(), target.path, Credentials.None,
            withPublication = { move ->
                assertTrue(auth.switchAccount("github:202"))
                bindPublication(target.path, captured.id, move)
            }).getOrThrow()
        assertEquals(Credentials.Pat("alpha-fixture-token"), token(target.path))
        assertEquals("beta-fixture-token", auth.getToken())
        assertTrue(File(target, ".git").isDirectory)
        val failed = File(workspace, "failed-publication")
        val result = SafeCloneService(LocalGitRepository()).clone(File(source.parentFile, "sample-remote.git").toURI().toString(), failed.path, Credentials.None,
            withPublication = { _ -> bindPublication(failed.path, captured.id) { error("fixture move failure") } })
        assertTrue(result.isFailure); assertFalse(failed.exists()); assertNull(credentials.summary(failed.path))
    }
    private fun bindPublication(path: String, id: String, move: () -> Unit) = credentials.updateConnection(path, url,
        RepositoryCredentialStore.Choice.GITHUB, null, credentials.recordVersion(path), id, move)
    @Test fun assignedAuthorIsIndependentOfBrowsingAndCustomIdentityWins() = runBlocking {
        addBoth()
        val root = File(workspace, "identity")
        Git.init().setDirectory(root).setInitialBranch("main").call().close()
        bind(root.path, "github:101")
        val authors = RepositoryAuthors(auth, credentials)
        assertEquals("Alpha Author" to "alpha@example.invalid", authors.resolve(root.path))
        auth.removeAccount("github:101")
        assertEquals("Alpha Author" to "alpha@example.invalid", authors.resolve(root.path))
        RepositoryIdentity().save(RepositoryIdentity().inspect(root.path), "Custom Author", "custom@example.invalid")
        assertEquals("Custom Author" to "custom@example.invalid", authors.resolve(root.path))
        File(root, "note.md").writeText("local note\n")
        val author = authors.resolve(root.path)
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        LocalDiffRepository().commit(root.path, "Identity test", author.first, author.second).getOrThrow()
        Git.open(root).use { git ->
            assertEquals("Custom Author", git.log().call().first().authorIdent.name)
            assertEquals("custom@example.invalid", git.log().call().first().authorIdent.emailAddress)
        }
    }
    @Test fun accountsScreenSwitchesBrowsingAndConfirmsRemovalWithoutReassigningRepositories() {
        addBoth()
        val path = File(workspace, "ui-bound").path
        bind(path, "github:101")
        var added = false
        compose.setContent { SyncMdTheme { AccountsScreen(auth, {}, { added = true }) } }
        compose.onNodeWithTag("account-use-github:101").performScrollTo().performClick()
        compose.waitUntil { auth.getToken() == "alpha-fixture-token" }
        compose.onNodeWithTag("account-remove-github:101").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(Credentials.Pat("alpha-fixture-token"), token(path))
        compose.onNodeWithTag("account-remove-github:101").performClick()
        compose.onNodeWithTag("account-remove-confirm").performClick()
        compose.waitUntil { auth.getToken() == null }
        assertEquals("beta-fixture-token", auth.tokenForAccount("github:202"))
        assertTrue(runCatching { token(path) }.isFailure)
        compose.onNodeWithTag("account-add").performScrollTo().performClick()
        assertTrue(added)
    }
    @Test fun authorScreenSavesCheckedIdentityWithoutChangingExistingCommit() = runBlocking {
        addBoth()
        val root = SampleRepository.openOrCreate(File(workspace, "author-ui"))
        val head = Git.open(root).use { it.repository.resolve("HEAD") }
        val model = AuthorViewModel(RepositoryAuthors(auth, credentials))
        var returned = false
        compose.setContent { SyncMdTheme { AuthorScreen(root.path, { returned = true }, model) } }
        compose.waitUntil(10_000) { model.state.value.plan != null }
        compose.onNodeWithTag("author-name").performTextReplacement("New Local Author")
        compose.onNodeWithTag("author-email").performTextReplacement("new-local@example.invalid")
        compose.onNodeWithTag("author-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || model.state.value.error != null }
        assertNull(model.state.value.error); assertTrue(returned)
        Git.open(root).use { git ->
            assertEquals(head, git.repository.resolve("HEAD"))
            assertEquals("New Local Author", git.repository.config.getString("user", null, "name"))
            assertEquals("new-local@example.invalid", git.repository.config.getString("user", null, "email"))
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ViewModelStore().apply { put("author", model); clear() } }
    }
    @Test fun addAccountFlowKeepsExistingSignInUntilANewValidatedAccountIsSaved() {
        auth.saveValidatedToken("alpha-fixture-token", a)
        val api = object : GitHubClient {
            override suspend fun fetchUser(token: String) = if (token == "beta-fixture-token") b else a
            override suspend fun fetchRepos(token: String) = emptyList<GitHubRepo>()
            override suspend fun fetchPrimaryEmail(token: String): String? = null
        }
        lateinit var model: GitHubViewModel
        lateinit var settings: SettingsViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync { model = GitHubViewModel(auth, api); settings = SettingsViewModel(context) }
        models += model; models += settings
        var done = false
        compose.setContent { SyncMdTheme { LoginScreen(model, settings, {}, { done = true }, addingAccount = true) } }
        compose.onNodeWithText("PERSONAL ACCESS TOKEN", ignoreCase = true).performScrollTo().performClick()
        assertEquals("alpha-fixture-token", auth.getToken())
        compose.onNodeWithTag("login-pat-token").performTextInput("beta-fixture-token")
        compose.onNodeWithTag("login-pat-submit").performScrollTo().performClick()
        compose.waitUntil(10_000) { auth.getToken() == "beta-fixture-token" }
        compose.onNodeWithTag("account-login-done").performScrollTo().performClick()
        assertTrue(done)
        assertEquals("alpha-fixture-token", auth.tokenForAccount("github:101"))
        assertEquals(2, auth.accounts.value.count { it.connected })
        InstrumentationRegistry.getInstrumentation().runOnMainSync { ViewModelStore().apply { put("github", model); put("settings", settings); clear() } }
    }
    @Test fun repositoryEditorRetainsItsAssignedAccountAndAllowsExplicitReassignment() {
        addBoth()
        val root = SampleRepository.openOrCreate(File(workspace, "connection-ui"))
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
        bind(root.path, "github:101")
        val settings = SettingsViewModel(context).also { models += it }
        val model = ConnectionViewModel(credentials, auth).also { models += it }
        var returned = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync { settings.addRepository("Account-owned notes", root.path) }
        compose.waitUntil(10_000) { settings.allRepositories.value.any { File(it.path).canonicalPath == root.canonicalPath } || settings.errorMessage.value != null }
        assertNull(settings.errorMessage.value)
        compose.setContent { SyncMdTheme { ConnectionScreen(root.path, settings, { returned = true }, model) } }
        compose.waitUntil(10_000) { model.state.value.plan != null }
        compose.onNodeWithTag("connection-account-github:101").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("connection-account-github:202").performScrollTo().performClick()
        compose.onNodeWithTag("connection-save").performScrollTo().performClick()
        compose.waitUntil(10_000) { returned || model.state.value.error != null }
        assertNull(model.state.value.error); assertTrue(returned)
        auth.switchAccount("github:101")
        assertEquals(Credentials.Pat("beta-fixture-token"), token(root.path))
    }
    @Test fun legacyEditorKeepsOriginalAccountAndRequiresAChoiceWhenItIsDisconnected() {
        addBoth()
        val root = SampleRepository.openOrCreate(File(workspace, "legacy-connection"))
        Git.open(root).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
        val settings = SettingsViewModel(context).also { models += it }
        val model = ConnectionViewModel(credentials, auth).also { models += it }
        var manage = false
        compose.setContent { SyncMdTheme { ConnectionScreen(root.path, settings, {}, model, onManageAccounts = { manage = true }) } }
        compose.waitUntil(10_000) { model.state.value.plan != null }
        compose.onNodeWithTag("connection-auth-GITHUB").assertIsSelected()
        compose.onNodeWithTag("connection-account-github:101").performScrollTo().assertIsSelected()
        compose.runOnIdle { auth.removeAccount("github:101") }
        compose.onNodeWithTag("connection-auth-GITHUB").performScrollTo().assertIsSelected()
        compose.onNodeWithTag("connection-save").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Choose a connected account for this repository.").assertExists()
        assertEquals("beta-fixture-token", auth.getToken())
        assertTrue(runCatching { auth.defaultToken() }.isFailure)
        compose.onNodeWithText("Manage GitHub accounts").performScrollTo().performClick()
        assertTrue(manage)
        assertNull(credentials.summary(root.path))
    }
    @Test fun urlCloneFormUsesTheSelectedAccountAndPersistsItsBinding() {
        addBoth()
        val source = SampleRepository.openOrCreate(File(workspace, "url-source"))
        val local = LocalGitRepository()
        var requested: Credentials? = null
        val fixture = object : com.bontecou.syncmd.domain.repository.GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> {
                requested = creds
                auth.switchAccount("github:202")
                return local.clone(File(source.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None).onSuccess {
                    Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
            }
        }
        val model = RemoteCloneViewModel(fixture, credentials, context, auth).also { models += it }
        val settings = SettingsViewModel(context).also { models += it }
        var done = false
        compose.setContent { SyncMdTheme { RemoteCloneScreen(settings, {}, { done = true }, model) } }
        compose.onNodeWithTag("remote-url").performTextReplacement(url)
        compose.onNodeWithTag("remote-name").performTextReplacement("Account-owned notes")
        compose.onNodeWithTag("remote-auth-github").performScrollTo().performClick()
        compose.onNodeWithTag("remote-account-github:101").performScrollTo().performClick()
        compose.onNodeWithTag("remote-clone").performScrollTo().performClick()
        compose.waitUntil(10_000) { model.state.value.path != null || model.state.value.error != null }
        model.state.value.path?.let { externalPaths += it }
        assertNull(model.state.value.error)
        val path = model.state.value.path!!
        compose.waitUntil(10_000) { done }
        assertEquals(Credentials.Pat("alpha-fixture-token"), requested)
        assertEquals(Credentials.Pat("alpha-fixture-token"), token(path))
        assertEquals("beta-fixture-token", auth.getToken())
        assertTrue(settings.allRepositories.value.any { it.path == path })
    }
    @Test fun accountAndAuthorLayoutsSupportDarkThemeAndLargeText() {
        addBoth()
        auth.saveValidatedToken("long-name-fixture", GitHubUser("a-very-long-github-account-handle", "A long account display name that wraps across multiple lines", "long@example.invalid", null, 303))
        val root = SampleRepository.openOrCreate(File(workspace, "layout"))
        val author = AuthorViewModel(RepositoryAuthors(auth, credentials)).also { models += it }
        val dark = mutableStateOf(false)
        val large = mutableStateOf(false)
        val showAuthor = mutableStateOf(false)
        compose.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, if (large.value) 1.6f else 1f)) {
                SyncMdTheme(darkTheme = dark.value) {
                    if (showAuthor.value) AuthorScreen(root.path, {}, author) else AccountsScreen(auth, {}, {})
                }
            }
        }
        compose.onNodeWithTag("account-github:101").assertIsDisplayed()
        capture("accounts-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("accounts-list").performScrollToNode(hasTestTag("account-add"))
        capture("accounts-dark-xl")
        compose.onNodeWithTag("account-add").assertIsDisplayed()
        compose.runOnIdle { showAuthor.value = true; dark.value = false; large.value = false }
        compose.waitUntil(10_000) { author.state.value.plan != null }
        compose.onNodeWithTag("author-save").assertIsDisplayed()
        capture("author-light")
        compose.runOnIdle { dark.value = true; large.value = true }
        compose.onNodeWithTag("author-save").performScrollTo().assertIsDisplayed()
        capture("author-dark-xl")
    }
}
