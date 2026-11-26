//
//  OnboardingAnalyticsFunnel.swift
//  Sync.md
//
//  Typed helpers for privacy-safe onboarding instrumentation.
//

import Foundation

extension OnboardingAnalyticsClient {
    func trackOnboardingStarted(step: OnboardingAnalyticsStep = .welcome) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingStarted,
            properties: properties(onboardingStep: step)
        ))
    }

    func trackOnboardingStepViewed(_ step: OnboardingAnalyticsStep) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingStepViewed,
            properties: properties(onboardingStep: step)
        ))
    }

    func trackOnboardingAuthStarted(method: OnboardingAnalyticsAuthMethod) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingAuthStarted,
            properties: properties(
                authMethod: method,
                authOutcome: .started
            )
        ))
    }

    func trackOnboardingAuthCompleted(
        method: OnboardingAnalyticsAuthMethod,
        outcome: OnboardingAnalyticsAuthOutcome,
        errorCategory: OnboardingAnalyticsErrorCategory? = nil
    ) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingAuthCompleted,
            properties: properties(
                authMethod: method,
                authOutcome: outcome,
                errorCategory: errorCategory
            )
        ))
    }

    func trackOnboardingSaveLocationSelected(
        preference: OnboardingAnalyticsSaveLocationPreference
    ) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingSaveLocationSelected,
            properties: properties(
                onboardingStep: .saveLocation,
                saveLocationPreference: preference
            )
        ))
    }

    func trackOnboardingCompleted(
        authMethod: OnboardingAnalyticsAuthMethod? = nil,
        saveLocationPreference: OnboardingAnalyticsSaveLocationPreference? = nil
    ) {
        track(OnboardingAnalyticsEvent(
            name: .onboardingCompleted,
            properties: properties(
                onboardingStep: .ready,
                authMethod: authMethod,
                saveLocationPreference: saveLocationPreference
            )
        ))
    }

    // MARK: - Unlock paywall

    /// Sent once per paywall presentation, after the product load settles.
    /// `productLoaded == false` means the buy button can only fail.
    func trackPaywallViewed(context: OnboardingAnalyticsPaywallContext, productLoaded: Bool) {
        track(OnboardingAnalyticsEvent(
            name: .paywallViewed,
            properties: OnboardingAnalyticsProperties(
                errorCategory: productLoaded ? nil : .productUnavailable,
                paywallContext: context,
                productId: .lifetime
            )
        ))
    }

    func trackPaywallPurchaseStarted(context: OnboardingAnalyticsPaywallContext) {
        track(OnboardingAnalyticsEvent(
            name: .paywallPurchaseStarted,
            properties: OnboardingAnalyticsProperties(
                paywallContext: context,
                productId: .lifetime
            )
        ))
    }

    func trackPaywallPurchaseCompleted(
        context: OnboardingAnalyticsPaywallContext,
        outcome: OnboardingAnalyticsPurchaseOutcome,
        errorCategory: OnboardingAnalyticsErrorCategory? = nil
    ) {
        track(OnboardingAnalyticsEvent(
            name: .paywallPurchaseCompleted,
            properties: OnboardingAnalyticsProperties(
                errorCategory: errorCategory,
                paywallContext: context,
                productId: .lifetime,
                purchaseOutcome: outcome
            )
        ))
    }

    func trackPaywallRestoreCompleted(
        context: OnboardingAnalyticsPaywallContext,
        restored: Bool
    ) {
        track(OnboardingAnalyticsEvent(
            name: .paywallRestoreCompleted,
            properties: OnboardingAnalyticsProperties(
                paywallContext: context,
                productId: .lifetime,
                purchaseOutcome: restored ? .restored : .notFound
            )
        ))
    }

    private func properties(
        onboardingStep: OnboardingAnalyticsStep? = nil,
        authMethod: OnboardingAnalyticsAuthMethod? = nil,
        authOutcome: OnboardingAnalyticsAuthOutcome? = nil,
        saveLocationPreference: OnboardingAnalyticsSaveLocationPreference? = nil,
        errorCategory: OnboardingAnalyticsErrorCategory? = nil
    ) -> OnboardingAnalyticsProperties {
        OnboardingAnalyticsProperties(
            onboardingStep: onboardingStep,
            authMethod: authMethod,
            authOutcome: authOutcome,
            saveLocationPreference: saveLocationPreference,
            errorCategory: errorCategory
        )
    }
}
