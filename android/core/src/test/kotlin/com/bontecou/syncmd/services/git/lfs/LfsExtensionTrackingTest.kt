package com.bontecou.syncmd.services.git.lfs

import com.bontecou.syncmd.services.git.LocalDiffRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.util.LfsFactory
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LfsExtensionTrackingTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun root(): File = temporary.newFolder().canonicalFile.also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Fixture\n")
            git.add().addFilepattern(".").call()
            git.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("Initial").call()
        }
    }
    private fun filter(root: File, path: String) = GitLfs.open(root).use { LfsFactory.getAttributesForPath(it.repository, path).getValue("filter") }
    @Test fun `review previews exact case variants and unchanged tracked siblings without writing until apply`() = runBlocking {
        val root = root()
        File(root, "old.pdf").writeText("Previously committed regular bytes")
        Git.open(root).use { it.add().addFilepattern("old.pdf").call(); it.commit().setAuthor("Fixture", "fixture@example.invalid").setMessage("Old PDF").call() }
        listOf("new.PdF", "upper.PDF", "other.Pdf", "notes.txt").forEach { File(root, it).writeText("Bytes for $it") }
        File(root, ".gitignore").writeText("ignored.pdf\n"); File(root, "ignored.pdf").writeText("Ignored")
        val index = File(root, ".git/index").readBytes()
        val tracking = LfsTracking(); val plan = tracking.plan(root.path)
        val review = tracking.reviewExtensions(plan, setOf("new.PdF"))
        assertThat(review.patterns).containsExactly("*.pdf", "*.PDF", "*.PdF").inOrder()
        assertThat(review.affected.map { it.path }).containsExactly("new.PdF", "old.pdf", "upper.PDF")
        assertThat(File(root, ".gitattributes").exists()).isFalse()
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
        tracking.applyExtensions(review)
        assertThat(filter(root, "old.pdf")).isEqualTo("lfs")
        assertThat(filter(root, "other.Pdf")).isNull()
        assertThat(File(root, "old.pdf").readText()).isEqualTo("Previously committed regular bytes")
        assertThat(File(root, ".git/index").readBytes()).isEqualTo(index)
        File(root, "future.PDF").writeText("Future bytes")
        assertThat(tracking.plan(root.path).candidates.map { it.path }).doesNotContain("future.PDF")
        // The unselected mixed-case extension still requires its own explicit choice.
        tracking.decide(tracking.plan(root.path), setOf("other.Pdf"), false)
        LocalDiffRepository().stageAll(root.path).getOrThrow()
        GitLfs.open(root).use { git ->
            listOf("new.PdF", "old.pdf", "upper.PDF", "future.PDF").forEach { path ->
                assertThat(LfsPointer.parse(git.repository.open(git.repository.readDirCache().getEntry(path).objectId).bytes)).isNotNull()
            }
            assertThat(git.repository.readDirCache().getEntry("ignored.pdf")).isNull()
        }
    }
    @Test fun `root nested and global explicit choices survive extension tracking`() = runBlocking {
        val root = root()
        File(root,".gitattributes").writeText("/keep.pdf -filter diff=custom merge=union text\n")
        File(root,"nested").mkdir()
        File(root,"nested/.gitattributes").writeText("*.pdf -filter\n")
        val global=File(temporary.root.canonicalFile,"global-attrs").apply { writeText("global.pdf -filter\n") }
        Git.open(root).use { it.repository.config.apply { setString("core",null,"attributesFile",global.path);save() } }
        listOf("selected.pdf","keep.pdf","global.pdf","nested/keep.pdf").forEach { File(root,it).writeText("Original $it") }
        val tracking=LfsTracking();val review=tracking.reviewExtensions(tracking.plan(root.path),setOf("selected.pdf"))
        assertThat(review.affected.map { it.path }).containsExactly("selected.pdf")
        assertThat(review.retained).containsAtLeast("keep.pdf","global.pdf","nested/keep.pdf")
        tracking.applyExtensions(review)
        listOf("keep.pdf","global.pdf","nested/keep.pdf").forEach { assertThat(filter(root,it)).isNull() }
        assertThat(filter(root,"selected.pdf")).isEqualTo("lfs")
        GitLfs.open(root).use { git ->
            val attrs=LfsFactory.getAttributesForPath(git.repository,"keep.pdf")
            assertThat(attrs.getValue("diff")).isEqualTo("custom");assertThat(attrs.getValue("merge")).isEqualTo("union");assertThat(attrs.isSet("text")).isTrue()
        }
        File(root,"nested/future.pdf").writeText("Future excluded")
        assertThat(filter(root,"nested/future.pdf")).isNull()
    }
    @Test fun `new matching file invalidates confirmation without creating attributes`() = runBlocking {
        val root=root();File(root,"selected.pdf").writeText("Selected")
        val tracking=LfsTracking();val review=tracking.reviewExtensions(tracking.plan(root.path),setOf("selected.pdf"))
        File(root,"new.pdf").writeText("Unreviewed addition")
        assertThat(runCatching { tracking.applyExtensions(review) }.exceptionOrNull()).isInstanceOf(LfsExtensionReviewChanged::class.java)
        assertThat(File(root,".gitattributes").exists()).isFalse()
    }
    @Test fun `changed unselected affected file invalidates confirmation and preserves index`() = runBlocking {
        val root=root();File(root,"selected.pdf").writeText("Selected");val sibling=File(root,"sibling.pdf").apply { writeText("Original") }
        val index=File(root,".git/index").readBytes()
        val tracking=LfsTracking();val review=tracking.reviewExtensions(tracking.plan(root.path),setOf("selected.pdf"))
        sibling.writeText("External edit")
        assertThat(runCatching { tracking.applyExtensions(review) }.exceptionOrNull()).isInstanceOf(LfsExtensionReviewChanged::class.java)
        assertThat(File(root,".gitattributes").exists()).isFalse();assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
        assertThat(sibling.readText()).isEqualTo("External edit")
    }
    @Test fun `global and nested rule edits invalidate confirmation even when selected bytes stay unchanged`() = runBlocking {
        val root=root();File(root,"selected.pdf").writeText("Selected");File(root,"nested").mkdir();File(root,"nested/file.pdf").writeText("Sibling")
        val global=File(temporary.root.canonicalFile,"global-attrs").apply { writeText("*.txt text\n") }
        Git.open(root).use { it.repository.config.apply { setString("core",null,"attributesFile",global.path);save() } }
        val tracking=LfsTracking();val plan=tracking.plan(root.path);val review=tracking.reviewExtensions(plan,setOf("selected.pdf"))
        global.appendText("*.md text\n")
        assertThat(runCatching { tracking.applyExtensions(review) }.exceptionOrNull()).isInstanceOf(LfsExtensionReviewChanged::class.java)
        val second=tracking.reviewExtensions(plan,setOf("selected.pdf"))
        File(root,"nested/.gitattributes").writeText("*.pdf -filter\n")
        assertThat(runCatching { tracking.applyExtensions(second) }.exceptionOrNull()).isInstanceOf(LfsExtensionReviewChanged::class.java)
        assertThat(File(root,".gitattributes").exists()).isFalse()
    }
    @Test fun `same commit on another branch invalidates both exact and extension choices`() = runBlocking {
        val root=root();File(root,"selected.pdf").writeText("Selected")
        val tracking=LfsTracking();val plan=tracking.plan(root.path);val review=tracking.reviewExtensions(plan,setOf("selected.pdf"))
        Git.open(root).use { it.checkout().setCreateBranch(true).setName("other").call() }
        assertThat(runCatching { tracking.applyExtensions(review) }.exceptionOrNull()).isInstanceOf(LfsExtensionReviewChanged::class.java)
        assertThat(runCatching { tracking.decide(plan,setOf("selected.pdf"),true) }.isFailure).isTrue()
        assertThat(File(root,".gitattributes").exists()).isFalse()
    }
    @Test fun `large unknown extension stays an exact path alongside recognized extension rules`() = runBlocking {
        val root=root();File(root,"selected.pdf").writeText("Selected")
        val large=File(root,"large [draft]*名.dat")
        java.io.RandomAccessFile(large,"rw").use { it.setLength(10L*1024*1024+1) }
        File(root,"other.dat").writeText("Regular")
        val tracking=LfsTracking();val review=tracking.reviewExtensions(tracking.plan(root.path),setOf("selected.pdf",large.name))
        assertThat(review.patterns).containsAtLeast("*.pdf","*.PDF")
        assertThat(review.patterns).doesNotContain("*.dat")
        tracking.applyExtensions(review)
        assertThat(filter(root,large.name)).isEqualTo("lfs");assertThat(filter(root,"other.dat")).isNull()
    }
}
