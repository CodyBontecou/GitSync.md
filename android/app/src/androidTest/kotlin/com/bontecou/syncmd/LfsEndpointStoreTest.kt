package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.LfsEndpointStore
import com.bontecou.syncmd.services.git.SshLfsOperation
import com.bontecou.syncmd.services.git.lfs.LfsEndpointApprovalRequired
import com.bontecou.syncmd.ui.components.LfsEndpointGate
import com.bontecou.syncmd.ui.screens.LfsEndpointsScreen
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsEndpointViewModel
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

class LfsEndpointStoreTest {
    @get:Rule val compose = createComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val suffix = UUID.randomUUID().toString()
    private val preferenceName = "lfs-endpoints-test-$suffix"
    private val delegate get() = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private lateinit var preferences: FailingPreferences
    private lateinit var scoped: Context
    private lateinit var store: LfsEndpointStore
    private lateinit var workspace: File
    private val reviewThreads = java.util.concurrent.CopyOnWriteArrayList<Thread>()
    private val pool = Executors.newCachedThreadPool { task ->
        Thread(task,"lfs-review-test-$suffix").also { reviewThreads += it }
    }
    /** Submission is not arrival: both callers must be waiting before dismissing their shared review. */
    private fun awaitReviewWaiters(count: Int) {
        compose.waitUntil(5_000) {
            reviewThreads.count { thread ->
                thread.state == Thread.State.TIMED_WAITING && thread.stackTrace.any {
                    it.className == LfsEndpointStore::class.java.name && it.methodName == "verify"
                }
            } == count
        }
    }
    private val remote = "https://git.example/team/repo.git"
    private val endpoint = "https://storage.example/team/lfs"
    private val download = SshLfsOperation.DOWNLOAD
    private val upload = SshLfsOperation.UPLOAD
    private class FailingPreferences(private val actual: SharedPreferences) : SharedPreferences by actual {
        var failNext = false
        var failuresRemaining = 0
        override fun edit(): SharedPreferences.Editor {
            val editor = actual.edit()
            return object : SharedPreferences.Editor by editor {
                override fun commit(): Boolean {
                    val result = editor.commit()
                    if (failNext) { failNext = false; return false }
                    if (failuresRemaining > 0) { failuresRemaining--; return false }
                    return result
                }
            }
        }
    }
    @Before fun setup() {
        workspace = File(context.filesDir, "lfs-endpoint-store-$suffix").apply { mkdirs() }
        preferences = FailingPreferences(delegate)
        scoped = object : ContextWrapper(context) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences = preferences
        }
        store = LfsEndpointStore(scoped)
    }
    @After fun cleanup() {
        store.setInteractive(false)
        pool.shutdownNow(); pool.awaitTermination(5, TimeUnit.SECONDS)
        context.deleteSharedPreferences(preferenceName); workspace.deleteRecursively()
    }
    private fun request(operation: SshLfsOperation = download, repo: String = workspace.path, address: String = endpoint): Future<Boolean> =
        pool.submit<Boolean> { runCatching { store.verify(repo, remote, address, operation) }.isSuccess }
    private var lastChallengeId: String? = null
    private fun pending(): LfsEndpointStore.Challenge {
        compose.waitUntil(5_000) { store.challenge.value?.id?.let { it != lastChallengeId } == true }
        return store.challenge.value!!.also { lastChallengeId = it.id }
    }
    private fun approve(operation: SshLfsOperation, method: LfsEndpointStore.Authentication = LfsEndpointStore.Authentication.PUBLIC,
        username: String = "", secret: String = "", repo: String = workspace.path): LfsEndpointStore.Approval {
        store.setInteractive(true)
        val work = request(operation, repo)
        val challenge = pending()
        store.approve(challenge, method, username, secret)
        assertTrue(work.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.known.value.any { it.scope.repository == File(repo).canonicalPath && operation in it.operations } }
        return store.known.value.single { it.scope.repository == File(repo).canonicalPath }
    }
    @Test fun httpEndpointApprovalShowsNoticeAndKeepsSchemeDirectionAndRevocationBound() {
        val target="http://storage.example:80/team/lfs"
        val model=LfsEndpointViewModel(store)
        compose.setContent { SyncMdTheme(darkTheme=false) { LfsEndpointGate(model) } }
        compose.waitForIdle()
        val work=request(download,address=target);pending()
        compose.onNodeWithTag("lfs-approval-http-warning").performScrollTo().assertIsDisplayed()
        val evidence=File(context.getExternalFilesDir(null),"parity-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(evidence,"lfs-http-approval.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        compose.onNodeWithTag("lfs-approval-new").performScrollTo().performClick()
        compose.onNodeWithTag("lfs-endpoint-username").performScrollTo().performTextReplacement("http-lfs-reader")
        compose.onNodeWithTag("lfs-endpoint-secret").performScrollTo().performTextReplacement("fixture-http-lfs-secret")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("lfs-endpoint-approve").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(work.get(5,TimeUnit.SECONDS))
        val normalized="http://storage.example/team/lfs"
        assertTrue(store.headers(workspace.path,remote,normalized,download).containsKey("Authorization"))
        val reloaded=LfsEndpointStore(scoped)
        assertTrue(reloaded.headers(workspace.path,remote,normalized,download).containsKey("Authorization"))
        assertTrue(runCatching { reloaded.headers(workspace.path,remote,normalized.replace("http:","https:"),download) }.isFailure)
        assertTrue(runCatching { reloaded.headers(workspace.path,remote,normalized,upload) }.isFailure)
        assertFalse(preferences.all.values.any { it.toString().contains("fixture-http-lfs-secret") })
        store.revoke(store.known.value.single(),download)
        assertTrue(runCatching { store.headers(workspace.path,remote,normalized,download) }.isFailure)
    }
    @Test fun sshIssuedHttpAuthorizationHasItsOwnVisibleReviewAndRevocablePolicy() {
        val origin="ssh://git@git.example/team/repo.git"
        val target="http://storage.example/team/lfs"
        val model=LfsEndpointViewModel(store)
        compose.setContent { SyncMdTheme(darkTheme=false) { LfsEndpointGate(model) } };compose.waitForIdle()
        val work=pool.submit<Boolean> { runCatching { store.verifySshHttp(workspace.path,origin,target,download) }.isSuccess }
        val challenge=pending();assertTrue(challenge.sshAuthorization);assertFalse(challenge.canReuseCredentials)
        compose.onNodeWithTag("lfs-approval-http-warning").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lfs-approval-ssh").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("lfs-approval-public").assertDoesNotExist()
        compose.onNodeWithTag("lfs-approval-new").assertDoesNotExist()
        val evidence=File(context.getExternalFilesDir(null),"parity-evidence").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap -> File(evidence,"lfs-ssh-http-approval.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        compose.onNodeWithTag("lfs-endpoint-approve").assertIsDisplayed().assertIsEnabled().performClick()
        assertTrue(work.get(5,TimeUnit.SECONDS))
        val reloaded=LfsEndpointStore(scoped)
        reloaded.verifySshHttp(workspace.path,origin,target,download)
        assertTrue(runCatching { reloaded.headers(workspace.path,origin,target,download) }.isFailure)
        assertTrue(runCatching { reloaded.verify(workspace.path,origin,target,download) }.isFailure)
        assertTrue(runCatching { reloaded.verifySshHttp(workspace.path,origin,target,upload) }.isFailure)
        assertTrue(runCatching { reloaded.verifySshHttp(workspace.path,origin,target.replace("http:","https:"),download) }.isFailure)
        assertTrue(runCatching { reloaded.verifySshHttp(workspace.path,origin.replace("team/repo","other/repo"),target,download) }.isFailure)
        compose.waitUntil(5_000) { reloaded.known.value.size==1 }
        val approval=reloaded.known.value.single();assertEquals(setOf(download),approval.sshAuthorized)
        reloaded.revoke(approval,download)
        assertTrue(runCatching { reloaded.verifySshHttp(workspace.path,origin,target,download) }.isFailure)
    }
    @Test fun existingPublicApprovalCannotAuthorizeSshHeadersAndBothPoliciesCanCoexist() {
        val origin="ssh://git@git.example/team/repo.git";val target="http://storage.example/lfs"
        store.setInteractive(true)
        val public=pool.submit<Boolean> { runCatching { store.verify(workspace.path,origin,target,download) }.isSuccess }
        store.approve(pending(),LfsEndpointStore.Authentication.PUBLIC);assertTrue(public.get(5,TimeUnit.SECONDS))
        store.setInteractive(false)
        assertTrue(runCatching { store.verifySshHttp(workspace.path,origin,target,download) }.exceptionOrNull() is LfsEndpointApprovalRequired)
        store.setInteractive(true)
        val ssh=pool.submit<Boolean> { runCatching { store.verifySshHttp(workspace.path,origin,target,download) }.isSuccess }
        val reviewed=pending()
        store.approve(reviewed.copy(sshAuthorization=false),LfsEndpointStore.Authentication.SSH)
        assertTrue(ssh.get(5,TimeUnit.SECONDS))
        assertTrue(store.headers(workspace.path,origin,target,download).isEmpty())
        compose.waitUntil(5_000) { store.known.value.singleOrNull()?.sshAuthorized==setOf(download) }
        assertEquals(setOf(download),store.known.value.single().publicAccess)
        assertEquals(setOf(download),store.known.value.single().sshAuthorized)
        store.verifySshHttp(workspace.path,origin,target,download)
        store.revoke(store.known.value.single(),download);store.setInteractive(false)
        assertTrue(runCatching { store.verifySshHttp(workspace.path,origin,target,download) }.isFailure)
        assertTrue(runCatching { store.headers(workspace.path,origin,target,download) }.isFailure)
    }
    @Test fun failedSshAuthorizationApprovalCannotBecomeAnInMemoryGrant() {
        val origin="ssh://git@git.example/team/repo.git";val target="http://storage.example/lfs"
        store.setInteractive(true)
        val work=pool.submit<Boolean> { runCatching { store.verifySshHttp(workspace.path,origin,target,download) }.isSuccess }
        val challenge=pending();preferences.failNext=true
        store.approve(challenge,LfsEndpointStore.Authentication.SSH)
        assertFalse(work.get(5,TimeUnit.SECONDS));assertTrue(preferences.all.isEmpty())
        assertTrue(runCatching { LfsEndpointStore(scoped).verifySshHttp(workspace.path,origin,target,download) }.isFailure)
    }
    @Test fun unknownBackgroundEndpointFailsBeforeCreatingAPromptOrCredential() {
        assertTrue(runCatching { store.verify(workspace.path, remote, endpoint, download) }.exceptionOrNull() is LfsEndpointApprovalRequired)
        assertNull(store.challenge.value)
        assertTrue(preferences.all.isEmpty())
        assertTrue(runCatching { store.headers(workspace.path, remote, endpoint, download) }.isFailure)
    }
    @Test fun credentialsAreEncryptedReloadedAndBoundToRepositoryRemoteEndpointAndDirection() {
        approve(download, LfsEndpointStore.Authentication.NEW, "endpoint-user", "fixture-lfs-secret")
        val encrypted = preferences.all.values.single() as String
        for (plain in listOf("fixture-lfs-secret", "endpoint-user", endpoint, workspace.path)) assertFalse(encrypted.contains(plain))
        val expected = "Basic " + Base64.getEncoder().encodeToString("endpoint-user:fixture-lfs-secret".toByteArray())
        val reloaded = LfsEndpointStore(scoped)
        compose.waitUntil(5_000) { reloaded.known.value.size==1 || reloaded.error.value!=null }
        assertNull(reloaded.error.value)
        assertEquals(setOf(download),reloaded.known.value.single().authenticated)
        reloaded.verify(workspace.path, remote, endpoint, download)
        assertEquals(expected, reloaded.headers(workspace.path, remote, endpoint, download)["Authorization"])
        for ((repo, git, address, operation) in listOf(
            arrayOf(workspace.path, remote, endpoint, upload), arrayOf(File(workspace, "other").path, remote, endpoint, download),
            arrayOf(workspace.path, "https://git.example/other/repo.git", endpoint, download),
            arrayOf(workspace.path, remote, "https://storage.example/other/lfs", download),
            arrayOf(workspace.path, remote, "https://storage.example:444/team/lfs", download))) {
            assertTrue(runCatching { reloaded.headers(repo as String, git as String, address as String, operation as SshLfsOperation) }.isFailure)
        }
        assertTrue(preferences.all.values.none { it.toString().contains("fixture-lfs-secret") })
    }
    @Test fun uploadAndDownloadPoliciesAreIndependentAndRevocationPreservesTheOtherDirection() {
        approve(download, LfsEndpointStore.Authentication.NEW, "reader", "fixture-read-token")
        val both = approve(upload, LfsEndpointStore.Authentication.PUBLIC)
        assertTrue(store.headers(workspace.path, remote, endpoint, upload).isEmpty())
        assertTrue(store.headers(workspace.path, remote, endpoint, download).containsKey("Authorization"))
        store.revoke(both, upload)
        assertTrue(runCatching { store.headers(workspace.path, remote, endpoint, upload) }.isFailure)
        assertTrue(store.headers(workspace.path, remote, endpoint, download).containsKey("Authorization"))
        store.setInteractive(false)
        assertTrue(runCatching { LfsEndpointStore(scoped).verify(workspace.path, remote, endpoint, upload) }.exceptionOrNull() is LfsEndpointApprovalRequired)
    }
    @Test fun reuseRequiresASeparateDirectionReviewAndIgnoresAlteredChallengeFields() {
        approve(download, LfsEndpointStore.Authentication.NEW, "reader", "fixture-reused-token")
        val previous = store.headers(workspace.path, remote, endpoint, download)
        val work = request(upload)
        val challenge = pending()
        assertTrue(challenge.canReuseCredentials)
        assertEquals(setOf(download), challenge.previousOperations)
        assertFalse(work.isDone)
        // Only the stored request associated with the ID can determine the approved scope and direction.
        store.approve(challenge.copy(scope = challenge.scope.copy(endpoint = "https://other.example/lfs"), operation = download), LfsEndpointStore.Authentication.REUSE)
        assertTrue(work.get(5, TimeUnit.SECONDS))
        assertEquals(previous, store.headers(workspace.path, remote, endpoint, upload))
        assertTrue(runCatching { store.headers(workspace.path, remote, "https://other.example/lfs", download) }.isFailure)
        val loaded = LfsEndpointStore(scoped)
        assertEquals(previous, loaded.headers(workspace.path, remote, endpoint, download))
        assertEquals(previous, loaded.headers(workspace.path, remote, endpoint, upload))
    }
    @Test fun corruptedApprovalFailsClosedAndResetAllowsAnExplicitFreshReview() {
        approve(download, LfsEndpointStore.Authentication.NEW, "reader", "fixture-damaged-token")
        preferences.edit().putString(preferences.all.keys.single(), "invalid encrypted record").commit()
        val reloaded = LfsEndpointStore(scoped)
        assertTrue(runCatching { reloaded.verify(workspace.path, remote, endpoint, download) }.isFailure)
        assertTrue(runCatching { reloaded.headers(workspace.path, remote, endpoint, download) }.isFailure)
        compose.waitUntil(5_000) { reloaded.error.value == LfsEndpointStore.Failure.DAMAGED }
        assertTrue(reloaded.known.value.isEmpty())
        reloaded.reset()
        assertTrue(preferences.all.isEmpty())
        compose.waitUntil(5_000) { reloaded.error.value == null }
        store.refresh()
        store.clearError()
        val fresh = approve(download)
        assertTrue(fresh.authenticated.isEmpty())
        assertTrue(store.headers(workspace.path, remote, endpoint, download).isEmpty())
    }
    @Test fun cancelledForegroundReviewDoesNotGrantAccessAndBackgroundingReleasesAllWaiters() {
        store.setInteractive(true)
        val first = request(); val second = request()
        val shared = pending()
        awaitReviewWaiters(2)
        assertEquals(shared,store.challenge.value)
        store.reject(shared)
        assertFalse(first.get(5, TimeUnit.SECONDS)); assertFalse(second.get(5, TimeUnit.SECONDS))
        assertTrue(preferences.all.isEmpty())
        val background = request(upload); pending()
        store.setInteractive(false)
        assertFalse(background.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.challenge.value == null }
    }
    @Test fun simultaneousRequestsShareOneApprovalAndSwappedCiphertextCannotGrantAnotherRepository() {
        store.setInteractive(true)
        val first = request(); val second = request()
        val shared = pending()
        awaitReviewWaiters(2)
        assertEquals(shared,store.challenge.value)
        store.approve(shared, LfsEndpointStore.Authentication.PUBLIC)
        assertTrue(first.get(5, TimeUnit.SECONDS)); assertTrue(second.get(5, TimeUnit.SECONDS))
        val firstEntry = preferences.all.entries.single()
        approve(download, LfsEndpointStore.Authentication.NEW, "second", "fixture-second-token", repo = File(workspace, "second").path)
        val secondId = preferences.all.keys.single { it != firstEntry.key }
        preferences.edit().putString(secondId, firstEntry.value as String).commit()
        assertTrue(runCatching { LfsEndpointStore(scoped).headers(File(workspace, "second").path, remote, endpoint, download) }.isFailure)
        assertTrue(store.headers(workspace.path, remote, endpoint, download).isEmpty())
    }
    @Test fun staleApprovalCannotRestoreRevokedCredentialsAndStaleRevocationCannotRemoveNewPolicies() {
        val old = approve(download, LfsEndpointStore.Authentication.NEW, "reader", "fixture-token")
        val work = request(upload); val challenge = pending()
        val peer = LfsEndpointStore(scoped)
        peer.revoke(old, download)
        store.approve(challenge, LfsEndpointStore.Authentication.REUSE)
        assertFalse(work.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.CHANGED }
        assertTrue(preferences.all.isEmpty())
        store.clearError(); store.refresh()
        val first = approve(download)
        approve(upload)
        store.revoke(first, download)
        assertTrue(store.headers(workspace.path, remote, endpoint, download).isEmpty())
        assertTrue(store.headers(workspace.path, remote, endpoint, upload).isEmpty())
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.CHANGED }
    }
    @Test fun failedPersistenceRollsBackApprovalRevocationAndResetEvenAfterMemoryWasUpdated() {
        store.setInteractive(true)
        val denied = request()
        val challenge = pending()
        preferences.failNext = true
        store.approve(challenge, LfsEndpointStore.Authentication.NEW, "writer", "fixture-unpersisted-token")
        assertFalse(denied.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.SAVE }
        assertTrue(preferences.all.isEmpty())
        store.clearError()
        val approved = approve(download, LfsEndpointStore.Authentication.NEW, "writer", "fixture-kept-token")
        val before = preferences.all.toMap()
        preferences.failNext = true; store.revoke(approved, download)
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.REVOKE }
        assertEquals(before, preferences.all)
        assertTrue(store.headers(workspace.path, remote, endpoint, download).containsKey("Authorization"))
        preferences.failNext = true; store.reset()
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.RESET }
        assertEquals(before, preferences.all)
        assertTrue(LfsEndpointStore(scoped).headers(workspace.path, remote, endpoint, download).containsKey("Authorization"))
    }
    @Test fun dialogReviewsPrivateUploadAndSettingsRequireConfirmationBeforeRevoking() {
        val model = LfsEndpointViewModel(store)
        compose.setContent { SyncMdTheme(darkTheme = true) { LfsEndpointGate(model); LfsEndpointsScreen({}, model) } }
        compose.waitForIdle()
        val work = request(upload); pending()
        compose.onNodeWithTag("lfs-approval-endpoint").assertTextEquals(endpoint)
        compose.onNodeWithTag("lfs-approval-new").performScrollTo().performClick()
        compose.onNodeWithTag("lfs-endpoint-username").performScrollTo().performTextReplacement("endpoint-writer")
        compose.onNodeWithTag("lfs-endpoint-secret").performScrollTo().performTextReplacement("fixture-private-upload-token")
        compose.onNodeWithTag("lfs-endpoint-secret").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        compose.onNodeWithTag("lfs-endpoint-approve").performClick()
        assertTrue(work.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.known.value.size == 1 }
        compose.onNodeWithTag("lfs-revoke-upload").performScrollTo().performClick()
        compose.onNodeWithText("Cancel", substring = false).performClick()
        assertTrue(store.headers(workspace.path, remote, endpoint, upload).containsKey("Authorization"))
        compose.onNodeWithTag("lfs-revoke-upload").performScrollTo().performClick()
        compose.onNodeWithText("Revoke", substring = false).performClick()
        compose.waitUntil(5_000) { store.known.value.isEmpty() }
        assertTrue(preferences.all.isEmpty())
    }

    private val reviewLocale = mutableStateOf("en")
    @Composable private fun LargeLocalizedReview(content: @Composable () -> Unit) {
        val original = LocalContext.current
        val density = LocalDensity.current
        val tag = reviewLocale.value
        val localized = remember(original, tag) {
            val config = Configuration(original.resources.configuration).apply {
                setLocales(LocaleList.forLanguageTags(tag)); setLayoutDirection(Locale.forLanguageTag(tag))
            }
            val localizedResources = original.createConfigurationContext(config).resources
            object : ContextWrapper(original) { override fun getResources() = localizedResources }
        }
        val direction = if (android.text.TextUtils.getLayoutDirectionFromLocale(Locale.forLanguageTag(tag)) == android.view.View.LAYOUT_DIRECTION_RTL)
            LayoutDirection.Rtl else LayoutDirection.Ltr
        CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides localized.resources.configuration,
            LocalLayoutDirection provides direction, LocalDensity provides Density(density.density, 1.6f)) {
            SyncMdTheme(darkTheme = true) { content() }
        }
    }
    private fun translatedExpectations() = org.json.JSONObject(instrumentation.context.assets
        .open("localization_expected.json").bufferedReader().use { it.readText() }).getJSONObject("locales")
    private fun translatedCopy(expected: org.json.JSONObject, tag: String): Map<String, String> {
        val rows = expected.getJSONArray(tag)
        return (0 until rows.length()).map { rows.getJSONObject(it) }.associate { it.getString("name") to it.getString("source") }
    }
    private fun assertUnclipped(tag: String, language: String) {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        compose.onNodeWithTag(tag).performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        assertTrue("LFS consent clipped: $language / $tag", layouts.isNotEmpty() && layouts.all { !it.hasVisualOverflow })
    }
    @Test fun everyLocaleReviewsAndCancelsBothEndpointDirectionsWithoutSavingAuthorityAtLargeText() {
        // Component locale/direction/density; no system-language recreation or external transfer.
        val expected = translatedExpectations(); val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        val model = LfsEndpointViewModel(store)
        compose.setContent { LargeLocalizedReview { LfsEndpointGate(model) } }
        compose.waitForIdle()
        val before = preferences.all.toMap()
        for (tag in tags) {
            val copy = translatedCopy(expected, tag)
            compose.runOnIdle { reviewLocale.value = tag }
            for (operation in listOf(download, upload)) {
                val work = request(operation); pending()
                val direction = if (operation == download) "download" else "upload"
                compose.onNodeWithTag("lfs-approval-title").assertTextEquals(copy.getValue("lfs_consent_${direction}_title"))
                compose.onNodeWithTag("lfs-approval-direction").performScrollTo()
                    .assertTextEquals(copy.getValue("lfs_consent_${direction}_body")).assertIsDisplayed()
                assertUnclipped("lfs-approval-direction", tag)
                compose.onNodeWithTag("lfs-approval-endpoint").performScrollTo().assertTextEquals(endpoint)
                compose.onNodeWithTag("lfs-approval-new").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithTag("lfs-endpoint-username").performScrollTo().assertTextContains(copy.getValue("ui_lfs_endpoint_username_4a49e1f5"))
                compose.onNodeWithTag("lfs-endpoint-secret").performScrollTo().assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
                compose.onNodeWithTag("lfs-endpoint-approve").assertIsNotEnabled().assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("lfs-endpoint-cancel").assertHeightIsAtLeast(48.dp).performClick()
                assertFalse(work.get(5, TimeUnit.SECONDS))
                compose.waitUntil(5_000) { store.challenge.value == null }
                assertEquals(before, preferences.all)
                val fresh = LfsEndpointStore(scoped)
                assertTrue(runCatching { fresh.verify(workspace.path, remote, endpoint, operation) }.exceptionOrNull() is LfsEndpointApprovalRequired)
            }
        }
    }
    @Test fun everyLocaleCancelsDirectionalRevocationAndResetWithoutChangingEncryptedApprovalsAtLargeText() {
        val expected = translatedExpectations(); val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        approve(download, LfsEndpointStore.Authentication.NEW, "fixture-reader", "fixture-read-token")
        approve(upload, LfsEndpointStore.Authentication.NEW, "fixture-writer", "fixture-write-token")
        val before = preferences.all.toMap()
        val readHeaders = store.headers(workspace.path, remote, endpoint, download)
        val writeHeaders = store.headers(workspace.path, remote, endpoint, upload)
        val model = LfsEndpointViewModel(store)
        compose.setContent { LargeLocalizedReview { LfsEndpointsScreen({}, model) } }
        compose.waitForIdle()
        for (tag in tags) {
            val copy = translatedCopy(expected, tag)
            compose.runOnIdle { reviewLocale.value = tag }
            for (operation in listOf(download, upload)) {
                val direction = if (operation == download) "download" else "upload"
                compose.onNodeWithTag("lfs-revoke-${operation.wire}").performScrollTo().assertHeightIsAtLeast(48.dp).performClick()
                compose.onNodeWithTag("lfs-revoke-body").assertTextEquals(String.format(Locale.forLanguageTag(tag), copy.getValue("lfs_revoke_${direction}_body"), endpoint))
                    .performScrollTo().assertIsDisplayed()
                assertUnclipped("lfs-revoke-body", tag)
                compose.onNodeWithTag("lfs-revoke-confirm").assertHeightIsAtLeast(48.dp)
                compose.onNodeWithTag("lfs-revoke-cancel").assertHeightIsAtLeast(48.dp).performClick()
                assertEquals(before, preferences.all)
            }
            compose.onNodeWithTag("lfs-endpoints-reset").performScrollTo().performClick()
            compose.onNodeWithTag("lfs-endpoints-reset-body").assertTextEquals(copy.getValue("ui_remove_all_custom_endpoint_approvals_and_t_ffc30a9e"))
                .performScrollTo().assertIsDisplayed()
            assertUnclipped("lfs-endpoints-reset-body", tag)
            compose.onNodeWithTag("lfs-endpoints-reset-confirm").assertHeightIsAtLeast(48.dp)
            compose.onNodeWithTag("lfs-endpoints-reset-cancel").assertHeightIsAtLeast(48.dp).performClick()
            assertEquals(before, preferences.all)
            val fresh = LfsEndpointStore(scoped)
            assertEquals(readHeaders, fresh.headers(workspace.path, remote, endpoint, download))
            assertEquals(writeHeaders, fresh.headers(workspace.path, remote, endpoint, upload))
        }
    }

    @Test fun wrongAuthorizationAndInvalidNewCredentialsDoNotChangeExistingDirectionOrApproveTheWaiter() {
        approve(download, LfsEndpointStore.Authentication.NEW, "fixture-reader", "fixture-kept-read-token")
        val before = preferences.all.toMap()
        val originalRead = store.headers(workspace.path, remote, endpoint, download)
        for ((method, reason) in listOf(LfsEndpointStore.Authentication.SSH to LfsEndpointStore.Failure.AUTHENTICATION,
            LfsEndpointStore.Authentication.NEW to LfsEndpointStore.Failure.CREDENTIALS)) {
            store.clearError()
            val work = request(upload); val challenge = pending()
            store.approve(challenge, method, "invalid:username", "fixture-unsaved-write-token")
            assertFalse(work.get(5, TimeUnit.SECONDS))
            compose.waitUntil(5_000) { store.error.value == reason }
            assertEquals(before, preferences.all)
            val fresh = LfsEndpointStore(scoped)
            assertEquals(originalRead, fresh.headers(workspace.path, remote, endpoint, download))
            assertTrue(runCatching { fresh.headers(workspace.path, remote, endpoint, upload) }.exceptionOrNull() is LfsEndpointApprovalRequired)
        }
    }
    @Test fun failedRollbackReportsRecoveryAndDoesNotApproveTheWaitingTransfer() {
        store.setInteractive(true)
        val work = request(); val challenge = pending()
        // Simulate failure of both the policy commit and its compensating commit.
        preferences.failuresRemaining = 2
        store.approve(challenge, LfsEndpointStore.Authentication.PUBLIC)
        assertFalse(work.get(5, TimeUnit.SECONDS))
        compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.RECOVERY }
        assertTrue(runCatching { LfsEndpointStore(scoped).verify(workspace.path, remote, endpoint, download) }
            .exceptionOrNull() is LfsEndpointApprovalRequired)
    }
    @Test fun everyLocaleRendersAndDismissesDamagedApprovalRecoveryWithoutRemovingTheStoredRecordAtLargeText() {
        approve(download, LfsEndpointStore.Authentication.NEW, "fixture-reader", "fixture-corrupted-token")
        preferences.edit().putString(preferences.all.keys.single(), "invalid encrypted record").commit()
        val before = preferences.all.toMap()
        val expected = translatedExpectations(); val tags = expected.keys().asSequence().toList().sorted(); assertEquals(25, tags.size)
        val model = LfsEndpointViewModel(store)
        compose.setContent { LargeLocalizedReview { LfsEndpointGate(model) } }
        for (tag in tags) {
            val copy = translatedCopy(expected, tag)
            compose.runOnIdle { reviewLocale.value = tag; store.refresh() }
            compose.waitUntil(5_000) { store.error.value == LfsEndpointStore.Failure.DAMAGED }
            compose.onNodeWithTag("lfs-endpoint-error").assertTextEquals(copy.getValue("lfs_error_damaged"))
                .performScrollTo().assertIsDisplayed()
            assertUnclipped("lfs-endpoint-error", tag)
            compose.onNodeWithTag("lfs-endpoint-error-dismiss").assertHeightIsAtLeast(48.dp).performClick()
            compose.waitUntil(5_000) { store.error.value == null }
            assertEquals(before, preferences.all)
            val fresh = LfsEndpointStore(scoped)
            assertTrue(runCatching { fresh.headers(workspace.path, remote, endpoint, download) }.isFailure)
            assertEquals(before, preferences.all)
        }
    }
}
