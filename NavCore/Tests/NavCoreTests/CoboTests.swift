import XCTest
@testable import NavCore

/// Every expected byte string below is copied from the iOS HCI capture of the official app.
final class CoboTests: XCTestCase {
    private func hex(_ s: String) -> [UInt8] { Hex.decode(s) ?? [] }

    func testSingleFramesMatchCapture() {
        XCTAssertEqual(Cobo.frames(0x70, Cobo.initPayload),
                       [hex("C0 44 51 00 70 0E 00 01 01 03 02 00 00 01 01 01 04 02 00 FF 22")])
        XCTAssertEqual(Cobo.frames(0x01), [hex("C0 44 51 00 01 00 96")])
        XCTAssertEqual(Cobo.frames(0x06), [hex("C0 44 51 00 06 00 9B")])
        XCTAssertEqual(Cobo.frames(0x10), [hex("C0 44 51 00 10 00 A5")])
        XCTAssertEqual(Cobo.frames(0x23, Cobo.subscribePayload), [hex("C0 44 51 00 23 02 01 00 BB")])
        XCTAssertEqual(Cobo.frames(0x27, Cobo.readRequestPayload([0xFE, 0x0B])),
                       [hex("C0 44 51 00 27 0C 01 04 09 00 00 FE 0B 55 55 55 55 55 88")])
        XCTAssertEqual(Cobo.frames(0x27, Cobo.readRequestPayload([0xFF, 0x06])),
                       [hex("C0 44 51 00 27 0C 01 04 09 00 00 FF 06 55 55 55 55 55 84")])
    }

    func testMultiFrame0x28MatchesCapture() {
        XCTAssertEqual(Cobo.structuredSubscribePayload.count, 407)
        let frames = Cobo.frames(0x28, Cobo.structuredSubscribePayload)
        XCTAssertEqual(frames.map(\.count), [128, 128, 128, 34])
        XCTAssertEqual(frames.map { $0[0] }, [0xC1, 0xD0, 0xD1, 0xE2])
        XCTAssertEqual(Array(frames[0].prefix(10)), hex("C1 44 51 00 28 81 97 00 01 0E"))
        XCTAssertEqual(Array(frames[3].suffix(7)), hex("01 03 07 01 03 06 99"), "payload tail + checksum from the capture")
    }

    func testIdleDashMatchesCapture() {
        XCTAssertEqual(Cobo.idleDash,
                       hex("C6 01 00 00 00 00 FF FF FF 00 02 00 00 03 00 00 FF FF FF FF FF FF 00 04 4E 4F 20 41 44 44 52 45 53 53 00"))
    }

    func testDashForState() {
        let s = NavigationState(maneuver: .left, streetName: "Lê Lợi", instruction: "", distanceToManeuver: 199.6,
                                remainingDistance: 1500, remainingDuration: 61, isOffRoute: false,
                                hasArrived: false, snappedPosition: nil)
        XCTAssertEqual(Cobo.dashPayload(for: s),
                       hex("C6 01 0B 00 00 00 FF FF FF 00 02 00 00 03 00 02 00 05 DC 00 00 C8 00 04 4C 45 20 4C 4F 49 00 06 04 00"))
        // With the maneuver after it (arrival 1500 m away): tag 01 carries its icon and distance
        // instead of 00 FF FF FF, which the TFT shows as "…".
        var then = s
        then.afterNextManeuver = .arrive
        then.distanceToAfterNextManeuver = 1500
        XCTAssertEqual(Cobo.dashPayload(for: then),
                       hex("C6 01 0B 00 00 C9 00 05 DC 00 02 00 00 03 00 02 00 05 DC 00 00 C8 00 04 4C 45 20 4C 4F 49 00 06 04 00"))
        XCTAssertEqual(Cobo.stopDash.suffix(3), [0x06, 0x03, 0x00])
        XCTAssertFalse(Cobo.idleDash.contains(0x06), "idle dash has no tag 06 (matches the capture)")
    }

