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
