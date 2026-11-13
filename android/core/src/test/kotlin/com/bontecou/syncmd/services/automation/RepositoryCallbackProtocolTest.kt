package com.bontecou.syncmd.services.automation

import org.junit.Assert.*
import org.junit.Test
import java.net.URI
import java.net.URLEncoder

class RepositoryCallbackProtocolTest {
    private fun encoded(value: String) = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun request(action: String = "status", suffix: String = "") = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/$action?repo=Notes$suffix")

    @Test fun `parses the iOS contract preserving literal plus Unicode and caller state`() {
        val callback = "obsidian://open?vault=Notes%2B東京&state=caller%3D42#anchor"
        val r = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/push?repo=Notes+東京&message=${encoded("Commit + 東京\nsecond line")}&x-success=${encoded(callback)}&x-source=Unverified")
        assertEquals("Notes+東京", r.repository); assertEquals("Commit + 東京\nsecond line", r.message)
        assertEquals(callback, r.success); assertEquals(RepositoryAction.PUSH, r.action); assertNull(r.problem)
        assertEquals("obsidian://open", RepositoryCallbackProtocol.displayDestination(callback))
    }
    @Test fun `rejects duplicate ambiguous malformed and credential-bearing request data`() {
        val bad = listOf(
            "repo=A&repo=B", "repo=A&%72epo=B", "repo=A&token=never", "repo=%FF", "repo=%C0%AF", "repo=../outside", "repo=A%00B",
            "repo=A&message=%00", "repo=A&x-success=${encoded("https://user:secret@example.invalid/return")}",
            "repo=A&message=${"a".repeat(65537)}", "repo=A&x-success=${encoded("obsidian://open")}&x-success=${encoded("other://open")}")
        bad.forEach { query -> assertTrue("Accepted invalid query", runCatching { RepositoryCallbackProtocol.parse("syncmd://x-callback-url/push?$query") }.isFailure) }
        listOf("syncmd://auth/status?repo=A", "syncmd://user@x-callback-url/status?repo=A", "syncmd://x-callback-url:80/status?repo=A", "syncmd://x-callback-url/status?repo=A#fragment").forEach {
            assertTrue(runCatching { RepositoryCallbackProtocol.parse(it) }.isFailure)
        }
    }
    @Test fun `rejects executable local and recursive return destinations`() {
        listOf("intent://open#Intent;end", "javascript:alert(1)", "data:text/plain,hello", "file:///private", "content://provider/item",
            "syncmd://x-callback-url/push?repo=Notes", "SYNCMD://auth", "tel:123", "mailto:person@example.invalid", "https:/missing-host", "obsidian:opaque", "https://example.invalid:0/return").forEach {
            assertTrue("Unsafe return destination", runCatching { request(suffix = "&x-success=${encoded(it)}") }.isFailure)
        }
    }
    @Test fun `valid request errors remain reviewable and use the error or success fallback`() {
        val unknown = request("destroy", "&x-success=${encoded("caller://done?state=42")}")
        assertEquals(CallbackProblem.UNKNOWN_ACTION, unknown.problem)
        val missing = RepositoryCallbackProtocol.parse("syncmd://x-callback-url/pull?x-error=${encoded("caller://error")}")
        assertEquals(CallbackProblem.MISSING_REPOSITORY, missing.problem)
        val response = RepositoryCallbackProtocol.failure(unknown, unknown.problem!!)
        val destination = RepositoryCallbackProtocol.destination(unknown, response)!!
        assertTrue(destination.startsWith("caller://done?state=42&")); assertTrue(destination.contains("status=error")); assertTrue(destination.contains("action=destroy"))
    }
    @Test fun `result parameters replace forged reserved fields and preserve state and fragment`() {
        val r = request(suffix = "&x-success=${encoded("caller://done?state=a%2Bb&status=forged&sha=old#position")}")
        val response = RepositoryCallbackProtocol.response(r, RepositoryActionResult(RepositoryAction.STATUS, RepositoryActionOutcome.STATUS, "feature/東京", "abc123", 3))
        val url = RepositoryCallbackProtocol.destination(r, response)!!
        assertTrue(url.endsWith("#position")); assertTrue(url.contains("state=a%2Bb")); assertFalse(url.contains("forged")); assertFalse(url.contains("sha=old"))
        assertEquals(1, URI(url).rawQuery.split('&').count { it.startsWith("status=") }); assertTrue(url.contains("branch=feature%2F%E6%9D%B1%E4%BA%AC"))
        assertEquals("3", response.values["changes"])
    }
    @Test fun `push no changes is an error while sync no changes is successful and skipped`() {
        val push = RepositoryCallbackProtocol.response(request("push"), RepositoryActionResult(RepositoryAction.PUSH, RepositoryActionOutcome.NO_CHANGES, "main", "head", 0))
        assertFalse(push.success); assertEquals("no_changes", push.values["errorCode"])
        val sync = RepositoryCallbackProtocol.response(request("sync"), RepositoryActionResult(RepositoryAction.SYNC, RepositoryActionOutcome.NO_CHANGES, "main", "head", 0))
        assertTrue(sync.success); assertEquals("true", sync.values["push_skipped"]); assertEquals("false", sync.values["pull_updated"])
    }
    @Test fun `partial completion is an error and retains the commit and completed pull receipt`() {
        val r = request("sync", "&x-success=${encoded("caller://success")}&x-error=${encoded("caller://failure?state=42")}")
        val result = RepositoryActionResult(RepositoryAction.SYNC, RepositoryActionOutcome.LOCAL_COMMIT_SAVED, "main", "retained", null, RepositoryActionProblem.CONNECTION, true)
        val response = RepositoryCallbackProtocol.response(r, result)
        assertFalse(response.success); assertEquals("retained", response.values["sha"]); assertEquals("true", response.values["commit_saved"])
        assertEquals("true", response.values["pull_updated"])
        assertTrue(RepositoryCallbackProtocol.destination(r, response)!!.startsWith("caller://failure?state=42&"))
        assertFalse(response.values.values.any { it.contains("/data/") })
    }
    @Test fun `pull attention reports the completed update without claiming success`() {
        val r = request("pull")
        val response = RepositoryCallbackProtocol.response(r, RepositoryActionResult(RepositoryAction.PULL, RepositoryActionOutcome.ATTENTION, "main", "new-head", 0, pullUpdated = true))
        assertFalse(response.success); assertEquals("true", response.values["updated"]); assertEquals("new-head", response.values["sha"])
        assertNull(RepositoryCallbackProtocol.destination(r, response))
    }
}
