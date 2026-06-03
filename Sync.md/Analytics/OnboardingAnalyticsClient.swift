//
//  OnboardingAnalyticsClient.swift
//  Sync.md
//
//  Offline-safe client for onboarding analytics.
//

import Foundation

nonisolated protocol OnboardingAnalyticsDefaultsStoring: Sendable {
    func data(forKey defaultName: String) -> Data?
    func string(forKey defaultName: String) -> String?
    func set(_ value: Any?, forKey defaultName: String)
    func removeObject(forKey defaultName: String)
}

nonisolated final class SystemOnboardingAnalyticsDefaults: OnboardingAnalyticsDefaultsStoring, @unchecked Sendable {
    nonisolated(unsafe) private let defaults: UserDefaults

    nonisolated init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    func data(forKey defaultName: String) -> Data? {
        defaults.data(forKey: defaultName)
    }

    func string(forKey defaultName: String) -> String? {
        defaults.string(forKey: defaultName)
    }

    func set(_ value: Any?, forKey defaultName: String) {
        defaults.set(value, forKey: defaultName)
    }

    func removeObject(forKey defaultName: String) {
        defaults.removeObject(forKey: defaultName)
    }
}

nonisolated struct OnboardingAnalyticsAppMetadata: Equatable, Sendable {
    let appVersion: String?
    let buildNumber: String?
    let platform: OnboardingAnalyticsPlatform

    static func current(bundle: Bundle = .main) -> OnboardingAnalyticsAppMetadata {
        #if os(macOS)
        let platform: OnboardingAnalyticsPlatform = .macOS
        #else
        let platform: OnboardingAnalyticsPlatform = .iOS
        #endif

        return OnboardingAnalyticsAppMetadata(
            appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String,
            buildNumber: bundle.object(forInfoDictionaryKey: "CFBundleVersion") as? String,
            platform: platform
        )
    }

    var properties: OnboardingAnalyticsProperties {
        OnboardingAnalyticsProperties(
            appVersion: appVersion,
            buildNumber: buildNumber,
            platform: platform
        )
    }
}

nonisolated final class OnboardingAnalyticsClient: @unchecked Sendable {
    static let shared = OnboardingAnalyticsClient(
        transport: OnboardingAnalyticsTransportFactory.makeDefaultTransport(),
        retryDelayNanoseconds: OnboardingAnalyticsClient.defaultRetryDelayForCurrentLaunch
    )

    private static let defaultQueueKey = "onboarding.analytics.queue.v1"
    private static let defaultQueueSize = 50
    private static let defaultRetryDelayNanoseconds: UInt64 = 30_000_000_000

    private let isEnabled: Bool
    private let state: OnboardingAnalyticsClientState
    private let transport: OnboardingAnalyticsTransport
    private let metadataProvider: @Sendable () -> OnboardingAnalyticsAppMetadata

    init(
        transport: OnboardingAnalyticsTransport,
        defaults: OnboardingAnalyticsDefaultsStoring = SystemOnboardingAnalyticsDefaults(),
        queueKey: String = OnboardingAnalyticsClient.defaultQueueKey,
        maxQueueSize: Int = OnboardingAnalyticsClient.defaultQueueSize,
        isEnabled: Bool = OnboardingAnalyticsClient.isEnabledByDefault,
        retryDelayNanoseconds: UInt64 = OnboardingAnalyticsClient.defaultRetryDelayNanoseconds,
        metadataProvider: @escaping @Sendable () -> OnboardingAnalyticsAppMetadata = { .current() }
    ) {
        self.isEnabled = isEnabled
        self.transport = transport
        self.metadataProvider = metadataProvider
        self.state = OnboardingAnalyticsClientState(
            store: OnboardingAnalyticsQueueStore(defaults: defaults, key: queueKey),
            maxQueueSize: max(0, maxQueueSize),
            retryDelayNanoseconds: retryDelayNanoseconds
        )
    }

    func track(_ event: OnboardingAnalyticsEvent) {
        guard isEnabled else { return }

        let payload = event.encodedPayload(defaultProperties: metadataProvider().properties)
        state.enqueue(payload)
        state.startFlushIfNeeded(transport: transport)
    }

    func flush() {
        guard isEnabled else { return }

        state.startFlushIfNeeded(transport: transport)
    }

    func flushAndWait() async {
        guard isEnabled else { return }

        await state.flushAndWait(transport: transport)
    }

    func queuedPayloads() async -> [OnboardingAnalyticsPayload] {
        state.queuedPayloads()
    }

    private static var isEnabledByDefault: Bool {
        #if DEBUG
        ProcessInfo.processInfo.environment["ONBOARDING_ANALYTICS_ENABLED"] == "1"
        #else
        true
        #endif
    }

    private static var defaultRetryDelayForCurrentLaunch: UInt64 {
        #if DEBUG
        if ProcessInfo.processInfo.environment["UITEST_ANALYTICS_TRANSPORT"] == "offline" {
            return 0
        }
        #endif

        return defaultRetryDelayNanoseconds
    }
}

