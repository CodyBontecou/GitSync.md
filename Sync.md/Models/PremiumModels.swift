import Foundation

struct PremiumProductIdentifiers: Sendable, Equatable {
    static let `default` = PremiumProductIdentifiers(
        monthly: "com.bontecou.gitsync.assist.monthly",
        annual: "com.bontecou.gitsync.assist.annual",
        subscriptionGroup: "gitsync-assist"
    )

    let monthly: String
    let annual: String
    let subscriptionGroup: String

    var all: [String] { [monthly, annual] }
}

enum PremiumBillingPeriod: String, Codable, Sendable { case month, year, unknown }

struct PremiumProduct: Identifiable, Codable, Sendable, Equatable {
    let id: String
    let displayName: String
    let displayPrice: String
    let period: PremiumBillingPeriod
}

enum PremiumStoreEnvironment: String, Codable, Sendable { case sandbox, production, xcode, unknown }

struct PremiumVerifiedTransaction: Codable, Sendable, Equatable {
    let productID: String
    let transactionID: UInt64
    let originalTransactionID: UInt64
    let purchaseDate: Date
    let expirationDate: Date?
    let revocationDate: Date?
    let appAccountToken: UUID?
    let environment: PremiumStoreEnvironment
    let signedTransaction: String

    /// Used only for newly delivered purchase/update transactions. StoreKit's
    /// `currentEntitlements` sequence is authoritative and may include access
    /// granted during a subscription grace period past this date.
    func isUsableEvent(at date: Date = Date()) -> Bool {
        revocationDate == nil && (expirationDate.map { $0 > date } ?? true)
    }
}

struct PremiumEntitlementProof: Codable, Sendable, Equatable {
    let productID: String
    let transactionID: UInt64
    let originalTransactionID: UInt64
    let expirationDate: Date?
    let environment: PremiumStoreEnvironment
    let signedTransaction: String

    init(transaction: PremiumVerifiedTransaction) {
        productID = transaction.productID
        transactionID = transaction.transactionID
        originalTransactionID = transaction.originalTransactionID
        expirationDate = transaction.expirationDate
        environment = transaction.environment
        signedTransaction = transaction.signedTransaction
    }
}

enum PremiumEntitlementState: Sendable, Equatable {
    case loading
    case inactive
    case active(PremiumEntitlementProof)
    case pending
    case error(String)

    var isActive: Bool {
        if case .active = self { return true }
        return false
    }
}

/// Pure gating rules for the optional Background Sync upsell surfaces. Every surface is
/// compile-time disabled until `gitSyncAssistEnabled` ships, never targets an
/// active subscriber or an installation that already enabled automation, and
/// is dismissible forever after one use. Manual Git features are never gated.
enum AssistUpsellEligibility {
    /// Successful manual pulls after which the one-time milestone sheet may
    /// appear. Counting runs even while the feature flag is off so long-time
    /// users are offered the upgrade on their first post-release pull instead
    /// of starting from zero.
    static let milestonePullThreshold = 5

    static func shouldShowBanner(
        featureEnabled: Bool,
        hasRepositories: Bool,
        subscriptionActive: Bool,
        assistEnabled: Bool,
        bannerDismissed: Bool
    ) -> Bool {
        featureEnabled && hasRepositories && !subscriptionActive && !assistEnabled && !bannerDismissed
    }

    static func shouldShowMilestone(
        featureEnabled: Bool,
        subscriptionActive: Bool,
        assistEnabled: Bool,
        milestoneShown: Bool,
        successfulPullCount: Int
    ) -> Bool {
        featureEnabled && !subscriptionActive && !assistEnabled && !milestoneShown
            && successfulPullCount >= milestonePullThreshold
    }
}

enum RepoAssistNetworkPolicy: String, Codable, Sendable { case any, wifiOnly }
enum RepoAssistPowerPolicy: String, Codable, Sendable { case any, externalPowerOnly }

enum RepoAssistAttention: String, Codable, Sendable, Equatable {
    case localChanges, lfsHydration, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, unpushedCommit, failed
}

enum RepoAssistHealthKind: String, Codable, Sendable { case never, updated, upToDate, deferred, attention, failed }

struct RepoAssistHealth: Codable, Sendable, Equatable {
    var kind: RepoAssistHealthKind
    var attention: RepoAssistAttention?
    var message: String?
    var lastAttemptDate: Date?
    var lastSuccessDate: Date?
    var commitSHA: String?

    static let never = RepoAssistHealth(kind: .never)

    init(
        kind: RepoAssistHealthKind,
        attention: RepoAssistAttention? = nil,
        message: String? = nil,
        lastAttemptDate: Date? = nil,
        lastSuccessDate: Date? = nil,
        commitSHA: String? = nil
    ) {
        self.kind = kind
        self.attention = attention
        self.message = message
        self.lastAttemptDate = lastAttemptDate
        self.lastSuccessDate = lastSuccessDate
        self.commitSHA = commitSHA
    }

    private enum CodingKeys: String, CodingKey {
        case kind, attention, message, lastAttemptDate, lastSuccessDate, commitSHA
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        kind = try c.decodeIfPresent(RepoAssistHealthKind.self, forKey: .kind) ?? .never
        attention = try c.decodeIfPresent(RepoAssistAttention.self, forKey: .attention)
        message = try c.decodeIfPresent(String.self, forKey: .message)
        lastAttemptDate = try c.decodeIfPresent(Date.self, forKey: .lastAttemptDate)
        lastSuccessDate = try c.decodeIfPresent(Date.self, forKey: .lastSuccessDate)
        commitSHA = try c.decodeIfPresent(String.self, forKey: .commitSHA)
    }
}

