package com.bontecou.syncmd.services.git

import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class HistoryIdentityTest {
    @get:Rule val temporary = TemporaryFolder()
    private val history = LocalHistoryRepository()
    private suspend fun stashed(): File {
        val root = SampleRepository.openOrCreate(temporary.newFolder())
        Git.open(root).use { it.reset().setMode(ResetCommand.ResetType.HARD).call() }
        File(root, "notes.md").writeText("Older owned draft\n")
        history.stashSave(root.path, "Older draft").getOrThrow()
        File(root, "notes.md").writeText("Newest owned draft\n")
        history.stashSave(root.path, "Newest draft").getOrThrow()
        return root
    }
    @Test fun `malformed stash drop cannot silently delete index zero`() = runBlocking {
        for (id in listOf("HEAD", "garbage", "stash@{bad}", "stash@{-1}", "stash@{00}", "stash@{9999999999999999999}")) {
            val root = stashed(); val before = history.listStashes(root.path).getOrThrow()
            assertEquals(2, before.size)
            assertTrue("Must reject malformed reference $id", history.stashDrop(root.path, id).isFailure)
            assertEquals(before, history.listStashes(root.path).getOrThrow())
        }
    }
    @Test fun `popping the older stash preserves newest and applies the selected contents`() = runBlocking {
        val root = stashed(); val before = history.listStashes(root.path).getOrThrow()
        history.stashPop(root.path, before[1].id).getOrThrow()
        assertEquals("Older owned draft\n", File(root, "notes.md").readText())
        val after = history.listStashes(root.path).getOrThrow()
        assertEquals(1, after.size); assertEquals(before[0].commitHash, after.single().commitHash)
    }
    @Test fun `tag inventories retain the exact annotated object identity`() = runBlocking {
        val root = stashed()
        history.createTag(root.path, "annotated", "HEAD", true, "Owned annotation").getOrThrow()
        history.createTag(root.path, "light", "HEAD", false, null).getOrThrow()
        Git.open(root).use { git ->
            val tags = history.listTags(root.path).getOrThrow()
            for (tag in tags) {
                assertEquals(git.repository.resolve("refs/tags/${tag.name}").name, tag.refObjectId)
                assertEquals(tag, history.getTag(root.path, tag.name).getOrThrow())
                assertEquals(git.repository.resolve("HEAD").name, tag.commitHash)
            }
            assertNotEquals(tags.first { it.name == "annotated" }.commitHash, tags.first { it.name == "annotated" }.refObjectId)
            assertEquals("Owned annotation", tags.first { it.name == "annotated" }.message)
        }
    }

}
