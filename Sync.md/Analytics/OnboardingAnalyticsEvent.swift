//
//  OnboardingAnalyticsEvent.swift
//  Sync.md
//
//  Privacy-safe event model for onboarding analytics.
//

import Foundation

/// Privacy contract for onboarding analytics:
/// - Allowed: app version/build/platform, coarse onboarding step, coarse auth
///   method/outcome, whether the save location was default or custom, and
///   coarse error category.
/// - Prohibited: repository URLs, paths, branch names, author names/emails,
///   access tokens, SSH keys, file contents, folder names, GitHub usernames,
///   raw device identifiers, IP addresses, user agents, and free-form user text.
///
/// This model is deliberately local and transport-free. Future sinks should
/// consume `encodedPayload()` and must not add keys outside
/// `OnboardingAnalyticsPropertyKey`.
nonisolated struct OnboardingAnalyticsEvent: Equatable, Sendable {
    let name: OnboardingAnalyticsEventName
    let properties: OnboardingAnalyticsProperties

    init(
        name: OnboardingAnalyticsEventName,
        properties: OnboardingAnalyticsProperties = OnboardingAnalyticsProperties()
    ) {
        self.name = name
        self.properties = properties
    }

    func encodedPayload(
        defaultProperties: OnboardingAnalyticsProperties = OnboardingAnalyticsProperties()
    ) -> OnboardingAnalyticsPayload {
        var encodedProperties = defaultProperties.encodedProperties()
        properties.encodedProperties().forEach { key, value in
            encodedProperties[key] = value
        }

        return OnboardingAnalyticsPayload(
            eventName: name.rawValue,
            properties: encodedProperties
        )
    }
}

nonisolated enum OnboardingAnalyticsEventName: String, CaseIterable, Sendable {
    case onboardingStarted = "sync_onboarding_started"
    case onboardingStepViewed = "sync_onboarding_step_viewed"
    case onboardingAuthStarted = "sync_onboarding_auth_started"
    case onboardingAuthCompleted = "sync_onboarding_auth_completed"
    case onboardingSaveLocationSelected = "sync_onboarding_save_location_selected"
    case onboardingCompleted = "sync_onboarding_completed"
}

nonisolated struct OnboardingAnalyticsPayload: Equatable, Sendable, Codable {
    let eventId: String?
    let eventName: String
    let properties: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]

    var transportProperties: [String: OnboardingAnalyticsValue] {
        Dictionary(uniqueKeysWithValues: properties.map { ($0.key.rawValue, $0.value) })
    }

    private enum CodingKeys: String, CodingKey {
        case eventId
        case eventName
        case properties
    }

    init(
        eventId: String? = nil,
        eventName: String,
        properties: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]
    ) {
        self.eventId = eventId
        self.eventName = eventName
        self.properties = properties
    }

    func withEventId(_ eventId: String) -> OnboardingAnalyticsPayload {
        OnboardingAnalyticsPayload(
            eventId: eventId,
            eventName: eventName,
            properties: properties
        )
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        let eventId = try container.decodeIfPresent(String.self, forKey: .eventId)
        let eventName = try container.decode(String.self, forKey: .eventName)
        let transportProperties = try container.decode(
            [String: OnboardingAnalyticsValue].self,
            forKey: .properties
        )

        self.eventId = eventId
        self.eventName = eventName
        self.properties = Dictionary(
            uniqueKeysWithValues: transportProperties.compactMap { key, value in
                guard let propertyKey = OnboardingAnalyticsPropertyKey(rawValue: key) else { return nil }
                return (propertyKey, value)
            }
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.container(keyedBy: CodingKeys.self)
        try container.encodeIfPresent(eventId, forKey: .eventId)
        try container.encode(eventName, forKey: .eventName)
        try container.encode(transportProperties, forKey: .properties)
    }
}

nonisolated enum OnboardingAnalyticsPropertyKey: String, CaseIterable, Sendable {
    case appVersion
    case buildNumber
    case platform
    case onboardingStep
    case authMethod
    case authOutcome
    case saveLocationPreference
    case errorCategory
}

nonisolated enum OnboardingAnalyticsValue: Equatable, Sendable, Codable {
    case string(String)
    case int(Int)

    init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()

        if let intValue = try? container.decode(Int.self) {
            self = .int(intValue)
            return
        }

        if let stringValue = try? container.decode(String.self) {
            self = .string(stringValue)
            return
        }

        throw DecodingError.typeMismatch(
            OnboardingAnalyticsValue.self,
            DecodingError.Context(
                codingPath: decoder.codingPath,
                debugDescription: "Expected onboarding analytics value to be a string or integer."
            )
        )
    }

    func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()

        switch self {
        case .string(let value):
            try container.encode(value)
        case .int(let value):
            try container.encode(value)
        }
    }
}

