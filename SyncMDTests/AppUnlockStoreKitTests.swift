import StoreKit
import StoreKitTest
import XCTest
@testable import Sync_md

/// Drives `AppUnlock` against `GitSync.storekit` (the local StoreKit
/// configuration the Run scheme also uses): buying the lifetime unlock and
/// restoring it on a fresh install both unlock.
@MainActor
final class AppUnlockStoreKitTests: XCTestCase {
    private var session: SKTestSession!
    private var suites: [String] = []

    override func setUp() async throws {
        let config = URL(fileURLWithPath: #filePath)
            .deletingLastPathComponent()
            .deletingLastPathComponent()
            .appendingPathComponent("GitSync.storekit")
        session = try SKTestSession(contentsOf: config)
        session.resetToDefaultState()
        session.disableDialogs = true
        session.clearTransactions()
    }

    override func tearDown() async throws {
        session?.clearTransactions()
        for suite in suites { UserDefaults().removePersistentDomain(forName: suite) }
        suites = []
    }

    private func freshDefaults(_ name: String) throws -> UserDefaults {
        let defaults = try XCTUnwrap(UserDefaults(suiteName: name))
        defaults.removePersistentDomain(forName: name)
        suites.append(name)
        return defaults
    }

    /// Hosted CI runners can't drive StoreKit test sessions: `buyProduct`
    /// throws `notEntitled` and `AppStore.sync()` waits on an Apple Account
    /// prompt until the job times out. The workflow sets this; locally the
    /// purchase and restore tests still run.
    private func skipPurchaseTestsOnCI() throws {
        try XCTSkipIf(
            ProcessInfo.processInfo.environment["SKIP_STOREKIT_PURCHASE_TESTS"] == "1",
            "StoreKit purchase/restore tests are not supported on hosted CI runners"
        )
    }

    func testLifetimeProductLoadsAtTheConfiguredPrice() async throws {
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.product"))
        await unlock.loadProduct()
        let product = try XCTUnwrap(unlock.product)
        XCTAssertEqual(product.id, AppUnlock.productID)
        XCTAssertEqual(product.type, .nonConsumable)
        XCTAssertEqual(product.price, Decimal(string: "9.99"))
    }

    func testPurchaseUnlocksAndRestoreUnlocksAFreshInstall() async throws {
        try skipPurchaseTestsOnCI()
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.buy"))
        await unlock.refresh()
        XCTAssertFalse(unlock.isUnlocked, "A new free download must start locked")

        // Buy through the test session, not `unlock.purchase()`: on the iOS 26
        // simulator `Product.purchase()` falls through to an Apple Account
        // sign-in sheet even with `disableDialogs`.
        _ = try await session.buyProduct(identifier: AppUnlock.productID)
        await unlock.refresh()
        XCTAssertTrue(unlock.isUnlocked)
        XCTAssertEqual(unlock.reason, .purchased)

        // Same Apple Account, new install: nothing cached, Restore finds it.
        let reinstall = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.restore"))
        XCTAssertFalse(reinstall.isUnlocked)
        let restored = await reinstall.restore()
        XCTAssertTrue(restored)
        XCTAssertEqual(reinstall.reason, .purchased)
    }

    func testRestoreWithoutAPurchaseStaysLocked() async throws {
        try skipPurchaseTestsOnCI()
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.none"))
        let restored = await unlock.restore()
        XCTAssertFalse(restored)
        XCTAssertFalse(unlock.isUnlocked)
    }
}
