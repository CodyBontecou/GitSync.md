package com.bontecou.syncmd

import android.os.Bundle
import android.content.ContextWrapper
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.security.RepositoryCredentialStore
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.net.URI
import java.util.UUID

/** Explicit maintenance methods: no preference/credential value is returned to the host. */
class FixtureSafetyTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val names = listOf("sync_md_prefs", "app_guide", "repository_callback_grants", "repository_credentials", "repository_shortcuts", "repository_shortcut_runs", "pull_all_background_runs",
        "obsidian_connections", "push_sync_state", "background_sync", "repository_locations", "ssh_known_hosts", "lfs_endpoint_approvals", "github_session")
    private fun baselineOwner(): String = InstrumentationRegistry.getArguments().getString("privateFixtureOwner")?.also {
        require(UUID.fromString(it).toString()==it)
    } ?: "suite"
    @Test fun removeOnlyValidatedInterruptedApi28Fixtures() {
        val args=InstrumentationRegistry.getArguments()
        Assume.assumeTrue(args.getString("repairOwnedApi28Fixtures")=="true")
        require(android.os.Build.VERSION.SDK_INT==28 && context.packageName=="md.gitsync.md.debug")
        require(!com.bontecou.syncmd.background.BackgroundSyncStore(context).read().active &&
            com.bontecou.syncmd.push.PushStateStore(context).read().let { !it.enabled && !it.unregisterPending })
        require(context.getSharedPreferences("sync_md_prefs",0).getInt("repository_count",0)==0)
        fun owner(key:String):String=checkNotNull(args.getString(key)).also { require(UUID.fromString(it).toString()==it) }
        val storage=owner("storageFixtureOwner")
        val storagePrefix="move-test-$storage-"
        require(!File(context.filesDir,"move-test-$storage").exists())
        val scoped=object:ContextWrapper(context) {
            override fun getSharedPreferences(name:String,mode:Int)=context.getSharedPreferences(storagePrefix+name,mode)
        }
        val auth=com.bontecou.syncmd.services.github.GitHubAuthManager(scoped)
        require(auth.accounts.value.map { it.id }.toSet()==setOf("github:101","github:202")) { "Interrupted account identities do not match the fixture" }
        require(auth.accountSummary("github:101")?.login=="default-user" && auth.accountSummary("github:202")?.login=="assigned-user")
        require(auth.tokenForAccount("github:101")=="default-fixture-token" && auth.tokenForAccount("github:202")=="assigned-fixture-token") { "Interrupted account secrets do not match the synthetic fixture" }
        val preferences=File(context.applicationInfo.dataDir,"shared_prefs")
        val storageNames=preferences.listFiles().orEmpty().filter { it.name.startsWith(storagePrefix) }
        require(storageNames.all { file -> !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.name.removePrefix(storagePrefix).removeSuffix(".bak").removeSuffix(".xml") in setOf("sync_md_prefs","github_session","repository_credentials") })
        val push=owner("pushFixtureOwner")
        val prefix="push-native-$push-"
        val candidate=File(context.filesDir,prefix)
        require(candidate.isDirectory && !java.nio.file.Files.isSymbolicLink(candidate.toPath()))
        val workspace=candidate.canonicalFile
        require(workspace.parentFile==context.filesDir.canonicalFile)
        require(workspace.listFiles().orEmpty().map { it.name }.toSet()==setOf("Notes"))
        workspace.walkTopDown().onEnter { check(!java.nio.file.Files.isSymbolicLink(it.toPath()));true }
            .forEach { check(!java.nio.file.Files.isSymbolicLink(it.toPath())) }
        val root=File(workspace,"Notes")
        val catalog=context.getSharedPreferences(prefix+"sync_md_prefs",0)
        require(catalog.getInt("repository_count",0)==1 && catalog.getString("repo_0_name",null)=="東京 — push fixture notes and documentation" &&
            catalog.getString("repo_0_path",null)==root.path && catalog.getLong("repo_0_date",0)==17L)
        require(root.listFiles().orEmpty().map { it.name }.toSet()==setOf(".git","note.md") && File(root,"note.md").readText()=="Keep native draft")
        org.eclipse.jgit.api.Git.open(root).use { git ->
            require(git.repository.config.getString("remote","origin","url")=="https://github.com/fixture/notes.git")
            val commits=git.log().call().toList()
            require(commits.size==1 && commits.single().fullMessage=="Initial" && commits.single().authorIdent.emailAddress=="fixture@example.invalid" && git.status().call().isClean)
        }
        val pushNames=preferences.listFiles().orEmpty().filter { it.name.startsWith(prefix) }
        require(pushNames.all { file -> !java.nio.file.Files.isSymbolicLink(file.toPath()) &&
            file.name.removePrefix(prefix).removeSuffix(".bak").removeSuffix(".xml") in setOf("sync_md_prefs","push_sync_state") })
        // All validation precedes deletion; no token or preference value leaves this process.
        for(name in storageNames.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct())check(context.deleteSharedPreferences(name))
        kotlinx.coroutines.runBlocking { com.bontecou.syncmd.services.git.RepositoryOperationCoordinator.withRepository(root.path) { check(workspace.deleteRecursively()) } }
        for(name in pushNames.map { it.name.removeSuffix(".bak").removeSuffix(".xml") }.distinct())check(context.deleteSharedPreferences(name))
        instrumentation.sendStatus(2,Bundle().apply { putString("stream","Validated synthetic interrupted fixtures removed: storage=1; push=1. No preference or credential values exported.\n") })
    }
    @Test fun removeEmptyOwnedStorageFixturePreferences() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupEmptyStorageFixtures") == "true")
        require(!com.bontecou.syncmd.background.BackgroundSyncStore(context).read().active &&
            com.bontecou.syncmd.push.PushStateStore(context).read().let { !it.enabled && !it.unregisterPending })
        val pattern=Regex("^(?:move-test-|gitsync-location-test-)([0-9a-f-]{36})-(.+)[.]xml$")
        var removed=0;var retained=0
        File(context.applicationInfo.dataDir,"shared_prefs").listFiles().orEmpty().forEach { file ->
            val match=pattern.matchEntire(file.name) ?: return@forEach
            require(UUID.fromString(match.groupValues[1]).toString()==match.groupValues[1])
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            val name=file.name.removeSuffix(".xml")
            if(context.getSharedPreferences(name,0).all.isEmpty()) {
                check(context.deleteSharedPreferences(name) && !file.exists());removed++
            } else retained++
        }
        instrumentation.sendStatus(2,Bundle().apply { putString("stream","Removed empty owned storage preference files=$removed; retained nonempty files=$retained. No values exported.\n") })
    }
    @Test fun removeEmptyOwnedHistoryFixturePreferences() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("cleanupEmptyHistoryFixtures") == "true")
        require(!com.bontecou.syncmd.background.BackgroundSyncStore(context).read().active &&
            com.bontecou.syncmd.push.PushStateStore(context).read().let { !it.enabled && !it.unregisterPending })
        val pattern = Regex("^history-clone-([0-9a-f-]{36})-(.+)[.]xml$")
        val directory = File(context.applicationInfo.dataDir, "shared_prefs")
        var removed = 0
        var retained = 0
        directory.listFiles().orEmpty().forEach { file ->
            val match = pattern.matchEntire(file.name) ?: return@forEach
            require(UUID.fromString(match.groupValues[1]).toString() == match.groupValues[1])
            require(file.isFile && !java.nio.file.Files.isSymbolicLink(file.toPath()))
            val name = file.name.removeSuffix(".xml")
            if (context.getSharedPreferences(name, 0).all.isEmpty()) {
                check(context.deleteSharedPreferences(name) && !file.exists())
                removed++
            } else retained++
        }
        instrumentation.sendStatus(2, Bundle().apply {
            putString("stream", "Empty owned history fixture preference files removed=$removed; nonempty fixture files retained=$retained. No preference values exported.\n")
        })
    }
    @Test fun capturePrivateBaselineBeforeSuite() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("privateFixtureBaseline") == "capture")
        require(!com.bontecou.syncmd.background.BackgroundSyncStore(context).read().active &&
            com.bontecou.syncmd.push.PushStateStore(context).read().let { !it.enabled && !it.unregisterPending }) { "Disable automatic Background/Push Sync before global fixture QA" }
        require(com.bontecou.syncmd.storage.clonePublications(context).inspect().isEmpty()) { "Finish or cancel existing clone recovery before global fixture QA" }
        require(context.noBackupFilesDir.listFiles().orEmpty().none { it.name.startsWith("native-fixture-recovery-") }) { "A prior private fixture baseline needs recovery first" }
        PrivateFixtureSnapshot(context, baselineOwner()).capture(names, includeApplicationLocales = true)
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Encrypted fixture baseline saved inside private no-backup storage. No values exported.\n") })
    }
    @Test fun restorePrivateBaselineAfterSuiteOrCrash() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("privateFixtureBaseline") == "restore")
        val owner=baselineOwner(); val snapshot=PrivateFixtureSnapshot(context,owner)
        if(InstrumentationRegistry.getArguments().getString("privateFixtureRestoreIfPresent")=="true") {
            require(owner!="suite")
            if(!snapshot.file.exists()) {
                instrumentation.sendStatus(2,Bundle().apply { putString("stream","No owned private baseline exists; no preference values changed.\n") })
                return
            }
        }
        snapshot.restore()
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Private preference maps restored; encrypted fixture baseline removed. No values exported.\n") })
    }
    @Test fun removeOnlyTheOwnedInterruptedCallbackFixture() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("repairOwnedCallbackFixture") == "true")
        val prefs = context.getSharedPreferences("sync_md_prefs", 0); val values = prefs.all
        require(values["repository_count"] == 1 && values["repo_0_name"] == "Production callback fixture" && values["repo_0_date"] == 17L)
        val root = File(requireNotNull(values["repo_0_path"] as? String)).canonicalFile
        val workspace = requireNotNull(root.parentFile)
        require(workspace.parentFile == context.filesDir.canonicalFile && workspace.name.startsWith("callback-transport-"))
        val id = workspace.name.removePrefix("callback-transport-"); require(UUID.fromString(id).toString() == id && root.name == "CallbackNotes-$id")
        require(!java.nio.file.Files.isSymbolicLink(workspace.toPath()) && !java.nio.file.Files.isSymbolicLink(root.toPath()))
        org.eclipse.jgit.api.Git.open(root).use { git ->
            require(File(URI(git.repository.config.getString("remote", "origin", "url"))).canonicalFile == File(workspace, "remote.git"))
        }
        RepositoryCredentialStore(context).remove(root.path)
        val edit = prefs.edit().putInt("repository_count", 0)
        values.keys.filter { it.matches(Regex("repo_0_(name|path|date|remote|branch|alias)")) }.forEach(edit::remove)
        check(edit.commit()); check(workspace.deleteRecursively())
        instrumentation.sendStatus(2, Bundle().apply { putString("stream", "Removed only the proven owned callback fixture. Prior RAM-only catalog/preferences cannot be reconstructed exactly.\n") })
    }
    @Test fun privateSnapshotRoundTripsTypedMapsAcrossInstancesWithoutPlaintextOnDisk() {
        val id = UUID.randomUUID().toString(); val name = "snapshot-native-$id"; val prefs = context.getSharedPreferences(name, 0)
        val snapshot = PrivateFixtureSnapshot(context, id)
        try {
            check(prefs.edit().putString("secret", "synthetic-private-value").putLong("long", Long.MAX_VALUE).putInt("int", 7)
                .putFloat("float", Float.NaN).putBoolean("boolean", true).putStringSet("set", setOf("東京", "a")).commit())
            val before = prefs.all.toMap(); snapshot.capture(listOf(name))
            assertFalse(String(snapshot.file.readBytes(), Charsets.ISO_8859_1).contains("synthetic-private-value"))
            check(prefs.edit().clear().putString("changed", "after capture").commit())
            PrivateFixtureSnapshot(context, id).restore(); assertEquals(before, prefs.all); assertFalse(snapshot.file.exists())
        } finally { snapshot.file.delete(); context.deleteSharedPreferences(name) }
    }
    @androidx.test.filters.SdkSuppress(minSdkVersion = 33)
    @Test fun privateSnapshotRestoresAppLanguageAcrossInstancesWithoutChangingTheSystemLanguage() {
        val manager = context.getSystemService(android.app.LocaleManager::class.java)
        val before = manager.applicationLocales; val systemBefore = manager.systemLocales
        val id = UUID.randomUUID().toString(); val snapshot = PrivateFixtureSnapshot(context, id)
        try {
            snapshot.capture(emptyList(), includeApplicationLocales = true)
            val changed = if (before.toLanguageTags() == "ar") "es" else "ar"
            manager.applicationLocales = android.os.LocaleList.forLanguageTags(changed)
            assertEquals(changed, manager.applicationLocales.toLanguageTags())
            PrivateFixtureSnapshot(context, id).restore()
            assertEquals(before, manager.applicationLocales); assertEquals(systemBefore, manager.systemLocales)
            assertFalse(snapshot.file.exists())
        } finally { manager.applicationLocales = before; snapshot.file.delete() }
    }

    @Test fun privateSnapshotCannotBeRestoredUnderAnotherOwner() {
        val id=UUID.randomUUID().toString(); val other=UUID.randomUUID().toString()
        val name="snapshot-owner-$id"; val prefs=context.getSharedPreferences(name,0)
        val original=PrivateFixtureSnapshot(context,id); val wrong=PrivateFixtureSnapshot(context,other)
        try {
            check(prefs.edit().putString("value","synthetic-before").commit()); original.capture(listOf(name))
            check(prefs.edit().putString("value","synthetic-after").commit())
            original.file.copyTo(wrong.file)
            assertTrue(runCatching { wrong.restore() }.isFailure)
            assertEquals("synthetic-after",prefs.getString("value",null)); assertTrue(original.file.exists())
            original.restore(); assertEquals("synthetic-before",prefs.getString("value",null)); assertFalse(original.file.exists())
        } finally { original.file.delete(); wrong.file.delete(); context.deleteSharedPreferences(name) }
    }

    @Test fun corruptPrivateSnapshotCannotChangeAnyPreference() {
        val id = UUID.randomUUID().toString(); val name = "snapshot-native-$id"; val prefs = context.getSharedPreferences(name, 0)
        val snapshot = PrivateFixtureSnapshot(context, id)
        try {
            check(prefs.edit().putString("value", "original").commit()); snapshot.capture(listOf(name))
            check(prefs.edit().putString("value", "current").commit())
            val bytes = snapshot.file.readBytes(); bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte(); snapshot.file.writeBytes(bytes)
            assertTrue(runCatching { snapshot.restore() }.isFailure); assertEquals("current", prefs.getString("value", null)); assertTrue(snapshot.file.exists())
        } finally { snapshot.file.delete(); context.deleteSharedPreferences(name) }
    }
}
