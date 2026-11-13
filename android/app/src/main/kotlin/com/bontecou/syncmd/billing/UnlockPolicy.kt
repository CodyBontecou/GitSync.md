package com.bontecou.syncmd.billing

/** Why this install may add vaults. Mirrors iOS `AppUnlock.Reason`; absent means locked. */
enum class UnlockReason { PURCHASED, EXISTING_VAULTS, PAYWALL_DISABLED }

object UnlockProducts {
    /** The one-time product sold on the free listing. */
    const val FULL_ACCESS = "full_access"

    /** Every unlock product this listing has ever sold; owning any of them unlocks the app. */
    val OWNED = setOf(FULL_ACCESS, "unlock-full-version", "com.bontecou.syncmd.unlock")
}

/**
 * The next reason after Google Play reports the products this account owns.
 * [owned] is null when Play could not be asked, which keeps the cached reason so
 * an offline launch never locks a paying user. A purchase that disappears (refund)
 * clears only a PURCHASED grant; existing-vault and flag grants are never revoked.
 */
internal fun nextUnlockReason(current: UnlockReason?, owned: Set<String>?): UnlockReason? = when {
    current == UnlockReason.PAYWALL_DISABLED -> current
    owned == null -> current
    owned.any { it in UnlockProducts.OWNED } -> UnlockReason.PURCHASED
    current == UnlockReason.PURCHASED -> null
    else -> current
}

/** People who already had vaults before the paywall shipped keep using the app. */
internal fun launchUnlockReason(cached: UnlockReason?, paywallEnabled: Boolean, hasExistingVaults: Boolean): UnlockReason? = when {
    !paywallEnabled -> UnlockReason.PAYWALL_DISABLED
    cached == UnlockReason.PAYWALL_DISABLED -> if (hasExistingVaults) UnlockReason.EXISTING_VAULTS else null
    cached != null -> cached
    hasExistingVaults -> UnlockReason.EXISTING_VAULTS
    else -> null
}
