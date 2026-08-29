import XCTest

final class SyncMDUITests: XCTestCase {
    func testLaunch() {
        let app = XCUIApplication()
        app.launch()
        XCTAssertEqual(app.state, .runningForeground)
    }

    func testFileBrowserNavigatesToDeeplyNestedFile() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-FileBrowserUITest",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        tap("second-brain", in: app)
        tap("Browse Files", in: app)
        tap("projects", in: app)
        tap("mobile", in: app)
        tap("client", in: app)
        tap("screens", in: app)

        XCTAssertTrue(
            app.staticTexts["deep-note.md"].waitForExistence(timeout: 5),
            "The browser should list files more than one folder deep"
        )
    }

    private func tap(_ label: String, in app: XCUIApplication) {
        let element = app.staticTexts[label]
        guard element.waitForExistence(timeout: 5) else {
            XCTFail("Expected to find \(label)")
            return
        }

        for _ in 0..<4 where !element.isHittable {
            app.swipeUp()
        }
        guard element.isHittable else {
            XCTFail("Expected \(label) to be tappable")
            return
        }
        element.tap()
    }

    // MARK: - Signed-Out App Settings Access

    /// Regression test for the simulator-QA finding: users who chose
    /// "Continue without GitHub" previously had no route to App Settings even
    /// though Assist and manual repository features support non-GitHub remotes.
    /// With the release feature flag off, Assist must also stay hidden.
    func testSignedOutUserCanOpenAppSettingsWhileAssistIsGated() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-SignedOutUITest",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        completeOnboardingIfPresent(in: app)
        continueWithoutGitHubIfPresent(in: app)

        // Signed-out toolbar exposes both 44pt actions.
        let signIn = app.buttons["Sign In"]
        let appSettings = app.buttons["App Settings"]
        XCTAssertTrue(signIn.waitForExistence(timeout: 10), "Signed-out Sign In toolbar action should exist")
        XCTAssertTrue(appSettings.exists, "Signed-out App Settings toolbar action should exist")

        appSettings.tap()

        let settingsSignIn = app.buttons["Sign in with GitHub"]
        XCTAssertTrue(settingsSignIn.waitForExistence(timeout: 10), "App Settings should offer GitHub sign-in when signed out")
        // Release gating: the Assist entry point must not exist while the
        // feature flag is off.
        XCTAssertFalse(
            app.staticTexts["GITSYNC ASSIST"].exists || app.buttons["GitSync Assist"].exists,
            "Assist UI must stay hidden while gitSyncAssistEnabled is false"
        )

        let done = app.buttons["Done"]
        if done.waitForExistence(timeout: 3) {
            done.tap()
        }
    }

    private func completeOnboardingIfPresent(in app: XCUIApplication) {
        let skip = button(app, labels: ["Skip", "SKIP"])
        guard skip.waitForExistence(timeout: 8) else { return }
        for _ in 0..<4 where !skip.isHittable { app.swipeUp() }
        skip.tap()
        // Wait for onboarding to disappear; retry once if the first tap was
        // swallowed by a page transition.
        let gone = NSPredicate(format: "exists == false")
        let expectation = XCTNSPredicateExpectation(predicate: gone, object: skip)
        let result = XCTWaiter().wait(for: [expectation], timeout: 5)
        if result == .timedOut, skip.exists, skip.isHittable {
            skip.tap()
        }
    }

    private func continueWithoutGitHubIfPresent(in app: XCUIApplication) {
        let continueWithout = button(app, labels: ["Continue without GitHub", "CONTINUE WITHOUT GITHUB"])
        if continueWithout.waitForExistence(timeout: 8) {
            for _ in 0..<4 where !continueWithout.isHittable { app.swipeUp() }
            continueWithout.tap()
        }
        // Setup flow's second step: default save location.
        let skipLocation = button(app, labels: ["Skip for Now", "SKIP FOR NOW"])
        if skipLocation.waitForExistence(timeout: 8) {
            for _ in 0..<4 where !skipLocation.isHittable { app.swipeUp() }
            skipLocation.tap()
        }
    }

    private func button(_ app: XCUIApplication, labels: [String]) -> XCUIElement {
        let query = app.descendants(matching: .button).matching(
            NSPredicate(format: "label IN %@", labels)
        )
        return query.firstMatch
    }
}
