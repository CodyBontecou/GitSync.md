package com.bontecou.syncmd.services.repositories

import com.bontecou.syncmd.data.models.Credentials
import com.bontecou.syncmd.services.git.*
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.eclipse.jgit.api.Git
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class RemoteCredentialMigrationTest {
    @get:Rule val temporary=TemporaryFolder()
    private val service=RemoteCredentialMigration()
    private fun fixture()=SampleRepository.openOrCreate(temporary.newFolder())
    private fun configure(root:File,fetch:List<String>,push:List<String> = emptyList())=Git.open(root).use { git ->
        git.repository.config.apply { setStringList("remote","origin","url",fetch);setStringList("remote","origin","pushurl",push);save() }
    }
    @Test fun `HTTP URL credentials migrate without changing scheme index or HEAD`() = runBlocking {
        val root=fixture();configure(root,listOf("http://writer:fixture-secret@git.example:8080/team/notes.git"))
        val index=File(root,".git/index").readBytes();val head=File(root,".git/HEAD").readBytes()
        val plan=service.inspect(root.path)
        assertThat(plan.canMigrate).isTrue();assertThat(plan.toString()).doesNotContain("fixture-secret")
        service.migrate(plan) { bindings,_,publish ->
            assertThat(bindings.single().url).isEqualTo("http://git.example:8080/team/notes.git")
            assertThat(bindings.single().credentials()).isEqualTo(Credentials.Basic("writer","fixture-secret"));publish()
        }
        Git.open(root).use { assertThat(it.repository.config.getString("remote","origin","url")).isEqualTo("http://git.example:8080/team/notes.git") }
        assertThat(File(root,".git/index").readBytes()).isEqualTo(index);assertThat(File(root,".git/HEAD").readBytes()).isEqualTo(head)
    }
    @Test fun `review never exposes secrets and migration retains distinct addresses and all Git data`() = runBlocking {
        val root=fixture();configure(root,listOf("https://reader:fetch-secret@git.example/team/notes.git"),listOf("https://writer:push-secret@push.example/team/notes.git"))
        Git.open(root).use { git ->
            git.repository.config.apply { setString("remote","backup","url","git@backup.example:group/notes.git");setString("custom",null,"keep","unchanged");save() }
            val head=git.repository.resolve("HEAD");val index=File(root,".git/index").readBytes();val contents=File(root,"notes.md").readBytes()
            val plan=service.inspect(root.path)
            assertThat(plan.canMigrate).isTrue();assertThat(plan.toString()).doesNotContain("secret");assertThat(plan.toString()).doesNotContain("reader:")
            val captured=mutableMapOf<String,Credentials.Basic>()
            service.migrate(plan) { bindings,_,publish ->
                bindings.forEach { captured[it.url]=it.credentials();assertThat(it.toString()).doesNotContain("secret") }
                assertThat(File(root,".git/config.lock").readText()).doesNotContain("secret")
                publish()
            }
            assertThat(captured).containsExactly("https://git.example/team/notes.git",Credentials.Basic("reader","fetch-secret"),"https://push.example/team/notes.git",Credentials.Basic("writer","push-secret"))
            git.repository.config.load()
            assertThat(git.repository.config.getString("remote","origin","url")).isEqualTo("https://git.example/team/notes.git")
            assertThat(git.repository.config.getString("remote","origin","pushurl")).isEqualTo("https://push.example/team/notes.git")
            assertThat(git.repository.config.getString("remote","backup","url")).isEqualTo("git@backup.example:group/notes.git")
            assertThat(git.repository.config.getString("custom",null,"keep")).isEqualTo("unchanged")
            assertThat(git.repository.resolve("HEAD")).isEqualTo(head);assertThat(File(root,".git/index").readBytes()).isEqualTo(index)
            assertThat(File(root,"notes.md").readBytes()).isEqualTo(contents);assertThat(service.inspect(root.path).entries).isEmpty()
        }
    }
    @Test fun `URL order percent escapes plus signs and unicode credentials retain exact meaning`() = runBlocking {
        val root=fixture();configure(root,listOf("https://user+name:p%3Ass+%E5%AF%86@git.example/team/a%2Fb.git","https://user+name:p%3Ass+%E5%AF%86@git.example/team/a%2Fb.git"))
        val plan=service.inspect(root.path);assertThat(plan.primaryUrl).isEqualTo("https://git.example/team/a%2Fb.git")
        service.migrate(plan) { bindings,_,publish ->
            assertThat(bindings).hasSize(1);assertThat(bindings.single().credentials()).isEqualTo(Credentials.Basic("user+name","p:ss+密"));publish()
        }
        Git.open(root).use { assertThat(it.repository.config.getStringList("remote","origin","url").toList()).containsExactly("https://git.example/team/a%2Fb.git","https://git.example/team/a%2Fb.git").inOrder() }
        assertThat(RepositoryDiscovery.redactRemoteUrl("https://user:secret@git.example/team/a%2Fb.git?token=secret")).isEqualTo("https://git.example/team/a%2Fb.git")
    }
    @Test fun `conflicting credentials for one normalized endpoint block all changes`() = runBlocking {
        val root=fixture();configure(root,listOf("https://a:first@git.example/team/notes.git"),listOf("https://b:second@git.example/team/notes.git"))
        val before=File(root,".git/config").readBytes();val plan=service.inspect(root.path)
        assertThat(plan.canMigrate).isFalse();assertThat(plan.toString()).doesNotContain("first");assertThat(plan.toString()).doesNotContain("second")
        assertThat(runCatching { service.migrate(plan) { _,_,_->error("Must not run") } }.isFailure).isTrue()
        assertThat(File(root,".git/config").readBytes()).isEqualTo(before)
    }
    @Test fun `incomplete insecure query SSH password and malformed UTF8 credentials need explicit review`() = runBlocking {
        for(url in listOf("https://token@git.example/team/notes.git","https://a:secret@git.example/team/notes.git?token=secret","ssh://a:secret@git.example/team/notes.git","https://a:%FF@git.example/team/notes.git")) {
            val root=fixture();configure(root,listOf(url));val before=File(root,".git/config").readBytes();val plan=service.inspect(root.path)
            assertThat(plan.canMigrate).isFalse();assertThat(plan.problems).isNotEmpty();assertThat(plan.toString()).doesNotContain("secret");assertThat(plan.toString()).doesNotContain("token@")
            assertThat(File(root,".git/config").readBytes()).isEqualTo(before)
        }
    }
    @Test fun `stale configuration or existing Git lock never reaches secure persistence`() = runBlocking {
        val root=fixture();configure(root,listOf("https://a:secret@git.example/team/notes.git"));val plan=service.inspect(root.path)
        File(root,".git/config").appendText("\n# external edit\n");var called=false
        assertThat(runCatching { service.migrate(plan) { _,_,_->called=true } }.isFailure).isTrue();assertThat(called).isFalse()
        val fresh=service.inspect(root.path);File(root,".git/config.lock").writeText("External lock")
        assertThat(runCatching { service.migrate(fresh) { _,_,_->called=true } }.isFailure).isTrue();assertThat(called).isFalse()
        assertThat(File(root,".git/config.lock").readText()).isEqualTo("External lock")
    }
    @Test fun `failed secure persistence or changed config during callback leaves original data available`() = runBlocking {
        val root=fixture();configure(root,listOf("https://a:secret@git.example/team/notes.git"));val plan=service.inspect(root.path)
        val before=File(root,".git/config").readBytes()
        assertThat(runCatching { service.migrate(plan) { _,_,_->error("Storage unavailable") } }.isFailure).isTrue()
        assertThat(File(root,".git/config").readBytes()).isEqualTo(before);assertThat(File(root,".git/config.lock").exists()).isFalse()
        assertThat(runCatching { service.migrate(plan) { _,_,publish->File(root,".git/config").appendText("\n# changed\n");publish() } }.isFailure).isTrue()
        assertThat(File(root,".git/config").readText()).contains("# changed");assertThat(File(root,".git/config").readText()).contains("a:secret@")
    }
    @Test fun `transport rejects embedded and malformed URL credentials before invoking resolvers`() {
        val root=fixture()
        for(url in listOf("HTTPS://a:secret@git.example/team/notes.git","https://a:secret@git.example/team/notes.git","https://a:secret@git.example/team/notes.git?token=x","https://a:%ZZ@git.example/team/notes.git","ssh://a:secret@git.example/team/notes.git")) {
            var called=false;configure(root,listOf(url))
            Git.open(root).use { git ->
                val error=runCatching { RemoteAuthentication.provider(git.repository,"origin",resolver={_,_->called=true;Credentials.None}) }.exceptionOrNull()
                assertThat(error).isNotNull();assertThat(error!!.message).doesNotContain("secret");assertThat(called).isFalse()
                assertThat(runCatching { ScopedCredentialsProvider(url,Credentials.None) }.isFailure).isTrue()
            }
        }
    }
    @Test fun `ordinary local and clean SSH addresses remain usable and clean config needs no migration`() = runBlocking {
        val root=fixture();assertThat(service.inspect(root.path).entries).isEmpty()
        RemoteAuthentication.requireSafeAddress(File(root.parentFile,"sample-remote.git").toURI().toString())
        RemoteAuthentication.requireSafeAddress("git@git.example:team/notes.git")
        RemoteAuthentication.requireSafeAddress("ssh://git@git.example:2222/team/notes.git")
    }
    @Test fun `redaction hides uppercase HTTPS and unsupported credential userinfo without changing escaped paths`() = runBlocking {
        assertThat(RepositoryDiscovery.redactRemoteUrl("HTTPS://raw-user-secret:password@git.example/team/a%2Fb.git?token=private"))
            .isEqualTo("HTTPS://git.example/team/a%2Fb.git")
        for(url in listOf("ftp://raw-user-secret:password@git.example/team/repo.git","raw-user-secret:password@git.example:team/repo.git"))
            assertThat(RepositoryDiscovery.redactRemoteUrl(url).orEmpty()).doesNotContain("secret")
        assertThat(RepositoryDiscovery.redactRemoteUrl("file:///tmp/reviewed%20repo.git")).isEqualTo("file:/tmp/reviewed%20repo.git")
        val root=fixture();configure(root,listOf("https://a:secret@git.example/team/repo.git"),listOf("raw-user:password@git.example:team/repo.git"))
        assertThat(service.inspect(root.path).canMigrate).isFalse()
    }

}
