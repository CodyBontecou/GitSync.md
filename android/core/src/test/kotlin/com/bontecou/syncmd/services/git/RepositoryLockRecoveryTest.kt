package com.bontecou.syncmd.services.git

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Issue #66: a stale index.lock can be found and, once confirmed, removed without a re-clone. */
class RepositoryLockRecoveryTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun repository() = SampleRepository.openOrCreate(temporary.newFolder("workspace"))

    @Test fun `no locks in a healthy repository`() {
        assertThat(RepositoryLockRecovery.find(repository().path)).isEmpty()
    }

    @Test fun `stale index lock blocks staging until removed`() = runBlocking {
        val root = repository()
        val lock = File(root, ".git/index.lock").apply { writeText("interrupted writer\n") }
        lock.setLastModified(System.currentTimeMillis() - 120_000)
        File(root, "after.md").writeText("after\n")
        val blocked = runCatching { Git.open(root).use { it.add().addFilepattern("after.md").call() } }
        assertThat(blocked.isFailure).isTrue()

        val found = RepositoryLockRecovery.find(root.path)
        assertThat(found.map { it.name }).containsExactly("index.lock")

        RepositoryLockRecovery.remove(root.path, found)

        assertThat(lock.exists()).isFalse()
        assertThat(RepositoryLockRecovery.find(root.path)).isEmpty()
        Git.open(root).use { it.add().addFilepattern("after.md").call() }
        Git.open(root).use { assertThat(it.status().call().added).contains("after.md") }
    }

    @Test fun `fresh or changed locks are never removed`() {
        val root = repository()
        val lock = File(root, ".git/index.lock").apply { writeText("active writer\n") }
        val modified = System.currentTimeMillis() - 60_000
        lock.setLastModified(modified)
        val found = RepositoryLockRecovery.find(root.path)

        assertThrows(RepositoryLockRecovery.LockChangedException::class.java) {
            runBlocking { RepositoryLockRecovery.remove(root.path, found, now = modified + 1_000) }
        }
        assertThat(lock.exists()).isTrue()

        lock.appendText("still writing\n")
        lock.setLastModified(modified)
        assertThrows(RepositoryLockRecovery.LockChangedException::class.java) {
            runBlocking { RepositoryLockRecovery.remove(root.path, found) }
        }
        assertThat(lock.readText()).isEqualTo("active writer\nstill writing\n")
    }
}
