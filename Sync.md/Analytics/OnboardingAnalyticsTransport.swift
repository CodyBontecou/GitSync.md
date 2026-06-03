//
//  OnboardingAnalyticsTransport.swift
//  Sync.md
//
//  Transport seam for offline-safe onboarding analytics.
//

import Foundation

nonisolated protocol OnboardingAnalyticsTransport: Sendable {
    func send(_ payload: OnboardingAnalyticsPayload) async throws
}

nonisolated enum OnboardingAnalyticsTransportFactory {
    static func makeDefaultTransport(
        environment: [String: String] = ProcessInfo.processInfo.environment,
        bundle: Bundle = .main,
        defaults: OnboardingAnalyticsDefaultsStoring = SystemOnboardingAnalyticsDefaults()
    ) -> OnboardingAnalyticsTransport {
        #if DEBUG
        if environment["UITEST_ANALYTICS_TRANSPORT"] == "offline" {
            return OfflineOnboardingAnalyticsTransport()
        }
        #endif

        if let transport = CloudflareOnboardingAnalyticsTransport.configured(
            environment: environment,
            bundle: bundle,
            defaults: defaults
        ) {
            return transport
        }

        return NoOpOnboardingAnalyticsTransport()
    }
}

nonisolated struct NoOpOnboardingAnalyticsTransport: OnboardingAnalyticsTransport {
    func send(_ payload: OnboardingAnalyticsPayload) async throws {}
}

nonisolated struct OfflineOnboardingAnalyticsTransport: OnboardingAnalyticsTransport {
    func send(_ payload: OnboardingAnalyticsPayload) async throws {
        throw URLError(.notConnectedToInternet)
    }
}
