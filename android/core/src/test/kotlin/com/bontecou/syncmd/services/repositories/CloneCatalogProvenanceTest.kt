package com.bontecou.syncmd.services.repositories

import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

class CloneCatalogProvenanceTest {
    private val id = UUID.randomUUID().toString()
    private val completed = CompletedClone("/tmp/provenance-notes", "root-identity", "git-identity", "https://example.invalid/notes.git", "main", "encrypted-revision")
    @Test fun catalogAndRegistrationArePlannedInTheSameCommitAndExactRetryIsIdempotent() {
        val receipt = CloneCatalogProvenance.registration(id, completed, 17)
        val first = CloneCatalogProvenance.plan(emptyMap<String, Any>(), mapOf(completed.path to 17L), setOf(id), receipt)
        assertEquals(mapOf(receipt), first)
        CloneCatalogProvenance.requireAllowed(first, id, completed, 17)
        assertEquals(first, CloneCatalogProvenance.plan(first, mapOf(completed.path to 17L), setOf(id), receipt))
        assertFalse(receipt.second.contains("encrypted-revision")); assertFalse(receipt.second.contains("https://example.invalid"))
    }
    @Test fun removalAndReplacementRevokeEvenBeforeThePrivateFileAcknowledgesRegistration() {
        val registration = CloneCatalogProvenance.registration(id, completed, 17)
        val raw = mapOf(registration)
        for (catalog in listOf(emptyMap(), mapOf(completed.path to 18L), mapOf("/tmp/moved-notes" to 17L))) {
            val edits = CloneCatalogProvenance.plan(raw, catalog, setOf(id))
            assertTrue(CloneCatalogProvenance.decode(registration.first, edits[registration.first])!!.revoked)
            assertTrue(runCatching { CloneCatalogProvenance.requireAllowed(edits, id, completed, null) }.isFailure)
            assertTrue(runCatching { CloneCatalogProvenance.plan(edits, mapOf(completed.path to 17L), setOf(id), registration) }.isFailure)
        }
    }
    @Test fun explicitDismissalCanBeRecordedBeforeAnyCatalogEntryWithoutGrantingAuthority() {
        val revoked = CloneCatalogProvenance.revocation(id, completed, null)
        val edits = CloneCatalogProvenance.plan(emptyMap<String, Any>(), emptyMap(), setOf(id), revoked)
        assertTrue(CloneCatalogProvenance.decode(revoked.first, edits[revoked.first])!!.revoked)
        assertTrue(runCatching { CloneCatalogProvenance.requireAllowed(edits, id, completed, null) }.isFailure)
        assertTrue(runCatching { CloneCatalogProvenance.plan(edits, mapOf(completed.path to 17L), setOf(id), CloneCatalogProvenance.registration(id, completed, 17)) }.isFailure)
    }
    @Test fun staleFolderOrDateCannotReuseAnActiveReceiptButAFreshPublicationCanRegister() {
        val old = mapOf(CloneCatalogProvenance.registration(id, completed, 17))
        assertTrue(runCatching { CloneCatalogProvenance.requireAllowed(old, id, completed.copy(rootIdentity = "replacement"), 17) }.isFailure)
        assertTrue(runCatching { CloneCatalogProvenance.requireAllowed(old, id, completed, 18) }.isFailure)
        val fresh = UUID.randomUUID().toString()
        val new = CloneCatalogProvenance.registration(fresh, completed.copy(rootIdentity = "new-clone"), 18)
        assertEquals(new.second, CloneCatalogProvenance.plan(old, mapOf(completed.path to 18L), setOf(id, fresh), new)[new.first])
    }
    @Test fun unrelatedCatalogWorkSurvivesCorruptReceiptsWithoutTreatingThemAsConsent() {
        val key = CloneCatalogProvenance.key(id)
        for (bad in listOf<Any>("{partial", true, "{}")) {
            val raw = mapOf(key to bad)
            assertTrue(CloneCatalogProvenance.plan(raw, emptyMap(), setOf(id)).isEmpty())
            assertTrue(runCatching { CloneCatalogProvenance.requireAllowed(raw, id, completed, 17) }.isFailure)
            assertTrue(runCatching { CloneCatalogProvenance.plan(raw, mapOf(completed.path to 17L), setOf(id), CloneCatalogProvenance.registration(id, completed, 17)) }.isFailure)
            val revoke = CloneCatalogProvenance.revocation(id, completed, null)
            assertTrue(CloneCatalogProvenance.decode(key, CloneCatalogProvenance.plan(raw, emptyMap(), setOf(id), revoke)[key])!!.revoked)
        }
    }
    @Test fun metadataIsPrunedOnlyWhenItsPrivatePublicationHasGoneAway() {
        val receipt = CloneCatalogProvenance.revocation(id, completed, null); val raw = mapOf(receipt)
        assertTrue(CloneCatalogProvenance.plan(raw, emptyMap(), setOf(id)).isEmpty())
        assertEquals(mapOf(receipt.first to null), CloneCatalogProvenance.plan(raw, emptyMap(), emptySet()))
        assertTrue(CloneCatalogProvenance.plan(raw, emptyMap(), null).isEmpty())
        assertTrue(runCatching { CloneCatalogProvenance.plan(emptyMap<String, Any>(), mapOf(completed.path to 17L), null, CloneCatalogProvenance.registration(id, completed, 17)) }.isFailure)
    }
    @Test fun revocationAndPruningClearAbandonedPendingMarkersButRetainANewActiveScope() {
        val receipt = CloneCatalogProvenance.registration(id, completed, 17)
        val pending = CloneCatalogProvenance.PENDING_PREFIX + CloneCatalogProvenance.fingerprint(completed)
        val raw = mapOf(receipt, pending to "pending")
        val removed = CloneCatalogProvenance.plan(raw, emptyMap(), setOf(id))
        assertTrue(pending in removed); assertNull(removed[pending])
        val pruned = CloneCatalogProvenance.plan(raw, mapOf(completed.path to 17L), emptySet())
        assertTrue(pending in pruned); assertNull(pruned[pending])
        val fresh = UUID.randomUUID().toString()
        val next = CloneCatalogProvenance.registration(fresh, completed, 18)
        val changed = CloneCatalogProvenance.plan(raw, mapOf(completed.path to 18L), setOf(fresh), next)
        assertFalse(pending in changed); assertEquals(next.second, changed[next.first])
    }
    @Test fun malformedAndNoncanonicalPayloadsFailClosed() {
        val receipt = CloneCatalogProvenance.registration(id, completed, 17)
        assertNull(CloneCatalogProvenance.decode(receipt.first, receipt.second + " "))
        assertNull(CloneCatalogProvenance.decode(CloneCatalogProvenance.key(UUID.randomUUID().toString()), receipt.second))
        assertNull(CloneCatalogProvenance.decode(receipt.first, receipt.second.replace("\"revoked\":false", "\"revoked\":\"false\"")))
        assertNull(CloneCatalogProvenance.decode(receipt.first, "x".repeat(16385)))
    }
}
