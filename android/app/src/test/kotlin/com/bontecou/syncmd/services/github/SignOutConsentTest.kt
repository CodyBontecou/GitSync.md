package com.bontecou.syncmd.services.github

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import com.bontecou.syncmd.security.GitHubSessionStore
import com.bontecou.syncmd.security.StoredGitHubAccount
import com.bontecou.syncmd.security.normalized
import com.bontecou.syncmd.security.withToken
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.*
import org.junit.Assert.*
import org.mockito.MockedConstruction
import org.mockito.Mockito.RETURNS_SELF
import org.mockito.Mockito.mockConstruction
import org.mockito.kotlin.*
import java.security.MessageDigest

/** Actual auth/session-lock and ViewModel paths; only Android encrypted persistence/preferences are mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutConsentTest {
    private lateinit var construction: MockedConstruction<GitHubSessionStore>
    private lateinit var auth: GitHubAuthManager
    private lateinit var context: Context
    private var persisted: GitHubSessionStore.Session? = null
    private var writes = 0
    private var failWrite = false
    private var failRead = false
    private var reads = 0
    private val requestedTokens = mutableListOf<String>()
    private val cancelledTokens = mutableListOf<String>()
    private val plainPreferences = mutableMapOf<String, String?>()
    private val models = mutableListOf<GitHubViewModel>()
    private val alpha = StoredGitHubAccount("github:1", "generated-alpha-token", 1, "alpha", "Alpha", "alpha@example.invalid")
    private val beta = StoredGitHubAccount("github:2", "generated-beta-token", 2, "beta", "Beta", "beta@example.invalid")
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        persisted = GitHubSessionStore.Session(token = alpha.token, accounts = listOf(alpha, beta),
            activeAccountId = alpha.id, defaultAccountId = alpha.id)
        val editor = mock<SharedPreferences.Editor>(defaultAnswer = RETURNS_SELF)
        whenever(editor.commit()).thenReturn(true)
        doAnswer { call -> plainPreferences.remove(call.getArgument<String>(0)); editor }
            .whenever(editor).remove(any())
        doAnswer { call -> plainPreferences[call.getArgument<String>(0)] = call.getArgument<String?>(1); editor }
            .whenever(editor).putString(any(), anyOrNull())
        val prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
        whenever(prefs.getString(any(), anyOrNull())).thenAnswer { call ->
            plainPreferences[call.getArgument<String>(0)] ?: call.getArgument<String?>(1)
        }
        context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        construction = mockConstruction(GitHubSessionStore::class.java) { store, _ ->
            whenever(store.read()).thenAnswer {
                reads++
                if (failRead) error("Generated read failure")
                persisted
            }
            doAnswer { call ->
                if (failRead) error("Generated read failure")
                val change = call.getArgument<(GitHubSessionStore.Session?) -> GitHubSessionStore.Session>(1)
                val next = change(persisted)
                if (failWrite) error("Generated persistence failure")
                persisted = next; writes++; next
            }.whenever(store).update(any(), any())
        }
        auth = GitHubAuthManager(context)
        assertEquals(alpha.id, auth.activeAccountId()); assertEquals(alpha.token, auth.getToken())
    }
    @After fun cleanup() {
        models.forEach { ViewModelStore().apply { put("vm", it); clear() } }
        construction.close(); Dispatchers.resetMain()
    }
    private fun review() = requireNotNull(auth.reviewSignOut(auth.sessionRevision.value))
    private fun model(): GitHubViewModel = GitHubViewModel(auth, object : GitHubClient {
        override suspend fun fetchUser(token: String): GitHubUser {
            requestedTokens += token
            try { awaitCancellation() } finally { cancelledTokens += token }
        }
        override suspend fun fetchRepos(token: String): List<GitHubRepo> = error("No network fixture")
        override suspend fun fetchPrimaryEmail(token: String): String? = error("No network fixture")
    }).also { models += it }
    private fun dialog(vm: GitHubViewModel): GitHubAuthManager.SignOutReview {
        vm.prepareSignOut(auth.sessionRevision.value)
        return requireNotNull(vm.signOutReview.value)
    }
    @Test fun switchingAccountsRejectsOldConsent(): Unit {
        val old = review(); assertEquals("alpha", old.login)
        assertTrue(auth.switchAccount(beta.id))
        val before = persisted; val count = writes
        assertFalse(auth.signOut(old)); assertEquals(before, persisted); assertEquals(count, writes)
        assertEquals(beta.id, auth.activeAccountId())
    }
    @Test fun sameTokenSessionRefreshRevokesOldConsent(): Unit {
        val old = review()
        assertTrue(auth.saveValidatedToken(alpha.token!!, GitHubUser("alpha", "Alpha", alpha.email, null, 1)))
        val before = persisted; val count = writes
        assertFalse(auth.signOut(old)); assertEquals(before, persisted); assertEquals(count, writes)
    }
    @Test fun switchingAwayAndBackDoesNotRestoreConsent(): Unit {
        val old = review()
        assertTrue(auth.switchAccount(beta.id)); assertTrue(auth.switchAccount(alpha.id))
        val before = persisted; val count = writes
        assertFalse(auth.signOut(old)); assertEquals(before, persisted); assertEquals(count, writes)
    }
    @Test fun reviewCannotBeUsedWithAnotherManager(): Unit {
        val old = review(); val other = GitHubAuthManager(context)
        assertFalse(other.signOut(old)); assertEquals(0, writes); assertEquals(alpha.token, persisted!!.token)
    }
    @Test fun externalPersistedAccountSwitchIsRejectedInsideStoreUpdate(): Unit {
        val old = review()
        persisted = persisted!!.copy(token = beta.token, activeAccountId = beta.id)
        val before = persisted
        assertFalse(auth.signOut(old)); assertEquals(before, persisted); assertEquals(0, writes)
    }
    @Test fun externalSameAccountTokenReplacementIsRetained(): Unit {
        val old = review()
        persisted = persisted!!.withToken("generated-replacement-token", GitHubUser("alpha", "Alpha", alpha.email, null, 1))
        val before = persisted
        assertFalse(auth.signOut(old)); assertEquals(before, persisted); assertEquals(0, writes)
    }
    @Test fun currentConsentDisconnectsOnlyReviewedAccountAndKeepsAssignments(): Unit {
        val old = review(); val before = persisted!!
        assertTrue(auth.signOut(old)); assertEquals(1, writes)
        val after = persisted!!
        assertNull(after.token); assertNull(after.activeAccountId)
        assertEquals(before.accounts.map { if (it.id == alpha.id) it.copy(token = null) else it }, after.accounts)
        assertEquals(before.defaultAccountId, after.defaultAccountId); assertEquals(before.aliases, after.aliases)
        assertNull(auth.getToken()); assertEquals(beta.token, auth.tokenForAccount(beta.id))
        assertFalse(auth.signOut(old)); assertEquals(1, writes)
    }
    @Test fun persistenceFailurePreservesSessionAndAllowsFreshReview(): Unit {
        val old = review(); val before = persisted
        failWrite = true
        assertFalse(auth.signOut(old)); assertEquals(before, persisted)
        assertEquals(alpha.token, auth.getToken()); assertNotNull(auth.sessionError.value)
        assertEquals(0, writes)
        failWrite = false
        assertTrue(auth.signOut(review())); assertEquals(1, writes)
    }
    @Test fun dismissedDialogCannotSignOut(): Unit {
        val vm = model(); val old = dialog(vm)
        vm.dismissSignOut(old); vm.confirmSignOut(old)
        assertEquals(0, writes); assertEquals(alpha.token, auth.getToken()); assertTrue(vm.isLoading.value)
    }
    @Test fun oldDialogCannotConfirmOrDismissNewReview(): Unit {
        val vm = model(); val old = dialog(vm); val fresh = dialog(vm)
        assertNotSame(old, fresh)
        vm.confirmSignOut(old); vm.dismissSignOut(old)
        assertSame(fresh, vm.signOutReview.value); assertEquals(0, writes)
        vm.confirmSignOut(fresh); assertEquals(1, writes); assertNull(vm.signOutReview.value)
    }
    @Test fun switchedSessionDismissesDialogAndRetainsCurrentRequests(): Unit {
        val vm = model(); val old = dialog(vm)
        assertTrue(auth.switchAccount(beta.id)); val count = writes
        assertNull(vm.signOutReview.value)
        vm.confirmSignOut(old)
        assertEquals(count, writes); assertEquals(beta.token, auth.getToken()); assertTrue(vm.isLoading.value)
    }
    @Test fun staleButtonCannotReplaceCurrentDialog(): Unit {
        val vm = model(); val revision = auth.sessionRevision.value
        assertTrue(auth.switchAccount(beta.id)); val fresh = dialog(vm)
        vm.prepareSignOut(revision)
        assertSame(fresh, vm.signOutReview.value); assertEquals("beta", fresh.login)
    }
    @Test fun changedStoreReconcilesProfileOwnerWithoutSigningOut(): Unit {
        val vm = model(); val old = dialog(vm)
        persisted = persisted!!.copy(token = beta.token, activeAccountId = beta.id)
        val before = persisted
        vm.confirmSignOut(old)
        assertEquals(before, persisted); assertEquals(0, writes); assertTrue(vm.isLoading.value)
        assertEquals(beta.token, auth.getToken())
        assertEquals(listOf(alpha.token), cancelledTokens)
        assertEquals(listOf(alpha.token, beta.token), requestedTokens)
        assertNull(vm.signOutReview.value)
    }
    @Test fun preparingAfterExternalSwitchRefreshesBeforeAllowingReview(): Unit {
        val revision = auth.sessionRevision.value
        persisted = persisted!!.copy(token = beta.token, activeAccountId = beta.id)
        val before = persisted
        assertNull(auth.reviewSignOut(revision))
        assertEquals(before, persisted); assertEquals(0, writes)
        assertEquals(beta.id, auth.activeAccountId()); assertEquals("beta", auth.cachedLogin.value)
        assertTrue(auth.sessionRevision.value > revision)
        val fresh = review(); assertEquals("beta", fresh.login)
        assertTrue(auth.signOut(fresh)); assertEquals(alpha.token, auth.tokenForAccount(alpha.id))
    }
    @Test fun externalSessionDeletionPublishesSignedOutStateWithoutWriting(): Unit {
        val vm = model(); val old = dialog(vm)
        persisted = null
        vm.prepareSignOut(auth.sessionRevision.value)
        assertNull(auth.getToken()); assertNull(auth.activeAccountId()); assertNull(auth.cachedLogin.value)
        assertTrue(auth.accounts.value.isEmpty()); assertNull(vm.signOutReview.value)
        assertFalse(vm.isLoading.value); assertEquals(listOf(alpha.token), cancelledTokens)
        vm.confirmSignOut(old); assertNull(persisted); assertEquals(0, writes)
    }
    @Test fun sameTokenProfileChangePublishesFreshLabelAndInvalidatesReview(): Unit {
        val vm = model(); val old = dialog(vm); val revision = auth.sessionRevision.value
        plainPreferences["github_cache_owner"] = MessageDigest.getInstance("SHA-256")
            .digest(alpha.token!!.toByteArray()).joinToString("") { "%02x".format(it) }
        plainPreferences["github_login"] = "stale-plaintext-login"
        plainPreferences["github_name"] = "Stale plaintext name"
        plainPreferences["github_repos_json"] = "Generated stale repository cache"
        persisted = persisted!!.copy(accounts = persisted!!.accounts.map {
            if (it.id == alpha.id) it.copy(login = "renamed-alpha", name = "Renamed Alpha") else it
        })
        val before = persisted
        vm.prepareSignOut(revision)
        assertEquals(alpha.token, auth.getToken()); assertEquals("renamed-alpha", auth.cachedLogin.value)
        assertEquals("Renamed Alpha", auth.cachedName.value)
        assertEquals("renamed-alpha", vm.user.value?.login)
        assertEquals("Renamed Alpha", vm.user.value?.name)
        assertFalse(plainPreferences.containsKey("github_repos_json"))
        assertTrue(auth.sessionRevision.value > revision); assertNull(vm.signOutReview.value)
        vm.confirmSignOut(old); assertEquals(before, persisted); assertEquals(0, writes)
        assertEquals("renamed-alpha", dialog(vm).login)
    }
    @Test fun unreadableSessionPreservesPublishedStateAndAllowsReadRetry(): Unit {
        val before = persisted; val revision = auth.sessionRevision.value
        failRead = true
        assertNull(auth.reviewSignOut(revision))
        assertEquals(before, persisted); assertEquals(alpha.token, auth.getToken())
        assertEquals(revision, auth.sessionRevision.value); assertNotNull(auth.sessionError.value)
        assertEquals(0, writes)
        failRead = false
        assertEquals("alpha", review().login); assertNull(auth.sessionError.value)
    }
    @Test fun anotherManagersSwitchIsReconciledWithoutReusingItsConsent(): Unit {
        val vm = model(); val old = dialog(vm)
        val other = GitHubAuthManager(context)
        assertTrue(other.switchAccount(beta.id)); val before = persisted; val count = writes
        vm.prepareSignOut(auth.sessionRevision.value)
        assertEquals(beta.token, auth.getToken()); assertNull(vm.signOutReview.value)
        vm.confirmSignOut(old)
        assertEquals(before, persisted); assertEquals(count, writes)
        assertEquals("beta", dialog(vm).login)
    }
    @Test fun changedConfirmationRequiresFreshConsentBeforeDisconnectingNewAccount(): Unit {
        val vm = model(); val old = dialog(vm)
        persisted = persisted!!.copy(token = beta.token, activeAccountId = beta.id)
        vm.confirmSignOut(old)
        assertEquals(0, writes); assertEquals(beta.token, auth.getToken()); assertNull(vm.signOutReview.value)
        val fresh = dialog(vm); assertEquals("beta", fresh.login)
        vm.confirmSignOut(old); assertEquals(0, writes)
        vm.confirmSignOut(fresh)
        assertEquals(1, writes); assertNull(auth.getToken()); assertEquals(alpha.token, auth.tokenForAccount(alpha.id))
    }
    @Test fun staleEntryDoesNotReadOrReconcileAwayANewerDialog(): Unit {
        val vm = model(); val oldRevision = auth.sessionRevision.value
        assertTrue(auth.switchAccount(beta.id)); val fresh = dialog(vm)
        persisted = persisted!!.copy(token = alpha.token, activeAccountId = alpha.id)
        val count = reads
        failRead = true
        vm.prepareSignOut(oldRevision)
        assertEquals(count, reads); assertSame(fresh, vm.signOutReview.value)
        assertEquals(beta.token, auth.getToken()); assertNull(auth.sessionError.value)
    }
    @Test fun currentDialogConfirmsOnlyOnce(): Unit {
        val vm = model(); val old = dialog(vm)
        vm.confirmSignOut(old); vm.confirmSignOut(old)
        assertEquals(1, writes); assertNull(auth.getToken()); assertFalse(vm.isLoading.value)
        assertNull(vm.user.value); assertTrue(vm.repos.value.isEmpty())
    }
}
