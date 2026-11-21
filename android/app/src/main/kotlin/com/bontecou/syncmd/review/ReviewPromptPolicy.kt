package com.bontecou.syncmd.review

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The small key-value store the policy keeps its counts in (SharedPreferences in the app, a map in tests). */
interface ReviewPromptStore {
    fun getInt(key: String): Int
    fun getLong(key: String): Long?
    fun getString(key: String): String?
    /** Writes every entry together; a null value removes the key. */
    fun put(values: Map<String, Any?>)
}

/**
 * When GitSync.md may ask for a Google Play rating.
 *
 * Successful syncs are counted (foreground and background alike). Once the person has synced at least
 * [MIN_SUCCESSES] times on [MIN_DISTINCT_DAYS] different local days, the next good foreground moment may
 * show Play's in-app review flow. After a prompt, a later app version may ask again once [COOLDOWN_DAYS]
 * have passed. Counts are lifetime: they are not reset after a prompt.
 *
 * The constants must match Sync.md/Models/ReviewPromptPolicy.swift (the iOS policy); change both together.
 */
class ReviewPromptPolicy(
    private val store: ReviewPromptStore,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {
    companion object {
        const val MIN_SUCCESSES = 5
        const val MIN_DISTINCT_DAYS = 3
        /** Days between prompts; the version must have changed too. */
        const val COOLDOWN_DAYS = 120L
        /** One sync can report a pull and a push; successes this close count once. */
        const val SUCCESS_SPACING_SECONDS = 60L
        /** No prompt this soon after the unlock purchase. */
        const val PURCHASE_QUIET_PERIOD_HOURS = 24L

        private const val COOLDOWN_MILLIS = COOLDOWN_DAYS * 24 * 60 * 60 * 1000
        private const val SPACING_MILLIS = SUCCESS_SPACING_SECONDS * 1000
        private const val QUIET_MILLIS = PURCHASE_QUIET_PERIOD_HOURS * 60 * 60 * 1000

        private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    }

    /** Same names as the iOS UserDefaults keys. */
    object Key {
        const val SUCCESS_COUNT = "reviewPrompt.successCount"
        const val DISTINCT_DAYS = "reviewPrompt.distinctDays"
        const val LAST_SUCCESS_DAY = "reviewPrompt.lastSuccessDay"
        const val LAST_COUNTED_AT = "reviewPrompt.lastCountedAt"
        const val LAST_PROMPT_AT = "reviewPrompt.lastPromptAt"
        const val LAST_PROMPT_VERSION = "reviewPrompt.lastPromptVersion"
        const val LAST_PURCHASE_AT = "reviewPrompt.lastPurchaseAt"
    }

    val successCount: Int get() = store.getInt(Key.SUCCESS_COUNT)
    val distinctDays: Int get() = store.getInt(Key.DISTINCT_DAYS)
    val lastPromptAt: Instant? get() = store.getLong(Key.LAST_PROMPT_AT)?.let(Instant::ofEpochMilli)
    val lastPromptVersion: String? get() = store.getString(Key.LAST_PROMPT_VERSION)

    /**
     * Counts one successful sync. Returns false when it fell inside [SUCCESS_SPACING_SECONDS] of the
     * previously counted one and was not counted.
     */
    @Synchronized
    fun recordSuccess(now: Instant): Boolean {
        val millis = now.toEpochMilli()
        val last = store.getLong(Key.LAST_COUNTED_AT)
        if (last != null && millis >= last && millis - last < SPACING_MILLIS) return false
        val day = DAY.format(now.atZone(zone()))
        val values = mutableMapOf<String, Any?>(
            Key.LAST_COUNTED_AT to millis,
            Key.SUCCESS_COUNT to successCount + 1,
        )
        if (store.getString(Key.LAST_SUCCESS_DAY) != day) {
            values[Key.LAST_SUCCESS_DAY] = day
            values[Key.DISTINCT_DAYS] = distinctDays + 1
        }
        store.put(values)
        return true
    }

    @Synchronized
    fun notePurchase(now: Instant) = store.put(mapOf(Key.LAST_PURCHASE_AT to now.toEpochMilli()))

    @Synchronized
    fun isEligible(now: Instant, appVersion: String): Boolean {
        if (successCount < MIN_SUCCESSES || distinctDays < MIN_DISTINCT_DAYS) return false
        val millis = now.toEpochMilli()
        val purchase = store.getLong(Key.LAST_PURCHASE_AT)
        if (purchase != null && millis - purchase < QUIET_MILLIS) return false
        val prompted = store.getLong(Key.LAST_PROMPT_AT) ?: return true
        if (lastPromptVersion == appVersion) return false
        return millis - prompted >= COOLDOWN_MILLIS
    }

    @Synchronized
    fun markPrompted(now: Instant, appVersion: String) =
        store.put(mapOf(Key.LAST_PROMPT_AT to now.toEpochMilli(), Key.LAST_PROMPT_VERSION to appVersion))
}

/** The foreground moments that may show the rating prompt (same cases as iOS `ReviewPromptMoment`). */
enum class ReviewPromptMoment {
    /** A pull, push or sync the person started in the app succeeded. */
    USER_SYNC,
    /** The person dismissed a healthy background-sync summary. Android has no such summary today. */
    BACKGROUND_SUMMARY_DISMISSED,
    /** A merge or rebase conflict was resolved and pushed with nothing left conflicting. */
    CONFLICT_RESOLVED,
}
