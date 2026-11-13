package com.bontecou.syncmd.ui.screens

import com.bontecou.syncmd.R
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NameValidationTest {
    private fun note(input: String) = noteBaseName(input).let { newEntryNameIssue(it, "$it.md") }

    @Test fun `ordinary note names pass`() {
        assertThat(note("Ideas")).isNull()
        assertThat(note("2026-11-01.md")).isNull()
        assertThat(note("")).isNull()
    }

    @Test fun `separators and dot names get their own messages`() {
        assertThat(note("a/b")).isEqualTo(NewEntryNameIssue.SEPARATOR)
        assertThat(note("a\\b")).isEqualTo(NewEntryNameIssue.SEPARATOR)
        assertThat(newEntryNameIssue("..", "..")).isEqualTo(NewEntryNameIssue.DOTS)
        assertThat(newEntryNameIssue(".", ".")).isEqualTo(NewEntryNameIssue.DOTS)
    }

    @Test fun `length counts UTF-8 bytes of the final file name including md`() {
        assertThat(note("a".repeat(252))).isNull() // 252 + ".md" = 255 bytes
        assertThat(note("a".repeat(253))).isEqualTo(NewEntryNameIssue.TOO_LONG)
        assertThat(note("a".repeat(300))).isEqualTo(NewEntryNameIssue.TOO_LONG)
        // "é" is two bytes: 126 × 2 + 3 = 255 passes, 127 × 2 + 3 = 257 fails.
        assertThat(note("é".repeat(126))).isNull()
        assertThat(note("é".repeat(127))).isEqualTo(NewEntryNameIssue.TOO_LONG)
        // Folders have no suffix.
        assertThat(newEntryNameIssue("a".repeat(255), "a".repeat(255))).isNull()
        assertThat(newEntryNameIssue("a".repeat(256), "a".repeat(256))).isEqualTo(NewEntryNameIssue.TOO_LONG)
    }

    @Test fun `remote clone display names refuse path-like names but allow dot repos`() {
        assertThat(remoteCloneNameIssue("notes")).isNull()
        assertThat(remoteCloneNameIssue(".dotfiles")).isNull()
        assertThat(remoteCloneNameIssue("../../evil")).isEqualTo(R.string.add_remote_name_invalid)
        assertThat(remoteCloneNameIssue("..")).isEqualTo(R.string.add_remote_name_invalid)
        assertThat(remoteCloneNameIssue(".")).isEqualTo(R.string.add_remote_name_invalid)
        assertThat(remoteCloneNameIssue("a\\b")).isEqualTo(R.string.add_remote_name_invalid)
        assertThat(remoteCloneNameIssue("  ")).isEqualTo(R.string.account_repository_name_length)
        assertThat(remoteCloneNameIssue("x".repeat(121))).isEqualTo(R.string.account_repository_name_length)
    }

    @Test fun `clone location preview names the folder the clone really gets`() {
        assertThat(remoteCloneFolderPreview("new folder")).isEqualTo("custom/new folder")
    }
}
