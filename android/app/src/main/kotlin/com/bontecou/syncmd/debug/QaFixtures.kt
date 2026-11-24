package com.bontecou.syncmd.debug

import android.content.Context
import android.content.Intent
import com.bontecou.syncmd.BuildConfig
import java.io.File

/**
 * DEBUG-only Argent QA fixtures (no-ops in release builds). Launch with
 * `-e syncmd.qaFixture stale-worktree` to rebuild the app's own sample repository (and nothing
 * else) in a state no UI action can produce:
 *
 * - `stale-worktree` (issue #130): `main` moved to `origin/main` without a checkout, as
 *   Obsidian Git <= 2.41 on mobile left it. Index and files still hold the base commit.
 *
 * The sample vault is then opened, like the Guide's "Try a sample" does.
 */
object QaFixtures {
    const val EXTRA = "syncmd.qaFixture"

    /** Set when a fixture was seeded; AppShell opens the sample vault and clears it. */
    val openSample = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** Handles `-e syncmd.qaFixture <scenario>` or `syncmd://qa-fixture/<scenario>` (debug manifest only). */
    fun handle(context: Context, intent: Intent?) {
        if (!BuildConfig.DEBUG || intent == null) return
        val data = intent.data?.takeIf { it.scheme == "syncmd" && it.host == "qa-fixture" }
        val scenario = intent.getStringExtra(EXTRA) ?: data?.lastPathSegment ?: return
        intent.removeExtra(EXTRA)
        if (data != null) intent.data = null
        when (scenario) {
            "stale-worktree" -> {
                com.bontecou.syncmd.services.git.SampleQaFixtures.seedStaleWorktree(
                    File(context.filesDir, "sample-workspace"), File(context.cacheDir, "qa-fixture-peer"))
                openSample.value = true
            }
        }
    }
}
