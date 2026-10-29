import XCTest
@testable import NavCore

final class PMP3Tests: XCTestCase {
    // Vectors from an independent transliteration of the reference client (64-bit signed arithmetic).
    func testAuthResponseVectors() {
        XCTAssertEqual(Hex.encode(PMP3.authResponse(key: Hex.decode("0102030405060708")!, challenge: Hex.decode("11223344")!), separator: ""),
                       "FCCE14AA45680772")
        XCTAssertEqual(Hex.encode(PMP3.authResponse(key: Hex.decode("DEADBEEF01234567")!, challenge: Hex.decode("A55AFF00")!), separator: ""),
                       "16C3BB4F108CA913")
        XCTAssertEqual(Hex.encode(PMP3.authResponse(key: [UInt8](repeating: 0, count: 8), challenge: [1, 2, 3, 4]), separator: ""),
                       "3F15C5293C6EF35A")
    }

    func testDeviceID() {
        // 0x19A2B3C4D5E → "0000" + 11 hex digits → odd → "0" inserted before last digit.
        XCTAssertEqual(Hex.encode(PMP3.makeDeviceID(millis: 0x19A_2B3C_4D5E), separator: ""), "000019A2B3C4D50E")
        XCTAssertEqual(PMP3.makeDeviceID(millis: 1).count, 8)
    }

    func testKeyConstraints() {
        var g = SystemRandomNumberGenerator()
        for _ in 0..<200 {
            let k = PMP3.makeKey(using: &g)
            XCTAssertEqual(k.count, 8)
            XCTAssertNotEqual(k[0], 0)
            XCTAssertNotEqual(k[1], 0x0F)
        }
    }

    func testDashPacketLayout() {
        let p = PMP3.dashPacket(icon: PMP3.Icon.left, distanceToTurn: 200, distanceToDestination: 0x012345,
                                minutesToDestination: 7, roadName: "Lê Lợi")
        XCTAssertEqual(Hex.encode(p),
                       "55 01 05 C6 " +
                       "01 0B 00 00 00 FF FF FF 00 " +
                       "03 00 07 01 23 45 00 00 C8 00 " +
                       "04 4C 65 20 4C 6F 69 00")
    }

    func testDashPacketTextModeAndUnknownDistance() {
        var o = PMP3.DashOptions()
        o.textMode = .modeFF
        let p = PMP3.dashPacket(icon: 0, distanceToTurn: 0, distanceToDestination: -5, minutesToDestination: 0,
                                roadName: nil, options: o)
        XCTAssertEqual(Hex.encode(p), "55 01 05 C6 04 FF 00 00")
        XCTAssertEqual(PMP3.be24(0), [0xFF, 0xFF, 0xFF])
    }

    func testDashFromNavigationState() {
        let s = NavigationState(maneuver: .uTurnLeft, streetName: "Đường 3/2", instruction: "", distanceToManeuver: 49.6,
                                remainingDistance: 1500, remainingDuration: 61, isOffRoute: false,
                                hasArrived: false, snappedPosition: nil)
        let p = PMP3.dashPacket(for: s)
        XCTAssertEqual(Array(p[0..<6]), [0x55, 0x01, 0x05, 0xC6, 0x01, PMP3.Icon.uTurnCounterClockwise])
        // minutes rounded up (61 s → 2), distance to turn 50 m
        XCTAssertEqual(Array(p[13..<23]), [0x03, 0x00, 0x02, 0x00, 0x05, 0xDC, 0x00, 0x00, 0x32, 0x00])
        XCTAssertEqual(Array(p[23...]), [0x04] + Array("Duong 3/2".utf8) + [0x00])
    }

    func testTextEncoding() {
        XCTAssertEqual(String(decoding: PMP3.encodeText("Nguyễn Thị Minh Khai", maxLength: 40), as: UTF8.self), "Nguyen Thi Minh Khai")
        XCTAssertEqual(String(decoding: PMP3.encodeText("Điện Biên Phủ", maxLength: 6, uppercase: true), as: UTF8.self), "DIEN B")
    }

    func testHex() {
        XCTAssertEqual(Hex.decode("0a 1B-ff"), [0x0A, 0x1B, 0xFF])
        XCTAssertNil(Hex.decode("0a1"))
        XCTAssertNil(Hex.decode("zz"))
    }

    // MARK: - Session

    private func session(_ pairing: PMP3Pairing? = nil) -> PMP3Session {
        PMP3Session(pairing: pairing, phoneName: "iPhone của Sơn", millis: 0x19A_2B3C_4D5E,
                    makeKey: { [0xAA, 0xBB, 1, 2, 3, 4, 5, 6] })
    }

    func testFirstPairingFlow() {
        let s = session()
        let start = s.start()
        XCTAssertEqual(start.writes, [Hex.decode("000019A2B3C4D50E")!])
        XCTAssertEqual(start.events, [.pairingChanged(PMP3Pairing(deviceID: Hex.decode("000019A2B3C4D50E")!))])

        XCTAssertEqual(s.handle([0, 0, 0, 0x0E]).writes, [[0x00, 0x0E] + Array("iPhone cua Son".utf8)])

        let pair = s.handle([0, 0, 0, 0x0F])
        XCTAssertEqual(pair.writes, [[0x00, 0x01, 0xAA, 0xBB, 1, 2, 3, 4, 5, 6]])
        XCTAssertTrue(pair.events.contains(.newPairingRequested))
        XCTAssertEqual(s.pairing.key, [0xAA, 0xBB, 1, 2, 3, 4, 5, 6])

        XCTAssertEqual(s.handle([0, 0, 0, 0x4F]).events, [.authenticated])
        XCTAssertEqual(s.handle([0x45, 0, 0, 0, 1]).events, [.ready(true)])
        XCTAssertTrue(s.isReady)
    }

    func testChallengeUsesStoredKey() {
        let key = Hex.decode("0102030405060708")!
        let s = session(PMP3Pairing(deviceID: [1, 2, 3, 4, 5, 6, 7, 8], key: key))
        XCTAssertTrue(s.start().events.isEmpty, "known pairing → no pairingChanged event")
        let out = s.handle([0x00, 0x00, 0x11, 0x22, 0x33, 0x44])
        XCTAssertEqual(out.writes, [[0x00, 0x01] + Hex.decode("FCCE14AA45680772")!])
        XCTAssertEqual(s.handle([0x00, 0x01, 0x01]).events, [.authResult(success: true)])
        XCTAssertEqual(s.handle([0x00, 0x01, 0x00]).events, [.authResult(success: false)])
    }

    func testFirmwareExchange() {
        let s = session()
        XCTAssertEqual(s.handle([0x00, 0x03]).writes.first?.count, 16)
        XCTAssertEqual(s.handle([0x40, 0x41]).writes, [[0x44, 0x34] + [UInt8](repeating: 0, count: 14)])
        XCTAssertEqual(s.handle([0x44, 0x34, 0x07]).writes, [[0x44, 0x38]])
        XCTAssertEqual(s.handle([0x44, 0x38, 0x02, 0x05]).writes, [[0x44, 0x39]])
        XCTAssertEqual(s.handle([0x44, 0x39, 0x01, 0x00]).events, [.firmware(variant: 7, firmware: "2.5", hardware: "1.0")])
        XCTAssertEqual(s.handle([0x4B, 0x53, 0x02]).events, [.button(2)])
        XCTAssertEqual(s.handle([0x99]).events, [.unhandled([0x99])])
    }
}
