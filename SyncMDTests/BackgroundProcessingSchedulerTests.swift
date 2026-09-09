import Foundation
import XCTest
@testable import Sync_md

final class BackgroundProcessingSchedulerTests: XCTestCase {
    @MainActor
    func testRegistrationRetriesOnlyFailedIdentifierAndNeverDuplicatesSuccess() throws {
        let backend = RecordingBackgroundTaskSchedulerBackend()
        backend.registrationResults = [
            SystemPremiumBackgroundProcessingScheduler.refreshIdentifier: [true],
            SystemPremiumBackgroundProcessingScheduler.processingIdentifier: [false, true],
        ]
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, logs: logs)

        scheduler.register { _ in }
        scheduler.register { _ in }
        scheduler.register { _ in }

        XCTAssertEqual(backend.registrationAttempts, [
            SystemPremiumBackgroundProcessingScheduler.refreshIdentifier,
            SystemPremiumBackgroundProcessingScheduler.processingIdentifier,
            SystemPremiumBackgroundProcessingScheduler.processingIdentifier,
        ])
        XCTAssertEqual(
            backend.registrationAttempts.filter {
                $0 == SystemPremiumBackgroundProcessingScheduler.refreshIdentifier
            }.count,
            1,
            "A successful refresh registration must never be repeated while processing retries"
        )
        XCTAssertEqual(
            backend.registrationAttempts.filter {
                $0 == SystemPremiumBackgroundProcessingScheduler.processingIdentifier
            }.count,
            2
        )

