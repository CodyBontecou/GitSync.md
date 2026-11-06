package com.bontecou.syncmd

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import com.bontecou.syncmd.automation.PullAllCatalog
import com.bontecou.syncmd.background.*
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.automation.RepositoryActionRunner
import com.bontecou.syncmd.services.background.BackgroundPolicy
import com.bontecou.syncmd.services.git.*
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.transport.RefSpec
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual WorkManager database/scheduler and production Hilt worker, with only an owned local Git remote. */
class BackgroundSyncSchedulerTest {
    @Test fun uniqueScheduledWorkerPublishesOwnedFixtureAndOptOutCancelsAllItsFutureWork() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = BackgroundSyncStore(context); require(!store.read().active)
        val work = WorkManager.getInstance(context)
        for (name in listOf(BackgroundSyncJobs.NOW, BackgroundSyncJobs.PERIODIC))
            require(work.getWorkInfosForUniqueWork(name).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
        val originals = listOf("sync_md_prefs", "background_sync", "background_runs").associateWith { context.getSharedPreferences(it, 0).all.toMap() }
        val workspace = File(context.filesDir, "background-scheduler-${UUID.randomUUID()}").canonicalFile.apply { check(mkdir()) }
        val bare = File(workspace, "remote.git"); val seed = File(workspace, "seed"); val root = File(workspace, "Notes")
        val credentials = RepositoryCredentialStore(context)
        try {
            Git.init().setDirectory(bare).setBare(true).setInitialBranch("main").call().close()
            Git.init().setDirectory(seed).setInitialBranch("main").call().use { git ->
                File(seed, "note.md").writeText("Initial")
                git.add().addFilepattern(".").call(); git.commit().setMessage("Initial").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
                git.push().setRemote(bare.toURI().toString()).setRefSpecs(RefSpec("refs/heads/main:refs/heads/main")).call()
            }
            Git.cloneRepository().setDirectory(root).setURI(bare.toURI().toString()).call().use { git ->
                git.repository.config.apply { setString("user", null, "name", "Fixture"); setString("user", null, "email", "fixture@example.invalid"); save() }
            }
            // Explicit no-credentials policy prevents any saved GitHub account from entering this fixture.
            credentials.save(root.path, "https://example.invalid/background-fixture.git", Credentials.None)
            check(context.getSharedPreferences("sync_md_prefs", 0).edit().clear().putInt("repository_count", 1)
                .putString("repo_0_name", "Scheduled native fixture").putString("repo_0_path", root.path).putLong("repo_0_date", 17).commit())
            val catalog = PullAllCatalog(context)
            val actions = RepositoryActionRunner(LocalDiffRepository(), LocalPullRepository(), LocalRemoteRepository(), { "Fixture" to "fixture@example.invalid" },
                catalog::requireCurrent, credentialRevision = credentials::recordVersion)
            // A file transport is installed only by this test. Production enrollment accepts reviewed HTTPS/SSH.
            val plan = actions.review(catalog.snapshot().single())
            val policy = BackgroundPolicy.reviewed(plan.copy(credentialRevision = BackgroundSyncBackend.connectionRevision(plan,
                "saved:${credentials.recordVersion(root.path)}")), false, false)
            File(root, "note.md").writeText("Approved scheduled change")
            store.configure(store.read().generation) { it.copy(pull = false, push = true, policies = mapOf(root.path to policy)) }
            BackgroundSyncJobs.schedule(context)
            withTimeout(15_000) {
                while (work.getWorkInfosForUniqueWork(BackgroundSyncJobs.PERIODIC).get(10, TimeUnit.SECONDS).none { !it.state.isFinished }) delay(50)
            }
            val periodic = work.getWorkInfosForUniqueWork(BackgroundSyncJobs.PERIODIC).get(10, TimeUnit.SECONDS).single { !it.state.isFinished }
            assertEquals(15 * 60_000L, periodic.periodicityInfo?.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, periodic.constraints.requiredNetworkType)
            BackgroundSyncJobs.now(context)
            withTimeout(30_000) {
                while (work.getWorkInfosForUniqueWork(BackgroundSyncJobs.NOW).get(10, TimeUnit.SECONDS).none { it.state == WorkInfo.State.SUCCEEDED }) delay(50)
            }
            val head = Git.open(root).use { it.repository.resolve("HEAD").name }
            assertEquals(head, Git.open(bare).use { it.repository.resolve("HEAD").name })
            assertEquals(2, Git.open(root).use { it.log().call().count() })
            assertEquals(com.bontecou.syncmd.services.background.BackgroundHealthKind.UPDATED, store.read().policies[root.path]?.health?.kind)
            // The production worker records the pass for Background Sync › Recent runs: published, nothing pulled.
            val run = BackgroundRunHistory(context).runs().first().vaults.single { it.path == root.path }
            assertEquals(com.bontecou.syncmd.services.background.BackgroundHealthKind.UPDATED, run.kind); assertTrue(run.pushed); assertEquals(0, run.pulled)
            store.configure(store.read().generation) { it.copy(pull = false, push = false) }
            BackgroundSyncJobs.preferencesChanged(context)
            work.cancelUniqueWork(BackgroundSyncJobs.PERIODIC).result.get(10, TimeUnit.SECONDS)
            assertTrue(work.getWorkInfosForUniqueWork(BackgroundSyncJobs.PERIODIC).get(10, TimeUnit.SECONDS).all { it.state.isFinished })
            File(root, "note.md").writeText("Private after opt out")
            assertTrue(runCatching { BackgroundSyncJobs.now(context) }.isFailure)
            assertEquals(head, Git.open(bare).use { it.repository.resolve("HEAD").name })
        } finally {
            try {
                store.configure(store.read().generation) { it.copy(pull = false, push = false) }
                work.cancelUniqueWork(BackgroundSyncJobs.NOW).result.get(10, TimeUnit.SECONDS)
                work.cancelUniqueWork(BackgroundSyncJobs.PERIODIC).result.get(10, TimeUnit.SECONDS)
                if (root.exists()) withContext(Dispatchers.IO) { RepositoryOperationCoordinator.withRepository(root.path) {} }
            } finally {
                originals.forEach { (name, values) -> restore(context, name, values) }
                credentials.remove(root.path); workspace.deleteRecursively()
            }
        }
    }
    private fun restore(context: Context, name: String, values: Map<String, *>) {
        val edit = context.getSharedPreferences(name, 0).edit().clear()
        values.forEach { (key, value) -> when (value) {
            is String -> edit.putString(key, value); is Int -> edit.putInt(key, value); is Long -> edit.putLong(key, value)
            is Boolean -> edit.putBoolean(key, value); is Float -> edit.putFloat(key, value)
            is Set<*> -> { @Suppress("UNCHECKED_CAST") edit.putStringSet(key, value as Set<String>) }
            else -> error("Invalid preferences type")
        } }; check(edit.commit())
    }
}
