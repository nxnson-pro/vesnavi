import Foundation

/// "Cobo" link — the BT-ROUTER module of PMP2-generation Vespas, over BLE.
/// Reconstructed byte-for-byte from an iOS HCI capture of the official Piaggio app;
/// see docs/pmp2-cobo-protocol.md.
///
/// Two BLE links are involved, in this order:
/// 1. **Key link** — phone = central. The scooter notifies a challenge `05 04 00 c0 c1 c2 c3`,
///    the phone writes `Cobo.keyReply` and the scooter notifies a 6-byte answer. The phone keeps
///    the answer, closes the link and starts advertising.
/// 2. **Data link** — phone = peripheral (GATT server with the Cobo service). The scooter connects,
///    subscribes to indications and writes `05 04 00`; the phone indicates the answer, then runs
///    the DQ init sequence and streams 0xC6 dash packets.
public enum Cobo {
    public static let serviceUUID = "24839AC4-8D7E-515C-AA42-267340CF2339"
    /// Key link: the scooter notifies on it. Data link: the phone indicates on it.
    public static let notifyCharacteristicUUID = "1734594A-B8E7-5B1A-B6B1-DD5243059A57"
    /// Data link: the scooter writes commands to it.
    public static let writeCharacteristicUUID = "9B00ACE7-FB0B-59B0-CBE9-AAEE0A26E1A3"

    /// The scooter writes this on the data link to ask for the answer.
    public static let answerRequest: [UInt8] = [0x05, 0x04, 0x00]

    /// Constant 19 bytes the official app appends to the 4-byte key (identical in every captured session).
    public static let keyReplyTail: [UInt8] = [0x03, 0x6D, 0x3B, 0x33, 0x14, 0x92, 0x85, 0xBC, 0x4A, 0xA4,
                                               0x4C, 0xC3, 0xAA, 0x75, 0x20, 0x6C, 0xC3, 0xAA, 0x75]

    /// Key-link reply to the challenge `05 04 00 c0 c1 c2 c3`: the "FEDE" key
    /// (same formula as the classic SPP auth) + `keyReplyTail`, written without response to 9B00.
    /// Verified 4/4 against the capture.
    public static func keyReply(challenge c: [UInt8]) -> [UInt8] {
        let k0 = c[1] ^ 0x46
        let k1 = (c[0] ^ c[1]) & 0x45
        let k2 = ~((c[3] ^ c[2]) & 0x44)
        let k3 = c[2] ^ 0x45
        return [k0, k1, k2, k3] + keyReplyTail
    }

    static let maxFrameData = 127

    /// DQ command packet (`44 51 00 cmd len payload checksum`) split into Cobo frames:
    /// one `C0` frame, or `C1`, `D0`…, `En` when the packet is longer than 127 bytes.
    public static func frames(_ cmd: UInt8, _ payload: [UInt8] = []) -> [[UInt8]] {
        var packet: [UInt8] = [0x44, 0x51, 0x00, cmd]
        if payload.count <= 127 {
            packet.append(UInt8(payload.count))
        } else {
            let len = payload.count | 0x8000
            packet.append(UInt8(len >> 8))
            packet.append(UInt8(len & 0xFF))
        }
        packet.append(contentsOf: payload)
        let sum = packet.reduce(0) { (acc: Int, b: UInt8) in acc + Int(b) }
        packet.append(UInt8(sum & 0xFF))

        guard packet.count > maxFrameData else { return [[0xC0] + packet] }
        var out: [[UInt8]] = []
        var index = 0
        var start = 0
        while start < packet.count {
            let end = min(start + maxFrameData, packet.count)
            let header: UInt8
            if index == 0 {
                header = 0xC1
            } else if end == packet.count {
                header = 0xE0 | UInt8((index - 1) & 0x0F)
            } else {
                header = 0xD0 | UInt8((index - 1) & 0x0F)
            }
            var frame: [UInt8] = [header]
            frame.append(contentsOf: packet[start..<end])
            out.append(frame)
            start = end
            index += 1
        }
        return out
    }

    /// 0x70 payload.
    public static let initPayload: [UInt8] = [0x00, 0x01, 0x01, 0x03, 0x02, 0x00, 0x00, 0x01, 0x01, 0x01, 0x04, 0x02, 0x00, 0xFF]
    /// 0x23 payload as sent by the official app.
    public static let subscribePayload: [UInt8] = [0x01, 0x00]
    /// 0x28 payload as sent by the official app (CAN signal subscription), 407 bytes, constant across sessions.
    public static let structuredSubscribePayload: [UInt8] = Hex.decode([
        "00010E000000000590300800000000059028080000000005901701000000000590160100000000059015010000000005900F",
        "010000000005900E010000000005892F010000000005892E0100000000058938080000000005893503000000000589300500",
        "0000000589280600000000058910080000000005890808000000000589000800000000030018080000000003000808000000",
        "0001803010000000000180180800000000018010080000000002200901000000000220080100000000022000080000000002",
        "60181000000000026008100000000001303D02000000000130180800000000010030010000000001002A020000000001002E",
        "0100000000012018080000000001201010000000000701380800000000070130080000000007012808000000000701200800",
        "0000000701180800000000070110080000000007010808000000000701000800000000070038080000000007003008000000",
        "0007002808000000000700200800000000070018080000000007001008000000000700080800000000070000080103090103",
        "08010307010306",
    ].joined()) ?? []
    /// Parameters the official app reads once per session with 0x27 (the scooter answers with 0x30),
    /// first ~2 s after init, retried every ~10 s until answered, then the next one.
    public static let readIDs: [[UInt8]] = [[0xFE, 0x0B], [0xFF, 0x06]]
    /// 0x27 read-request payload, e.g. `01 04 09 00 00 FE 0B 55 55 55 55 55`.
    public static func readRequestPayload(_ id: [UInt8]) -> [UInt8] {
        [0x01, 0x04, 0x09, 0x00, 0x00] + id + [0x55, 0x55, 0x55, 0x55, 0x55]
    }

