package com.bontecou.syncmd

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.github.*
import com.bontecou.syncmd.ui.viewmodels.GitHubViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

class GitHubViewModelTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val names = mutableSetOf<String>()
    private val models = mutableListOf<GitHubViewModel>()
    private val gates = mutableListOf<CompletableDeferred<Unit>>()
    private lateinit var context: Context
    @Before fun setup() {
        val prefix = "github-vm-test-${UUID.randomUUID()}-"
        context = object : ContextWrapper(app) {
            override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
                val key = prefix + name; names += key
                return app.getSharedPreferences(key, mode)
            }
        }
    }
    @After fun cleanup() {
        gates.forEach { it.complete(Unit) }
        main { models.forEach { ViewModelStore().apply { put("vm", it); clear() } } }
        names.forEach {
            assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).edit().clear().commit())
            app.deleteSharedPreferences(it)
            assertTrue(app.getSharedPreferences(it, Context.MODE_PRIVATE).all.isEmpty())
        }
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun gate() = CompletableDeferred<Unit>().also { gates += it }
    private fun model(auth: GitHubAuthManager, api: GitHubClient): GitHubViewModel {
        lateinit var vm: GitHubViewModel
        main { vm = GitHubViewModel(auth, api); models += vm }
        return vm
    }
    private fun user(name: String) = GitHubUser(name, name, "$name@example.invalid", null)
    private fun repo(name: String) = GitHubRepo(1, "notes", "$name/notes", null, true, "https://github.com/$name/notes", "main", null, name, null, 0)
    private fun await(action: suspend () -> Unit) = runBlocking { withTimeout(10_000) { action() } }

    @Test fun delayedOldAccountResponseCannotReplaceTheNewAccountsProfileOrRepos() {
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("alpha-fixture-token")
        val started = gate(); val release = gate()
        val api = object : GitHubClient {
            override suspend fun fetchUser(token: String): GitHubUser {
                if (token == "alpha-fixture-token") { started.complete(Unit); withContext(NonCancellable) { release.await() } }
                return user(if (token == "alpha-fixture-token") "alpha" else "beta")
            }
            override suspend fun fetchRepos(token: String) = listOf(repo(if (token == "alpha-fixture-token") "alpha" else "beta"))
            override suspend fun fetchPrimaryEmail(token: String): String? = null
        }
        val vm = model(auth, api)
        await { started.await() }
        main { auth.saveValidatedToken("beta-fixture-token") }
        await { vm.repos.first { it.singleOrNull()?.ownerLogin == "beta" } }
        release.complete(Unit)
        main { }
        assertEquals("beta", vm.user.value?.login)
        assertEquals("beta/notes", vm.repos.value.single().fullName)
        assertEquals("beta", auth.cachedLogin.value)
        assertEquals("beta/notes", auth.getCachedRepos()!!.single().fullName)
    }
    @Test fun signOutDuringTokenValidationCannotBeUndoneByTheLateResult() {
        val auth = GitHubAuthManager(context)
        val started = gate(); val release = gate()
        var repoRequests = 0
        val api = object : GitHubClient {
            override suspend fun fetchUser(token: String): GitHubUser {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; return user("late")
            }
            override suspend fun fetchRepos(token: String): List<GitHubRepo> { repoRequests++; return listOf(repo("late")) }
            override suspend fun fetchPrimaryEmail(token: String): String? = null
        }
        val vm = model(auth, api)
        main { vm.signInWithPAT("pending-fixture-token") }
        await { started.await() }
        main { vm.signOut() }
        release.complete(Unit)
        main { }
        assertNull(auth.getToken())
        assertNull(GitHubAuthManager(context).getToken())
        assertNull(vm.user.value)
        assertTrue(vm.repos.value.isEmpty())
        assertEquals(0, repoRequests)
    }
    @Test fun acceptingANewTokenClearsThePreviousIdentityBeforeEmailLoadingFinishes() {
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("alpha-fixture-token")
        val emailStarted = gate(); val releaseEmail = gate()
        val api = object : GitHubClient {
            override suspend fun fetchUser(token: String) = if (token == "beta-fixture-token") user("beta").copy(email = null) else user("alpha")
            override suspend fun fetchRepos(token: String) = listOf(repo(if (token == "beta-fixture-token") "beta" else "alpha"))
            override suspend fun fetchPrimaryEmail(token: String): String? {
                emailStarted.complete(Unit); releaseEmail.await(); return "beta@example.invalid"
            }
        }
        val vm = model(auth, api)
        await { vm.repos.first { it.singleOrNull()?.ownerLogin == "alpha" } }
        main { vm.signInWithPAT("beta-fixture-token") }
        await { emailStarted.await() }
        assertNotEquals("alpha", vm.user.value?.login)
        assertEquals("beta", auth.cachedLogin.value)
        releaseEmail.complete(Unit)
        await { vm.repos.first { it.singleOrNull()?.ownerLogin == "beta" } }
        assertEquals("beta", vm.user.value?.login)
        assertEquals("beta@example.invalid", auth.getAuthorEmail())
    }

    @Test fun aNewPatSessionFinishesWithoutWaitingForAnOldAccountRequest() {
        val auth = GitHubAuthManager(context)
        auth.saveValidatedToken("alpha-fixture-token")
        val started = gate(); val release = gate()
        val api = object : GitHubClient {
            override suspend fun fetchUser(token: String): GitHubUser {
                if (token == "alpha-fixture-token") { started.complete(Unit); withContext(NonCancellable) { release.await() } }
                return user(if (token == "alpha-fixture-token") "alpha" else "beta")
            }
            override suspend fun fetchRepos(token: String) = listOf(repo(if (token == "alpha-fixture-token") "alpha" else "beta"))
            override suspend fun fetchPrimaryEmail(token: String): String? = null
        }
        val vm = model(auth, api)
        await { started.await() }
        main { vm.signInWithPAT("beta-fixture-token") }
        await { vm.repos.first { it.singleOrNull()?.ownerLogin == "beta" } }
        assertFalse(vm.isLoading.value)
        assertEquals("beta", vm.user.value?.login)
        release.complete(Unit)
    }
    @Test fun switchingAccountsDuringPatValidationLoadsTheSelectedAccountBeforeValidationReturns() {
        val auth=GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("beta-fixture-token",user("beta").copy(id=202)))
        assertTrue(auth.saveValidatedToken("alpha-fixture-token",user("alpha").copy(id=101)))
        val started=gate();val release=gate()
        val pendingRepoRequests=java.util.concurrent.atomic.AtomicInteger()
        val api=object:GitHubClient {
            override suspend fun fetchUser(token:String):GitHubUser {
                if(token=="pending-fixture-token") { started.complete(Unit);withContext(NonCancellable) { release.await() } }
                return when(token) {
                    "alpha-fixture-token" -> user("alpha").copy(id=101)
                    "beta-fixture-token" -> user("beta").copy(id=202)
                    else -> user("pending").copy(id=303)
                }
            }
            override suspend fun fetchRepos(token:String):List<GitHubRepo> {
                if(token=="pending-fixture-token") pendingRepoRequests.incrementAndGet()
                return listOf(repo(if(token=="beta-fixture-token") "beta" else "alpha"))
            }
            override suspend fun fetchPrimaryEmail(token:String):String?=null
        }
        val vm=model(auth,api)
        await { vm.repos.first { it.singleOrNull()?.ownerLogin=="alpha" } }
        main { vm.signInWithPAT("pending-fixture-token") };await { started.await() }
        main { assertTrue(auth.switchAccount("github:202")) }
        // The selected account must finish while the old validation is still held.
        await { vm.repos.first { it.singleOrNull()?.ownerLogin=="beta" };vm.isLoading.first { !it } }
        assertEquals("beta",vm.user.value?.login);assertEquals("github:202",auth.activeAccountId())
        release.complete(Unit);main { }
        assertEquals("beta",vm.user.value?.login);assertEquals("beta/notes",auth.getCachedRepos()!!.single().fullName)
        assertEquals("beta-fixture-token",GitHubAuthManager(context).getToken());assertEquals(0,pendingRepoRequests.get())
    }

    @Test fun reselectingTheSameAccountRestartsAnInvalidatedLoadAndRetainsItsSearch() {
        val auth=GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("alpha-fixture-token",user("alpha").copy(id=101)))
        val started=gate();val release=gate();val requests=java.util.concurrent.atomic.AtomicInteger()
        val api=object:GitHubClient {
            override suspend fun fetchUser(token:String)=user("alpha").copy(id=101)
            override suspend fun fetchRepos(token:String):List<GitHubRepo> {
                if(requests.incrementAndGet()==1) {
                    started.complete(Unit);withContext(NonCancellable) { release.await() }
                    return listOf(repo("alpha").copy(name="outdated",fullName="alpha/outdated"))
                }
                return listOf(repo("alpha"))
            }
            override suspend fun fetchPrimaryEmail(token:String):String?=null
        }
        val vm=model(auth,api);await { started.await() }
        main { vm.onSearchQueryChanged("notes");assertTrue(auth.switchAccount("github:101")) }
        await { vm.repos.first { it.singleOrNull()?.fullName=="alpha/notes" };vm.isLoading.first { !it } }
        assertEquals("notes",vm.searchQuery.value);assertEquals("alpha/notes",vm.filteredRepos.value.single().fullName)
        release.complete(Unit);main { }
        assertEquals("alpha/notes",vm.repos.value.single().fullName)
        assertEquals("alpha/notes",auth.getCachedRepos()!!.single().fullName);assertEquals(2,requests.get())
    }

    @Test fun removingTheActiveAccountEndsPendingValidationWithoutWaitingForTheNetwork() {
        val auth=GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("alpha-fixture-token",user("alpha").copy(id=101)))
        val started=gate();val release=gate();val pendingRepoRequests=java.util.concurrent.atomic.AtomicInteger()
        val api=object:GitHubClient {
            override suspend fun fetchUser(token:String):GitHubUser {
                if(token=="pending-fixture-token") { started.complete(Unit);withContext(NonCancellable) { release.await() } }
                return if(token=="alpha-fixture-token") user("alpha").copy(id=101) else user("pending").copy(id=303)
            }
            override suspend fun fetchRepos(token:String):List<GitHubRepo> {
                if(token=="pending-fixture-token") pendingRepoRequests.incrementAndGet()
                return listOf(repo("alpha"))
            }
            override suspend fun fetchPrimaryEmail(token:String):String?=null
        }
        val vm=model(auth,api);await { vm.repos.first { it.isNotEmpty() } }
        main { vm.signInWithPAT("pending-fixture-token") };await { started.await() }
        main { assertTrue(auth.removeAccount("github:101")) }
        await { vm.isLoading.first { !it };vm.isLoggedIn.first { !it } }
        assertNull(vm.user.value);assertTrue(vm.repos.value.isEmpty());assertNull(auth.getToken())
        release.complete(Unit);main { }
        assertNull(GitHubAuthManager(context).getToken());assertNull(vm.user.value)
        assertTrue(vm.repos.value.isEmpty());assertEquals(0,pendingRepoRequests.get())
    }

    @Test fun switchingAfterPatAcceptanceKeepsTheAcceptedAccountButDropsItsLateProfileLoad() {
        val auth=GitHubAuthManager(context)
        assertTrue(auth.saveValidatedToken("beta-fixture-token",user("beta").copy(id=202)))
        assertTrue(auth.saveValidatedToken("alpha-fixture-token",user("alpha").copy(id=101)))
        val started=gate();val release=gate();val pendingRepoRequests=java.util.concurrent.atomic.AtomicInteger()
        val api=object:GitHubClient {
            override suspend fun fetchUser(token:String)=when(token) {
                "alpha-fixture-token" -> user("alpha").copy(id=101)
                "beta-fixture-token" -> user("beta").copy(id=202)
                else -> user("pending").copy(id=303,email=null)
            }
            override suspend fun fetchPrimaryEmail(token:String):String? {
                assertEquals("pending-fixture-token",token)
                started.complete(Unit);withContext(NonCancellable) { release.await() };return "pending@example.invalid"
            }
            override suspend fun fetchRepos(token:String):List<GitHubRepo> {
                if(token=="pending-fixture-token") pendingRepoRequests.incrementAndGet()
                return listOf(repo(if(token=="beta-fixture-token") "beta" else "alpha"))
            }
        }
        val vm=model(auth,api);await { vm.repos.first { it.singleOrNull()?.ownerLogin=="alpha" } }
        main { vm.signInWithPAT("pending-fixture-token") };await { started.await() }
        assertEquals("github:303",auth.activeAccountId())
        main { assertTrue(auth.switchAccount("github:202")) }
        await { vm.repos.first { it.singleOrNull()?.ownerLogin=="beta" };vm.isLoading.first { !it } }
        release.complete(Unit);main { }
        assertEquals("beta",vm.user.value?.login);assertEquals("github:202",auth.activeAccountId())
        assertEquals("beta/notes",auth.getCachedRepos()!!.single().fullName)
        assertEquals("pending-fixture-token",GitHubAuthManager(context).tokenForAccount("github:303"))
        assertEquals(0,pendingRepoRequests.get())
    }

}
