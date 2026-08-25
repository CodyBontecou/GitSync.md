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
}
