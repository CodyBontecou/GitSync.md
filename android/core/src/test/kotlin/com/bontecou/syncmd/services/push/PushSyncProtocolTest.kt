package com.bontecou.syncmd.services.push

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*

class PushSyncProtocolTest {
    private val state = "s".repeat(40)
    private val hint = mapOf("type" to "gitsync.push.v1", "repo" to "Acme/Notes", "branch" to "notes/東京", "hint" to "delivery:123", "head" to "A".repeat(40))
    @Test fun hintsPreserveBranchButNormalizeRepositoryAndTreatHeadAsMetadata() {
        val parsed = PushSyncProtocol.hint(hint)
        assertEquals("acme/notes", parsed.repository); assertEquals("notes/東京", parsed.branch); assertEquals("a".repeat(40), parsed.head)
        assertEquals(parsed, PushSyncProtocol.hint(parsed.data()))
    }
    @Test fun malformedOrOversizedHintsCannotChooseRepositoriesOrRefs() {
        listOf(hint + ("type" to "other"), hint + ("repo" to "../notes"), hint + ("repo" to "acme/notes/extra"), hint + ("branch" to "../HEAD"),
            hint + ("branch" to "a\nmain"), hint + ("branch" to "東".repeat(100)), hint + ("head" to "not-a-sha"), hint + ("hint" to "a".repeat(129))).forEach {
            assertTrue(runCatching { PushSyncProtocol.hint(it) }.isFailure)
        }
    }
    @Test fun enrollmentAcceptsOnlyOrdinaryGithubRemotesWithoutEmbeddedHttpsSecrets() {
        for (remote in listOf("https://github.com/Acme/Notes.git", "git@github.com:Acme/Notes.git", "ssh://git@ssh.github.com:443/Acme/Notes.git"))
            assertEquals("acme/notes", PushSyncProtocol.repositoryForRemote(remote))
        for (remote in listOf("https://user:secret@github.com/a/b.git", "https://github.com.evil/a/b", "file:///a/b", "https://github.com/a/b?token=x", "https://github.com/a/../b", "http://github.com/a/b"))
            assertNull(PushSyncProtocol.repositoryForRemote(remote))
    }
    @Test fun androidRegistrationIsExplicitBoundedAndDeterministic() {
        val raw = PushSyncProtocol.registration("synthetic-token:" + "a".repeat(80), "synthetic-device-secret", listOf("Acme/Notes", "acme/notes", "other/repo"), true)
        val json = JsonParser.parseString(raw).asJsonObject
        assertEquals("android", json["platform"].asString); assertEquals("development", json["environment"].asString)
        assertEquals(listOf("acme/notes", "other/repo"), json["repos"].asJsonArray.map { it.asString })
        assertTrue(runCatching { PushSyncProtocol.registration("invalid token", "synthetic-device-secret", emptyList(), false) }.isFailure)
    }
    @Test fun githubAppStartAndCallbackRequireTheExactStateAndOrigins() {
        val raw = "{\"ok\":true,\"state\":\"$state\",\"url\":\"https://github.com/apps/gitsync-md-push-sync/installations/new?state=$state\"}"
        assertEquals(state, PushSyncProtocol.linkStart(raw).state)
        assertNull(PushSyncProtocol.callback("syncmd://github-app?state=$state&result=connected", state))
        assertEquals("account_owner_required", PushSyncProtocol.callback("syncmd://github-app?state=$state&result=error&error=account_owner_required", state))
        for (callback in listOf("syncmd://github-app?state=$state&state=$state&result=connected", "syncmd://github-app?state=other&result=connected",
            "syncmd://github-app?state=$state&result=connected&error=cancelled", "syncmd://github-app/path?state=$state&result=connected"))
            assertTrue(runCatching { PushSyncProtocol.callback(callback, state) }.isFailure)
        assertTrue(runCatching { PushSyncProtocol.linkStart(raw.replace("github.com", "github.com.evil")) }.isFailure)
    }
    private fun installationNumber(id:String="101",connected:String="1",urlId:String="101") = """{"ok":true,"installations":[{"id":$id,"accountLogin":"acme","accountType":"Organization","repositorySelection":"selected","htmlURL":"https://github.com/organizations/acme/settings/installations/$urlId","status":"active","connectedAt":$connected}]}"""
    @Test fun existingInstallationRequiresPinnedPkceOAuthAndAnOrdinaryAccountName() {
        assertEquals("CodyBontecou", PushSyncProtocol.existingAccount(" @CodyBontecou "))
        for (login in listOf("", "a/b", "../owner", "owner?secret=x", "owner@example.com", "--owner"))
            assertTrue(runCatching { PushSyncProtocol.existingAccount(login) }.isFailure)
        val url = "https://github.com/login/oauth/authorize?client_id=Iv1.test-client&redirect_uri=https%3A%2F%2Fsyncmd-push.costream.workers.dev%2Fv1%2Fgithub-app%2Foauth%2Fcallback&state=$state&code_challenge=${"c".repeat(43)}&code_challenge_method=S256&prompt=select_account"
        fun response(value: String) = com.google.gson.JsonObject().apply {
            addProperty("ok", true); addProperty("state", state); addProperty("url", value)
        }.toString()
        assertEquals(url, PushSyncProtocol.linkStart(response(url), existingInstallation = true).url)
        assertTrue(runCatching { PushSyncProtocol.linkStart(response(url)) }.isFailure)
        for (invalid in listOf(
            url.replace("github.com", "github.com.evil"), url.replace("S256", "plain"),
            url.replace("code_challenge=${"c".repeat(43)}", "code_challenge=short"),
            url.replace("syncmd-push.costream.workers.dev", "other.example"),
            "$url&scope=offline_access", "$url&state=$state", url.replace("client_id=Iv1.test-client", "client_id="),
            url.replace("state=$state", "state=wrong"), "$url#fragment",
        )) assertTrue(invalid, runCatching { PushSyncProtocol.linkStart(response(invalid), existingInstallation = true) }.isFailure)
        assertTrue(runCatching { PushSyncProtocol.linkStart(response("https://github.com/apps/gitsync-md-push-sync/installations/new?state=$state"), existingInstallation = true) }.isFailure)
    }
    @Test fun installationNumbersRetainTheFullLongRangeAndExactIntegralExponents() {
        val limit=Long.MAX_VALUE.toString()
        val maximum=PushSyncProtocol.installations(installationNumber(limit,limit,limit)).single()
        assertEquals(Long.MAX_VALUE,maximum.id);assertEquals(Long.MAX_VALUE,maximum.connectedAt)
        val scientific=PushSyncProtocol.installations(installationNumber("1.01e2","1e3")).single()
        assertEquals(101L,scientific.id);assertEquals(1000L,scientific.connectedAt)
        assertEquals(0L,PushSyncProtocol.installations(installationNumber(connected="-0.0")).single().connectedAt)
    }
    @Test fun installationNumbersRejectFractionsCoercionAndWrappingInEitherField() {
        for(value in listOf("101.25","9223372036854775808","-9223372036854775809","18446744073709551717","1e20","\"101\"","null","true")) {
            assertTrue("Invalid installation ID: $value",runCatching { PushSyncProtocol.installations(installationNumber(id=value)) }.isFailure)
            assertTrue("Invalid connection timestamp: $value",runCatching { PushSyncProtocol.installations(installationNumber(connected=value)) }.isFailure)
        }
    }
    @Test fun installationsRequireUniqueImmutableIdsAndGithubManagementUrls() {
        val raw = """{"ok":true,"installations":[{"id":101,"accountLogin":"acme","accountType":"Organization","repositorySelection":"selected","htmlURL":"https://github.com/organizations/acme/settings/installations/101","status":"active","connectedAt":1}]}"""
        assertEquals("acme", PushSyncProtocol.installations(raw).single().login)
        assertTrue(runCatching { PushSyncProtocol.installations(raw.replace("github.com", "evil.example")) }.isFailure)
        assertTrue(runCatching { PushSyncProtocol.installations(raw.replace("\"id\":101", "\"id\":\"101\"")) }.isFailure)
    }
}