    func testKeyReplyMatchesCapture() {
        let tail = " 03 6D 3B 33 14 92 85 BC 4A A4 4C C3 AA 75 20 6C C3 AA 75"
        XCTAssertEqual(Cobo.keyReply(challenge: hex("89 06 13 66")), hex("40 05 BB 56" + tail))
        XCTAssertEqual(Cobo.keyReply(challenge: hex("EC 6B B8 8B")), hex("2D 05 FF FD" + tail))
        XCTAssertEqual(Cobo.keyReply(challenge: hex("1E BA 92 F5")), hex("FC 04 BB D7" + tail))
        XCTAssertEqual(Cobo.keyReply(challenge: hex("DF 1C F8 2A")), hex("5A 41 BF BD" + tail))
    }

    func testKeyFetch() {
        let k = CoboKeyFetch()
        XCTAssertEqual(k.handle(hex("9D 1E A3 BE 50 25")), .ignored(hex("9D 1E A3 BE 50 25")), "answer before challenge")
        XCTAssertEqual(k.handle(hex("05 04 00")), .ignored(hex("05 04 00")), "3-byte request is not a challenge")
        XCTAssertEqual(k.handle(hex("05 04 00 89 06 13 66")),
                       .challenge(hex("05 04 00 89 06 13 66"), reply: Cobo.keyReply(challenge: hex("89 06 13 66"))))
        XCTAssertEqual(k.handle(hex("01 02 03")), .ignored(hex("01 02 03")), "wrong length is not the answer")
        XCTAssertEqual(k.handle(hex("9D 1E A3 BE 50 25")), .answer(hex("9D 1E A3 BE 50 25")))
        XCTAssertEqual(k.answer, hex("9D 1E A3 BE 50 25"))
        // A new challenge discards the old answer.
        XCTAssertEqual(k.handle(hex("05 04 00 1E BA 92 F5")),
                       .challenge(hex("05 04 00 1E BA 92 F5"), reply: Cobo.keyReply(challenge: hex("1E BA 92 F5"))))
        XCTAssertNil(k.answer)
        XCTAssertEqual(k.handle(hex("8A 1F A7 4E 68 1C")), .answer(hex("8A 1F A7 4E 68 1C")))
    }

    func testServerSessionReplaysOfficialSequence() {
        let answer = hex("9D 1E A3 BE 50 25")
        let s = CoboServerSession(answer: answer)

        let r1 = s.handleWrite(Cobo.answerRequest)
        XCTAssertEqual(r1.indications, [answer] + Cobo.frames(0x70, Cobo.initPayload))
        XCTAssertEqual(r1.events, [.answerSent])

        let r2 = s.handleWrite(hex("C0 44 51 00 70 0A 00 01 01 02 01 01 03 02 00 00 1A"))
        XCTAssertEqual(r2.indications, Cobo.frames(0x01))

        let r3 = s.handleWrite(hex("C0 44 51 00 02 00 97"))
        XCTAssertEqual(r3.indications, Cobo.frames(0x06) + Cobo.frames(0x10), "0x10 must follow 0x06")

        let r4 = s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        XCTAssertEqual(r4.indications, Cobo.frames(0x23, Cobo.subscribePayload) + Cobo.frames(0x28, Cobo.structuredSubscribePayload))
        XCTAssertTrue(r4.events.contains(.ready))
        XCTAssertEqual(s.stage, .ready)

        // 0x27 reads: FE 0B first, the scooter's 0x30 answer triggers FF 06, then nothing.
        XCTAssertEqual(s.readRequestFrames(), Cobo.frames(0x27, Cobo.readRequestPayload([0xFE, 0x0B])))
        let r5 = s.handleWrite(hex("C0 44 51 00 30 1C 01 00 19 01 C0 12 FE 0B 04 00 01 00 00 00"))
        XCTAssertEqual(r5.indications, Cobo.frames(0x27, Cobo.readRequestPayload([0xFF, 0x06])))
        XCTAssertEqual(s.readRequestFrames(), r5.indications, "retry resends the pending read")
        let r6 = s.handleWrite(hex("C0 44 51 00 30 0C 01 00 09 01 43 FF 06 00 54 24 AA AA F0"))
        XCTAssertTrue(r6.indications.isEmpty)
        XCTAssertTrue(s.readRequestFrames().isEmpty)
        XCTAssertTrue(s.pendingReads.isEmpty)

        let can = hex("C0 80 78 34 01 01 00 00 00 00 00 16 05 10 19 C3 79 00 00 00")
        XCTAssertEqual(s.handleWrite(can).events, [.canData(can)])
        XCTAssertTrue(s.handleWrite(can).indications.isEmpty, "no F0 acks on BLE")
    }

