import XCTest
@testable import NavCore

final class FormattingAndThrottleTests: XCTestCase {
    func testDistanceText() {
        XCTAssertEqual(DistanceFormatter.text(43), "40 m")
        XCTAssertEqual(DistanceFormatter.text(337), "350 m")
        XCTAssertEqual(DistanceFormatter.text(1240), "1,2 km")
        XCTAssertEqual(DistanceFormatter.text(12_340), "12 km")
    }

    func testInstructionText() {
        XCTAssertEqual(InstructionFormatter.text(for: .left, street: "Lê Lợi"), "Rẽ trái vào Lê Lợi")
        XCTAssertEqual(InstructionFormatter.text(for: .uTurnLeft, street: nil), "Quay đầu")
        XCTAssertEqual(InstructionFormatter.text(for: .roundabout(exit: 2), street: nil),
                       "Vào vòng xuyến, ra lối thứ 2")
        XCTAssertEqual(InstructionFormatter.text(for: .arrive, street: "X"), "Đến nơi")
        XCTAssertEqual(InstructionFormatter.text(for: .straight, street: "  "), "Đi thẳng")
    }

    private func state(_ m: Maneuver, _ d: Double) -> NavigationState {
        NavigationState(maneuver: m, streetName: nil, instruction: "", distanceToManeuver: d,
                        remainingDistance: 1000, remainingDuration: 100,
                        isOffRoute: false, hasArrived: false, snappedPosition: nil)
    }

    func testThrottle() {
        var t = DashboardThrottle()
        XCTAssertTrue(t.shouldSend(state(.right, 300), now: 0), "first state always sent")
        XCTAssertFalse(t.shouldSend(state(.right, 290), now: 0.5), "same rounded distance (300)")
        XCTAssertFalse(t.shouldSend(state(.right, 240), now: 0.8), "distance changed but < minInterval")
        XCTAssertTrue(t.shouldSend(state(.right, 240), now: 1.2), "distance changed, interval passed")
        XCTAssertTrue(t.shouldSend(state(.left, 500), now: 1.3), "maneuver change is immediate")
        XCTAssertFalse(t.shouldSend(state(.left, 500), now: 5))
        XCTAssertTrue(t.shouldSend(state(.left, 500), now: 11.4), "heartbeat")
    }
}
