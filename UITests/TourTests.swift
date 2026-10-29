import XCTest

/// Not checks but scripted tours for the README GIFs: `.github/workflows/demo-gifs.yml` records the
/// simulator while each test runs. The app starts in demo mode (fake scooter, rides, places).
/// Pauses are for the viewer, so keep them when editing a tour.
final class TourTests: XCTestCase {
    private var app: XCUIApplication!

    override func setUp() {
        continueAfterFailure = true
        app = XCUIApplication()
        app.launchArguments = ["-demoMode", "YES", "-appLanguage", language,
                               "-AppleLanguages", "(\(language))", "-AppleLocale", language == "vi" ? "vi_VN" : "en_US"]
        app.launch()
        allowLocationIfAsked()
        pause(3)  // map tiles + first engine data
    }

    /// The recording script ends the GIF 2 s into this hold, well before the app is closed.
    override func tearDown() {
        pause(5)
    }

    /// "vi" or "en", from `TEST_RUNNER_TOUR_LANGUAGE` (set by the recording script).
    private var language: String { ProcessInfo.processInfo.environment["TOUR_LANGUAGE"] ?? "vi" }

    /// Route to Home, then the simulated ride with turn arrows (what the scooter TFT shows too).
    func test1Navigation() {
        tap(app.buttons["homeChip"])
        tap(app.buttons["simulateButton"], timeout: 15)
        pause(22)
    }

    /// Live engine data, fuel, service reminders and trips on the scooter sheet.
    func test2Scooter() {
        tap(app.buttons["scooterButton"])
        pause(4)
        scroll()
        pause(3)
        scroll()
        pause(3)
        scroll()
        pause(3)
    }

    /// Trip history and one trip's details.
    func test3Trips() {
        tap(app.buttons["scooterButton"])
        pause(1)
        // Scroll to the end: "hittable" is already true while the link sits under the home indicator.
        for _ in 0..<3 { scroll() }
        pause(1.5)  // a tap while the list still glides only stops the scrolling
        tap(app.buttons["allTripsLink"])
        pause(2)
        tap(app.cells.element(boundBy: 0))
        pause(4)
    }

    /// Settings: switch the whole app to the other language and back to the map.
    func test4Language() {
        tap(app.buttons["settingsButton"])
        pause(2)
        tap(app.buttons["languagePicker"])
        pause(1)
        tap(app.buttons[language == "vi" ? "English" : "Tiếng Việt"])
        pause(2)
        tap(app.buttons["settingsDone"])
        pause(4)
    }

    // MARK: - Helpers

    private func tap(_ element: XCUIElement, timeout: TimeInterval = 8) {
        XCTAssertTrue(element.waitForExistence(timeout: timeout), "missing \(element)")
        if element.exists { element.tap() }
    }

    /// Scrolls the scooter sheet (the map screen behind it has scroll views too).
    private func scroll() {
        app.scrollViews["scooterScroll"].swipeUp(velocity: .slow)
    }

    private func pause(_ seconds: TimeInterval) {
        Thread.sleep(forTimeInterval: seconds)
    }

    /// The workflow grants location up front; this only covers a prompt that still shows.
    private func allowLocationIfAsked() {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        for label in ["Allow While Using App", "Allow Once", "Allow"] {
            let button = springboard.buttons[label]
            if button.waitForExistence(timeout: 1) {
                button.tap()
                return
            }
        }
    }
}