        let registrationFailure = try XCTUnwrap(logs.events.first {
            $0.level == .warning && $0.message == "Could not register background task"
        })
        XCTAssertTrue(registrationFailure.detail.contains(
            "identifier=\(SystemPremiumBackgroundProcessingScheduler.processingIdentifier)"
        ))
        XCTAssertTrue(registrationFailure.detail.contains("kind=processing"))
        XCTAssertEqual(
            logs.events.filter {
                $0.level == .info
                    && $0.detail.contains(
                        "identifier=\(SystemPremiumBackgroundProcessingScheduler.refreshIdentifier)"
                    )
            }.count,
            1,
            "Repeated register calls must not emit duplicate success logs"
        )
        XCTAssertEqual(
            logs.events.filter { $0.message == "Registered background task after retry" }.count,
            1
        )
    }

    @MainActor
    func testLaunchesValidateKindsAndForwardBothRegisteredTaskTypes() throws {
        let backend = RecordingBackgroundTaskSchedulerBackend()
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, logs: logs)
        var forwarded: [ObjectIdentifier] = []
        scheduler.register { task in
            forwarded.append(ObjectIdentifier(task))
        }

        let refreshTask = RecordingPremiumBackgroundTask()
        let processingTask = RecordingPremiumBackgroundTask()
        XCTAssertTrue(backend.launch(
            identifier: SystemPremiumBackgroundProcessingScheduler.refreshIdentifier,
            kind: .appRefresh,
            task: refreshTask
        ))
        XCTAssertTrue(backend.launch(
            identifier: SystemPremiumBackgroundProcessingScheduler.processingIdentifier,
            kind: .processing,
            task: processingTask
        ))

        XCTAssertEqual(forwarded, [
            ObjectIdentifier(refreshTask),
            ObjectIdentifier(processingTask),
        ])
        XCTAssertTrue(refreshTask.completions.isEmpty)
        XCTAssertTrue(processingTask.completions.isEmpty)

        let wrongProcessingTask = RecordingPremiumBackgroundTask()
        XCTAssertTrue(backend.launch(
            identifier: SystemPremiumBackgroundProcessingScheduler.processingIdentifier,
            kind: .appRefresh,
            task: wrongProcessingTask
        ))

        XCTAssertEqual(
            wrongProcessingTask.completions,
            [false],
            "A wrong task type for the processing identifier must fail exactly once"
        )
        XCTAssertEqual(forwarded.count, 2, "A wrong task type must never reach the runtime handler")
        let rejection = try XCTUnwrap(logs.events.last {
            $0.message == "Rejected background task with unexpected type"
        })
        XCTAssertTrue(rejection.detail.contains(
            "identifier=\(SystemPremiumBackgroundProcessingScheduler.processingIdentifier)"
        ))
        XCTAssertTrue(rejection.detail.contains("kind=processing"))
        XCTAssertTrue(rejection.detail.contains("actual-kind=app-refresh"))
    }

    @MainActor
    func testScheduleBuildsExactRefreshAndProcessingDescriptors() throws {
        let now = Date(timeIntervalSince1970: 1_700_000_000)
        let backend = RecordingBackgroundTaskSchedulerBackend()
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, now: now, logs: logs)

        scheduler.schedule()

        XCTAssertEqual(
            backend.submissionAttempts.map(\.identifier),
            SystemPremiumBackgroundProcessingScheduler.permittedIdentifiers
        )
        let expectedBeginDate = now.addingTimeInterval(15 * 60)
        let refresh = try XCTUnwrap(backend.submissionAttempts.first {
            $0.identifier == SystemPremiumBackgroundProcessingScheduler.refreshIdentifier
        })
        guard case .appRefresh(let refreshIdentifier, let refreshBeginDate) = refresh else {
            return XCTFail("Expected an app-refresh descriptor")
        }
        XCTAssertEqual(
            refreshIdentifier,
            "com.bontecou.Sync-md.background-refresh",
            "The permitted refresh identifier is part of the scheduler contract"
        )
        XCTAssertEqual(refreshBeginDate, expectedBeginDate)

        let processing = try XCTUnwrap(backend.submissionAttempts.first {
            $0.identifier == SystemPremiumBackgroundProcessingScheduler.processingIdentifier
        })
        guard case .processing(
            let processingIdentifier,
            let processingBeginDate,
            let requiresNetworkConnectivity,
            let requiresExternalPower
        ) = processing else {
            return XCTFail("Expected a processing descriptor")
        }
        XCTAssertEqual(
            processingIdentifier,
            "com.bontecou.Sync-md.background-sync",
            "The permitted processing identifier is part of the scheduler contract"
        )
        XCTAssertEqual(processingBeginDate, expectedBeginDate)
        XCTAssertTrue(requiresNetworkConnectivity)
        XCTAssertFalse(requiresExternalPower)
    }

    @MainActor
    func testRepeatedScheduleCancelsAndReplacesWithoutPendingRequestBuildup() {
        let backend = RecordingBackgroundTaskSchedulerBackend()
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, logs: logs)

        scheduler.schedule()
        scheduler.schedule()
        scheduler.schedule()

        XCTAssertEqual(backend.pendingRequestCount, 2)
        XCTAssertEqual(
            backend.pendingRequests[
                SystemPremiumBackgroundProcessingScheduler.refreshIdentifier
            ]?.count,
            1
        )
        XCTAssertEqual(
            backend.pendingRequests[
                SystemPremiumBackgroundProcessingScheduler.processingIdentifier
            ]?.count,
            1
        )
        XCTAssertEqual(backend.submissionAttempts.count, 6)
        XCTAssertEqual(backend.cancellationAttempts, Array(repeating: [
            SystemPremiumBackgroundProcessingScheduler.refreshIdentifier,
            SystemPremiumBackgroundProcessingScheduler.processingIdentifier,
        ], count: 3).flatMap { $0 })
        XCTAssertEqual(
            logs.events.filter { $0.message == "Scheduled background task" }.count,
            2,
            "Unchanged repeated scheduling success must be logged only once per kind"
        )
    }

    @MainActor
    func testRefreshSubmissionFailureDoesNotSuppressProcessingReplacement() throws {
        let backend = RecordingBackgroundTaskSchedulerBackend()
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, logs: logs)
        scheduler.schedule()
        XCTAssertEqual(backend.pendingRequestCount, 2)

        backend.submissionFailureIdentifiers = [
            SystemPremiumBackgroundProcessingScheduler.refreshIdentifier,
        ]
        scheduler.schedule()

        XCTAssertEqual(
            backend.submissionAttempts.suffix(2).map(\.identifier),
            SystemPremiumBackgroundProcessingScheduler.permittedIdentifiers,
            "Processing submission must still be attempted after refresh submission fails"
        )
        XCTAssertNil(backend.pendingRequests[
            SystemPremiumBackgroundProcessingScheduler.refreshIdentifier
        ])
        XCTAssertEqual(
            backend.pendingRequests[
                SystemPremiumBackgroundProcessingScheduler.processingIdentifier
            ]?.count,
            1
        )
        let failure = try XCTUnwrap(logs.events.last {
            $0.message == "Could not replace background task"
        })
        XCTAssertTrue(failure.detail.contains(
            "identifier=\(SystemPremiumBackgroundProcessingScheduler.refreshIdentifier)"
        ))
        XCTAssertTrue(failure.detail.contains("kind=app-refresh"))
        XCTAssertTrue(failure.detail.contains(
            "any previous pending request was canceled before submission"
        ))

        backend.submissionFailureIdentifiers.removeAll()
        scheduler.schedule()
        XCTAssertEqual(backend.pendingRequestCount, 2, "A later call safely retries only pending work")
        XCTAssertEqual(
            logs.events.filter {
                $0.message == "Scheduled background task after prior failure"
                    && $0.detail.contains(
                        "identifier=\(SystemPremiumBackgroundProcessingScheduler.refreshIdentifier)"
                    )
            }.count,
            1
        )
    }

    @MainActor
    func testCancelRemovesBothPendingIdentifiers() {
        let backend = RecordingBackgroundTaskSchedulerBackend()
        let logs = BackgroundSchedulerLogRecorder()
        let scheduler = makeScheduler(backend: backend, logs: logs)
        scheduler.schedule()
        XCTAssertEqual(backend.pendingRequestCount, 2)
        backend.resetCancellationAttempts()

        scheduler.cancel()

        XCTAssertEqual(
            backend.cancellationAttempts,
            SystemPremiumBackgroundProcessingScheduler.permittedIdentifiers
        )
        XCTAssertEqual(backend.pendingRequestCount, 0)
    }

    @MainActor
    private func makeScheduler(
        backend: RecordingBackgroundTaskSchedulerBackend,
        now: Date = Date(timeIntervalSince1970: 1_700_000_000),
        logs: BackgroundSchedulerLogRecorder
    ) -> SystemPremiumBackgroundProcessingScheduler {
        SystemPremiumBackgroundProcessingScheduler(
            backend: backend,
            now: { now },
            log: { logs.record($0) }
        )
    }
}

