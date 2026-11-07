package com.bontecou.syncmd

import android.content.*
import androidx.lifecycle.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.domain.repository.GitRepository
import com.bontecou.syncmd.security.RepositoryCredentialStore
import com.bontecou.syncmd.services.git.*
import com.bontecou.syncmd.services.repositories.*
import com.bontecou.syncmd.storage.clonePublications
import com.bontecou.syncmd.ui.viewmodels.SettingsViewModel
import kotlinx.coroutines.*
import org.eclipse.jgit.api.Git
import java.io.File
import java.security.MessageDigest
import java.util.UUID

/** Faults affect only this fixture's catalog and private files, never production fault hooks. */
internal class CloneCatalogFixture(id: String = UUID.randomUUID().toString(), cold: Boolean = false) {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val app get() = instrumentation.targetContext
    private val prefix = (if (cold) "clone-catalog-cold-" else "clone-catalog-provenance-") + id
    val workspace = File(app.filesDir, prefix)
    private val names = mutableSetOf<String>()
    private val models = mutableListOf<ViewModel>()
    private val readOnly = mutableListOf<File>()
    @Volatile var failNextCommit = false
    @Volatile var afterCommit: (() -> Unit)? = null
    val context = object : ContextWrapper(app) {
        override fun getFilesDir() = workspace
        override fun getNoBackupFilesDir() = File(workspace, "no-backup").apply { mkdirs() }
        override fun getExternalMediaDirs(): Array<File> = emptyArray()
        override fun getExternalFilesDirs(type: String?): Array<File> = emptyArray()
        override fun getExternalFilesDir(type: String?): File? = null
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val actual = app.getSharedPreferences("$prefix-$name".also(names::add), mode)
            if (name != "sync_md_prefs") return actual
            return object : SharedPreferences by actual {
                override fun edit(): SharedPreferences.Editor {
                    val editor = actual.edit()
                    return object : SharedPreferences.Editor by editor {
                        override fun putString(key: String, value: String?) = apply { editor.putString(key, value) }
                        override fun putStringSet(key: String, value: MutableSet<String>?) = apply { editor.putStringSet(key, value) }
                        override fun putInt(key: String, value: Int) = apply { editor.putInt(key, value) }
                        override fun putLong(key: String, value: Long) = apply { editor.putLong(key, value) }
                        override fun putFloat(key: String, value: Float) = apply { editor.putFloat(key, value) }
                        override fun putBoolean(key: String, value: Boolean) = apply { editor.putBoolean(key, value) }
                        override fun remove(key: String) = apply { editor.remove(key) }
                        override fun clear() = apply { editor.clear() }
                        override fun commit(): Boolean {
                            if (failNextCommit) { failNextCommit = false; return false }
                            return editor.commit().also { success -> if (success) afterCommit?.let { action -> afterCommit = null; action() } }
                        }
                    }
                }
            }
        }
    }
    val prefs get() = context.getSharedPreferences("sync_md_prefs", 0)
    val ledger get() = clonePublications(context)
    val credentials get() = RepositoryCredentialStore(context)
    fun create() { require(!workspace.exists()); check(workspace.mkdirs()) }
    fun <T> main(action: () -> T): T { var value: Result<T>? = null; instrumentation.runOnMainSync { value = runCatching(action) }; return requireNotNull(value).getOrThrow() }
    suspend fun settings(): SettingsViewModel {
        val model = main { SettingsViewModel(context).also(models::add) }
        val jobs = main { model.viewModelScope.coroutineContext[Job]!!.children.toList() }
        withTimeout(15_000) { jobs.forEach { it.join() } }
        return model
    }
    suspend fun await(model: SettingsViewModel, condition: () -> Boolean) {
        withTimeout(15_000) { while (!main { condition() && !model.isLoading.value && !model.destructiveBusy.value }) delay(10) }
    }
    suspend fun prepare(name: String = "Notes", destination: String? = null): ClonePublication {
        val source = SampleRepository.openOrCreate(File(workspace, "source"))
        val target = destination ?: File(workspace, "repos/${UUID.randomUUID()}").canonicalPath
        val remote = "https://github.com/fixture/provenance.git"
        credentials.save(target, remote, Credentials.None)
        val local = LocalGitRepository()
        val backend = object : GitRepository by local {
            override suspend fun clone(url: String, path: String, creds: Credentials): Result<Unit> =
                local.clone(File(source.parentFile, "sample-remote.git").toURI().toString(), path, Credentials.None).onSuccess {
                    Git.open(File(path)).use { it.repository.config.apply { setString("remote", "origin", "url", url); save() } }
                }
        }
        var record: ClonePublication? = null
        SafeCloneService(backend).clone(remote, target, Credentials.None, beforePublication = { stage ->
            File(stage, "draft.md").writeText("Retain this local draft\n")
            record = ledger.reserve(UUID.randomUUID().toString(), stage, target, credentials.recordVersion(target), name, "fixture/provenance")
        }).getOrThrow()
        return requireNotNull(record)
    }
    fun failAcknowledgement(record: ClonePublication) {
        afterCommit = {
            val directory = File(context.noBackupFilesDir, "clone-publications/.write-${record.id}.tmp")
            check(!directory.exists() && directory.mkdir())
            File(directory, "fault.marker").writeText("Synthetic acknowledgement fault")
        }
    }
    fun repairAcknowledgement(record: ClonePublication) {
        val directory = File(context.noBackupFilesDir, "clone-publications/.write-${record.id}.tmp")
        if (!directory.exists()) return
        check(!java.nio.file.Files.isSymbolicLink(directory.toPath()) && directory.isDirectory)
        check(directory.listFiles()!!.map { it.name } == listOf("fault.marker"))
        val marker = File(directory, "fault.marker"); check(marker.readText() == "Synthetic acknowledgement fault")
        check(marker.delete() && directory.delete())
    }
    fun failPrivateDeletionAfterRevocation() {
        val directory = File(context.noBackupFilesDir, "clone-publications")
        afterCommit = { check(directory.setWritable(false, true)); readOnly += directory }
    }
    fun repairPermissions() { readOnly.forEach { check(it.setWritable(true, true)) }; readOnly.clear() }
    fun receipt(record: ClonePublication) = CloneCatalogProvenance.decode(CloneCatalogProvenance.key(record.id), prefs.all[CloneCatalogProvenance.key(record.id)])
    fun hash(file: File) = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }
    suspend fun closeModels() {
        repairPermissions(); afterCommit = null; failNextCommit = false
        val jobs = main { models.flatMap { it.viewModelScope.coroutineContext[Job]!!.children.toList() }.also {
            ViewModelStore().apply { models.forEachIndexed { i, vm -> put("model-$i", vm) }; clear() }; models.clear()
        } }
        withTimeout(15_000) { jobs.forEach { it.join() } }
    }
    suspend fun clean() {
        closeModels(); GitTrackingRemoval.deleteWithoutFollowingLinks(workspace); names.forEach { app.deleteSharedPreferences(it) }
    }
}
