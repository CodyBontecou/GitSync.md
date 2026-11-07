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

    func testLifetimeProductLoadsAtTheConfiguredPrice() async throws {
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.product"))
        await unlock.loadProduct()
        let product = try XCTUnwrap(unlock.product)
        XCTAssertEqual(product.id, AppUnlock.productID)
        XCTAssertEqual(product.type, .nonConsumable)
        XCTAssertEqual(product.price, Decimal(string: "9.99"))
    }

    func testPurchaseUnlocksAndRestoreUnlocksAFreshInstall() async throws {
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.buy"))
        await unlock.refresh()
        XCTAssertFalse(unlock.isUnlocked, "A new free download must start locked")

        let outcome = await unlock.purchase()
        XCTAssertEqual(outcome, .unlocked)
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
        let unlock = AppUnlock(defaults: try freshDefaults("AppUnlockStoreKitTests.none"))
        let restored = await unlock.restore()
        XCTAssertFalse(restored)
        XCTAssertFalse(unlock.isUnlocked)
    }
}