nonisolated struct OnboardingAnalyticsProperties: Equatable, Sendable {
    private let appVersion: String?
    private let buildNumber: String?
    private let platform: OnboardingAnalyticsPlatform?
    private let onboardingStep: OnboardingAnalyticsStep?
    private let authMethod: OnboardingAnalyticsAuthMethod?
    private let authOutcome: OnboardingAnalyticsAuthOutcome?
    private let saveLocationPreference: OnboardingAnalyticsSaveLocationPreference?
    private let errorCategory: OnboardingAnalyticsErrorCategory?

    init(
        appVersion: String? = nil,
        buildNumber: String? = nil,
        platform: OnboardingAnalyticsPlatform? = nil,
        onboardingStep: OnboardingAnalyticsStep? = nil,
        authMethod: OnboardingAnalyticsAuthMethod? = nil,
        authOutcome: OnboardingAnalyticsAuthOutcome? = nil,
        saveLocationPreference: OnboardingAnalyticsSaveLocationPreference? = nil,
        errorCategory: OnboardingAnalyticsErrorCategory? = nil
    ) {
        self.appVersion = OnboardingAnalyticsSanitizer.sanitizedAppVersion(appVersion)
        self.buildNumber = OnboardingAnalyticsSanitizer.sanitizedBuildNumber(buildNumber)
        self.platform = platform
        self.onboardingStep = onboardingStep
        self.authMethod = authMethod
        self.authOutcome = authOutcome
        self.saveLocationPreference = saveLocationPreference
        self.errorCategory = errorCategory
    }

    func encodedProperties() -> [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue] {
        var encoded: [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue] = [:]

        encode(appVersion, for: .appVersion, into: &encoded)
        encode(buildNumber, for: .buildNumber, into: &encoded)
        encode(platform?.rawValue, for: .platform, into: &encoded)
        encode(onboardingStep?.rawValue, for: .onboardingStep, into: &encoded)
        encode(authMethod?.rawValue, for: .authMethod, into: &encoded)
        encode(authOutcome?.rawValue, for: .authOutcome, into: &encoded)
        encode(saveLocationPreference?.rawValue, for: .saveLocationPreference, into: &encoded)
        encode(errorCategory?.rawValue, for: .errorCategory, into: &encoded)

        return encoded
    }

    private func encode(
        _ value: String?,
        for key: OnboardingAnalyticsPropertyKey,
        into encoded: inout [OnboardingAnalyticsPropertyKey: OnboardingAnalyticsValue]
    ) {
        guard let value else { return }
        encoded[key] = .string(value)
    }
}

nonisolated enum OnboardingAnalyticsPlatform: String, CaseIterable, Sendable {
    case iOS = "ios"
    case macOS = "macos"
}

nonisolated enum OnboardingAnalyticsStep: String, CaseIterable, Sendable {
    case welcome
    case editAnywhere = "edit_anywhere"
    case fullGit = "full_git"
    case backgroundSync = "background_sync"
    case accountChoice = "account_choice"
    case githubSignIn = "github_sign_in"
    case personalAccessToken = "personal_access_token"
    case saveLocation = "save_location"
    case demo
    case ready
}

nonisolated enum OnboardingAnalyticsAuthMethod: String, CaseIterable, Sendable {
    case githubOAuth = "github_oauth"
    case personalAccessToken = "personal_access_token"
    case none
    case demo
}

nonisolated enum OnboardingAnalyticsAuthOutcome: String, CaseIterable, Sendable {
    case started
    case succeeded
    case failed
    case skipped
}

nonisolated enum OnboardingAnalyticsSaveLocationPreference: String, CaseIterable, Sendable {
    case defaultAppFolder = "default_app_folder"
    case customFolder = "custom_folder"
}

nonisolated enum OnboardingAnalyticsErrorCategory: String, CaseIterable, Sendable {
    case networkUnavailable = "network_unavailable"
    case configurationUnavailable = "configuration_unavailable"
    case authFailed = "auth_failed"
    case unknown
}

nonisolated private enum OnboardingAnalyticsSanitizer {
    private static let digitCharacters = CharacterSet(charactersIn: "0123456789")
    private static let versionCharacters = CharacterSet(charactersIn: "0123456789.")

    static func sanitizedAppVersion(_ rawValue: String?) -> String? {
        guard let value = trimmed(rawValue), value.count <= 20 else { return nil }
        guard containsOnly(value, characters: versionCharacters) else { return nil }
        let parts = value.split(separator: ".", omittingEmptySubsequences: false)
        guard (1...4).contains(parts.count) else { return nil }
        guard parts.allSatisfy({ !$0.isEmpty && $0.allSatisfy(\.isNumber) }) else { return nil }
        return value
    }

    static func sanitizedBuildNumber(_ rawValue: String?) -> String? {
        guard let value = trimmed(rawValue), (1...12).contains(value.count) else { return nil }
        guard containsOnly(value, characters: digitCharacters) else { return nil }
        return value
    }

    private static func trimmed(_ rawValue: String?) -> String? {
        guard let rawValue else { return nil }
        let value = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        return value.isEmpty ? nil : value
    }

    private static func containsOnly(_ value: String, characters: CharacterSet) -> Bool {
        value.unicodeScalars.allSatisfy { characters.contains($0) }
    }
}
