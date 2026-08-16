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

enum RepoAssistNetworkPolicy: String, Codable, Sendable { case any, wifiOnly }
enum RepoAssistPowerPolicy: String, Codable, Sendable { case any, externalPowerOnly }

enum RepoAssistAttention: String, Codable, Sendable, Equatable {
    case localChanges, diverged, remoteBranchMissing, authenticationOrTrust, wrongBranch, unavailable, failed
}

enum OpaqueAssistIdentifier {
    static func isValid(_ value: String) -> Bool {
        guard (8...128).contains(value.utf8.count) else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.contains($0) || $0 == "-" || $0 == "_"
        }
    }
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

struct RepoAssistSettings: Codable, Sendable, Equatable {
    var enabled: Bool
    var channel: String?
    var selectedBranch: String?
    var networkPolicy: RepoAssistNetworkPolicy
    var powerPolicy: RepoAssistPowerPolicy
    var health: RepoAssistHealth

    static let disabled = RepoAssistSettings()

    init(
        enabled: Bool = false,
        channel: String? = nil,
        selectedBranch: String? = nil,
        networkPolicy: RepoAssistNetworkPolicy = .any,
        powerPolicy: RepoAssistPowerPolicy = .any,
        health: RepoAssistHealth = .never
    ) {
        self.enabled = enabled
        self.channel = channel
        self.selectedBranch = selectedBranch
        self.networkPolicy = networkPolicy
        self.powerPolicy = powerPolicy
        self.health = health
    }

    private enum CodingKeys: String, CodingKey {
        case enabled, channel, selectedBranch, networkPolicy, powerPolicy, health
    }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = try c.decodeIfPresent(Bool.self, forKey: .enabled) ?? false
        channel = try c.decodeIfPresent(String.self, forKey: .channel)
        selectedBranch = try c.decodeIfPresent(String.self, forKey: .selectedBranch)
        networkPolicy = try c.decodeIfPresent(RepoAssistNetworkPolicy.self, forKey: .networkPolicy) ?? .any
        powerPolicy = try c.decodeIfPresent(RepoAssistPowerPolicy.self, forKey: .powerPolicy) ?? .any
        health = try c.decodeIfPresent(RepoAssistHealth.self, forKey: .health) ?? .never
    }
}

struct PremiumInstallation: Codable, Sendable, Equatable {
    let installationID: UUID
    let bundleID: String
    let appVersion: String
}

enum APNsEnvironment: String, Codable, Sendable { case sandbox, production }

struct PremiumEntitlementUploadRequest: Codable, Sendable, Equatable {
    let installation: PremiumInstallation
    let proof: PremiumEntitlementProof
}

struct PremiumInstallationCredential: Codable, Sendable, Equatable {
    let installationID: UUID
    let token: String
    let deletionToken: String
    let expiresAt: Date

    func isValid(for installationID: UUID, at date: Date = Date()) -> Bool {
        self.installationID == installationID && !token.isEmpty && expiresAt > date
    }

    func canDelete(for installationID: UUID) -> Bool {
        self.installationID == installationID && !deletionToken.isEmpty
    }
}

struct PremiumDeviceRegistrationRequest: Codable, Sendable, Equatable {
    let installation: PremiumInstallation
    let token: String
    let environment: APNsEnvironment
    let channels: [String]
    /// Monotonic per-installation sequence. The relay accepts an update only
    /// when it is not older than the device row it already committed.
    let registrationGeneration: UInt64
}

struct PremiumDeviceDeletionRequest: Codable, Sendable, Equatable {
    let installationID: UUID
    let token: String?
    let environment: APNsEnvironment
}

struct PremiumSilentPush: Sendable, Equatable {
    let channel: String
    let hintID: String

    enum ParseError: Error, Equatable { case invalidPayload }

    static func parse(_ userInfo: [AnyHashable: Any]) throws -> PremiumSilentPush {
        guard Set(userInfo.keys.compactMap { $0 as? String }) == ["aps", "channel", "hint"],
              let aps = userInfo["aps"] as? [String: Any],
              Set(aps.keys) == ["content-available"],
              let contentAvailable = aps["content-available"] as? NSNumber,
              contentAvailable.intValue == 1,
              let channel = userInfo["channel"] as? String,
              let hint = userInfo["hint"] as? String,
              OpaqueAssistIdentifier.isValid(channel), OpaqueAssistIdentifier.isValid(hint) else {
            throw ParseError.invalidPayload
        }
        return PremiumSilentPush(channel: channel, hintID: hint)
    }

}
