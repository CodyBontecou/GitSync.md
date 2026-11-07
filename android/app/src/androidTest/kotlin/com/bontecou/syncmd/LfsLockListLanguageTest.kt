package com.bontecou.syncmd

import android.content.Intent
import android.content.res.Configuration
import android.os.LocaleList
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.services.git.lfs.*
import com.bontecou.syncmd.ui.screens.LockConfirmation
import com.bontecou.syncmd.ui.screens.lfsLockControls
import com.bontecou.syncmd.ui.theme.SyncMdTheme
import com.bontecou.syncmd.ui.viewmodels.LfsViewModel
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.Locale

/** Stateful rendering and callback dispatch only; never executes Git, server operations or settings changes. */
class LfsLockListLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext
    private data class Frame(val language: String, val index: Int, val busy: Boolean = false)
    private fun visible(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag("lfs-lock-list-screen").performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag).performScrollTo()
    }
    @Test fun readOnlyOwnershipEmptyUnsupportedAndMutationDispatchFollowAllLocales() {
        val expected = JSONObject(InstrumentationRegistry.getInstrumentation().context.assets.open("localization_expected.json")
            .bufferedReader().use { it.readText() }).getJSONObject("locales")
        val languages = expected.keys().asSequence().toList().sorted(); assertEquals(25, languages.size)
        fun text(language: String, name: String): String {
            val rows = expected.getJSONArray(language)
            return (0 until rows.length()).map { rows.getJSONObject(it) }.single { it.getString("name") == name }.getString("source")
        }
        val path = "docs/long-reviewed-folder/guide_日本語.pdf"
        val context = LfsLockContext("/generated/lock-list", "refs/heads/review_日本語", "a".repeat(40), "https://example.invalid/repo.git", "generated")
        val base = LfsLockReview(context, "https://lfs.example.invalid/repo/info/lfs", emptyList(), emptyList(), emptyList(), true, true, listOf(path))
        val owned = LfsLock("owned", path, "Fixture owner", null)
        val other = LfsLock("other", path, null, null)
        val listed = other.copy(id = "listed")
        val reviews = listOf(null, base.copy(supported = false),
            base.copy(context = context.copy(writeAccess = false), verified = false, listed = listOf(listed)),
            base, base.copy(ours = listOf(owned)), base.copy(theirs = listOf(other)))
        assertEquals(6, reviews.size)
        val frame = mutableStateOf(Frame(languages.first(), 0))
        val checks = mutableListOf<Boolean>(); val confirmations = mutableListOf<LockConfirmation>()
        ActivityScenario.launch<ComponentActivity>(Intent(app, ComponentActivity::class.java)).use { scenario ->
            scenario.onActivity { activity -> activity.setContent {
                val current = frame.value
                val configuration = Configuration(app.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(current.language)); setLayoutDirection(Locale.forLanguageTag(current.language))
                }
                val localized = app.createConfigurationContext(configuration); val density = LocalDensity.current
                CompositionLocalProvider(LocalContext provides localized, LocalConfiguration provides configuration,
                    LocalLayoutDirection provides if (current.language in listOf("ar", "he")) LayoutDirection.Rtl else LayoutDirection.Ltr,
                    LocalDensity provides Density(density.density, 1.6f)) {
                    SyncMdTheme(darkTheme = current.language in listOf("ar", "he", "ja")) {
                        LazyColumn(Modifier.testTag("lfs-lock-list-screen")) {
                            lfsLockControls(LfsViewModel.State(busy = current.busy, locks = reviews[current.index]),
                                onCheck = { checks += it }, confirm = { confirmations += it })
                        }
                    }
                }
            } }
            for (language in languages) for ((index, review) in reviews.withIndex()) {
                compose.runOnIdle { checks.clear(); confirmations.clear(); frame.value = Frame(language, index) }
                visible("lfs-locks-title").assertTextEquals(text(language, "lfs_locks_title"))
                visible("lfs-locks-intro").assertTextEquals(text(language, "lfs_locks_intro"))
                visible("lfs-locks-check").assertTextEquals(text(language, "lfs_locks_check")).assertHeightIsAtLeast(48.dp).performClick()
                visible("lfs-locks-list").assertTextEquals(text(language, "lfs_locks_list")).assertHeightIsAtLeast(48.dp).performClick()
                compose.runOnIdle { assertEquals(listOf(true, false), checks) }
                var mutationTag: String? = null
                when (index) {
                    0 -> compose.onNodeWithTag("lfs-locks-scope").assertDoesNotExist()
                    1 -> {
                        visible("lfs-locks-scope").assertTextEquals(text(language, "lfs_lock_unverified"))
                        visible("lfs-locks-unsupported").assertTextEquals(text(language, "lfs_locks_unsupported"))
                        compose.onNodeWithTag("lfs-locks-create-heading").assertDoesNotExist()
                    }
                    2 -> {
                        visible("lfs-locks-scope").assertTextEquals(text(language, "lfs_locks_read_only"))
                        visible("lfs-lock-ownership-listed").assertTextEquals(text(language, "lfs_lock_unverified"))
                        compose.onNodeWithTag("lfs-lock-force-listed").assertDoesNotExist()
                        compose.onNodeWithTag("lfs-lock-release-listed").assertDoesNotExist()
                        compose.onNodeWithTag("lfs-locks-create-heading").assertDoesNotExist()
                    }
                    3 -> {
                        visible("lfs-locks-empty").assertTextEquals(text(language, "lfs_locks_empty"))
                        mutationTag = "lfs-lock-create-$path"
                        visible(requireNotNull(mutationTag)).assertTextEquals(String.format(Locale.forLanguageTag(language), text(language, "lfs_lock_create_review"), path)).performClick()
                    }
                    else -> {
                        val lock = if (index == 4) owned else other
                        visible("lfs-lock-ownership-${lock.id}").assertTextEquals(text(language, if (index == 4) "lfs_lock_ours" else "lfs_lock_theirs"))
                        visible("lfs-locks-candidates-empty").assertTextEquals(text(language, "lfs_lock_candidates_empty"))
                        mutationTag = if (index == 4) "lfs-lock-release-owned" else "lfs-lock-force-other"
                        visible(requireNotNull(mutationTag)).assertTextEquals(text(language, if (index == 4) "lfs_lock_release_review" else "lfs_lock_force_review")).performClick()
                    }
                }
                if (review != null) {
                    visible("lfs-locks-path").assertTextEquals(context.path)
                    visible("lfs-locks-branch").assertTextEquals(context.branch)
                    visible("lfs-locks-remote").assertTextEquals(String.format(Locale.forLanguageTag(language), text(language, "lfs_locks_remote"), context.remote))
                    visible("lfs-locks-server").assertTextEquals(String.format(Locale.forLanguageTag(language), text(language, "lfs_locks_server"), base.endpoint))
                }
                compose.runOnIdle {
                    if (mutationTag == null) assertTrue(confirmations.isEmpty()) else {
                        assertEquals(1, confirmations.size); val selected = confirmations.single()
                        assertSame(review, selected.review); assertEquals(path, selected.path)
                        assertEquals(index == 5, selected.force)
                        assertEquals(if (index == 4) owned else if (index == 5) other else null, selected.lock)
                    }
                    frame.value = frame.value.copy(busy = true)
                }
                visible("lfs-locks-check").assertIsNotEnabled().performClick()
                visible("lfs-locks-list").assertIsNotEnabled().performClick()
                mutationTag?.let { visible(it).assertIsNotEnabled().performClick() }
                compose.runOnIdle {
                    assertEquals(listOf(true, false), checks)
                    assertEquals(if (mutationTag == null) 0 else 1, confirmations.size)
                }
            }
        }
    }
}
