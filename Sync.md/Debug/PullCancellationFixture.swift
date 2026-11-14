#if DEBUG
import Foundation

/// Holds the real repository coordinator lease in an isolated, credential-free
/// fixture. Cancelling the normal Pull UI exercises its real queued waiter;
/// no Git result, cancellation outcome, or user inventory is substituted.
@MainActor
enum PullCancellationFixture {
    private static var leaseTask: Task<Void, Never>?
    private static var releaseLatch: Latch?

    static func hold(at url: URL) async throws {
        precondition(Bundle.main.bundleIdentifier == "bontecou.Sync-md.prqa")
        precondition(MarketingCapture.gitToolsUITestScenario == "pull-cancel")
        precondition(url.lastPathComponent == "git-tools-fixture")
        guard leaseTask == nil else { return }
        let ready = Latch()
        let release = Latch()
        releaseLatch = release
        leaseTask = Task {
            do {
                try await RepositoryOperationCoordinator.shared.withRepository(at: url) {
                    await ready.open(true)
                    _ = await release.wait()
                }
            } catch {
                await ready.open(false)
            }
        }
        guard await ready.wait() else {
            leaseTask = nil
            releaseLatch = nil
            throw CancellationError()
        }
    }

    static func release() async {
        guard let releaseLatch else { return }
        await releaseLatch.open(true)
        await leaseTask?.value
        leaseTask = nil
        self.releaseLatch = nil
    }

    private actor Latch {
        private var value: Bool?
        private var waiters: [CheckedContinuation<Bool, Never>] = []

        func wait() async -> Bool {
            if let value { return value }
            return await withCheckedContinuation { waiters.append($0) }
        }

        func open(_ value: Bool) {
            guard self.value == nil else { return }
            self.value = value
            let pending = waiters
            waiters.removeAll()
            for waiter in pending { waiter.resume(returning: value) }
        }
    }
}
#endif
