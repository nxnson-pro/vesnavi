import XCTest
@testable import NavCore

/// Roundabout pictograms, speed limits, maintenance and refuel detection.
final class FeatureTests: XCTestCase {
    // MARK: Roundabouts

    func testRoundaboutIconByExitDirection() {
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: 0), 104)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: 45), 103)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: 90), 102)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: 135), 101)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: 175), 100)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: -45), 105)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: -90), 106)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: -135), 107)
        XCTAssertEqual(RouteAnnotator.roundaboutIcon(turnDegrees: -179), 100)
    }

    private func roundaboutRoute() -> Route {
        let pts = [
            Coordinate(latitude: 10.0, longitude: 106.0),
            Coordinate(latitude: 10.001, longitude: 106.0),      // entry, arriving northbound
            Coordinate(latitude: 10.0015, longitude: 106.0005),
            Coordinate(latitude: 10.001, longitude: 106.001),    // exit
            Coordinate(latitude: 10.001, longitude: 106.002),    // leaving eastbound
        ]
        let steps = [
            RouteStep(maneuver: .depart, streetName: nil, pointIndex: 0),
            RouteStep(maneuver: .roundabout(exit: 2), streetName: "Lê Lợi", pointIndex: 1),
            RouteStep(maneuver: .straight, streetName: "Lê Lợi", pointIndex: 3),
            RouteStep(maneuver: .arrive, streetName: nil, pointIndex: 4),
        ]
        return Route(polyline: pts, steps: steps, distance: 500, duration: 60, provider: "test")
    }

    func testRoundaboutExitIsAnnotated() throws {
        let r = RouteAnnotator.annotateRoundabouts(roundaboutRoute())
        let d = try XCTUnwrap(r.steps[1].exitTurnDegrees)
        XCTAssertEqual(d, 90, accuracy: 2)
        XCTAssertNil(r.steps[2].exitTurnDegrees)

        let engine = NavigationEngine(route: r)
        let s = engine.update(location: Coordinate(latitude: 10.0003, longitude: 106.0))
        XCTAssertEqual(PMP3.icon(for: s), 102, "right-hand exit → roundabout pictogram 102")
        XCTAssertEqual(Cobo.dashPayload(for: s)[2], 102)
    }

    // MARK: Speed limits

    func testGraphHopperMaxSpeedDetails() throws {
        let json = """
        {"paths":[{"distance":300,"time":60000,
          "points":{"coordinates":[[106.0,10.0],[106.0,10.001],[106.0,10.002],[106.0,10.003]]},
          "instructions":[{"sign":0,"interval":[0,1],"street_name":"A"},{"sign":4,"interval":[3,3],"street_name":""}],
          "details":{"max_speed":[[0,2,50.0],[2,3,null]]}}]}
        """
        let r = try GraphHopperProvider.parse(Data(json.utf8))
        XCTAssertEqual(r.speedLimits, [SpeedLimitSpan(from: 0, to: 2, kmh: 50)])
        let engine = NavigationEngine(route: r)
        XCTAssertEqual(engine.update(location: Coordinate(latitude: 10.0005, longitude: 106.0)).speedLimit, 50)
        XCTAssertNil(engine.update(location: Coordinate(latitude: 10.0025, longitude: 106.0)).speedLimit)
    }

    func testDashCarriesSpeedLimit() {
        var s = NavigationState(maneuver: .left, streetName: "A", instruction: "", distanceToManeuver: 100,
                                remainingDistance: 500, remainingDuration: 60, isOffRoute: false,
                                hasArrived: false, snappedPosition: nil)
        s.speedLimit = 50
        let p = Cobo.dashPayload(for: s)
        XCTAssertEqual(Array(p[10...12]), [0x02, 50, 0x00])
    }

    func testOverpassResolve() throws {
        let json = """
        {"elements":[
          {"type":"way","id":1,"tags":{"highway":"residential","name":"Hẻm 12"}},
          {"type":"way","id":2,"tags":{"highway":"primary","name":"Đường Nguyễn Hữu Thọ","maxspeed":"60"}},
          {"type":"way","id":3,"tags":{"highway":"footway"}}
        ]}
        """
        let ways = try SpeedLimitResolver.parseOverpass(Data(json.utf8))
        XCTAssertEqual(ways.count, 3)
        let byName = SpeedLimitResolver.resolve(ways, street: "Nguyễn Hữu Thọ", vietnamDefaults: false)
        XCTAssertEqual(byName, SpeedLimitResult(kmh: 60, estimated: false, roadName: "Đường Nguyễn Hữu Thọ"))
        let alley = SpeedLimitResolver.resolve(ways, street: "Hẻm 12", vietnamDefaults: false)
        XCTAssertNil(alley, "untagged road without Vietnamese defaults")
        XCTAssertEqual(SpeedLimitResolver.resolve(ways, street: "Hẻm 12", vietnamDefaults: true),
                       SpeedLimitResult(kmh: 50, estimated: true, roadName: "Hẻm 12"))
    }

    func testMaxspeedParsingAndVietnamDefaults() {
        XCTAssertEqual(SpeedLimitResolver.parseMaxspeed("50"), 50)
        XCTAssertEqual(SpeedLimitResolver.parseMaxspeed("40 km/h"), 40)
        XCTAssertEqual(SpeedLimitResolver.parseMaxspeed("30 mph"), 48)
        XCTAssertEqual(SpeedLimitResolver.parseMaxspeed("VN:urban"), 50)
        XCTAssertNil(SpeedLimitResolver.parseMaxspeed("signals"))
        XCTAssertEqual(SpeedLimitResolver.vietnamDefault(["highway": "primary", "oneway": "yes", "lanes": "3"]), 60)
        XCTAssertEqual(SpeedLimitResolver.vietnamDefault(["highway": "primary", "dual_carriageway": "yes"]), 60)
        XCTAssertEqual(SpeedLimitResolver.vietnamDefault(["highway": "secondary"]), 50)
        XCTAssertNil(SpeedLimitResolver.vietnamDefault(["highway": "motorway"]))
    }

    // MARK: Maintenance

    func testMaintenanceStatus() {
        let now = Date(timeIntervalSince1970: 1_800_000_000)
        var oil = MaintenanceItem.defaults[0]
        XCTAssertEqual(MaintenancePlanner.status(of: oil, odometer: 31_000, now: now).level, .unknown)
        oil.lastKm = 29_000
        oil.lastDate = now.addingTimeInterval(-30 * 86_400)
        var s = MaintenancePlanner.status(of: oil, odometer: 31_000, now: now)
        XCTAssertEqual(s.kmLeft, 1_000)
        XCTAssertEqual(s.level, .ok)
        s = MaintenancePlanner.status(of: oil, odometer: 31_800, now: now)
        XCTAssertEqual(s.level, .soon)
        s = MaintenancePlanner.status(of: oil, odometer: 32_100, now: now)
        XCTAssertEqual(s.level, .due)
        XCTAssertEqual(s.kmLeft, -100)
        oil.lastDate = now.addingTimeInterval(-200 * 86_400)  // > 6 months
        XCTAssertEqual(MaintenancePlanner.status(of: oil, odometer: 29_500, now: now).level, .due)
    }

    // MARK: Fuel gauge

    func testRefuelDetection() {
        var d = RefuelDetector(stable: 2)
        for _ in 0..<5 { XCTAssertNil(d.update(5)) }  // not stable yet
        var result: (bars: Int, rose: Bool)?
        for _ in 0..<20 { if let r = d.update(5) { result = r } }
        XCTAssertEqual(result?.bars, 5)
        XCTAssertEqual(result?.rose, true)
        // Sloshing back and forth never settles → nothing.
        for i in 0..<40 { XCTAssertNil(d.update(i % 2 == 0 ? 4 : 5)) }
        // A one-bar drop is consumption, not a fill-up.
        var drop: (bars: Int, rose: Bool)?
        for _ in 0..<20 { if let r = d.update(4) { drop = r } }
        XCTAssertEqual(drop?.rose, false)
    }

    func testEstimateFromGaugeBeforeFirstFill() throws {
        var s = FuelState()
        s.lastFuelBars = 5
        let e = try XCTUnwrap(FuelEstimator.estimate(s, history: [], now: Date()))
        XCTAssertEqual(e.litersLeft, 8.5 * 5 / 7, accuracy: 0.001)
    }

    func testOldSavedFuelStateStillDecodes() throws {
        let old = #"{"tankLiters":8.5,"litersAtFill":8.5,"litersMeasured":0,"kmMeasured":0}"#
        let s = try JSONDecoder().decode(FuelState.self, from: Data(old.utf8))
        XCTAssertNil(s.lastFuelBars)
        XCTAssertEqual(s.barsMax, 7)
    }
}
