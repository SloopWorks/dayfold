import XCTest

final class LocalSearchUITests: XCTestCase {
    override func setUpWithError() throws {
        continueAfterFailure = false
    }

    func testExactFuzzyAndCanonicalBackPaths() throws {
        let app = XCUIApplication()
        app.launch()

        let devSignIn = app.buttons["Dev sign-in (fake)"]
        XCTAssertTrue(devSignIn.waitForExistence(timeout: 10))
        devSignIn.tap()

        let openSearch = app.buttons["Search saved content"]
        XCTAssertTrue(openSearch.waitForExistence(timeout: 15))
        openSearch.tap()

        // Compose exposes editable text as an iOS TextView whose label also carries the
        // placeholder/value. Query by the stable semantic prefix rather than UIKit class detail.
        let field = app.textViews.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Search all Dayfold content")
        ).firstMatch
        XCTAssertTrue(field.waitForExistence(timeout: 5))
        field.tap()
        field.typeText("soccer")

        let exactResult = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Soccer registration closes Friday")
        ).firstMatch
        XCTAssertTrue(exactResult.waitForExistence(timeout: 5))

        app.buttons["Clear search"].tap()
        field.typeText("socer")
        XCTAssertTrue(element(in: app, labelled: "Close matches for “socer”").waitForExistence(timeout: 5))

        let closeResult = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Soccer registration closes Friday")
        ).firstMatch
        XCTAssertTrue(closeResult.waitForExistence(timeout: 5))
        closeResult.tap()

        XCTAssertTrue(element(in: app, labelled: "Soccer registration closes Friday").waitForExistence(timeout: 5))
        button(in: app, labelledWithPrefix: "Back to search").tap()
        let restoredField = searchField(in: app)
        XCTAssertEqual(restoredField.value as? String, "socer")

        app.buttons["Back"].tap()
        XCTAssertTrue(openSearch.waitForExistence(timeout: 5))
        openSearch.tap()
        let blockField = searchField(in: app)
        XCTAssertTrue(blockField.waitForExistence(timeout: 5))
        blockField.typeText("balloons")
        let blockResult = app.buttons.matching(
            NSPredicate(format: "label CONTAINS %@", "Order cake")
        ).firstMatch
        XCTAssertTrue(blockResult.waitForExistence(timeout: 5))
        blockResult.tap()

        XCTAssertTrue(element(in: app, labelled: "SEARCH MATCH").waitForExistence(timeout: 5))
        button(in: app, labelledWithPrefix: "Back to search").tap()
        XCTAssertEqual(searchField(in: app).value as? String, "balloons")
    }

    private func element(in app: XCUIApplication, labelled label: String) -> XCUIElement {
        app.descendants(matching: .any).matching(
            NSPredicate(format: "label == %@", label)
        ).firstMatch
    }

    private func button(in app: XCUIApplication, labelledWithPrefix prefix: String) -> XCUIElement {
        app.buttons.matching(
            NSPredicate(format: "label BEGINSWITH %@", prefix)
        ).firstMatch
    }

    private func searchField(in app: XCUIApplication) -> XCUIElement {
        app.textViews.matching(
            NSPredicate(format: "label BEGINSWITH %@", "Search all Dayfold content")
        ).firstMatch
    }
}
