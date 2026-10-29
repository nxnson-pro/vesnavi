import XCTest
@testable import NavCore

final class LanguageTests: XCTestCase {
    func testEnglishInstructions() {
        XCTAssertEqual(InstructionFormatter.text(for: .left, street: "Le Loi", language: .en), "Turn left onto Le Loi")
        XCTAssertEqual(InstructionFormatter.text(for: .uTurnRight, street: nil, language: .en), "Make a U-turn")
        XCTAssertEqual(InstructionFormatter.text(for: .roundabout(exit: 3), street: nil, language: .en),
                       "At the roundabout, take exit 3")
        XCTAssertEqual(InstructionFormatter.text(for: .depart, street: "A", language: .en), "Start on A")
        XCTAssertEqual(InstructionFormatter.text(for: .arrive, street: "X", language: .en), "Arrived")
        XCTAssertEqual(InstructionFormatter.text(for: .straight, street: nil, language: .en), "Continue straight")
    }

    func testEnglishDistanceUsesDecimalPoint() {
        XCTAssertEqual(DistanceFormatter.text(1240, language: .en), "1.2 km")
        XCTAssertEqual(DistanceFormatter.text(1240), "1,2 km")
        XCTAssertEqual(DistanceFormatter.text(337, language: .en), "350 m")
    }

    func testEngineFollowsLanguageChange() {
        let route = Route(polyline: [Coordinate(latitude: 10, longitude: 106),
                                     Coordinate(latitude: 10.001, longitude: 106),
                                     Coordinate(latitude: 10.001, longitude: 106.001)],
                          steps: [RouteStep(maneuver: .depart, streetName: nil, pointIndex: 0),
                                  RouteStep(maneuver: .right, streetName: nil, pointIndex: 1),
                                  RouteStep(maneuver: .arrive, streetName: nil, pointIndex: 2)],
                          distance: 220, duration: 60, provider: "test")
        let engine = NavigationEngine(route: route)
        let start = Coordinate(latitude: 10, longitude: 106)
        XCTAssertEqual(engine.update(location: start).instruction, "Rẽ phải")
        engine.language = .en
        XCTAssertEqual(engine.update(location: start).instruction, "Turn right")
    }

    func testEnglishTFTText() {
        var t = ScooterTelemetry()
        t.rpm = 1700
        t.coolantC = 85
        t.batteryVolts = 13.8
        XCTAssertEqual(Cobo.infoText(t, language: .en), "COOL 85C  BAT 13.8V")
        XCTAssertEqual(Cobo.infoText(t), "NUOC 85C  AQ 13.8V")
        var s = NavigationState(maneuver: .left, streetName: nil, instruction: "", distanceToManeuver: 50,
                                remainingDistance: 500, remainingDuration: 60,
                                isOffRoute: false, hasArrived: false, snappedPosition: nil)
        XCTAssertEqual(Cobo.dashText(for: s, language: .en), "Turn left")
        s.isOffRoute = true
        let payload = Cobo.dashPayload(for: s, language: .en)
        XCTAssertNotNil(String(decoding: payload, as: UTF8.self).range(of: "REROUTING", options: .caseInsensitive))
    }

    func testMaintenanceEnglishNames() {
        let oil = MaintenanceItem.defaults[0]
        XCTAssertEqual(oil.displayName(.en), "Engine oil")
        XCTAssertEqual(oil.displayName(.vi), "Nhớt máy")
        XCTAssertEqual(oil.popupText(.en), "Change engine oil")
        let custom = MaintenanceItem(id: "x", name: "Lốp", icon: "circle", intervalKm: 1, intervalMonths: nil, tftText: "Lop")
        XCTAssertEqual(custom.displayName(.en), "Lốp", "custom items keep their own name")
    }

    func testRoutingErrorMessages() {
        XCTAssertEqual(RoutingError.noRoute.message(.en), "No route found.")
        XCTAssertEqual(RoutingError.noRoute.errorDescription, "Không tìm được đường đi.")
    }
}

final class GraphHopperPlanTests: XCTestCase {
    /// What the free plan answers to profile=scooter — the app switches to "car" on it.
    func testProfileNotInPlanIsAServerError() {
        let body = #"{"message":"For your account the profile parameter can only be one of [car, bike, foot] but was scooter"}"#
        XCTAssertThrowsError(try GraphHopperProvider.parse(Data(body.utf8))) { error in
            guard case RoutingError.server(let msg) = error else { return XCTFail("\(error)") }
            XCTAssertTrue(msg.contains("profile"))
        }
    }
}
