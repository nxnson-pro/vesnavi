import XCTest
@testable import NavCore

final class NavigationEngineTests: XCTestCase {
    func testStartShowsUpcomingTurn() {
        let engine = NavigationEngine(route: Fixtures.lRoute)
        let s = engine.update(location: Fixtures.p0)
        XCTAssertEqual(s.maneuver, .right)
        XCTAssertEqual(s.streetName, "Đường B")
        XCTAssertEqual(s.instruction, "Rẽ phải vào Đường B")
        XCTAssertEqual(s.distanceToManeuver, 222, accuracy: 3)
        XCTAssertFalse(s.isOffRoute)
        XCTAssertFalse(s.hasArrived)
    }

    func testDistanceShrinksAndSnapsSlightlyOffLine() {
        let engine = NavigationEngine(route: Fixtures.lRoute)
        _ = engine.update(location: Fixtures.p0)
        // ~10 m east of p1 — still on route.
        let s = engine.update(location: Coordinate(latitude: 10.7710, longitude: 106.70009))
        XCTAssertEqual(s.distanceToManeuver, 111, accuracy: 3)
        XCTAssertNotNil(s.snappedPosition)
    }

    func testAfterTurnNextIsArrival() {
        let engine = NavigationEngine(route: Fixtures.lRoute)
        _ = engine.update(location: Fixtures.p0)
        _ = engine.update(location: Fixtures.p2)
        let s = engine.update(location: Fixtures.p3)
        XCTAssertEqual(s.maneuver, .arrive)
        XCTAssertEqual(s.distanceToManeuver, 109, accuracy: 3)
    }

    func testOffRouteNeedsConsecutiveFixes() {
        let engine = NavigationEngine(route: Fixtures.lRoute)
        let far = Coordinate(latitude: 10.7700, longitude: 106.7030)  // ~330 m east
        XCTAssertFalse(engine.update(location: far).isOffRoute)
        XCTAssertFalse(engine.update(location: far).isOffRoute)
        XCTAssertTrue(engine.update(location: far).isOffRoute)
        // Back on route resets.
        XCTAssertFalse(engine.update(location: Fixtures.p1).isOffRoute)
    }

    func testArrival() {
        let engine = NavigationEngine(route: Fixtures.lRoute)
        _ = engine.update(location: Fixtures.p3)
        let s = engine.update(location: Fixtures.p4)
        XCTAssertTrue(s.hasArrived)
        XCTAssertEqual(s.maneuver, .arrive)
        XCTAssertEqual(s.remainingDistance, 0)
    }

    func testSimulatorDrivesEngineToArrival() {
        let sim = RouteSimulator(route: Fixtures.lRoute)
        let engine = NavigationEngine(route: Fixtures.lRoute)
        var d = 0.0
        var sawTurn = false
        while d <= sim.length {
            let s = engine.update(location: sim.coordinate(atDistance: d))
            XCTAssertFalse(s.isOffRoute)
            if s.maneuver == .right { sawTurn = true }
            d += 8  // ~30 km/h at 1 Hz
        }
        _ = engine.update(location: sim.coordinate(atDistance: sim.length))
        XCTAssertTrue(sawTurn)
        XCTAssertTrue(engine.hasArrived)
    }
}
