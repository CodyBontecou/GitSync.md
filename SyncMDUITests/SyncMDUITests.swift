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

    // MARK: - Background Sync Behavior

    /// Exercises the installation-global Background Sync consent and action
    /// preferences without a repository, so no Git transport or publication
    /// can occur. The starting state is normalized through the UI because the
    /// simulator's UserDefaults intentionally survive UI-test launches.
    func testBackgroundSyncBehaviorPersistsAndResetsSafeDefaults() {
        let app = XCUIApplication()
        app.launchArguments = signedOutLaunchArguments(extra: ["-UITestCloneFixture"])
        app.launch()
        finishSignedOutEmptyLaunch(in: app)

        var globalToggle = openBackgroundSyncSettings(in: app)
        normalizeBackgroundSyncOff(globalToggle: globalToggle, in: app)

        let pullToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Pull remote changes"
        )
        let pushToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Commit and push local changes"
        )

        // First consent request is cancelled: the branded modal disappears,
        // global mode remains off, and action controls remain unavailable.
        tapWhenHittable(globalToggle, in: app, message: "Enable Background Sync toggle should be tappable")
        assertBrandedConfirmation(
            title: "Enable Background Sync for all repositories?",
            in: app
        )
        tapFirstHittableButton(labeled: "Cancel", in: app)
        XCTAssertTrue(
            waitUntil(timeout: 5) {
                !app.staticTexts["Enable Background Sync for all repositories?"].exists
            },
            "Cancelling should dismiss the Background Sync confirmation"
        )
        assertSwitch(globalToggle, isOn: false, "Cancelling global consent should leave Background Sync off")
        XCTAssertFalse(pullToggle.exists, "Pull control should stay hidden after cancelled global consent")
        XCTAssertFalse(pushToggle.exists, "Push control should stay hidden after cancelled global consent")

        // Confirming global consent starts with fail-closed publishing:
        // automatic pull on, automatic commit/push off.
        tapWhenHittable(globalToggle, in: app, message: "Enable Background Sync toggle should remain tappable")
        assertBrandedConfirmation(
            title: "Enable Background Sync for all repositories?",
            in: app
        )
        tapFirstHittableButton(labeled: "Enable Background Sync", in: app)
        assertSwitch(globalToggle, isOn: true, "Confirming should enable Background Sync")
        XCTAssertTrue(
            reveal(pullToggle, in: app),
            "Enabled Background Sync should expose the automatic pull control"
        )
        assertSwitch(pullToggle, isOn: true, "A fresh activation should default automatic pull on")
        XCTAssertTrue(
            reveal(pushToggle, in: app),
            "Enabled Background Sync should expose the automatic push control"
        )
        assertSwitch(pushToggle, isOn: false, "A fresh activation should default automatic push off")

        // Publishing has its own explicit consent. Cancel once, then request
        // again and confirm. The empty repository list makes this incapable of
        // publishing even after the preference turns on.
        tapWhenHittable(pushToggle, in: app, message: "Automatic push toggle should be tappable")
        assertBrandedConfirmation(
            title: "Automatically commit and push local changes?",
            in: app
        )
        tapFirstHittableButton(labeled: "Cancel", in: app)
        XCTAssertTrue(
            waitUntil(timeout: 5) {
                !app.staticTexts["Automatically commit and push local changes?"].exists
            },
            "Cancelling should dismiss the automatic push confirmation"
        )
        assertSwitch(pushToggle, isOn: false, "Cancelling publishing consent should leave push off")

        tapWhenHittable(pushToggle, in: app, message: "Automatic push toggle should remain tappable")
        assertBrandedConfirmation(
            title: "Automatically commit and push local changes?",
            in: app
        )
        tapFirstHittableButton(labeled: "Enable automatic push", in: app)
        assertSwitch(pushToggle, isOn: true, "Confirming publishing consent should turn push on")

        // Pull and push are independent. Push stays on when pull turns off;
        // with both actions off, the UI must expose the no-operation warning.
        tapWhenHittable(pullToggle, in: app, message: "Automatic pull toggle should be tappable")
        assertSwitch(pullToggle, isOn: false, "Automatic pull should turn off independently")
        assertSwitch(pushToggle, isOn: true, "Turning pull off must not revoke push consent")

        tapWhenHittable(pushToggle, in: app, message: "Enabled automatic push toggle should be tappable")
        assertSwitch(pushToggle, isOn: false, "Automatic push should turn off independently")
        assertSwitch(pullToggle, isOn: false, "Turning push off must leave pull off")
        let neitherActionWarning = app.staticTexts[
            "Both automatic actions are off. No background Git operation will run."
        ]
        XCTAssertTrue(
            reveal(neitherActionWarning, in: app),
            "The visible surface should warn when neither automatic action can run"
        )

        // Restore an intentionally non-default state so relaunch proves both
        // independent preferences, not merely the global bit, persisted.
        tapWhenHittable(pullToggle, in: app, message: "Automatic pull toggle should be restorable")
        assertSwitch(pullToggle, isOn: true, "Automatic pull should restore to on")
        XCTAssertTrue(
            waitUntil(timeout: 5) { !neitherActionWarning.exists },
            "The neither-action warning should clear once pull is restored"
        )
        tapWhenHittable(pushToggle, in: app, message: "Automatic push toggle should be restorable")
        assertBrandedConfirmation(
            title: "Automatically commit and push local changes?",
            in: app
        )
        tapFirstHittableButton(labeled: "Enable automatic push", in: app)
        assertSwitch(pushToggle, isOn: true, "Automatic push consent should restore to on")
        assertSwitch(pullToggle, isOn: true, "Restoring push must leave pull on")

        // UserDefaults persistence boundary: terminate, relaunch into another
        // UI-seeded empty signed-out state, and reopen the settings surface.
        app.terminate()
        app.launch()
        finishSignedOutEmptyLaunch(in: app)

        globalToggle = openBackgroundSyncSettings(in: app)
        let persistedPullToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Pull remote changes"
        )
        let persistedPushToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Commit and push local changes"
        )
        assertSwitch(globalToggle, isOn: true, "Global Background Sync should persist across relaunch")
        XCTAssertTrue(reveal(persistedPullToggle, in: app), "Persisted pull control should be visible")
        assertSwitch(persistedPullToggle, isOn: true, "Automatic pull should persist across relaunch")
        XCTAssertTrue(reveal(persistedPushToggle, in: app), "Persisted push control should be visible")
        assertSwitch(persistedPushToggle, isOn: true, "Automatic push should persist across relaunch")

        // Return to a freshly presented sheet before tapping the global row;
        // the prior assertions leave the long action card scrolled near its
        // bottom, where iOS can report the offscreen global switch hittable.
        globalToggle = reopenBackgroundSyncFromAppSettings(in: app)
        assertSwitch(globalToggle, isOn: true, "Global preference should remain on before explicit disable")

        // Global disable revokes both action preferences. Re-enabling must
        // therefore return to the safe pull-on / push-off defaults.
        setSwitchDirectly(
            globalToggle,
            to: false,
            in: app,
            message: "Global disable should turn Background Sync off"
        )
        XCTAssertTrue(
            waitUntil(timeout: 5) { !persistedPullToggle.exists && !persistedPushToggle.exists },
            "Global disable should remove both reset action controls"
        )
        XCTAssertTrue(
            reveal(
                app.staticTexts[
                    "Background Sync is off. Manual Git, Shortcuts, callbacks, and local repository features are unchanged."
                ],
                in: app
            ),
            "Global disable should expose the stable off-state copy"
        )

        // Reopen after the conditional pull/push rows collapse. This gives
        // XCUITest a fresh switch snapshot instead of tapping through a
        // just-mutated scroll hierarchy.
        globalToggle = reopenBackgroundSyncFromAppSettings(in: app)
        assertSwitch(globalToggle, isOn: false, "Global disable should persist across reopening the sheet")
        tapWhenHittable(globalToggle, in: app, message: "Disabled global toggle should be re-enableable")
        assertBrandedConfirmation(
            title: "Enable Background Sync for all repositories?",
            in: app
        )
        tapFirstHittableButton(labeled: "Enable Background Sync", in: app)
        assertSwitch(globalToggle, isOn: true, "Background Sync should re-enable")
        XCTAssertTrue(reveal(persistedPullToggle, in: app), "Re-enabled pull control should return")
        assertSwitch(persistedPullToggle, isOn: true, "Re-enable should restore the safe pull-on default")
        XCTAssertTrue(reveal(persistedPushToggle, in: app), "Re-enabled push control should return")
        assertSwitch(persistedPushToggle, isOn: false, "Re-enable should keep publishing default-off")

        // Fail-closed test cleanup: leave installation-global mode off. This
        // also resets both independent action preferences for later tests.
        globalToggle = reopenBackgroundSyncFromAppSettings(in: app)
        setSwitchDirectly(
            globalToggle,
            to: false,
            in: app,
            message: "Background Sync should be off at test completion"
        )
        XCTAssertTrue(
            waitUntil(timeout: 5) { !persistedPullToggle.exists && !persistedPushToggle.exists },
            "Cleanup should leave no automatic action control enabled"
        )
        closeBackgroundSyncAndAppSettings(in: app)
    }

    /// The local `file://` conflict fixture is used only as persisted settings
    /// data here. No Pull, Push, Sync now, merge, rebase, notification, or
    /// other transport control is touched.
    func testRepositoryBackgroundSyncExclusionPersistsAndRestoresWhileGlobalModeIsOff() {
        let app = XCUIApplication()

        // Normalize installation preferences before introducing any managed
        // repository, then relaunch into the local fixture with global mode off.
        app.launchArguments = signedOutLaunchArguments(extra: ["-UITestCloneFixture"])
        app.launch()
        finishSignedOutEmptyLaunch(in: app)
        let emptyGlobalToggle = openBackgroundSyncSettings(in: app)
        normalizeBackgroundSyncOff(globalToggle: emptyGlobalToggle, in: app)
        closeBackgroundSyncAndAppSettings(in: app)
        app.terminate()

        app.launchArguments = signedOutLaunchArguments(extra: ["-UITestConflictFixture"])
        app.launch()
        finishConflictFixtureLaunch(in: app)

        // Verify the fixture arrived under the normalized global-off gate
        // before opening its repository settings.
        let fixtureGlobalToggle = openBackgroundSyncSettings(in: app)
        assertSwitch(fixtureGlobalToggle, isOn: false, "Fixture should start with global mode off")
        XCTAssertFalse(
            backgroundSyncSwitch(in: app, labelPrefix: "Pull remote changes").exists,
            "Global-off fixture should expose no automatic pull control"
        )
        XCTAssertFalse(
            backgroundSyncSwitch(in: app, labelPrefix: "Commit and push local changes").exists,
            "Global-off fixture should expose no automatic push control"
        )
        closeBackgroundSyncAndAppSettings(in: app)

        tap("conflict-fixture", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Seeded local repository should open without a Git action"
        )

        var inclusionToggle = openRepositorySettingsAndRevealBackgroundSync(in: app)
        assertSwitch(inclusionToggle, isOn: true, "The new fixture should initially be included")
        tapWhenHittable(inclusionToggle, in: app, message: "Repository inclusion toggle should be tappable")
        assertSwitch(inclusionToggle, isOn: false, "Repository should be locally excluded")
        tapFirstHittableButton(labeled: "Save", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Saving exclusion should dismiss back to the repository"
        )

        inclusionToggle = openRepositorySettingsAndRevealBackgroundSync(in: app)
        assertSwitch(inclusionToggle, isOn: false, "Exclusion should persist after reopening Settings")
        tapWhenHittable(inclusionToggle, in: app, message: "Repository inclusion should be restorable")
        assertSwitch(inclusionToggle, isOn: true, "Repository inclusion should restore locally")
        tapFirstHittableButton(labeled: "Save", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Saving restored inclusion should dismiss back to the repository"
        )

        inclusionToggle = openRepositorySettingsAndRevealBackgroundSync(in: app)
        assertSwitch(inclusionToggle, isOn: true, "Restored inclusion should persist after reopening Settings")
        tapFirstHittableButton(labeled: "Cancel", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Cancel should close the final read-only Settings check"
        )

        let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
        tapWhenHittable(back, in: app, message: "Repository view should expose labelled back navigation")
        XCTAssertTrue(
            app.staticTexts["conflict-fixture"].waitForExistence(timeout: 10),
            "Fixture should remain listed after restoring inclusion"
        )

        let finalGlobalToggle = openBackgroundSyncSettings(in: app)
        assertSwitch(finalGlobalToggle, isOn: false, "Per-repository test must leave global mode off")
        XCTAssertFalse(
            backgroundSyncSwitch(in: app, labelPrefix: "Pull remote changes").exists,
            "Per-repository cleanup should leave automatic pull unavailable"
        )
        XCTAssertFalse(
            backgroundSyncSwitch(in: app, labelPrefix: "Commit and push local changes").exists,
            "Per-repository cleanup should leave automatic push unavailable"
        )
        closeBackgroundSyncAndAppSettings(in: app)
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

    // MARK: - Repo Clone Flow (Issue #19)

    /// Issue #19 clone-flow slice: from the signed-out repo list, add a
    /// repository by manual `file://` URL pointing at the `-UITestCloneFixture`
    /// bare remote (a REAL git repository seeded under /tmp), with `none`
    /// credentials — no network, no GitHub. Asserts the Add form's key controls
    /// are discoverable by label, the clone completes into the repo list, and
    /// the cloned repo's files are browsable.
    func testCloneFlowAddsRepositoryFromLocalFileRemote() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-SignedOutUITest",
            "-UITestCloneFixture",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        // Seeding completes in ContentView.onAppear, which may briefly flash
        // onboarding before the seeded (onboarded) state lands.
        if button(app, labels: ["Skip", "SKIP"]).waitForExistence(timeout: 3) {
            completeOnboardingIfPresent(in: app)
            continueWithoutGitHubIfPresent(in: app)
        }

        // Signed-out empty repo list exposes its copy and Add action by label
        // (the label varies if a "previously cloned" ghost row is present).
        XCTAssertTrue(
            app.staticTexts["NO REPOSITORIES"].waitForExistence(timeout: 10),
            "Seeded signed-out repo list should expose the empty state copy"
        )
        let addRepository = app.buttons.matching(
            NSPredicate(format: "label CONTAINS 'ADD' AND label CONTAINS 'REPOSITORY'")
        ).firstMatch
        XCTAssertTrue(
            addRepository.waitForExistence(timeout: 10),
            "Repo list should expose the Add Repository action as a labelled button"
        )
        for _ in 0..<4 where !addRepository.isHittable { app.swipeUp() }
        addRepository.tap()

        // AddRepoView chrome is discoverable by label.
        XCTAssertTrue(
            app.staticTexts["ADD REPOSITORY"].waitForExistence(timeout: 10),
            "Add Repository sheet should expose its titled chrome"
        )
        let enterURL = app.staticTexts["ENTER URL MANUALLY"]
        XCTAssertTrue(
            enterURL.waitForExistence(timeout: 10),
            "Manual URL entry affordance should be discoverable by label"
        )
        for _ in 0..<4 where !enterURL.isHittable { app.swipeUp() }
        enterURL.tap()

        // Type the local file remote. `GitRemoteURL.parse` accepts file://
        // URLs, so the INVALID URL badge must stay absent.
        let urlField = app.textFields.firstMatch
        XCTAssertTrue(
            urlField.waitForExistence(timeout: 10),
            "Manual URL entry should expose a text field"
        )
        urlField.tap()
        urlField.typeText("file:///tmp/syncmd-uitest-fixtures/bare-remote.git")
        urlField.typeText("\n") // dismiss the keyboard for the sections below
        XCTAssertFalse(
            app.staticTexts["INVALID URL"].waitForExistence(timeout: 1),
            "A file:// remote URL should validate"
        )

        // Configuration and authentication sections surface by label. The
        // "No Authentication" method is the credential-free default for a
        // non-GitHub remote; author defaults arrive from the seeded state.
        XCTAssertTrue(
            app.staticTexts["BRANCH"].waitForExistence(timeout: 10),
            "Configuration section should expose the Branch field label"
        )
        let noAuthentication = app.staticTexts["No Authentication"]
        XCTAssertTrue(
            noAuthentication.waitForExistence(timeout: 10),
            "Credential-free (No Authentication) method should be discoverable by label"
        )
        for _ in 0..<6 where !noAuthentication.isHittable { app.swipeUp() }
        XCTAssertTrue(
            app.staticTexts["CLONE TO"].exists,
            "Clone location section should be exposed to accessibility"
        )

        // Submit: Add & Clone Repository runs a real libgit2 clone against the
        // local bare remote with `none` credentials.
        let addAndClone = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "ADD & CLONE REPOSITORY")
        ).firstMatch
        XCTAssertTrue(
            addAndClone.waitForExistence(timeout: 10),
            "Add & Clone action should be discoverable as a labelled button"
        )
        let cloneEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: addAndClone
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [cloneEnabled], timeout: 10),
            .completed,
            "Add & Clone should be enabled for a valid file:// remote with no credentials"
        )
        for _ in 0..<6 where !addAndClone.isHittable { app.swipeUp() }
        addAndClone.tap()

        // The clone lands the repo in the list. Wait for the stable cloned
        // state (the transient syncing badge is too short-lived to assert).
        let repoCard = app.staticTexts["bare-remote"]
        XCTAssertTrue(
            repoCard.waitForExistence(timeout: 20),
            "Cloned repository should appear in the repo list"
        )
        let cloneSettled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: app.staticTexts["syncing"]
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [cloneSettled], timeout: 30),
            .completed,
            "Clone should settle (syncing indicator should clear)"
        )
        tap("bare-remote", in: app)

        // The cloned repo opens with its health card and file browser, and the
        // cloned files are browsable — proving the clone wrote real content.
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Cloned repo should land in the vault view"
        )
        tap("Browse Files", in: app)
        XCTAssertTrue(
            app.staticTexts["README.md"].waitForExistence(timeout: 10),
            "File browser should list the cloned README.md"
        )
        XCTAssertTrue(
            app.staticTexts["notes"].exists,
            "File browser should list the cloned notes directory"
        )
    }

    // MARK: - Conflict Resolver (Issue #19)

    /// Issue #19 conflict-resolver slice: the `-UITestConflictFixture` launch
    /// arg seeds a real working copy whose local commit diverged from its
    /// local bare remote. Pull reports divergence, Merge produces a genuine
    /// conflict, and the resolver's controls (ours/theirs panes, USE
    /// OURS/THEIRS, RESOLVE, confirm) are discoverable by label. Resolving
    /// with theirs stages a real change and clears the conflict (Conflict
    /// Center: "All conflicts resolved") with the Abort escape hatch still
    /// discoverable. No network, no credentials, no pushes.
    func testConflictResolverControlsDiscoverableAndKeepTheirsClearsConflict() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestConflictFixture",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        // Seeded divergent repo is in the list; open it.
        tap("conflict-fixture", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Seeded repo should open into the vault view"
        )

        // Pull against the local bare remote classifies the fixture as
        // diverged and surfaces Merge/Rebase controls by label.
        tap("Pull", in: app)
        XCTAssertTrue(
            app.staticTexts[
                "Local and remote have diverged. Merge support is required to continue."
            ].waitForExistence(timeout: 20),
            "Pull on the diverged fixture should surface the diverged outcome"
        )
        let merge = app.buttons["MERGE"]
        XCTAssertTrue(
            merge.waitForExistence(timeout: 10),
            "Diverged banner should expose the Merge action as a labelled button"
        )
        XCTAssertTrue(
            app.buttons["REBASE"].exists,
            "Diverged banner should expose the Rebase action as a labelled button"
        )

        // Merging the diverged fixture produces a genuine conflict session.
        // (The banner can appear while the pull's progress delay still holds
        // isSyncing, so wait for enabled, not merely hittable.)
        let mergeReady = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"),
            object: merge
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [mergeReady], timeout: 10),
            .completed,
            "Merge action should become enabled and tappable"
        )
        merge.tap()
        XCTAssertTrue(
            app.staticTexts[
                "Merge has conflicts — tap a conflicted file to resolve"
            ].waitForExistence(timeout: 20),
            "Merging the fixture should surface the conflict outcome"
        )

        // The conflicted file is listed; its row exposes the conflict state
        // through its accessibility label (path + status summary + CONFLICT
        // badge are combined by SwiftUI into the row button's label).
        let conflictedRow = app.staticTexts["notes/shared.md"]
        XCTAssertTrue(
            conflictedRow.waitForExistence(timeout: 10),
            "Conflicted file should be listed in Changed Files"
        )
        let conflictedRowButton = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "notes/shared.md,")
        ).firstMatch
        XCTAssertTrue(
            conflictedRowButton.exists,
            "Conflicted file row should be discoverable as a labelled element"
        )
        XCTAssertTrue(
            conflictedRowButton.label.contains("CONFLICT"),
            "Conflicted file row should expose its Conflict badge by label (got: \(conflictedRowButton.label))"
        )

        // Open the resolver by tapping the conflicted file.
        tap("notes/shared.md", in: app)

        // Resolver chrome and both sides are discoverable by label.
        XCTAssertTrue(
            app.staticTexts["RESOLVE CONFLICT"].waitForExistence(timeout: 10),
            "Conflict editor should expose its titled chrome"
        )
        XCTAssertTrue(
            app.staticTexts["OURS"].waitForExistence(timeout: 10),
            "Ours pane should be discoverable by label"
        )
        XCTAssertTrue(
            app.staticTexts["THEIRS"].exists,
            "Theirs pane should be discoverable by label"
        )
        XCTAssertTrue(
            app.staticTexts["your version"].exists,
            "Ours pane should expose its descriptive subtitle"
        )
        XCTAssertTrue(
            app.staticTexts["remote version"].exists,
            "Theirs pane should expose its descriptive subtitle"
        )
        XCTAssertTrue(
            app.buttons["USE THIS"].firstMatch.exists,
            "Per-pane USE THIS pickers should be discoverable by label"
        )
        XCTAssertTrue(
            app.staticTexts["RESULT"].exists,
            "Result editor section should be exposed to accessibility"
        )
        XCTAssertTrue(
            app.buttons["USE OURS"].exists,
            "USE OURS picker should be discoverable by label"
        )
        XCTAssertTrue(
            app.buttons["USE THEIRS"].exists,
            "USE THEIRS picker should be discoverable by label"
        )

        // Resolve by explicitly taking theirs (which differs from HEAD, so the
        // staged resolution surfaces as a change), then confirm via the modal.
        let useTheirs = app.buttons["USE THEIRS"]
        for _ in 0..<6 where !useTheirs.isHittable { app.swipeUp() }
        useTheirs.tap()

        let resolve = app.buttons["RESOLVE"]
        XCTAssertTrue(
            resolve.waitForExistence(timeout: 10),
            "Conflict editor should expose the Resolve action as a labelled button"
        )
        let resolveEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: resolve
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [resolveEnabled], timeout: 10),
            .completed,
            "Resolve should be enabled for a text conflict"
        )
        resolve.tap()

        XCTAssertTrue(
            app.staticTexts["MARK AS RESOLVED?"].waitForExistence(timeout: 10),
            "Resolve should present a discoverable confirmation modal"
        )
        // The toolbar RESOLVE and the modal's confirm share a label; the modal
        // button sits below the navigation bar, so pick the lower match.
        tapLowermostButton(labeled: "RESOLVE", in: app)

        // Resolution completes and dismisses back to the vault view. The
        // stable end state: the conflict is cleared (staged), verified via the
        // Git sheet's Conflict Center.
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Resolving should dismiss back to the vault view"
        )
        // The staged resolution is an async change scan away; wait until the
        // Commit & Push row is actually enabled before opening the Git sheet.
        let commitPush = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Commit & Push")
        ).firstMatch
        XCTAssertTrue(
            commitPush.waitForExistence(timeout: 10),
            "Vault view should expose the Commit & Push action after resolving"
        )
        let commitPushEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: commitPush
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [commitPushEnabled], timeout: 15),
            .completed,
            "Commit & Push should become enabled once the staged resolution is scanned"
        )
        for _ in 0..<4 where !commitPush.isHittable { app.swipeUp() }
        commitPush.tap()
        XCTAssertTrue(
            app.staticTexts["GIT"].waitForExistence(timeout: 10) || app.buttons["Close"].waitForExistence(timeout: 10),
            "Commit & Push should open the Git control sheet"
        )

        let allResolved = app.staticTexts["All conflicts resolved. Complete or abort the merge."]
        var found = allResolved.exists
        for _ in 0..<6 where !found {
            app.swipeUp()
            found = allResolved.exists
        }
        XCTAssertTrue(
            found,
            "Conflict Center should report all conflicts resolved after the keep-theirs resolution"
        )
        XCTAssertTrue(
            app.buttons["ABORT MERGE"].exists,
            "Conflict Center should keep the Abort Merge escape hatch discoverable by label"
        )
    }

    // MARK: - Rebase Conflict Session (Issue #19)

    /// Issue #19 rebase-session slice (extends the merge conflict-resolver
    /// test): the same `-UITestConflictFixture` divergent working copy drives
    /// a genuine REBASE conflict session. Pull reports divergence, REBASE
    /// replays the local commit onto the diverged remote and conflicts on
    /// `notes/shared.md` with rebase-flavored outcome copy, the SAME resolver
    /// path (keep theirs) clears the conflict, and the Git sheet's Conflict
    /// Center reports "All conflicts resolved" with CONTINUE REBASE / ABORT
    /// REBASE escape hatches. ABORT REBASE (local `git rebase --abort`, never
    /// a push) is the sanctioned exit: it must return the vault to a stable,
    /// healthy state with the repository still listed. Continue Rebase,
    /// Complete Merge, and every push path are NEVER tapped.
    func testRebaseConflictSessionResolvableAndAbortReturnsCleanState() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-UITestConflictFixture",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        // Seeded divergent repo is in the list; open it.
        tap("conflict-fixture", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Seeded repo should open into the vault view"
        )

        // Pull against the local bare remote classifies the fixture as
        // diverged (same banner the merge test asserts).
        tap("Pull", in: app)
        XCTAssertTrue(
            app.staticTexts[
                "Local and remote have diverged. Merge support is required to continue."
            ].waitForExistence(timeout: 20),
            "Pull on the diverged fixture should surface the diverged outcome"
        )
        let rebase = app.buttons["REBASE"]
        XCTAssertTrue(
            rebase.waitForExistence(timeout: 10),
            "Diverged banner should expose the Rebase action as a labelled button"
        )

        // The banner can appear while the pull's trailing progress delay still
        // holds isSyncing, so wait for enabled, not merely hittable.
        let rebaseReady = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"),
            object: rebase
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [rebaseReady], timeout: 15),
            .completed,
            "Rebase action should become enabled and tappable"
        )
        rebase.tap()

        // Rebase replays the local commit onto origin/main and conflicts on
        // notes/shared.md — the rebase-flavored outcome copy surfaces.
        XCTAssertTrue(
            app.staticTexts[
                "Rebase has conflicts — resolve them, then continue rebase"
            ].waitForExistence(timeout: 20),
            "Rebasing the fixture should surface the rebase conflict outcome"
        )

        // The conflicted file is listed; its row exposes the conflict state
        // through its combined accessibility label (same technique as the
        // merge test).
        let conflictedRow = app.staticTexts["notes/shared.md"]
        XCTAssertTrue(
            conflictedRow.waitForExistence(timeout: 10),
            "Conflicted file should be listed in Changed Files during the rebase session"
        )
        let conflictedRowButton = app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", "notes/shared.md,")
        ).firstMatch
        XCTAssertTrue(
            conflictedRowButton.exists,
            "Conflicted file row should be discoverable as a labelled element"
        )
        XCTAssertTrue(
            conflictedRowButton.label.contains("CONFLICT"),
            "Conflicted file row should expose its Conflict badge by label (got: \(conflictedRowButton.label))"
        )

        // Open the resolver and drive the SAME resolution path as the merge
        // test: take theirs explicitly, resolve, confirm via the modal.
        tap("notes/shared.md", in: app)
        XCTAssertTrue(
            app.staticTexts["RESOLVE CONFLICT"].waitForExistence(timeout: 10),
            "Conflict editor should expose its titled chrome"
        )
        XCTAssertTrue(
            app.staticTexts["OURS"].waitForExistence(timeout: 10),
            "Ours pane should be discoverable by label"
        )
        XCTAssertTrue(
            app.staticTexts["THEIRS"].exists,
            "Theirs pane should be discoverable by label"
        )

        let useTheirs = app.buttons["USE THEIRS"]
        XCTAssertTrue(
            useTheirs.waitForExistence(timeout: 10),
            "USE THEIRS picker should be discoverable by label"
        )
        for _ in 0..<6 where !useTheirs.isHittable { app.swipeUp() }
        useTheirs.tap()

        let resolve = app.buttons["RESOLVE"]
        XCTAssertTrue(
            resolve.waitForExistence(timeout: 10),
            "Conflict editor should expose the Resolve action as a labelled button"
        )
        let resolveEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: resolve
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [resolveEnabled], timeout: 10),
            .completed,
            "Resolve should be enabled for a text conflict"
        )
        resolve.tap()

        XCTAssertTrue(
            app.staticTexts["MARK AS RESOLVED?"].waitForExistence(timeout: 10),
            "Resolve should present a discoverable confirmation modal"
        )
        // The toolbar RESOLVE and the modal's confirm share a label; the modal
        // button sits below the navigation bar, so pick the lower match.
        tapLowermostButton(labeled: "RESOLVE", in: app)

        // Resolution completes and dismisses back to the vault view. Open the
        // Git control sheet via the vault's Commit & Push row (never tapped
        // inside the sheet); the staged theirs-resolution is an async change
        // scan away, so wait for the row to be enabled first (merge-test rule).
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Resolving should dismiss back to the vault view"
        )
        let commitPush = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Commit & Push")
        ).firstMatch
        XCTAssertTrue(
            commitPush.waitForExistence(timeout: 10),
            "Vault view should expose the Commit & Push row after resolving"
        )
        let commitPushEnabled = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true"),
            object: commitPush
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [commitPushEnabled], timeout: 15),
            .completed,
            "Commit & Push row should become enabled once the staged resolution is scanned"
        )
        for _ in 0..<4 where !commitPush.isHittable { app.swipeUp() }
        commitPush.tap()
        XCTAssertTrue(
            app.staticTexts["GIT"].waitForExistence(timeout: 10) || app.buttons["Close"].waitForExistence(timeout: 10),
            "Commit & Push row should open the Git control sheet"
        )

        // Rebase-flavored stable state: the Conflict Center reports all
        // conflicts resolved with both Continue and Abort Rebase escape
        // hatches discoverable as labelled (uppercased) buttons. Continue
        // Rebase is asserted discoverable but NEVER tapped (it pushes).
        let allResolved = app.staticTexts["All conflicts resolved. Continue or abort the rebase."]
        var found = allResolved.exists
        for _ in 0..<8 where !found {
            app.swipeUp()
            found = allResolved.exists
        }
        XCTAssertTrue(
            found,
            "Conflict Center should report all conflicts resolved for the rebase session"
        )
        XCTAssertTrue(
            app.buttons["CONTINUE REBASE"].firstMatch.exists,
            "Conflict Center should expose Continue Rebase as a labelled button"
        )
        let abortRebase = app.buttons["ABORT REBASE"].firstMatch
        XCTAssertTrue(
            abortRebase.exists,
            "Conflict Center should expose Abort Rebase as a labelled button"
        )

        // Abort is the sanctioned escape hatch: a local `git rebase --abort`
        // that never pushes. It must clear the conflict session (Conflict
        // Center and its buttons disappear) and leave a stable state behind.
        for _ in 0..<6 where !abortRebase.isHittable { app.swipeUp() }
        let abortReady = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "isEnabled == true AND isHittable == true"),
            object: abortRebase
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [abortReady], timeout: 15),
            .completed,
            "Abort Rebase should become enabled and tappable"
        )
        abortRebase.tap()

        let sessionCleared = XCTNSPredicateExpectation(
            predicate: NSPredicate(format: "exists == false"),
            object: allResolved
        )
        XCTAssertEqual(
            XCTWaiter().wait(for: [sessionCleared], timeout: 20),
            .completed,
            "Aborting the rebase should clear the Conflict Center"
        )
        XCTAssertFalse(
            app.buttons["ABORT REBASE"].exists,
            "Abort Rebase should disappear once the rebase session is cleared"
        )

        // Close the Git sheet; the vault returns healthy with the stable
        // post-abort diverged banner (abort restores the pre-rebase state —
        // local and remote still diverge, nothing was merged or pushed).
        tapFirstHittableButton(labeled: "Close", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Vault should remain healthy after aborting the rebase"
        )
        XCTAssertTrue(
            app.staticTexts["Rebase aborted. Local and remote still diverge."].waitForExistence(timeout: 10),
            "Post-abort state should surface the stable diverged banner copy"
        )

        // The repository list still lists the fixture repository.
        let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
        XCTAssertTrue(
            back.waitForExistence(timeout: 10),
            "Vault should expose a back navigation control"
        )
        back.tap()
        XCTAssertTrue(
            app.staticTexts["conflict-fixture"].waitForExistence(timeout: 15),
            "Repo list should still list the conflict-fixture repository"
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

    // MARK: - Push Sync Settings Surface (Issue #19)

    /// Issue #19 Push Sync slice (assert-only): the per-repository Settings
    /// sheet exposes the Push Sync notification preference — its section
    /// header, the "Notify when GitHub changes" toggle, and the relay privacy
    /// disclosure — all discoverable by label, with the toggle off by default
    /// (the unset `pushSyncEnabled` UserDefaults key reads false). The Push
    /// Sync surface lives in SettingsView (the per-repo sheet opened from a
    /// vault's Settings gear — AppSettingsView has no Push Sync surface), so
    /// this launches signed-out with the local conflict fixture, which seeds
    /// `isSignedIn == false` plus one credential-free local repository — no
    /// network, no sign-in, no APNs. The toggle is NEVER tapped: enabling it
    /// drives APNs registration (an external-service path).
    func testPushSyncSettingsSurfaceDiscoverableWithRelayDisclosure() {
        let app = XCUIApplication()
        app.launchArguments = [
            "-SignedOutUITest",
            "-UITestConflictFixture",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ]
        app.launch()

        // Seeding completes in ContentView.onAppear, which may briefly flash
        // onboarding before the seeded (onboarded) state lands (clone-test
        // rule for the same launch-arg composition).
        if button(app, labels: ["Skip", "SKIP"]).waitForExistence(timeout: 3) {
            completeOnboardingIfPresent(in: app)
            continueWithoutGitHubIfPresent(in: app)
        }

        // Open the seeded (signed-out, credential-free) repository.
        tap("conflict-fixture", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 10),
            "Seeded repo should open into the vault view"
        )

        // The vault's Settings gear opens the per-repo SettingsView sheet.
        let settingsGear = app.buttons["Settings"]
        XCTAssertTrue(
            settingsGear.waitForExistence(timeout: 10),
            "Vault should expose the per-repo Settings action as a labelled button"
        )
        settingsGear.tap()

        // Wait for the sheet's stable leading content: BSectionHeader
        // uppercases section titles, so the first section reads REPOSITORY.
        XCTAssertTrue(
            app.staticTexts["REPOSITORY"].waitForExistence(timeout: 10),
            "Settings sheet should expose its leading Repository section header"
        )

        // Push Sync sits below Repository / Authentication / Git Author /
        // Storage / Sync Info / Background Sync — scroll gently within the
        // sheet until its section header is exposed (label-based, never
        // positional).
        let pushSyncHeader = app.staticTexts["PUSH SYNC"]
        var found = pushSyncHeader.exists
        for _ in 0..<10 where !found {
            app.swipeUp()
            found = pushSyncHeader.exists
        }
        XCTAssertTrue(
            found,
            "Settings sheet should expose the PUSH SYNC section header to accessibility"
        )

        // The notification toggle is discoverable by its label (switch
        // elements carry the Toggle's text as their accessibility label).
        let notifyToggle = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH 'Notify when GitHub changes'")
        ).firstMatch
        var toggleFound = notifyToggle.exists
        for _ in 0..<6 where !toggleFound {
            app.swipeUp()
            toggleFound = notifyToggle.exists
        }
        XCTAssertTrue(
            toggleFound,
            "Push Sync should expose the Notify when GitHub changes toggle by label"
        )

        // Off by default: PushSyncManager.isEnabled reads the
        // `pushSyncEnabled` UserDefaults key, and an unset key reads false.
        // Assert-only — the toggle is never tapped.
        let toggleValue = (notifyToggle.value as? String ?? "").lowercased()
        XCTAssertTrue(
            ["0", "off", "false"].contains(toggleValue),
            "Notify when GitHub changes should be off by default (got: \(toggleValue))"
        )

        // The relay privacy disclosure is present as static text (CONTAINS
        // query for the long multi-sentence copy, pinned-copy rule).
        let disclosure = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS 'Uses a relay that sees repository names only'")
        ).firstMatch
        var disclosureFound = disclosure.exists
        for _ in 0..<6 where !disclosureFound {
            app.swipeUp()
            disclosureFound = disclosure.exists
        }
        XCTAssertTrue(
            disclosureFound,
            "Push Sync should disclose the relay privacy copy as accessibility text"
        )

        // Close via Cancel — SettingsView's side-effect-free dismiss (Save
        // would write repo settings) — and confirm the stable return states:
        // back in the vault, then the repo list still lists the fixture.
        tapFirstHittableButton(labeled: "Cancel", in: app)
        XCTAssertTrue(
            app.staticTexts["REPO HEALTH"].waitForExistence(timeout: 15),
            "Cancel should close the settings sheet back to the vault"
        )
        let back = app.navigationBars.firstMatch.buttons.element(boundBy: 0)
        XCTAssertTrue(
            back.waitForExistence(timeout: 10),
            "Vault should expose a back navigation control"
        )
        back.tap()
        XCTAssertTrue(
            app.staticTexts["conflict-fixture"].waitForExistence(timeout: 15),
            "Repo list should still list the conflict-fixture repository"
        )
    }

    // MARK: - Push Sync Global Settings Surface

    /// The Push Sync toggle is installation-global (`PushSyncManager.shared`)
    /// and registers every cloned GitHub repository; App Settings exposes it
    /// so users can enable notifications for all repos — including signed-out
    /// users with no repositories yet. Assert-only: the toggle is NEVER tapped
    /// (enabling drives APNs registration, an external-service path).
    func testPushSyncGlobalSurfaceInAppSettings() {
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
        XCTAssertTrue(
            appSettings.waitForExistence(timeout: 10),
            "Signed-out App Settings toolbar action should exist"
        )
        appSettings.tap()

        XCTAssertTrue(
            app.buttons["Sign in with GitHub"].waitForExistence(timeout: 10),
            "App Settings should offer GitHub sign-in when signed out"
        )

        // Scroll to the Push Sync section (below Background Sync).
        var pushSyncHeaderFound = false
        for _ in 0..<10 {
            if app.staticTexts["PUSH SYNC"].exists {
                pushSyncHeaderFound = true
                break
            }
            app.swipeUp()
        }
        XCTAssertTrue(
            pushSyncHeaderFound,
            "App Settings should expose the global Push Sync section"
        )

        let toggle = app.switches.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Notify when GitHub changes")
        ).firstMatch
        XCTAssertTrue(
            toggle.waitForExistence(timeout: 10),
            "Global Push Sync toggle should be discoverable by label"
        )
        let toggleValue = (toggle.value as? String ?? "").lowercased()
        XCTAssertTrue(
            ["0", "off", "false"].contains(toggleValue),
            "Push Sync should be off by default (got \(toggleValue))"
        )

        let disclosure = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Uses a relay that sees repository names only")
        ).firstMatch
        XCTAssertTrue(
            disclosure.exists,
            "Relay disclosure copy should be present on the global surface"
        )
        let scopeDisclosure = app.staticTexts.matching(
            NSPredicate(format: "label CONTAINS %@", "Covers all of your cloned GitHub repositories")
        ).firstMatch
        XCTAssertTrue(
            scopeDisclosure.exists,
            "Global surface should state it covers all cloned GitHub repositories"
        )

        // Close via Done and confirm the stable return to the repo list.
        tapFirstHittableButton(labeled: "Done", in: app)
        XCTAssertTrue(
            app.buttons["App Settings"].waitForExistence(timeout: 10),
            "Done should close App Settings back to the repo list"
        )
    }

    private func signedOutLaunchArguments(extra: [String] = []) -> [String] {
        [
            "-SignedOutUITest",
            "-AppleLanguages", "(en)",
            "-AppleLocale", "en_US"
        ] + extra
    }

    private func finishSignedOutEmptyLaunch(in app: XCUIApplication) {
        let emptyState = app.staticTexts["NO REPOSITORIES"]
        if !emptyState.waitForExistence(timeout: 3) {
            completeOnboardingIfPresent(in: app)
            continueWithoutGitHubIfPresent(in: app)
        }
        XCTAssertTrue(
            emptyState.waitForExistence(timeout: 10)
                || app.staticTexts["No Repositories"].exists,
            "Background Sync behavior must run with no managed repositories"
        )
    }

    private func finishConflictFixtureLaunch(in app: XCUIApplication) {
        if button(app, labels: ["Skip", "SKIP"]).waitForExistence(timeout: 3) {
            completeOnboardingIfPresent(in: app)
            continueWithoutGitHubIfPresent(in: app)
        }
        XCTAssertTrue(
            app.staticTexts["conflict-fixture"].waitForExistence(timeout: 15),
            "The local file:// conflict fixture should be present"
        )
    }

    /// Opens App Settings and its Background Sync sheet using only labelled
    /// controls, scrolling whichever duplicate-free row is currently hidden.
    @discardableResult
    private func openBackgroundSyncSettings(in app: XCUIApplication) -> XCUIElement {
        let appSettings = app.buttons["App Settings"].firstMatch
        tapWhenHittable(appSettings, in: app, message: "App Settings should be available")
        XCTAssertTrue(
            app.buttons["Sign in with GitHub"].waitForExistence(timeout: 10),
            "Signed-out App Settings should finish presenting"
        )

        let row = app.buttons.matching(
            NSPredicate(format: "label == %@", "Background Sync")
        ).firstMatch
        tapWhenHittable(row, in: app, message: "Background Sync row should be tappable by label")
        XCTAssertTrue(
            app.buttons["Done"].firstMatch.waitForExistence(timeout: 10),
            "Background Sync settings should expose its Done toolbar control"
        )

        let globalToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Enable Background Sync"
        )
        XCTAssertTrue(
            reveal(globalToggle, in: app),
            "Background Sync settings should expose its global toggle by label"
        )
        return globalToggle
    }

    private func closeBackgroundSyncAndAppSettings(in app: XCUIApplication) {
        tapFirstHittableButton(labeled: "Done", in: app)
        XCTAssertTrue(
            app.buttons["Background Sync"].firstMatch.waitForExistence(timeout: 10),
            "Done should close Background Sync settings back to App Settings"
        )
        tapFirstHittableButton(labeled: "Done", in: app)
        XCTAssertTrue(
            app.buttons["App Settings"].firstMatch.waitForExistence(timeout: 10),
            "Done should close App Settings"
        )
    }

    private func reopenBackgroundSyncFromAppSettings(in app: XCUIApplication) -> XCUIElement {
        tapFirstHittableButton(labeled: "Done", in: app)
        let row = app.buttons["Background Sync"].firstMatch
        XCTAssertTrue(
            row.waitForExistence(timeout: 10),
            "Done should close Background Sync settings back to App Settings"
        )
        tapWhenHittable(row, in: app, message: "Background Sync row should reopen by label")
        XCTAssertTrue(
            app.buttons["Done"].firstMatch.waitForExistence(timeout: 10),
            "Reopened Background Sync settings should expose Done"
        )
        let globalToggle = backgroundSyncSwitch(in: app, labelPrefix: "Enable Background Sync")
        XCTAssertTrue(
            reveal(globalToggle, in: app),
            "Reopened Background Sync settings should expose its global toggle"
        )
        return globalToggle
    }

    private func openRepositorySettingsAndRevealBackgroundSync(in app: XCUIApplication) -> XCUIElement {
        let settings = app.buttons["Settings"].firstMatch
        tapWhenHittable(settings, in: app, message: "Repository Settings should be tappable by label")
        XCTAssertTrue(
            app.staticTexts["REPOSITORY"].waitForExistence(timeout: 10),
            "Repository Settings should finish presenting"
        )
        let inclusionToggle = backgroundSyncSwitch(
            in: app,
            labelPrefix: "Include in Background Sync"
        )
        XCTAssertTrue(
            reveal(inclusionToggle, in: app),
            "Repository Settings should expose inclusion by accessibility label"
        )
        return inclusionToggle
    }

    private func backgroundSyncSwitch(in app: XCUIApplication, labelPrefix: String) -> XCUIElement {
        app.switches.matching(
            NSPredicate(format: "label BEGINSWITH %@", labelPrefix)
        ).firstMatch
    }

    /// Forces the runtime through its public UI disable path. If global mode
    /// already appears off, it is enabled (with consent) and disabled once so
    /// stale hidden pull/push defaults cannot leak in from an earlier run.
    private func normalizeBackgroundSyncOff(globalToggle: XCUIElement, in app: XCUIApplication) {
        guard waitUntil(timeout: 5, condition: { switchState(of: globalToggle) != nil }),
              let initiallyOn = switchState(of: globalToggle) else {
            XCTFail("Global Background Sync toggle should expose a readable value")
            return
        }

        if !initiallyOn {
            tapWhenHittable(globalToggle, in: app, message: "Global toggle should be tappable for normalization")
            assertBrandedConfirmation(
                title: "Enable Background Sync for all repositories?",
                in: app
            )
            tapFirstHittableButton(labeled: "Enable Background Sync", in: app)
            assertSwitch(globalToggle, isOn: true, "Normalization should reach the enabled state")
        }

        setSwitchDirectly(
            globalToggle,
            to: false,
            in: app,
            message: "Normalization should finish with global mode off"
        )
        let pull = backgroundSyncSwitch(in: app, labelPrefix: "Pull remote changes")
        let push = backgroundSyncSwitch(in: app, labelPrefix: "Commit and push local changes")
        XCTAssertTrue(
            waitUntil(timeout: 5) { !pull.exists && !push.exists },
            "Normalization should reset and remove both automatic action controls"
        )
    }

    private func assertBrandedConfirmation(title: String, in app: XCUIApplication) {
        XCTAssertTrue(
            app.staticTexts[title].waitForExistence(timeout: 10),
            "Expected branded confirmation titled: \(title)"
        )
        XCTAssertTrue(
            app.buttons["Cancel"].firstMatch.exists,
            "Branded confirmation should expose a labelled Cancel button"
        )
    }

    private func switchState(of element: XCUIElement) -> Bool? {
        guard element.exists else { return nil }
        if let number = element.value as? NSNumber {
            return number.boolValue
        }
        guard let value = element.value as? String else { return nil }
        switch value.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() {
        case "1", "on", "true", "yes": return true
        case "0", "off", "false", "no": return false
        default: return nil
        }
    }

    private func assertSwitch(
        _ element: XCUIElement,
        isOn expected: Bool,
        _ message: String,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        XCTAssertTrue(
            waitUntil(timeout: 10) { switchState(of: element) == expected },
            "\(message) (accessibility value: \(String(describing: element.value)))",
            file: file,
            line: line
        )
    }

    @discardableResult
    private func setSwitchDirectly(
        _ element: XCUIElement,
        to expected: Bool,
        in app: XCUIApplication,
        message: String
    ) -> Bool {
        if switchState(of: element) == expected { return true }

        for attempt in 0..<3 {
            tapWhenHittable(element, in: app, message: message)
            if waitUntil(timeout: 5, condition: { switchState(of: element) == expected }) {
                return true
            }
            if attempt < 2 {
                // Refresh the scroll position before retrying an accessibility
                // element that iOS considered hittable but whose tap was lost.
                app.swipeDown()
                _ = reveal(element, in: app)
            }
        }
        XCTFail("\(message) (accessibility value: \(String(describing: element.value)))")
        return false
    }

    private func tapWhenHittable(_ element: XCUIElement, in app: XCUIApplication, message: String) {
        guard reveal(element, in: app) else {
            XCTFail(message)
            return
        }
        guard waitUntil(timeout: 10, condition: {
            element.exists && element.isEnabled && element.isHittable
        }) else {
            XCTFail("\(message) (element did not become enabled)")
            return
        }
        element.tap()
    }

    /// Reveals elements in either direction based on their accessibility
    /// frame. Taps still target the element itself; frames are never used as
    /// tap coordinates.
    private func reveal(_ element: XCUIElement, in app: XCUIApplication, maxSwipes: Int = 16) -> Bool {
        if element.waitForExistence(timeout: 2), element.isHittable { return true }

        for _ in 0..<maxSwipes {
            let window = app.windows.firstMatch
            let viewportMidY = window.exists ? window.frame.midY : app.frame.midY
            if element.exists, !element.frame.isEmpty, element.frame.midY < viewportMidY {
                app.swipeDown()
            } else {
                app.swipeUp()
            }
            if element.waitForExistence(timeout: 0.5), element.isHittable { return true }
        }
        return element.exists && element.isHittable
    }

    private func waitUntil(timeout: TimeInterval, condition: () -> Bool) -> Bool {
        let deadline = Date().addingTimeInterval(timeout)
        repeat {
            if condition() { return true }
            RunLoop.current.run(until: min(deadline, Date().addingTimeInterval(0.1)))
        } while Date() < deadline
        return condition()
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

    /// Taps the vertically-lowest button with the given label — used when a
    /// confirmation modal and the toolbar behind it expose the same label
    /// (e.g. the conflict editor's "RESOLVE" toolbar action vs. the modal's
    /// confirm button); the modal button always sits below the nav bar.
    private func tapLowermostButton(labeled label: String, in app: XCUIApplication) {
        let candidates = app.buttons.matching(NSPredicate(format: "label == %@", label))
        var best: XCUIElement?
        var bestY: CGFloat = -1
        for index in 0..<max(candidates.count, 1) {
            let candidate = candidates.element(boundBy: index)
            guard candidate.exists, candidate.isHittable else { continue }
            let y = candidate.frame.midY
            if y > bestY {
                bestY = y
                best = candidate
            }
        }
        guard let best else {
            XCTFail("Expected a tappable \(label) button")
            return
        }
        best.tap()
    }
}