    /// 0xC6 dash payload laid out like the official app: tags 01, 02, 03 and 04 are always present.
    /// `navigating` appends tag `06 04 00` ("navigation running"), needed on every packet while a route
    /// is active on the GTS Supertech — without it the TFT keeps its normal screen and ignores the arrow.
    public static func dashPayload(icon: UInt8, distanceToTurn: Int, distanceToDestination: Int,
                                   minutesToDestination: Int, text: String, navigating: Bool = true,
                                   speedLimit: Int = 0) -> [UInt8] {
        var p: [UInt8] = [PMP3.dashTag]
        p.append(contentsOf: [0x01, icon, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00])
        // Tag 02: speed limit in km/h (official `writeSpeedLimitInfo`), 0 = none.
        p.append(contentsOf: [0x02, UInt8(max(0, min(speedLimit, 250))), 0x00])
        p.append(0x03)
        p.append(contentsOf: PMP3.be16(minutesToDestination))
        p.append(contentsOf: PMP3.be24(distanceToDestination))
        p.append(contentsOf: PMP3.be24(distanceToTurn))
        p.append(0x00)
        p.append(0x04)
        p.append(contentsOf: PMP3.encodeText(text, maxLength: 20, uppercase: true))
        p.append(0x00)
        if navigating { p.append(contentsOf: navigationStarted) }
        return p
    }

    /// Tag 06: `06 04 00` navigation running, `06 03 00` navigation stopped.
    public static let navigationStarted: [UInt8] = [0x06, 0x04, 0x00]
    public static let navigationStopped: [UInt8] = [0x06, 0x03, 0x00]

    /// What the official app streams while no route is active.
    public static let idleDash: [UInt8] = dashPayload(icon: 0, distanceToTurn: 0, distanceToDestination: 0,
                                                      minutesToDestination: 0, text: "NO ADDRESS", navigating: false)
    /// Sent once when a route ends, so the TFT leaves the navigation screen.
    public static let stopDash: [UInt8] = idleDash + navigationStopped

    /// Two-line pop-up: `C2 01 bg line1 ';' line2 00`, sent in place of the dash packet for
    /// 9 consecutive packets. `background` 13 suits the GTS 300 Supertech.
    public static func popupPayload(_ line1: String, _ line2: String, background: UInt8 = 13) -> [UInt8] {
        [0xC2, 0x01, background]
            + PMP3.encodeText(line1.replacingOccurrences(of: ";", with: ","), maxLength: 20, uppercase: true)
            + [0x3B]
            + PMP3.encodeText(line2.replacingOccurrences(of: ";", with: ","), maxLength: 20, uppercase: true)
            + [0x00]
    }
    public static let popupRepeats = 9

    /// Idle text line with engine data, e.g. "NUOC 85C  AQ 13.8V" / "COOL 85C  BAT 13.8V" (≤ 20 ASCII chars).
    public static func infoText(_ t: ScooterTelemetry, language: Language = .vi) -> String? {
        var parts: [String] = []
        if let c = t.coolantC, t.engineRunning { parts.append("\(language.pick("NUOC", "COOL")) \(c)C") }
        if let v = t.batteryVolts { parts.append(String(format: "\(language.pick("AQ", "BAT")) %.1fV", v)) }
        if !t.engineRunning, let a = t.ambientC { parts.append("\(a)C") }
        return parts.isEmpty ? nil : parts.joined(separator: "  ")
    }

    /// Idle dash carrying `text` on the street-name line; `navigationScreen` also sends `06 04 00`
    /// (the TFT may only show the line while its navigation screen is active).
    public static func infoDash(_ text: String, navigationScreen: Bool) -> [UInt8] {
        dashPayload(icon: 0, distanceToTurn: 0, distanceToDestination: 0, minutesToDestination: 0,
                    text: text, navigating: navigationScreen)
    }

