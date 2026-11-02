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

/** Actual auth/session-lock and ViewModel paths; only Android encrypted persistence/preferences are mocked. */
@OptIn(ExperimentalCoroutinesApi::class)
class SignOutConsentTest {
    private lateinit var construction: MockedConstruction<GitHubSessionStore>
    private lateinit var auth: GitHubAuthManager
    private lateinit var context: Context
    private var persisted: GitHubSessionStore.Session? = null
    private var writes = 0
    private var failWrite = false
    private val models = mutableListOf<GitHubViewModel>()
    private val alpha = StoredGitHubAccount("github:1", "generated-alpha-token", 1, "alpha", "Alpha", "alpha@example.invalid")
    private val beta = StoredGitHubAccount("github:2", "generated-beta-token", 2, "beta", "Beta", "beta@example.invalid")
    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        persisted = GitHubSessionStore.Session(token = alpha.token, accounts = listOf(alpha, beta),
            activeAccountId = alpha.id, defaultAccountId = alpha.id)
        val editor = mock<SharedPreferences.Editor>(defaultAnswer = RETURNS_SELF)
        whenever(editor.commit()).thenReturn(true)
        val prefs = mock<SharedPreferences>()
        whenever(prefs.edit()).thenReturn(editor)
        context = mock()
        whenever(context.getSharedPreferences(any(), any())).thenReturn(prefs)
        construction = mockConstruction(GitHubSessionStore::class.java) { store, _ ->
            whenever(store.read()).thenAnswer { persisted }
            doAnswer { call ->
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
        override suspend fun fetchUser(token: String): GitHubUser = awaitCancellation()
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
    @Test fun rejectedStoreCheckDoesNotCancelCurrentProfileLoad(): Unit {
        val vm = model(); val old = dialog(vm)
        persisted = persisted!!.copy(token = beta.token, activeAccountId = beta.id)
        val before = persisted
        vm.confirmSignOut(old)
        assertEquals(before, persisted); assertEquals(0, writes); assertTrue(vm.isLoading.value)
        assertNull(vm.signOutReview.value)
    }
    @Test fun currentDialogConfirmsOnlyOnce(): Unit {
        val vm = model(); val old = dialog(vm)
        vm.confirmSignOut(old); vm.confirmSignOut(old)
        assertEquals(1, writes); assertNull(auth.getToken()); assertFalse(vm.isLoading.value)
        assertNull(vm.user.value); assertTrue(vm.repos.value.isEmpty())
    }
}
