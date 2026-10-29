import Foundation

/// Piaggio "PMP3" dashboard protocol over BLE (Vespa TFT / MIA generation).
/// Field-level notes and provenance: docs/pmp3-protocol.md.
public enum PMP3 {
    /// GATT service (Automation IO, 16-bit) and its two characteristics.
    public static let serviceUUID = "1815"
    /// Phone → scooter (write with response).
    public static let writeCharacteristicUUID = "2A56"
    /// Scooter → phone (notify).
    public static let notifyCharacteristicUUID = "2A5A"

    /// Prefix the phone puts in front of every dashboard (0xC6) packet.
    public static let dashHeader: [UInt8] = [0x55, 0x01, 0x05]
    public static let dashTag: UInt8 = 0xC6
    /// Largest dash payload the original client allows (excluding header).
    public static let maxDashPayload = 120

    // MARK: - Pairing / authentication

    /// Response to a `00 00 c0 c1 c2 c3` challenge: two XTEA-style rounds over the
    /// 8-byte pairing key, keyed by the 4 challenge bytes.
    public static func authResponse(key: [UInt8], challenge: [UInt8]) -> [UInt8] {
        precondition(challenge.count >= 4)
        let k = key.count == 8 ? key : [UInt8](repeating: 0, count: 8)
        let c = challenge.prefix(4).map(UInt32.init)
        var v0 = UInt32(bytesBE: k[0..<4]), v1 = UInt32(bytesBE: k[4..<8])
        var sum: UInt32 = 0
        for _ in 0..<2 {
            let n0 = v0 &+ ((c[Int(sum & 3)] &+ sum) ^ (((v1 << 4) ^ (v1 >> 5)) &+ v1))
            sum = sum &+ 0x9E37_79B9
            let n1 = v1 &+ ((c[Int((sum >> 11) & 3)] &+ sum) ^ (((n0 << 4) ^ (n0 >> 5)) &+ n0))
            v0 = n1
            v1 = n0
        }
        return (v0 &+ c[2]).bigEndianBytes + (v1 &+ c[3]).bigEndianBytes
    }

    /// 8-byte client identifier sent first on every connection ("0000" + hex(millis)).
    public static func makeDeviceID(millis: UInt64) -> [UInt8] {
        var hex = "0000" + String(millis, radix: 16, uppercase: true)
        if hex.count % 2 == 1 { hex.insert("0", at: hex.index(before: hex.endIndex)) }
        var bytes = Hex.decode(hex) ?? []
        if bytes.count < 8 { bytes = [UInt8](repeating: 0, count: 8 - bytes.count) + bytes }
        return Array(bytes.suffix(8))
    }

    /// Random 8-byte secret handed to the scooter when it asks for a new pairing (`00 00 00 0F`).
    public static func makeKey<G: RandomNumberGenerator>(using rng: inout G) -> [UInt8] {
        while true {
            let key = (0..<8).map { _ in UInt8.random(in: 0...255, using: &rng) }
            if key[0] != 0 && key[1] != 0x0F { return key }
        }
    }

    // MARK: - Dashboard packet

    /// TFT pictogram codes (Google maneuver → code table recovered from the reference client).
    public enum Icon {
        public static let straight: UInt8 = 1
        public static let uTurnClockwise: UInt8 = 2
        public static let uTurnCounterClockwise: UInt8 = 3
        public static let keepRight: UInt8 = 4
        public static let slightRight: UInt8 = 5
        public static let right: UInt8 = 6
        public static let sharpRight: UInt8 = 7
        public static let merge: UInt8 = 8
        public static let keepLeft: UInt8 = 9
        public static let slightLeft: UInt8 = 10
        public static let left: UInt8 = 11
        public static let sharpLeft: UInt8 = 12
        public static let rampRight: UInt8 = 13
        public static let rampLeft: UInt8 = 14
        public static let exitRight: UInt8 = 15
        public static let exitLeft: UInt8 = 16
        /// Counter-clockwise roundabout (right-hand traffic, e.g. Vietnam): 100 + exit direction
        /// 0 U-turn, 1 sharp right, 2 right, 3 slight right, 4 straight, 5 slight left, 6 left, 7 sharp left.
        public static let roundaboutCCWStraight: UInt8 = 104
        public static let continueOn: UInt8 = 200
        public static let arrive: UInt8 = 201
        /// Shown while guidance is unavailable / recalculating.
        public static let recalculating: UInt8 = 203
    }

    /// Icon for right-hand traffic (Vietnam): U-turns go counter-clockwise, roundabouts too.
    public static func icon(for maneuver: Maneuver) -> UInt8 {
        switch maneuver {
        case .depart: return Icon.continueOn
        case .straight: return Icon.straight
        case .slightLeft: return Icon.slightLeft
        case .left: return Icon.left
        case .sharpLeft: return Icon.sharpLeft
        case .slightRight: return Icon.slightRight
        case .right: return Icon.right
        case .sharpRight: return Icon.sharpRight
        case .keepLeft: return Icon.keepLeft
        case .keepRight: return Icon.keepRight
        case .uTurnLeft: return Icon.uTurnCounterClockwise
        case .uTurnRight: return Icon.uTurnClockwise
        case .roundabout: return Icon.roundaboutCCWStraight
        case .arrive: return Icon.arrive
        }
    }

    /// Like `icon(for: Maneuver)`, but roundabouts get the pictogram of the actual exit direction.
    public static func icon(for state: NavigationState) -> UInt8 {
        if case .roundabout = state.maneuver, let d = state.roundaboutTurnDegrees {
            return RouteAnnotator.roundaboutIcon(turnDegrees: d)
        }
        return icon(for: state.maneuver)
    }