@MainActor
private final class BackgroundSchedulerLogRecorder {
    private(set) var events: [PremiumBackgroundSchedulerLogEvent] = []

    func record(_ event: PremiumBackgroundSchedulerLogEvent) {
        events.append(event)
    }
}

@MainActor
private final class RecordingPremiumBackgroundTask: PremiumBackgroundProcessingTask {
    var expirationHandler: (() -> Void)?
    private(set) var completions: [Bool] = []

    func complete(success: Bool) {
        completions.append(success)
    }
}

private struct RecordingBackgroundTaskSubmissionError: LocalizedError {
    var errorDescription: String? { "deterministic submission failure" }
}

@MainActor
private final class RecordingBackgroundTaskSchedulerBackend: PremiumBackgroundTaskSchedulerBackend {
    private typealias LaunchHandler = @MainActor (PremiumBackgroundTaskLaunch) -> Void

    var registrationResults: [String: [Bool]] = [:]
    var submissionFailureIdentifiers: Set<String> = []
    private(set) var registrationAttempts: [String] = []
    private(set) var submissionAttempts: [PremiumBackgroundTaskRequestDescriptor] = []
    private(set) var cancellationAttempts: [String] = []
    private(set) var pendingRequests: [String: [PremiumBackgroundTaskRequestDescriptor]] = [:]
    private var launchHandlers: [String: LaunchHandler] = [:]

    var pendingRequestCount: Int {
        pendingRequests.values.reduce(0) { $0 + $1.count }
    }

    func register(
        forTaskWithIdentifier identifier: String,
        launchHandler: @escaping @MainActor (PremiumBackgroundTaskLaunch) -> Void
    ) -> Bool {
        registrationAttempts.append(identifier)
        let result: Bool
        if var configuredResults = registrationResults[identifier], !configuredResults.isEmpty {
            result = configuredResults.removeFirst()
            registrationResults[identifier] = configuredResults
        } else {
            result = true
        }
        if result {
            launchHandlers[identifier] = launchHandler
        }
        return result
    }

    func submit(_ request: PremiumBackgroundTaskRequestDescriptor) throws {
        submissionAttempts.append(request)
        if submissionFailureIdentifiers.contains(request.identifier) {
            throw RecordingBackgroundTaskSubmissionError()
        }
        pendingRequests[request.identifier, default: []].append(request)
    }

    func cancel(taskRequestWithIdentifier identifier: String) {
        cancellationAttempts.append(identifier)
        pendingRequests.removeValue(forKey: identifier)
    }

    @discardableResult
    func launch(
        identifier: String,
        kind: PremiumBackgroundTaskKind?,
        task: any PremiumBackgroundProcessingTask
    ) -> Bool {
        guard let launchHandler = launchHandlers[identifier] else { return false }
        launchHandler(PremiumBackgroundTaskLaunch(kind: kind, task: task))
        return true
    }

    func resetCancellationAttempts() {
        cancellationAttempts.removeAll()
    }
}
