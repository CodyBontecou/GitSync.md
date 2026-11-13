package com.bontecou.syncmd.ui

import com.bontecou.syncmd.services.github.GitHubRepo
import com.bontecou.syncmd.services.github.RepoNoteSignals
import com.bontecou.syncmd.ui.components.addVaultSize
import com.bontecou.syncmd.ui.components.addVaultSizePair
import com.bontecou.syncmd.ui.components.addVaultInitials
import com.bontecou.syncmd.ui.components.cloneLfsRows
import com.bontecou.syncmd.ui.components.parseGitHubTime
import com.bontecou.syncmd.ui.screens.AddVaultTarget
import com.bontecou.syncmd.ui.screens.addVaultUrlTarget
import com.bontecou.syncmd.ui.screens.repoPickerGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddVaultLogicTest {
    private fun repo(id: Long, name: String) = GitHubRepo(id, name, "cody/$name", null, true, "", "main", null, "cody", null, 0)

    @Test fun sizesReadLikeTheDesign() {
        assertEquals("4.0 MB", addVaultSize(4_000_000))
        assertEquals("18 KB", addVaultSize(18_000))
        assertEquals("210 MB", addVaultSize(210_000_000))
        assertEquals("1.8 / 4.0 MB", addVaultSizePair(1_800_000, 4_000_000))
    }

    @Test fun githubAddressesCloneThroughTheAccount() {
        assertEquals(AddVaultTarget.GitHubRepo("cody/notes"), addVaultUrlTarget("cody/notes", signedIn = true))
        assertEquals(AddVaultTarget.GitHubRepo("cody/notes"), addVaultUrlTarget("https://github.com/cody/notes.git", signedIn = true))
        assertTrue(addVaultUrlTarget("cody/notes", signedIn = false) is AddVaultTarget.Remote)
        assertTrue(addVaultUrlTarget("ssh://git@gitea.home.lan/cody/recipes.git", signedIn = true) is AddVaultTarget.Remote)
    }

    @Test fun notesGroupComesFirst() {
        val signals = mapOf("cody/vault" to RepoNoteSignals(true, 0, 0, false), "cody/docs" to RepoNoteSignals(false, 9, 2, false),
            "cody/app" to RepoNoteSignals(false, 1, 40, false))
        val groups = repoPickerGroups(listOf(repo(1, "app"), repo(2, "vault"), repo(3, "docs"), repo(4, "unknown")), signals)
        assertEquals(2, groups.size)
        assertTrue(groups[0].notes)
        assertEquals(listOf("vault", "docs"), groups[0].repos.map { it.name })
        assertEquals(listOf("app", "unknown"), groups[1].repos.map { it.name })
    }

    @Test fun lfsFoldersCollapse() {
        val rows = cloneLfsRows(listOf("a/map.pdf" to 18L, "audio/x.m4a" to 42L) + (1..6).map { "covers/$it.jpg" to 1L })
        assertEquals(listOf("a/map.pdf", "audio/x.m4a", "covers/"), rows.map { it.path })
        assertEquals(6, rows.last().count)
    }

    @Test fun helpers() {
        assertEquals("CB", addVaultInitials("Cody Bontecou", "CodyBontecou"))
        assertEquals("I", addVaultInitials(null, "isolated"))
        assertNotNull(parseGitHubTime("2026-11-06T08:00:00Z"))
        assertFalse(RepoNoteSignals(false, 1, 5, false).looksLikeNotes)
    }
}