    public static func dashPayload(for state: NavigationState, language: Language = .vi) -> [UInt8] {
        if state.isOffRoute {
            return dashPayload(icon: PMP3.Icon.recalculating, distanceToTurn: 0, distanceToDestination: 0,
                               minutesToDestination: 0, text: language.pick("Dang tim duong", "Rerouting"))
        }
        if state.hasArrived {
            return dashPayload(icon: PMP3.Icon.arrive, distanceToTurn: 1, distanceToDestination: 1,
                               minutesToDestination: 1, text: language.pick("Den noi", "Arrived"))
        }
        return dashPayload(icon: PMP3.icon(for: state),
                           distanceToTurn: max(1, Int(state.distanceToManeuver.rounded())),
                           distanceToDestination: max(1, Int(state.remainingDistance.rounded())),
                           minutesToDestination: max(1, Int((state.remainingDuration / 60).rounded(.up))),
                           text: dashText(for: state, language: language), speedLimit: state.speedLimit ?? 0)
    }

    /// Street name, or the short instruction ("RE PHAI") when the road has no name —
    /// an empty text line looks broken on the TFT (seen on the scooter 2026-10-29).
    static func dashText(for state: NavigationState, language: Language = .vi) -> String {
        if let street = state.streetName?.trimmingCharacters(in: .whitespaces), !street.isEmpty { return street }
        return InstructionFormatter.text(for: state.maneuver, street: nil, language: language)
    }
}

/// Key link (phone = central): answer the challenge with `Cobo.keyReply`, then keep the scooter's 6-byte answer.
public final class CoboKeyFetch {
    public enum Event: Equatable, Sendable {
        /// Write `reply` to 9B00 (without response); the scooter then notifies the 6-byte answer.
        case challenge([UInt8], reply: [UInt8])
        case answer([UInt8])
        case ignored([UInt8])
    }

    public private(set) var answer: [UInt8]?
    private var challenge: [UInt8]?

    public init() {}

    public func handle(_ b: [UInt8]) -> Event {
        if b.count == 7, b[0] == 0x05, b[1] == 0x04, b[2] == 0x00 {
            // A new challenge always restarts the exchange.
            challenge = b
            answer = nil
            return .challenge(b, reply: Cobo.keyReply(challenge: Array(b[3...])))
        }
        if challenge != nil, answer == nil, b.count == 6 {
            answer = b
            return .answer(b)
        }
        return .ignored(b)
    }
}

/// Data link (phone = GATT server): answers the scooter's writes the way the official app does.
/// No F0 acks on BLE — ATT write responses and indication confirmations replace them.
public final class CoboServerSession {
    public enum Stage: Equatable, Sendable { case waitingForRequest, initializing, ready }

    public enum Event: Equatable, Sendable {
        case answerSent
        /// The scooter asked for the answer but the key link didn't provide one.
        case missingAnswer
        case command(UInt8, [UInt8])
        case canData([UInt8])
        case ready
        case unhandled([UInt8])
    }

    public struct Output: Equatable, Sendable {
        public var indications: [[UInt8]] = []
        public var events: [Event] = []
    }

    public private(set) var stage: Stage = .waitingForRequest
    /// 0x27 reads not answered yet (front = the one in flight).
    public private(set) var pendingReads: [[UInt8]] = Cobo.readIDs
    private let answer: [UInt8]?

    public init(answer: [UInt8]?) {
        self.answer = answer
    }

    public func handleWrite(_ b: [UInt8]) -> Output {
        var out = Output()
        if b == Cobo.answerRequest {
            guard let answer else {
                out.events.append(.missingAnswer)
                return out
            }
            out.indications.append(answer)
            out.indications += Cobo.frames(0x70, Cobo.initPayload)
            out.events.append(.answerSent)
            stage = .initializing
        } else if b.count >= 6, b[0] == 0xC0, b[1] == 0x44, b[2] == 0x51 {
            let cmd = b[4]
            out.events.append(.command(cmd, Array(b.dropFirst(6).prefix(Int(b[5])))))
            switch cmd {
            case 0x70:
                out.indications += Cobo.frames(0x01)
            case 0x02:
                out.indications += Cobo.frames(0x06)
                out.indications += Cobo.frames(0x10)
            case 0x30:
                // Answer to our 0x27 read: drop it and ask for the next parameter straight away.
                if let first = pendingReads.first, b.count >= 8, Self.contains(Array(b[6...]), first) {
                    pendingReads.removeFirst()
                    out.indications += readRequestFrames()
                }
            case 0x11:
                out.indications += Cobo.frames(0x23, Cobo.subscribePayload)
                out.indications += Cobo.frames(0x28, Cobo.structuredSubscribePayload)
                stage = .ready
                out.events.append(.ready)
            default:
                break
            }
        } else if b.count >= 2, b[0] == 0xC0, b[1] == 0x80 {
            out.events.append(.canData(b))
        } else {
            out.events.append(.unhandled(b))
        }
        return out
    }

    /// 0x27 frames for the next unanswered read, or nothing once both were answered.
    public func readRequestFrames() -> [[UInt8]] {
        guard stage == .ready, let id = pendingReads.first else { return [] }
        return Cobo.frames(0x27, Cobo.readRequestPayload(id))
    }

    private static func contains(_ haystack: [UInt8], _ needle: [UInt8]) -> Bool {
        guard haystack.count >= needle.count else { return false }
        return (0...(haystack.count - needle.count)).contains { Array(haystack[$0..<($0 + needle.count)]) == needle }
    }
}
