package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.services.git.SampleRepository
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RepositoryIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `author edit preserves files index refs and remote and applies to future commits`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val service = RepositoryIdentity()
        Git.open(root).use { git ->
            git.add().addFilepattern("notes.md").call()
            val index = File(root, ".git/index").readBytes()
            val head = git.repository.resolve("HEAD")
            val notes = File(root, "notes.md").readBytes()
            val remote = git.repository.config.getString("remote", "origin", "url")
            service.save(service.inspect(root.path), "  Ada Example  ", "ada@example.invalid")
            assertArrayEquals(index, File(root, ".git/index").readBytes())
            assertArrayEquals(notes, File(root, "notes.md").readBytes())
            assertEquals(head, git.repository.resolve("HEAD"))
            git.repository.config.load()
            assertEquals(remote, git.repository.config.getString("remote", "origin", "url"))
            val commit = git.commit().setMessage("Authored locally").call()
            assertEquals("Ada Example", commit.authorIdent.name)
            assertEquals("ada@example.invalid", commit.authorIdent.emailAddress)
            assertEquals("Ada Example", commit.committerIdent.name)
        }
    }
    @Test fun `invalid identities stale reviews and locks preserve config`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        val service = RepositoryIdentity()
        val plan = service.inspect(root.path)
        val before = File(root, ".git/config").readBytes()
        for ((name, email) in listOf("" to "a@b", "Bad\nName" to "a@b", "Name" to "bad", "Name" to "bad\n@host", "A<B" to "a@b")) {
            assertTrue(runCatching { service.save(plan, name, email) }.isFailure)
            assertArrayEquals(before, File(root, ".git/config").readBytes())
        }
        File(root, ".git/config").appendText("\n# another editor\n")
        assertTrue(runCatching { service.save(plan, "New", "new@example.invalid") }.isFailure)
        val fresh = service.inspect(root.path)
        File(root, ".git/config.lock").writeText("other process")
        assertTrue(runCatching { service.save(fresh, "New", "new@example.invalid") }.isFailure)
        assertEquals("other process", File(root, ".git/config.lock").readText())
    }
}
