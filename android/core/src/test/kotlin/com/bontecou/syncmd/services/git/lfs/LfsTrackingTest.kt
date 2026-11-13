package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LfsTrackingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): File = temporary.newFolder().also { Git.init().setDirectory(it).setInitialBranch("main").call().close() }
    @Test fun `binary tracking requires explicit consent and only then stages pointer plus attributes`() = runBlocking {
        val root = root(); val pdf = File(root, "guide.pdf"); pdf.writeBytes(byteArrayOf(0, 1, 2, 3))
        val diff = LocalDiffRepository()
        assertThat(diff.stageAll(root.path).exceptionOrNull()).isInstanceOf(LfsTrackingReviewRequired::class.java)
        assertThat(File(root, ".gitattributes").exists()).isFalse()
        Git.open(root).use { assertThat(it.repository.readDirCache().entryCount).isEqualTo(0) }
        val review = LfsTracking(); val plan = review.plan(root.path)
        assertThat(plan.candidates.map { it.path }).containsExactly("guide.pdf")
        review.decide(plan, setOf("guide.pdf"), true)
        diff.stageAll(root.path).getOrThrow()
        GitLfs.open(root).use { git ->
            val cache = git.repository.readDirCache()
            assertThat(LfsPointer.parse(git.repository.open(cache.getEntry("guide.pdf").objectId).bytes)).isEqualTo(LfsObjectStore.hash(pdf))
            assertThat(cache.getEntry(".gitattributes")).isNotNull()
        }
        assertThat(pdf.readBytes()).isEqualTo(byteArrayOf(0, 1, 2, 3))
    }
    @Test fun `regular Git choice remains explicit and does not ask again`() = runBlocking {
        val root = root(); File(root, "guide.pdf").writeText("PDF fixture")
        val review = LfsTracking(); review.decide(review.plan(root.path), setOf("guide.pdf"), false)
        assertThat(review.plan(root.path).candidates).isEmpty()
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        Git.open(root).use { git -> assertThat(String(git.repository.open(git.repository.readDirCache().getEntry("guide.pdf").objectId).bytes)).isEqualTo("PDF fixture") }
    }
    @Test fun `exact path choice supports spaces Unicode and literal glob characters without tracking siblings`() = runBlocking {
        val root = root()
        val name = "guide [draft]*名.pdf"
        File(root, name).writeText("Chosen PDF")
        File(root, "guide another.pdf").writeText("Sibling")
        val review = LfsTracking(); review.decide(review.plan(root.path), setOf(name), true)
        val gitCheck = ProcessBuilder("git", "-C", root.path, "check-attr", "filter", "--", name, "guide another.pdf").redirectErrorStream(true).start()
        val attributes = gitCheck.inputStream.bufferedReader().readText(); check(gitCheck.waitFor() == 0)
        assertThat(attributes).contains(": filter: lfs")
        assertThat(attributes).contains("guide another.pdf: filter: unspecified")
        val candidates = review.plan(root.path).candidates.map { it.path }
        assertThat(candidates).containsExactly("guide another.pdf")
        LocalDiffRepository().stageFile(root.path, name).getOrThrow()
        GitLfs.open(root).use { git -> assertThat(LfsPointer.parse(git.repository.open(git.repository.readDirCache().getEntry(name).objectId).bytes)).isNotNull() }
    }
    @Test fun `changed reviewed file leaves attributes and index untouched`() = runBlocking {
        val root = root(); val pdf = File(root, "guide.pdf"); pdf.writeText("First draft")
        val review = LfsTracking(); val plan = review.plan(root.path)
        pdf.writeText("New external draft")
        assertThat(runCatching { review.decide(plan, setOf("guide.pdf"), true) }.isFailure).isTrue()
        assertThat(File(root, ".gitattributes").exists()).isFalse()
        Git.open(root).use { assertThat(it.repository.readDirCache().entryCount).isEqualTo(0) }
    }
    @Test fun `changed attributes preserve existing user edits and staged index`() = runBlocking {
        val root = root(); File(root, "guide.pdf").writeText("PDF fixture")
        val attrs = File(root, ".gitattributes"); attrs.writeText("*.txt text\n")
        val review = LfsTracking(); val plan = review.plan(root.path)
        attrs.writeText("*.txt -text\n")
        assertThat(runCatching { review.decide(plan, setOf("guide.pdf"), true) }.isFailure).isTrue()
        assertThat(attrs.readText()).isEqualTo("*.txt -text\n")
    }
    @Test fun `oversized regular Git blob cannot commit and keeps its staged index`() = runBlocking {
        val root = root()
        File(root, ".gitattributes").writeText("*.dat -filter\n")
        val large = File(root, "large.dat")
        java.io.RandomAccessFile(large, "rw").use { it.setLength(100L * 1024 * 1024 + 1) }
        val diff = LocalDiffRepository()
        diff.stageAll(root.path).getOrThrow()
        val before = File(root, ".git/index").readBytes()
        val result = diff.commit(root.path, "Oversized fixture", "Fixture", "fixture@example.invalid")
        assertThat(result.isFailure).isTrue()
        assertThat(result.exceptionOrNull()!!.message).contains("exceeds 100 MiB")
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(before)
        assertThat(large.length()).isEqualTo(100L * 1024 * 1024 + 1)
        Git.open(root).use { assertThat(it.repository.resolve("HEAD")).isNull() }
    }
    @Test fun `empty LFS files remain empty blobs`() = runBlocking {
        val root = root(); File(root, ".gitattributes").writeText("*.bin filter=lfs -text\n"); File(root, "empty.bin").writeBytes(byteArrayOf())
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        GitLfs.open(root).use { git -> assertThat(git.repository.open(git.repository.readDirCache().getEntry("empty.bin").objectId).size).isEqualTo(0) }
    }
}