enum RepoAssistEnrollmentStatus: String, Codable, Sendable, Equatable {
    case disabled, excluded, foregroundOnly, enrolling, enrolled, failed
}

struct PremiumAssistSummary: Sendable, Equatable {
    let total: Int
    let included: Int
    let excluded: Int
    let disabled: Int
    let failed: Int
}

struct PremiumInstallation: Codable, Sendable, Equatable {
    let installationID: UUID
    let bundleID: String
    let appVersion: String
}

/// Stable per-installation identity used solely to bind StoreKit purchases
/// to this install via the app account token. Entirely local: no server
/// registration is involved.
enum PremiumInstallationIdentity {
    static let defaultsKey = "premium.installation-id.v1"

    static func current(
        defaults: UserDefaults = .standard,
        bundle: Bundle = .main
    ) -> PremiumInstallation {
        let id = defaults.string(forKey: defaultsKey).flatMap(UUID.init(uuidString:))
            ?? UUID()
        defaults.set(id.uuidString, forKey: defaultsKey)
        return PremiumInstallation(
            installationID: id,
            bundleID: bundle.bundleIdentifier ?? "unknown",
            appVersion: bundle.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "unknown"
        )
    }
}

struct RepoAssistSettings: Codable, Sendable, Equatable {
    // Historical fields remain intact for persisted records and callers.
    var enabled: Bool
    var channel: String?
    var selectedBranch: String?
    var networkPolicy: RepoAssistNetworkPolicy
    var powerPolicy: RepoAssistPowerPolicy
    var health: RepoAssistHealth

    // Automatic-mode policy and exact enrolled target.
    var excludedFromAutomaticSync: Bool
    var githubRepositoryID: Int64?
    var githubRepositoryFullName: String?
    var linkedGitHubInstallationID: Int64?
    var enrolledBranch: String?
    var enrollmentStatus: RepoAssistEnrollmentStatus
    var enrollmentMessage: String?
    var enrollmentLastAttemptDate: Date?

    static let disabled = RepoAssistSettings()

    init(
        enabled: Bool = false,
        channel: String? = nil,
        selectedBranch: String? = nil,
        networkPolicy: RepoAssistNetworkPolicy = .any,
        powerPolicy: RepoAssistPowerPolicy = .any,
        health: RepoAssistHealth = .never,
        excludedFromAutomaticSync: Bool = false,
        githubRepositoryID: Int64? = nil,
        githubRepositoryFullName: String? = nil,
        linkedGitHubInstallationID: Int64? = nil,
        enrolledBranch: String? = nil,
        enrollmentStatus: RepoAssistEnrollmentStatus = .disabled,
        enrollmentMessage: String? = nil,
        enrollmentLastAttemptDate: Date? = nil
    ) {
        self.enabled = enabled
        self.channel = channel
        self.selectedBranch = selectedBranch
        self.networkPolicy = networkPolicy
        self.powerPolicy = powerPolicy
        self.health = health
        self.excludedFromAutomaticSync = excludedFromAutomaticSync
        self.githubRepositoryID = githubRepositoryID
        self.githubRepositoryFullName = githubRepositoryFullName
        self.linkedGitHubInstallationID = linkedGitHubInstallationID
        self.enrolledBranch = enrolledBranch
        self.enrollmentStatus = enrollmentStatus
        self.enrollmentMessage = enrollmentMessage
        self.enrollmentLastAttemptDate = enrollmentLastAttemptDate
        normalizeAutomaticExclusion()
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, channel, selectedBranch, networkPolicy, powerPolicy, health
        case excludedFromAutomaticSync, githubRepositoryID, githubRepositoryFullName
        case linkedGitHubInstallationID, enrolledBranch, enrollmentStatus
        case enrollmentMessage, enrollmentLastAttemptDate
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        channel = try c.decodeIfPresent(String.self, forKey: .channel)
        selectedBranch = try c.decodeIfPresent(String.self, forKey: .selectedBranch)
        networkPolicy = try c.decodeIfPresent(RepoAssistNetworkPolicy.self, forKey: .networkPolicy) ?? .any
        powerPolicy = try c.decodeIfPresent(RepoAssistPowerPolicy.self, forKey: .powerPolicy) ?? .any
        health = try c.decodeIfPresent(RepoAssistHealth.self, forKey: .health) ?? .never
        excludedFromAutomaticSync = try c.decodeIfPresent(Bool.self, forKey: .excludedFromAutomaticSync) ?? false
        githubRepositoryID = try c.decodeIfPresent(Int64.self, forKey: .githubRepositoryID)
        githubRepositoryFullName = try c.decodeIfPresent(String.self, forKey: .githubRepositoryFullName)
        linkedGitHubInstallationID = try c.decodeIfPresent(Int64.self, forKey: .linkedGitHubInstallationID)
        enrolledBranch = try c.decodeIfPresent(String.self, forKey: .enrolledBranch)
        enrollmentStatus = try c.decodeIfPresent(RepoAssistEnrollmentStatus.self, forKey: .enrollmentStatus)
            ?? (channel == nil ? .disabled : .enrolled)
        enrollmentMessage = try c.decodeIfPresent(String.self, forKey: .enrollmentMessage)
        enrollmentLastAttemptDate = try c.decodeIfPresent(Date.self, forKey: .enrollmentLastAttemptDate)
        normalizeAutomaticExclusion()
    }

    mutating func normalizeAutomaticExclusion() {
        guard excludedFromAutomaticSync else { return }
        enabled = false
        channel = nil
        githubRepositoryID = nil
        githubRepositoryFullName = nil
        linkedGitHubInstallationID = nil
        enrolledBranch = nil
        enrollmentStatus = .excluded
    }
}

