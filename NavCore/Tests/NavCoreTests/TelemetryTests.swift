import XCTest
@testable import NavCore

final class TelemetryTests: XCTestCase {
    /// A data frame from the owner's GTS 300 Supertech, key on, engine off (2026-10-29 11:04).
    /// TFT at the time: 32 °C, ODO 31171 km.
    private let captured = Hex.decode("C0 80 76 34 01 01 00 00 00 00 00 16 05 11 39 C3 79 00 00 00 27 3E 4D 4A 00 00 00 00 00 00 00 01 00 00 01 00 00 00 00 00 00 00 00 00 00 00 00 02 00 00 00 00 01 01 01 00 00 00 00 00 00 00 00") ?? []

    func testLayoutMatchesSubscription() {
        XCTAssertEqual(CoboTelemetry.signals.count, 49)
        XCTAssertEqual(CoboTelemetry.frameDataLength, 61)
        XCTAssertEqual(captured.count - 2, CoboTelemetry.frameDataLength)
    }

    func testDecodesCapturedFrame() throws {
        let t = try XCTUnwrap(CoboTelemetry.parse(captured))
        XCTAssertEqual(t.odometerKm, 31171)
        XCTAssertEqual(t.ambientC, 32)
        XCTAssertEqual(t.batteryVolts ?? 0, 11.8, accuracy: 0.001)
        XCTAssertEqual(t.coolantC, 34)
        XCTAssertEqual(t.intakeAirC, 37)
        XCTAssertEqual(t.rpm, 0)
        XCTAssertEqual(t.throttle, 0)
        XCTAssertEqual(t.rearWheelKmh, 0)
        XCTAssertEqual(t.clockHour, 17)
        XCTAssertEqual(t.clockMinute, 57)
        XCTAssertFalse(t.engineRunning)
    }

    func testRejectsOtherFrames() {
        XCTAssertNil(CoboTelemetry.parse(Hex.decode("C0 44 51 00 02 00 97") ?? []))
        XCTAssertNil(CoboTelemetry.parse(Array(captured.prefix(20))))
    }

    func testSixteenBitSignalsAreBigEndian() throws {
        var f = captured
        f[2 + 35] = 0x06  // RPM high byte (0x120 start 16, 2 bytes at data offset 35)
        f[2 + 36] = 0x40
        XCTAssertEqual(try XCTUnwrap(CoboTelemetry.parse(f)).rpm, 1600)
    }

    func testPopupLayout() {
        XCTAssertEqual(Cobo.popupPayload("Nhiệt độ cao", "Nuoc 108C"),
                       [0xC2, 0x01, 13] + Array("NHIET DO CAO".utf8) + [0x3B] + Array("NUOC 108C".utf8) + [0x00])
        XCTAssertFalse(Cobo.popupPayload("a;b", "c").dropFirst(3).dropLast().filter { $0 == 0x3B }.count > 1,
                       "a ';' inside a line would split it")
    }

    func testInfoText() throws {
        var t = try XCTUnwrap(CoboTelemetry.parse(captured))
        XCTAssertEqual(Cobo.infoText(t), "AQ 11.8V  32C")
        t.rpm = 1600
        t.coolantC = 85
        t.batteryVolts = 13.8
        XCTAssertEqual(Cobo.infoText(t), "NUOC 85C  AQ 13.8V")
        XCTAssertLessThanOrEqual(Cobo.infoText(t)?.count ?? 99, 20)
        XCTAssertEqual(Cobo.infoDash("X", navigationScreen: true).suffix(3), [0x06, 0x04, 0x00])
        XCTAssertNotEqual(Cobo.infoDash("X", navigationScreen: false).suffix(3), [0x06, 0x04, 0x00])
    }

    /// Riding at ~47 km/h, 4600 rpm, coolant 85 °C (on-scooter log 2026-10-29 12:38:33).
    func testDecodesRidingFrame() throws {
        var f = captured
        f[2 + 21] = 85 + 40                        // coolant
        f[2 + 24] = 52                             // TFT speedometer
        f[2 + 25] = 0x12; f[2 + 26] = 0x72         // rear wheel 4722 → 47.22 km/h
        f[2 + 35] = 0x11; f[2 + 36] = 0xF9         // 4601 rpm
        let t = try XCTUnwrap(CoboTelemetry.parse(f))
        XCTAssertEqual(t.rpm, 4601)
        XCTAssertEqual(t.dashboardSpeedKmh, 52)
        XCTAssertEqual(t.rearWheelKmh ?? 0, 47.22, accuracy: 0.001)
        XCTAssertEqual(t.coolantC, 85)
        XCTAssertTrue(t.engineRunning)
    }

    func testUnnamedRoadFallsBackToInstruction() {
        let s = NavigationState(maneuver: .right, streetName: nil, instruction: "", distanceToManeuver: 100,
                                remainingDistance: 500, remainingDuration: 60, isOffRoute: false,
                                hasArrived: false, snappedPosition: nil)
        XCTAssertEqual(Cobo.dashText(for: s), "Rẽ phải")
        let payload = Cobo.dashPayload(for: s)
        let text = Array("RE PHAI".utf8)
        XCTAssertTrue((0...(payload.count - text.count)).contains { Array(payload[$0..<($0 + text.count)]) == text })
    }
}

