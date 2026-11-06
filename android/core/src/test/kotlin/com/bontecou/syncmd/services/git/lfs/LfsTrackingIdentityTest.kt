package com.bontecou.syncmd.services.git.lfs

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class LfsTrackingIdentityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun exactChoiceRejectsIdenticalReplacementCheckout(): Unit = runBlocking { rejectReplacement(false, false) }
    @Test fun extensionChoiceRejectsIdenticalReplacementCheckout(): Unit = runBlocking { rejectReplacement(true, false) }
    @Test fun exactChoiceRejectsIdenticalReplacementMetadata(): Unit = runBlocking { rejectReplacement(false, true) }
    @Test fun extensionChoiceRejectsIdenticalReplacementMetadata(): Unit = runBlocking { rejectReplacement(true, true) }
    @Test fun exactChoiceRejectsChangedConfiguration(): Unit = runBlocking { rejectConfig(false) }
    @Test fun extensionChoiceRejectsChangedConfiguration(): Unit = runBlocking { rejectConfig(true) }

    private fun root(): File = temporary.newFolder().also { root ->
        Git.init().setDirectory(root).setInitialBranch("main").call().use { git ->
            File(root, "README.md").writeText("Committed fixture\n")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("Fixture").setAuthor("Fixture", "fixture@example.invalid").setCommitter("Fixture", "fixture@example.invalid").call()
        }
        File(root, "guide.pdf").writeText("Reviewed binary fixture\n")
    }
    private fun contents(root: File): Map<String, List<Byte>> = root.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(root).invariantSeparatorsPath to it.readBytes().toList() }

    private suspend fun rejectReplacement(extension: Boolean, metadataOnly: Boolean) {
        val root = root()
        val tracking = LfsTracking()
        val plan = tracking.plan(root.path)
        val review = if (extension) tracking.reviewExtensions(plan, setOf("guide.pdf")) else null
        val target = if (metadataOnly) File(root, ".git") else root
        val replacement = File(temporary.root, "replacement")
        check(target.copyRecursively(replacement))
        val parked = File(temporary.root, "parked")
        Files.move(target.toPath(), parked.toPath())
        Files.move(replacement.toPath(), target.toPath())
        val before = contents(root)
        val parkedBefore = contents(parked)
        val failure = runCatching {
            if (review != null) tracking.applyExtensions(review) else tracking.decide(plan, setOf("guide.pdf"), true)
        }.exceptionOrNull()
        assertThat(failure).isNotNull()
        assertThat(contents(root)).isEqualTo(before)
        assertThat(contents(parked)).isEqualTo(parkedBefore)
        // Reloading authorizes this new checkout and still permits the intended operation.
        val fresh = tracking.plan(root.path)
        if (extension) tracking.applyExtensions(tracking.reviewExtensions(fresh, setOf("guide.pdf")))
        else tracking.decide(fresh, setOf("guide.pdf"), true)
        assertThat(File(root, ".gitattributes").readText()).contains("filter=lfs")
    }
    private suspend fun rejectConfig(extension: Boolean) {
        val root = root()
        val tracking = LfsTracking()
        val plan = tracking.plan(root.path)
        val review = if (extension) tracking.reviewExtensions(plan, setOf("guide.pdf")) else null
        File(root, ".git/config").appendText("\n[review]\n\tfixture = changed\n")
        val before = contents(root)
        assertThat(runCatching {
            if (review != null) tracking.applyExtensions(review) else tracking.decide(plan, setOf("guide.pdf"), false)
        }.isFailure).isTrue()
        assertThat(contents(root)).isEqualTo(before)
    }
}