nonisolated private final class OnboardingAnalyticsClientState: @unchecked Sendable {
    private let queue = DispatchQueue(label: "com.codybontecou.syncmd.onboarding-analytics-client")
    private let store: OnboardingAnalyticsQueueStore
    private let maxQueueSize: Int
    private let retryDelayNanoseconds: UInt64

    private var payloads: [OnboardingAnalyticsPayload]
    private var flushTask: Task<Void, Never>?

    init(store: OnboardingAnalyticsQueueStore, maxQueueSize: Int, retryDelayNanoseconds: UInt64) {
        self.store = store
        self.maxQueueSize = maxQueueSize
        self.retryDelayNanoseconds = retryDelayNanoseconds
        self.payloads = store.load()
        trimToQueueCap()
        store.save(payloads)
    }

    func enqueue(_ payload: OnboardingAnalyticsPayload) {
        queue.sync {
            payloads.append(payloadWithStableEventId(payload))
            trimToQueueCap()
            store.save(payloads)
        }
    }

    func startFlushIfNeeded(transport: OnboardingAnalyticsTransport) {
        queue.sync {
            guard flushTask == nil else { return }

            flushTask = Task.detached(priority: .utility) { [weak self, transport] in
                await self?.flushLoop(transport: transport)
            }
        }
    }

    func flushAndWait(transport: OnboardingAnalyticsTransport) async {
        startFlushIfNeeded(transport: transport)

        let task = queue.sync { flushTask }
        await task?.value
    }

    func queuedPayloads() -> [OnboardingAnalyticsPayload] {
        queue.sync { payloads }
    }

    private func flushLoop(transport: OnboardingAnalyticsTransport) async {
        var stoppedAfterFailure = false

        while let payload = nextPayload() {
            do {
                try await transport.send(payload)
                removeSentPayload(payload)
            } catch {
                stoppedAfterFailure = true
                break
            }
        }

        queue.sync {
            if payloads.isEmpty {
                flushTask = nil
            } else if stoppedAfterFailure, retryDelayNanoseconds > 0 {
                flushTask = Task.detached(priority: .utility) { [weak self, transport, retryDelayNanoseconds] in
                    try? await Task.sleep(nanoseconds: retryDelayNanoseconds)
                    await self?.flushLoop(transport: transport)
                }
            } else if stoppedAfterFailure {
                flushTask = nil
            } else {
                flushTask = Task.detached(priority: .utility) { [weak self, transport] in
                    await self?.flushLoop(transport: transport)
                }
            }
        }
    }

    private func nextPayload() -> OnboardingAnalyticsPayload? {
        queue.sync { payloads.first }
    }

    private func removeSentPayload(_ payload: OnboardingAnalyticsPayload) {
        queue.sync {
            guard payloads.first == payload else { return }

            payloads.removeFirst()
            store.save(payloads)
        }
    }

    private func payloadWithStableEventId(_ payload: OnboardingAnalyticsPayload) -> OnboardingAnalyticsPayload {
        guard payload.eventId == nil else { return payload }
        return payload.withEventId(UUID().uuidString.lowercased())
    }

    private func trimToQueueCap() {
        guard maxQueueSize > 0 else {
            payloads.removeAll()
            return
        }

        if payloads.count > maxQueueSize {
            payloads.removeFirst(payloads.count - maxQueueSize)
        }
    }
}

nonisolated private struct OnboardingAnalyticsQueueStore: Sendable {
    private let defaults: OnboardingAnalyticsDefaultsStoring
    private let key: String

    init(defaults: OnboardingAnalyticsDefaultsStoring, key: String) {
        self.defaults = defaults
        self.key = key
    }

    func load() -> [OnboardingAnalyticsPayload] {
        guard let data = defaults.data(forKey: key) else { return [] }

        let decoder = JSONDecoder()
        let decoded = (try? decoder.decode([OnboardingAnalyticsPayload].self, from: data)) ?? []
        return decoded.filter { OnboardingAnalyticsEventName(rawValue: $0.eventName) != nil }
    }

    func save(_ payloads: [OnboardingAnalyticsPayload]) {
        guard !payloads.isEmpty else {
            defaults.removeObject(forKey: key)
            return
        }

        let encoder = JSONEncoder()
        encoder.outputFormatting = [.sortedKeys]

        if let data = try? encoder.encode(payloads) {
            defaults.set(data, forKey: key)
        }
    }
}