    /// How the road-name field (tag 4) is prefixed; depends on the scooter model.
    public enum TextMode: Int, CaseIterable, Codable, Sendable {
        case plain = 0       // 04 <text> 00
        case modeFF = 1      // 04 FF 00 <text> 00
        case mode02 = 2      // 04 02 00 <text> 00
        case mode01 = 3      // 04 01 00 <text> 00

        var prefix: [UInt8] {
            switch self {
            case .plain: return []
            case .modeFF: return [0xFF, 0x00]
            case .mode02: return [0x02, 0x00]
            case .mode01: return [0x01, 0x00]
            }
        }
    }

    public struct DashOptions: Equatable, Sendable {
        public var textMode: TextMode = .plain
        public var maxTextLength = 20
        public var uppercase = false
        public init() {}
    }

    /// Full write (header + 0xC6 payload). Distances in meters.
    public static func dashPacket(icon: UInt8,
                                  distanceToTurn: Int,
                                  distanceToDestination: Int,
                                  minutesToDestination: Int,
                                  roadName: String?,
                                  options: DashOptions = DashOptions(),
                                  includeHeader: Bool = true) -> [UInt8] {
        var p: [UInt8] = [dashTag]
        if icon != 0 {
            // tag 1: icon, 2 reserved, lane field (unused → 0), 24-bit lane mask (unused → FF FF FF)
            p += [0x01, icon, 0x00, 0x00, 0x00, 0xFF, 0xFF, 0xFF, 0x00]
        }
        if minutesToDestination > 0 {
            // tag 3: minutes (16-bit), distance to destination (24-bit), distance to turn (24-bit)
            p += [0x03] + be16(minutesToDestination) + be24(distanceToDestination) + be24(distanceToTurn) + [0x00]
        }
        let text = encodeText(roadName ?? "", maxLength: options.maxTextLength, uppercase: options.uppercase)
        if !text.isEmpty || options.textMode != .plain {
            p += [0x04] + options.textMode.prefix + text + [0x00]
        }
        if p.count > maxDashPayload { p = Array(p.prefix(maxDashPayload)) }
        return includeHeader ? dashHeader + p : p
    }

    /// `includeHeader: false` gives the bare 0xC6 payload (PMP2/Cobo sends it unframed).
    public static func dashPacket(for state: NavigationState, options: DashOptions = DashOptions(),
                                  includeHeader: Bool = true) -> [UInt8] {
        if state.isOffRoute {
            return dashPacket(icon: Icon.recalculating, distanceToTurn: 0, distanceToDestination: 0,
                              minutesToDestination: 0, roadName: "Dang tim duong", options: options,
                              includeHeader: includeHeader)
        }
        let minutes = state.hasArrived ? 1 : max(1, Int((state.remainingDuration / 60).rounded(.up)))
        return dashPacket(icon: state.hasArrived ? Icon.arrive : icon(for: state),
                          distanceToTurn: max(1, Int(state.distanceToManeuver.rounded())),
                          distanceToDestination: max(1, Int(state.remainingDistance.rounded())),
                          minutesToDestination: minutes,
                          roadName: state.streetName,
                          options: options,
                          includeHeader: includeHeader)
    }

    /// The TFT font is ASCII-only: strip Vietnamese diacritics, map đ/Đ, drop anything else.
    public static func encodeText(_ s: String, maxLength: Int, uppercase: Bool = false) -> [UInt8] {
        let source = uppercase ? s.uppercased() : s
        var out: [UInt8] = []
        for scalar in source.decomposedStringWithCanonicalMapping.unicodeScalars {
            if scalar.properties.generalCategory == .nonspacingMark { continue }
            switch scalar {
            case "đ": out.append(UInt8(ascii: "d"))
            case "Đ": out.append(UInt8(ascii: "D"))
            default:
                if scalar.isASCII, scalar.value >= 0x20, scalar.value < 0x7F { out.append(UInt8(scalar.value)) }
            }
            if out.count >= maxLength { break }
        }
        return out
    }

    static func be16(_ v: Int) -> [UInt8] {
        let x = max(0, min(v, 0xFFFF))
        return [UInt8((x >> 8) & 0xFF), UInt8(x & 0xFF)]
    }

    /// 24-bit big-endian; non-positive values mean "unknown" (FF FF FF) like the reference client.
    static func be24(_ v: Int) -> [UInt8] {
        guard v > 0 else { return [0xFF, 0xFF, 0xFF] }
        let x = min(v, 0xFF_FFFE)
        return [UInt8((x >> 16) & 0xFF), UInt8((x >> 8) & 0xFF), UInt8(x & 0xFF)]
    }
}

public enum Hex {
    public static func encode(_ bytes: [UInt8], separator: String = " ") -> String {
        bytes.map { String(format: "%02X", $0) }.joined(separator: separator)
    }

    /// Accepts "0A1B", "0a 1b", "0A-1B"…; nil on odd length or bad digit.
    public static func decode(_ s: String) -> [UInt8]? {
        let digits = s.filter { $0.isHexDigit }
        guard digits.count % 2 == 0,
              digits.count == s.filter({ !" -:,".contains($0) }).count else { return nil }
        var out: [UInt8] = []
        var i = digits.startIndex
        while i < digits.endIndex {
            let j = digits.index(i, offsetBy: 2)
            guard let b = UInt8(digits[i..<j], radix: 16) else { return nil }
            out.append(b)
            i = j
        }
        return out
    }
}

extension UInt32 {
    init(bytesBE bytes: ArraySlice<UInt8>) {
        self = bytes.reduce(0) { ($0 << 8) | UInt32($1) }
    }

    var bigEndianBytes: [UInt8] {
        [UInt8(self >> 24), UInt8((self >> 16) & 0xFF), UInt8((self >> 8) & 0xFF), UInt8(self & 0xFF)]
    }
}
