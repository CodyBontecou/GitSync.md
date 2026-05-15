import XCTest

final class SyncMDUITests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        super.setUp()
        continueAfterFailure = false
    }

    override func tearDown() {
        app = nil
        super.tearDown()
    }

    func testOnboardingProgressionExposesAccessibleControls() {
        launch(arguments: ["-ui-testing-reset"])

        let continueButton = app.buttons["onboarding.continueButton"]
        XCTAssertTrue(continueButton.waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["onboarding.skipButton"].exists)

        continueButton.tap()
        app.buttons["onboarding.continueButton"].tap()

        let getStartedButton = app.buttons["onboarding.getStartedButton"]
        XCTAssertTrue(getStartedButton.waitForExistence(timeout: 2))
        getStartedButton.tap()

        XCTAssertTrue(app.descendants(matching: .any)["setup.personalAccessTokenButton"].waitForExistence(timeout: 3))
    }

    func testRepoCloneAddFlowUsesOfflineFixtureAndAccessibleControls() {
        launch(arguments: ["-ui-testing-add-repo", "-ui-testing-reset"])

        app.buttons["addRepo.manualURLButton"].tapWhenReady()

        let repoURLField = app.textFields["addRepo.repositoryURLField"]
        repoURLField.tapWhenReady()
        repoURLField.typeText("https://github.com/example/UITestCloneRepo")

        dismissKeyboardIfPresent()
        app.swipeUp()
        app.buttons["addRepo.addAndCloneButton"].tapWhenReady()
        app.buttons["freeSlot.useFreeSlotButton"].tapWhenReady()

        XCTAssertTrue(app.buttons["repoList.repo.UITestCloneRepo"].waitForExistence(timeout: 5))
    }

    func testFileBrowserAndEditorExposeEditSaveDeleteControls() {
        launch(arguments: ["-ui-testing-seed-repo", "-ui-testing-reset"])
        openSeededRepository()

        app.buttons["vault.browseFilesButton"].tapWhenReady()
        app.buttons["fileBrowser.item.README.md"].tapWhenReady()

        let editor = app.textViews["fileEditor.textEditor"]
        editor.tapWhenReady()
        editor.typeText("\nAccessibility edit")

        XCTAssertTrue(app.buttons["fileEditor.saveButton"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["fileEditor.renameButton"].exists)
        XCTAssertTrue(app.buttons["fileEditor.deleteButton"].exists)
        app.buttons["fileEditor.saveButton"].tap()
        XCTAssertTrue(app.staticTexts["SAVED"].waitForExistence(timeout: 3))
    }

    func testConflictResolverExposesResolutionLabelsAndConfirmation() {
        launch(arguments: ["-ui-testing-seed-repo", "-ui-testing-reset"])
        openSeededRepository()

        app.buttons["vault.changedFile.conflict.md"].tapWhenReady()

        XCTAssertTrue(app.descendants(matching: .any)["conflict.oursPane"].waitForExistence(timeout: 3))
        XCTAssertTrue(app.descendants(matching: .any)["conflict.theirsPane"].exists)
        XCTAssertTrue(app.buttons["conflict.useOursButton"].exists)
        XCTAssertTrue(app.buttons["conflict.useTheirsButton"].exists)
        XCTAssertTrue(app.textViews["conflict.resultEditor"].exists)

        app.buttons["conflict.useTheirsButton"].tap()
        app.buttons["conflict.resolveButton"].tap()
        XCTAssertTrue(app.buttons["confirm.confirmButton"].waitForExistence(timeout: 2))
        XCTAssertTrue(app.buttons["confirm.cancelButton"].exists)
        app.buttons["confirm.cancelButton"].tap()
    }

    func testPaywallUpgradeRestoreAndCloseControlsAreAccessible() {
        launch(arguments: ["-ui-testing-paywall", "-ui-testing-reset"])

        XCTAssertTrue(app.buttons["paywall.upgradeButton"].waitForExistence(timeout: 5))
        XCTAssertTrue(app.buttons["paywall.restoreButton"].exists)
        XCTAssertTrue(app.buttons["paywall.closeButton"].exists)

        app.buttons["paywall.upgradeButton"].tap()
        XCTAssertTrue(app.staticTexts["UI test purchase flow"].waitForExistence(timeout: 2))

        app.buttons["paywall.restoreButton"].tap()
        XCTAssertTrue(app.staticTexts["UI test restore flow"].waitForExistence(timeout: 2))

        app.buttons["paywall.closeButton"].tap()
        XCTAssertFalse(app.buttons["paywall.upgradeButton"].waitForExistence(timeout: 2))
    }

    private func launch(arguments: [String]) {
        app = XCUIApplication()
        app.launchArguments = ["-ui-testing"] + arguments
        app.launch()
    }

    private func openSeededRepository() {
        app.buttons["repoList.repo.UITestRepo"].tapWhenReady()
        XCTAssertTrue(app.buttons["vault.browseFilesButton"].waitForExistence(timeout: 5))
    }

    private func dismissKeyboardIfPresent() {
        let labels = ["Return", "Done", "Go"]
        for label in labels where app.keyboards.buttons[label].exists {
            app.keyboards.buttons[label].tap()
            return
        }
    }
}

private extension XCUIElement {
    func tapWhenReady(timeout: TimeInterval = 5, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertTrue(waitForExistence(timeout: timeout), "Element did not exist: \(self)", file: file, line: line)
        XCTAssertTrue(isHittable, "Element was not hittable: \(self)", file: file, line: line)
        tap()
    }
}
