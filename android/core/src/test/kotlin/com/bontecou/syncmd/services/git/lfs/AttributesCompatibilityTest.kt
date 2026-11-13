package com.bontecou.syncmd.services.git.lfs

import com.google.common.truth.Truth.assertThat
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.attributes.Attribute
import org.eclipse.jgit.util.LfsFactory
import org.eclipse.jgit.util.QuotedString
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Compare the compatibility parser's observable results with canonical Git, including macro precedence. */
class AttributesCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `C quoted paths macros and overrides agree with native Git`() {
        val root = temporary.newFolder()
        val names = listOf("normal.bin", "folder/a b.bin", "folder/こんにちは.bin", "folder/quote\"file.bin", "folder/tab\tfile.bin", "folder/hidden#file.bin", "literal[1]*.bin")
        names.forEach { name -> File(root, name).apply { parentFile.mkdirs(); writeText("fixture") } }
        File(root, ".gitattributes").writeText(buildString {
            append("[attr]media filter=lfs diff=lfs merge=lfs -text\n*.bin media\n")
            for (name in names.filter { it.contains('/') }) append(QuotedString.GIT_PATH.quote("/$name") + " -filter\n")
        })
        Git.init().setDirectory(root).call().close()
        GitLfs.open(root).use { git ->
            for (name in names) {
                val canonical = ProcessBuilder("git", "-C", root.path, "check-attr", "-z", "filter", "diff", "--", name).redirectErrorStream(true).start()
                val fields = canonical.inputStream.readBytes().toString(Charsets.UTF_8).split('\u0000')
                check(canonical.waitFor() == 0)
                val actual = LfsFactory.getAttributesForPath(git.repository, name)
                for (index in 0..1) {
                    val key = fields[index * 3 + 1]
                    val value = when (actual.getState(key)) {
                        Attribute.State.SET -> "set"
                        Attribute.State.UNSET -> "unset"
                        Attribute.State.CUSTOM -> actual.getValue(key)
                        else -> "unspecified"
                    }
                    assertThat(value).isEqualTo(fields[index * 3 + 2])
                }
            }
        }
    }
}
