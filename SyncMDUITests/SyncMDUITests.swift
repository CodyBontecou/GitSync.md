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

    // MARK: - Onboarding Progression

    /// Issue #19 slice: every onboarding page's key controls (forward action,
    /// skip escape hatch, page copy) are discoverable as accessibility
    /// elements by label — not by visual position — and completing the flow
    /// (Continue without GitHub → Skip for Now) lands in the repo list.
    func testOnboardingProgressionExposesPageControlsAndLandsInRepoList() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-SignedOutUITest",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        // First slide: hero copy and both bottom controls must be exposed as
        // labelled accessibility elements.
        let continueButton = button(app, labels: ["Continue", "CONTINUE"])
        XCTAssertTrue(
            continueButton.waitForExistence(timeout: 10),
            "Onboarding forward action should be discoverable as a labelled button"
        )
        XCTAssertTrue(
            button(app, labels: ["Skip", "SKIP"]).exists,
            "Onboarding Skip escape hatch should be discoverable as a labelled button"
        )
        XCTAssertTrue(
            app.staticTexts["YOUR REPOS, ON YOUR IPHONE"].exists,
            "First onboarding slide subtitle should be exposed to accessibility"
        )

        advanceOnboardingToSignIn(
            in: app,
            expectingSlideSubtitles: ["MARKDOWN-FIRST WORKFLOW", "BRANCHES, DIFFS, HISTORY"]
        )

        // The final sign-in step exposes each auth choice as a labelled button.
        XCTAssertTrue(
            button(app, labels: ["Personal Access Token", "PERSONAL ACCESS TOKEN"]).exists,
            "PAT sign-in option should be discoverable as a labelled button"
        )
        XCTAssertTrue(
            button(app, labels: ["Continue without GitHub", "CONTINUE WITHOUT GITHUB"]).exists,
            "Continue-without-GitHub option should be discoverable as a labelled button"
        )
        XCTAssertTrue(
            button(app, labels: ["Try Demo", "TRY DEMO"]).exists,
            "First-run Try Demo affordance should be discoverable as a labelled button"
        )

        let continueWithout = button(app, labels: ["Continue without GitHub", "CONTINUE WITHOUT GITHUB"])
        XCTAssertTrue(
            continueWithout.waitForExistence(timeout: 8),
            "Sign-in step should expose Continue without GitHub"
        )
        for _ in 0..<4 where !continueWithout.isHittable { app.swipeUp() }
        continueWithout.tap()

        // Save-location step: both controls discoverable before finishing.
        let skipForNow = button(app, labels: ["Skip for Now", "SKIP FOR NOW"])
        XCTAssertTrue(
            skipForNow.waitForExistence(timeout: 8),
            "Save-location step should expose its Skip for Now control"
        )
        XCTAssertTrue(
            button(app, labels: ["Choose Location", "CHOOSE LOCATION"]).exists,
            "Save-location step should expose its Choose Location control"
        )
        skipForNow.tap()

        // Completing onboarding swaps in the repo list, whose signed-out
        // toolbar actions are labelled buttons.
        XCTAssertTrue(
            app.buttons["App Settings"].waitForExistence(timeout: 10),
            "Completing onboarding should land in the repository list"
        )
        XCTAssertTrue(
            app.buttons["Sign In"].exists,
            "Signed-out repo list should expose the Sign In toolbar action"
        )
        XCTAssertTrue(
            app.staticTexts["NO REPOSITORIES"].exists || app.staticTexts["No Repositories"].exists,
            "Empty repo list state should be exposed to accessibility"
        )
    }

    // MARK: - Premium (Background Sync) Surface

    /// Issue #19 slice (paywall): the app has no purchasable paywall — the
    /// premium surface is PremiumSettingsView (Background Sync), which ships
    /// included with no StoreKit products, so there are no upgrade/restore
    /// purchase controls to assert. This test pins that surface's key
    /// controls (close, consent toggle) are discoverable by label, using only
    /// launch-arg-seeded signed-out state — no network, no purchase actions.
    func testBackgroundSyncPremiumSurfaceControlsAreDiscoverable() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-SignedOutUITest",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        completeOnboardingIfPresent(in: app)
        continueWithoutGitHubIfPresent(in: app)

        let appSettings = app.buttons["App Settings"]
        XCTAssertTrue(appSettings.waitForExistence(timeout: 10), "Signed-out App Settings action should exist")
        appSettings.tap()

        // The Background Sync management surface opens from an
        // accessibility-labelled action row in App Settings.
        let backgroundSyncRow = app.buttons["Background Sync"]
        XCTAssertTrue(
            backgroundSyncRow.waitForExistence(timeout: 10),
            "App Settings should expose the Background Sync (premium) row as a labelled button"
        )
        for _ in 0..<6 where !backgroundSyncRow.isHittable { app.swipeUp() }
        backgroundSyncRow.tap()

        // Premium surface is up: titled chrome, close control, and the main
        // consent control are all discoverable by label.
        XCTAssertTrue(
            app.staticTexts["BACKGROUND SYNC"].exists || app.buttons["BACKGROUND SYNC"].exists,
            "Premium surface should expose its titled chrome to accessibility"
        )
        XCTAssertTrue(
            app.buttons["Done"].firstMatch.waitForExistence(timeout: 10),
            "Premium surface should expose a Done close control"
        )
        let enableSyncControl = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH 'Enable Background Sync'")
        ).firstMatch
        XCTAssertTrue(
            enableSyncControl.waitForExistence(timeout: 10) || app.staticTexts["Enable Background Sync"].exists,
            "Enable Background Sync consent control should be discoverable by label"
        )

        // Close via the tappable Done (the presenting App Settings sheet has
        // its own Done behind this one) and confirm we return to App Settings.
        tapFirstHittableButton(labeled: "Done", in: app)
        XCTAssertTrue(
            backgroundSyncRow.waitForExistence(timeout: 10),
            "Done should close the premium surface and return to App Settings"
        )
    }

    // MARK: - File Edit Flow

    /// Issue #19 slice: using the seeded `-FileBrowserUITest` vault, navigate
    /// to a file, edit it in the editor, and save — asserting the editor's
    /// chrome (title, Save) is discoverable by label, Save gates on the dirty
    /// state, and the saved edit persists across leaving and reopening the
    /// file. No network or credentials are involved.
    func testFileEditorSaveFlowPersistsEditToSeededFile() {
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
        tap("app-launch.md", in: app)

        // Editor chrome is discoverable by accessibility label.
        XCTAssertTrue(
            app.staticTexts["APP-LAUNCH.MD"].waitForExistence(timeout: 10),
            "Editor should expose the open file's name as its title"
        )
        let save = app.buttons["Save"]
        XCTAssertTrue(
            save.waitForExistence(timeout: 5),
            "Editor Save action should be discoverable as a labelled button"
        )
        usleep(300_000) // let the initial (clean) editor state settle
        XCTAssertFalse(save.isEnabled, "Save should start disabled while the document is clean")

        // Enter edit mode and modify the text.
        let editor = app.textViews.firstMatch
        XCTAssertTrue(
            editor.waitForExistence(timeout: 5),
            "Editor text surface should be exposed to accessibility"
        )
        editor.tap()
        let marker = "\nSaved by SyncMDUITests."
        editor.typeText(marker)

        // Save lights up only once the document is dirty.
        let saveEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: save
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [saveEnabled], timeout: 10),
            .completed,
            "Save should become enabled after an edit"
        )
        save.tap()

        // Save completes asynchronously and settles the editor back to its
        // clean state (the transient "SAVED" toast is too short-lived to
        // assert deterministically under XCUITest idle-waiting).
        let saveSettled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == false"),
            object: save
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [saveSettled], timeout: 10),
            .completed,
            "Save should complete and return the editor to its clean state"
        )

        // Persisted state: leave the editor and reopen the file — the marker
        // survives because Save wrote through to the seeded vault.
        let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
        XCTAssertTrue(back.waitForExistence(timeout: 5), "Editor should expose a back navigation control")
        back.tap()
        tap("app-launch.md", in: app)

        let reopened = app.textViews.firstMatch
        XCTAssertTrue(
            reopened.waitForExistence(timeout: 10),
            "Reopened file should expose its text to accessibility"
        )
        let reopenedContent = (reopened.value as? String) ?? ""
        XCTAssertTrue(
            reopenedContent.contains("Saved by SyncMDUITests."),
            "Reopened file should contain the saved edit (got: \(reopenedContent.prefix(120)))"
        )
    }

    // MARK: - Signed-Out App Settings Access

    /// Regression test for the simulator-QA finding: users who chose
    /// "Continue without GitHub" previously had no route to App Settings even
    /// though Background Sync and manual repository features support non-GitHub remotes.
    func testSignedOutUserCanOpenAppSettingsAndSeeBackgroundSync() {
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
        XCTAssertTrue(appSettings.waitForExistence(timeout: 10), "Signed-out App Settings toolbar action should exist")
        let appSettingsIsHittable = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isHittable == true"),
            object: appSettings
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [appSettingsIsHittable], timeout: 10),
            .completed,
            "Signed-out App Settings toolbar action should become tappable"
        )

        appSettings.tap()

        let settingsSignIn = app.buttons["Sign in with GitHub"]
        XCTAssertTrue(settingsSignIn.waitForExistence(timeout: 10), "App Settings should offer GitHub sign-in when signed out")
        XCTAssertTrue(
            app.staticTexts["BACKGROUND SYNC"].exists || app.buttons["Background Sync"].exists,
            "Background Sync should be available from signed-out App Settings"
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

    /// Taps Continue / Get Started until the embedded sign-in step of the
    /// onboarding paged flow is showing, asserting each informational slide's
    /// subtitle while that page is current (TabView materializes only nearby
    /// pages, so offscreen slides are not queryable afterwards). Works with or
    /// without the optional Background Sync feature slide.
    private func advanceOnboardingToSignIn(
        in app: XCUIApplication,
        expectingSlideSubtitles subtitles: [String]
    ) {
        let signInStep = button(app, labels: ["Sign in with GitHub", "SIGN IN WITH GITHUB"])
        var pendingSubtitles = subtitles
        var taps = 0
        while !signInStep.exists, taps < 6 {
            let advance = button(app, labels: ["Continue", "CONTINUE", "Get Started", "GET STARTED"])
            XCTAssertTrue(
                advance.exists,
                "Onboarding slides should always expose a labelled forward control"
            )
            for _ in 0..<4 where !advance.isHittable { app.swipeUp() }
            advance.tap()
            taps += 1
            if !pendingSubtitles.isEmpty {
                let subtitle = pendingSubtitles.removeFirst()
                XCTAssertTrue(
                    app.staticTexts[subtitle].waitForExistence(timeout: 5),
                    "Onboarding slide copy \(subtitle) should be exposed to accessibility while shown"
                )
            }
            _ = signInStep.waitForExistence(timeout: 2)
        }
        XCTAssertTrue(
            pendingSubtitles.isEmpty,
            "Every informational onboarding slide should have been walked"
        )
        XCTAssertTrue(
            signInStep.waitForExistence(timeout: 5),
            "Walking onboarding slides should reach the sign-in step"
        )
    }

    /// Taps the first hittable button with the given label — used when a
    /// presented sheet and its presenting view both expose the same control
    /// (e.g. the two "Done" toolbar buttons around the premium sheet).
    private func tapFirstHittableButton(labeled label: String, in app: XCUIApplication) {
        let candidates = app.buttons.matching(NSPredicate(format: "label == %@", label))
        for index in 0..<max(candidates.count, 1) {
            let candidate = candidates.element(boundBy: index)
            if candidate.exists, candidate.isHittable {
                candidate.tap()
                return
            }
        }
        XCTFail("Expected a tappable \(label) button")
    }
}
