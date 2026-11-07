package com.bontecou.syncmd.services.files

import com.bontecou.syncmd.services.git.SampleRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class FileInsightsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `compare counts rewritten lines once and names the hunk after its heading`() {
        val old = "# Plan\n\n## Steps\n1. Record\n2. Transcribe by hand\n3. Paste\n4. Done\n"
        val new = "# Plan\n\n## Steps\n1. Record\n2. Whisper\n3. Tag speakers\n4. Done\n"
        val diff = FileInsights.compare("plan.md", old, new)
        assertThat(diff.added).isEqualTo(2)
        assertThat(diff.removed).isEqualTo(2)
        assertThat(diff.linesChanged).isEqualTo(2)
        assertThat(diff.changedLines).containsExactly(5, 6)
        val hunk = diff.changes.first()
        assertThat(hunk.kind).isEqualTo(WorkingLineKind.HUNK)
        assertThat(hunk.text).isEqualTo("## Steps")
        assertThat(diff.whole.count { it.kind != WorkingLineKind.REMOVED }).isEqualTo(new.lines().size - 1)
    }

    @Test fun `unchanged text has no changes`() {
        val diff = FileInsights.compare("a.md", "same\n", "same\n")
        assertThat(diff.changes).isEmpty()
        assertThat(diff.linesChanged).isEqualTo(0)
    }

    @Test fun `working diff, history and entry insights read the repository`() = runBlocking {
        val root = SampleRepository.openOrCreate(temporary.newFolder("workspace"))
        val insights = FileInsights()
        val diff = insights.diff(root.path, "notes.md")
        assertThat(diff.headShortHash).hasLength(7)
        assertThat(diff.added + diff.removed).isGreaterThan(0)
        assertThat(insights.history(root.path, "notes.md")).isNotEmpty()
        File(root, "big.bin").writeText("version https://git-lfs.github.com/spec/v1\noid sha256:${"a".repeat(64)}\nsize 42\n")
        val entries = RepositoryFiles().list(root.path)
        val info = insights.entries(root.path, entries, showHidden = false)
        assertThat(info.getValue("big.bin").lfsPointer?.size).isEqualTo(42)
        assertThat(info.getValue("notes.md").lastModified).isGreaterThan(0L)
    }

    @Test fun `remote slug handles https and scp urls`() {
        assertThat(remoteSlug("https://github.com/CodyBontecou/VoxVault.git")).isEqualTo("CodyBontecou/VoxVault")
        assertThat(remoteSlug("git@github.com:CodyBontecou/VoxVault.git")).isEqualTo("CodyBontecou/VoxVault")
    }
}