    func testServerSessionResendsAStalledInitStep() {
        let answer = hex("9D 1E A3 BE 50 25")
        let s = CoboServerSession(answer: answer)
        XCTAssertTrue(s.retryStalledInit().isEmpty, "nothing to resend before the scooter asks")

        let r1 = s.handleWrite(Cobo.answerRequest)
        XCTAssertEqual(s.retryStalledInit(), r1.indications, "answer + 0x70 again")

        let r2 = s.handleWrite(hex("C0 44 51 00 70 0A 00 01 01 02 01 01 03 02 00 00 1A"))
        XCTAssertEqual(s.initRetries, 0, "a new step resets the count")
        XCTAssertEqual(s.retryStalledInit(), Cobo.frames(0x01))
        XCTAssertEqual(s.retryStalledInit(), r2.indications)
        XCTAssertTrue(s.retryStalledInit().isEmpty, "at most maxInitRetries per step")

        _ = s.handleWrite(hex("C0 44 51 00 02 00 97"))
        XCTAssertEqual(s.retryStalledInit(), Cobo.frames(0x06) + Cobo.frames(0x10))
        let r4 = s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        XCTAssertTrue(r4.events.contains(.ready))
        // The scooter may answer a re-sent step twice: 0x23 + 0x28 go out only once.
        let dup = s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        XCTAssertTrue(dup.indications.isEmpty, "0x23 + 0x28 only once")
        XCTAssertFalse(dup.events.contains(.ready))
        XCTAssertTrue(s.handleWrite(hex("C0 44 51 00 02 00 97")).indications.isEmpty, "no init replies once ready")
        XCTAssertTrue(s.retryStalledInit().isEmpty, "nothing to resend once ready")
    }

    func testServerSessionProceedsWithoutInit() {
        let s = CoboServerSession(answer: hex("9D 1E A3 BE 50 25"))
        XCTAssertFalse(s.proceedWithoutInit(), "nothing to skip before the scooter asks")
        _ = s.handleWrite(Cobo.answerRequest)
        _ = s.handleWrite(hex("C0 44 51 00 70 0A 00 01 01 02 01 01 03 02 00 00 1A"))
        // The scooter goes silent after our 0x01 (issue #6) but keeps the link.
        XCTAssertTrue(s.proceedWithoutInit())
        XCTAssertEqual(s.stage, .ready)
        XCTAssertTrue(s.initSkipped)
        XCTAssertTrue(s.readRequestFrames().isEmpty, "no 0x27 reads without the init")
        XCTAssertTrue(s.retryStalledInit().isEmpty)
        XCTAssertFalse(s.proceedWithoutInit(), "only once")

        // A late finish still subscribes to engine data, without a second ready.
        XCTAssertEqual(s.handleWrite(hex("C0 44 51 00 02 00 97")).indications, Cobo.frames(0x06) + Cobo.frames(0x10))
        let late = s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD"))
        XCTAssertEqual(late.indications, Cobo.frames(0x23, Cobo.subscribePayload) + Cobo.frames(0x28, Cobo.structuredSubscribePayload))
        XCTAssertFalse(late.events.contains(.ready))
        XCTAssertFalse(s.initSkipped)
        XCTAssertEqual(s.readRequestFrames(), Cobo.frames(0x27, Cobo.readRequestPayload([0xFE, 0x0B])))
        XCTAssertTrue(s.handleWrite(hex("C0 44 51 00 11 06 FF 02 00 FF 01 00 AD")).indications.isEmpty)
    }

