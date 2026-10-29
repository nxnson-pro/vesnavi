import XCTest
@testable import NavCore

final class ProviderParsingTests: XCTestCase {
    func testGraphHopperParse() throws {
        let route = try GraphHopperProvider.parse(Data(Fixtures.graphHopperJSON.utf8))
        XCTAssertEqual(route.polyline.count, 5)
        XCTAssertEqual(route.polyline[0], Fixtures.p0)
        XCTAssertEqual(route.duration, 60, accuracy: 0.001)
        XCTAssertEqual(route.steps.map(\.maneuver), [.depart, .right, .arrive])
        XCTAssertEqual(route.steps.map(\.pointIndex), [0, 2, 4])
        XCTAssertEqual(route.steps[1].streetName, "Đường B")
        XCTAssertNil(route.steps[2].streetName, "empty street name should become nil")
    }

    func testGraphHopperErrorMessage() {
        XCTAssertThrowsError(try GraphHopperProvider.parse(Data(Fixtures.graphHopperError.utf8))) { error in
            XCTAssertEqual(error as? RoutingError, .server("Cannot find point 0: 0.0,0.0"))
        }
    }

    func testGraphHopperSigns() {
        XCTAssertEqual(GraphHopperProvider.maneuver(sign: -2, exit: nil), .left)
        XCTAssertEqual(GraphHopperProvider.maneuver(sign: 7, exit: nil), .keepRight)
        XCTAssertEqual(GraphHopperProvider.maneuver(sign: 6, exit: 2), .roundabout(exit: 2))
        XCTAssertEqual(GraphHopperProvider.maneuver(sign: -98, exit: nil), .uTurnLeft)
        XCTAssertEqual(GraphHopperProvider.maneuver(sign: 5, exit: nil), .straight)
    }

    func testGraphHopperURL() {
        let p = GraphHopperProvider(apiKey: "KEY", profile: "scooter") { _ in Data() }
        let url = p.url(from: Fixtures.p0, to: Fixtures.p4).absoluteString
        XCTAssertTrue(url.contains("point=10.77,106.7"))
        XCTAssertTrue(url.contains("profile=scooter"))
        XCTAssertTrue(url.contains("points_encoded=false"))
        XCTAssertTrue(url.contains("key=KEY"))
    }

    func testOSRMParseStitchesSteps() throws {
        let route = try OSRMProvider.parse(Data(Fixtures.osrmJSON.utf8))
        XCTAssertEqual(Array(route.polyline.prefix(5)),
                       [Fixtures.p0, Fixtures.p1, Fixtures.p2, Fixtures.p3, Fixtures.p4])
        XCTAssertEqual(route.steps.map(\.maneuver), [.depart, .right, .arrive])
        XCTAssertEqual(route.steps.map(\.pointIndex), [0, 2, 4])
        XCTAssertNil(route.steps[2].streetName)
    }

    func testOSRMNoRoute() {
        let json = #"{"code": "NoRoute", "message": "Impossible route"}"#
        XCTAssertThrowsError(try OSRMProvider.parse(Data(json.utf8))) { error in
            XCTAssertEqual(error as? RoutingError, .noRoute)
        }
    }

    func testProviderUsesFetcher() async throws {
        let p = OSRMProvider { _ in Data(Fixtures.osrmJSON.utf8) }
        let route = try await p.route(from: Fixtures.p0, to: Fixtures.p4)
        XCTAssertEqual(route.provider, "OSRM")
    }
}
