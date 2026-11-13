package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.SampleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryConnectionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `connection edit preserves index files refs and other remote config`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { git ->
            git.repository.config.apply { setString("remote", "backup", "url", "https://backup.example/team/repo.git"); save() }
            git.add().addFilepattern("notes.md").call()
            val head = git.repository.resolve("HEAD")
            val index = File(root, ".git/index").readBytes()
            val notes = File(root, "notes.md").readText()
            val service = RepositoryConnection()
            val plan = service.inspect(root.path)
            var policyPrepared = false
            service.save(plan, "https://git.example/team/notes.git") { commit ->
                policyPrepared = true
                assertThat(File(root, ".git/config").readText()).doesNotContain("https://git.example/team/notes.git")
                commit()
            }
            assertThat(policyPrepared).isTrue()
            git.repository.config.load()
            assertThat(git.repository.config.getString("remote", "origin", "url")).isEqualTo("https://git.example/team/notes.git")
            assertThat(git.repository.config.getString("remote", "origin", "pushurl")).isEqualTo("https://git.example/team/notes.git")
            assertThat(git.repository.config.getString("remote", "backup", "url")).isEqualTo("https://backup.example/team/repo.git")
            assertThat(git.repository.resolve("HEAD")).isEqualTo(head)
            assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
            assertThat(File(root, "notes.md").readText()).isEqualTo(notes)
            assertThat(File(root, ".git/config.lock").exists()).isFalse()
        }
    }
    @Test fun `stale plan or config lock never calls credential mutation`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val service = RepositoryConnection()
        val plan = service.inspect(root.path)
        File(root, ".git/config").appendText("\n# external edit\n")
        var called = false
        assertThat(runCatching { service.save(plan, "https://git.example/repo.git") { called = true; it() } }.isFailure).isTrue()
        assertThat(called).isFalse()
        assertThat(File(root, ".git/config").readText()).contains("# external edit")
        val fresh = service.inspect(root.path)
        File(root, ".git/config.lock").writeText("Other process\n")
        assertThat(runCatching { service.save(fresh, "https://git.example/repo.git") { called = true; it() } }.isFailure).isTrue()
        assertThat(called).isFalse()
        assertThat(File(root, ".git/config.lock").readText()).isEqualTo("Other process\n")
    }
    @Test fun `failed credential persistence never publishes prepared config`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val service = RepositoryConnection()
        val plan = service.inspect(root.path)
        val before = File(root, ".git/config").readBytes()
        assertThat(runCatching { service.save(plan, "https://git.example/repo.git") { error("Storage unavailable") } }.isFailure).isTrue()
        assertThat(File(root, ".git/config").readBytes()).isEqualTo(before)
        assertThat(File(root, ".git/config.lock").exists()).isFalse()
    }
    @Test fun `noncooperating config change during credential save is preserved`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val service = RepositoryConnection()
        val plan = service.inspect(root.path)
        assertThat(runCatching { service.save(plan, "https://git.example/repo.git") { commit ->
            File(root, ".git/config").appendText("\n# changed during save\n")
            commit()
        } }.isFailure).isTrue()
        assertThat(File(root, ".git/config").readText()).contains("# changed during save")
        assertThat(File(root, ".git/config").readText()).doesNotContain("https://git.example/repo.git")
    }
    @Test fun `repository without origin gets a fetch mapping and upstream without creating a commit`() = runBlocking {
        val root = temporary.newFolder()
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            val service = RepositoryConnection()
            service.save(service.inspect(root.path), "https://git.example/team/empty.git") { it() }
            git.repository.config.load()
            assertThat(git.repository.config.getStringList("remote", "origin", "fetch").toList()).containsExactly("+refs/heads/*:refs/remotes/origin/*")
            assertThat(git.repository.config.getString("branch", "main", "remote")).isEqualTo("origin")
            assertThat(git.repository.resolve("HEAD")).isNull()
        }
    }
}