    func testClockSetFrames() {
        // 0x27 = write one value; the known read request has the same shape (number 04, 9 bytes).
        XCTAssertEqual(Cobo.frames(0x27, Cobo.readRequestPayload([0xFE, 0x0B])),
                       [hex("C0 44 51 00 27 0C 01 04 09 00 00 FE 0B 55 55 55 55 55 88")])
        // 17:57 → CAN id 0x600 (low byte first), 8 bytes, minutes × 2 + 1 = 0x73, hours = 0x11.
        XCTAssertEqual(Cobo.clockSetFrames(hour: 17, minute: 57),
                       [hex("C0 44 51 00 27 10 01 0B 0D 00 06 00 00 08 00 00 00 73 11 00 00 00 77")])
        XCTAssertEqual(Cobo.clockSetFrames(hour: 0, minute: 0),
                       [hex("C0 44 51 00 27 10 01 0B 0D 00 06 00 00 08 00 00 00 01 00 00 00 00 F4")])
        XCTAssertEqual(Cobo.clockSetEndFrames,
                       [hex("C0 44 51 00 27 10 01 0B 0D 00 06 00 00 08 00 00 00 00 00 00 00 00 F3")])
        XCTAssertEqual(Cobo.clockSetFrames(hour: 99, minute: 99), Cobo.clockSetFrames(hour: 23, minute: 59), "clamped")
        XCTAssertEqual(Cobo.canFramePayload(id: 0x123, data: [1, 2]),
                       hex("01 0B 0D 23 01 00 00 02 01 02 FF FF FF FF FF FF"), "short frames are padded with FF")
    }

    func testClockSyncSequence() {
        var sync = CoboClockSync()
        XCTAssertTrue(sync.tick(hour: 8, minute: 15, second: 30).isEmpty, "waits for the start of a minute")
        XCTAssertFalse(sync.isRunning)
        // 4 × the clock frame (with the time of each tick), then 4 × the all-zero frame.
        XCTAssertEqual(sync.tick(hour: 8, minute: 16, second: 0), Cobo.clockSetFrames(hour: 8, minute: 16))
        XCTAssertTrue(sync.isRunning)
        for _ in 0..<3 { XCTAssertEqual(sync.tick(hour: 8, minute: 16, second: 40), Cobo.clockSetFrames(hour: 8, minute: 16)) }
        for _ in 0..<4 { XCTAssertEqual(sync.tick(hour: 8, minute: 16, second: 3), Cobo.clockSetEndFrames) }
        XCTAssertTrue(sync.isDone)
        XCTAssertTrue(sync.tick(hour: 8, minute: 17, second: 0).isEmpty, "once per data link")
    }

    func testServerSessionWithoutAnswer() {
        let s = CoboServerSession(answer: nil)
        let r = s.handleWrite(Cobo.answerRequest)
        XCTAssertEqual(r.events, [.missingAnswer])
        XCTAssertTrue(r.indications.isEmpty)
        XCTAssertEqual(s.stage, .waitingForRequest)
    }

    func testDashPayloadWithoutHeader() {
        let p = PMP3.dashPacket(icon: PMP3.Icon.left, distanceToTurn: 200, distanceToDestination: 500,
                                minutesToDestination: 2, roadName: nil, includeHeader: false)
        XCTAssertEqual(p.first, 0xC6)
    }
}
