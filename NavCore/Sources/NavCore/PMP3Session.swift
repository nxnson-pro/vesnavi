import Foundation

/// Persisted per scooter: our client ID and the secret the scooter accepted.
public struct PMP3Pairing: Codable, Equatable, Sendable {
    public var deviceID: [UInt8]
    /// nil until the scooter has asked for a new pairing (`00 00 00 0F`).
    public var key: [UInt8]?

    public init(deviceID: [UInt8], key: [UInt8]? = nil) {
        self.deviceID = deviceID
        self.key = key
    }
}

/// PMP3 handshake state machine, transport-free: feed it notifications from the
/// scooter, write back whatever it returns. Message order and replies mirror the
/// reference client (see docs/pmp3-protocol.md).
public final class PMP3Session {
    public enum Event: Equatable, Sendable {
        case pairingChanged(PMP3Pairing)
        /// Scooter asked for a fresh key — the TFT may ask the rider to confirm.
        case newPairingRequested
        case authResult(success: Bool)
        case authenticated
        /// Dashboard accepts commands (nav packets) when true.
        case ready(Bool)
        case firmware(variant: UInt8, firmware: String, hardware: String)
        case button(UInt8)
        case unhandled([UInt8])
    }

    public struct Output: Equatable, Sendable {
        public var writes: [[UInt8]] = []
        public var events: [Event] = []
    }

    public let phoneName: String
    public private(set) var pairing: PMP3Pairing
    public private(set) var isReady = false
    private let makeKey: () -> [UInt8]
    private var variant: UInt8 = 0
    private var firmware = ""
    private var pendingPairingEvent: Bool

    public init(pairing: PMP3Pairing?,
                phoneName: String,
                millis: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000),
                makeKey: @escaping () -> [UInt8] = { var g = SystemRandomNumberGenerator(); return PMP3.makeKey(using: &g) }) {
        self.pairing = pairing ?? PMP3Pairing(deviceID: PMP3.makeDeviceID(millis: millis))
        self.pendingPairingEvent = pairing == nil
        self.phoneName = phoneName
        self.makeKey = makeKey
    }

    /// First write after notifications are enabled: our 8-byte client ID, unframed.
    public func start() -> Output {
        isReady = false
        var out = Output(writes: [pairing.deviceID])
        if pendingPairingEvent {
            pendingPairingEvent = false
            out.events.append(.pairingChanged(pairing))
        }
        return out
    }

    public func handle(_ b: [UInt8]) -> Output {
        var out = Output()
        func has(_ prefix: [UInt8]) -> Bool { b.starts(with: prefix) }

        if has([0x00, 0x00, 0x00, 0x0E]) {
            // Scooter asks who we are → phone name (ASCII, ≤ 20 bytes).
            out.writes.append([0x00, 0x0E] + PMP3.encodeText(phoneName, maxLength: 20))
        } else if has([0x00, 0x00, 0x00, 0x0F]) {
            // New pairing: hand over a fresh secret.
            if pairing.key == nil {
                pairing.key = makeKey()
                out.events.append(.pairingChanged(pairing))
            }
            out.events.append(.newPairingRequested)
            out.writes.append([0x00, 0x01] + (pairing.key ?? []))
        } else if b.count >= 6, b[0] == 0, b[1] == 0, b[2] != 0, b[3] != 0x0F {
            // Known device: prove we hold the key.
            let key = pairing.key ?? [UInt8](repeating: 0, count: 8)
            out.writes.append([0x00, 0x01] + PMP3.authResponse(key: key, challenge: Array(b[2..<6])))
        } else if has([0x00, 0x01]) {
            let success = b.count > 2 && b[2...].contains { $0 != 0 }
            out.events.append(.authResult(success: success))
        } else if has([0x00, 0x00, 0x00, 0x4F]) {
            out.events.append(.authenticated)
        } else if has([0x45]) {
            isReady = b.count >= 5 && b[4] == 1
            out.events.append(.ready(isReady))
        } else if has([0x00, 0x03]) {
            out.writes.append([0x00, 0x03, 0x41] + [UInt8](repeating: 0, count: 13))
        } else if has([0x40, 0x41]) {
            out.writes.append([0x44, 0x34] + [UInt8](repeating: 0, count: 14))
        } else if has([0x44, 0x34]) {
            variant = b.count > 2 ? b[2] : 0
            out.writes.append([0x44, 0x38])
        } else if has([0x44, 0x38]) {
            firmware = Self.version(b)
            out.writes.append([0x44, 0x39])
        } else if has([0x44, 0x39]) {
            out.events.append(.firmware(variant: variant, firmware: firmware, hardware: Self.version(b)))
        } else if b.count >= 3, b[0] == 0x4B, b[1] == 0x4C || b[1] == 0x53 {
            out.events.append(.button(b[2]))
        } else {
            out.events.append(.unhandled(b))
        }
        return out
    }

    /// Sent once ~3 s after the dashboard reports ready.
    public static let heartbeat: [UInt8] = [0x48, 0x42]

    private static func version(_ b: [UInt8]) -> String {
        b.count >= 4 ? "\(b[2]).\(b[3])" : "?"
    }
}
