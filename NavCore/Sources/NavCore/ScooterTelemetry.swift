import Foundation

/// Live engine/vehicle data streamed by a PMP2 (Cobo) scooter after the 0x28 subscription.
/// `nil` = not received yet (or not part of the frame).
public struct ScooterTelemetry: Equatable, Sendable {
    public var rpm: Int?
    /// Engine coolant, °C.
    public var coolantC: Int?
    /// Intake air, °C.
    public var intakeAirC: Int?
    /// Outside temperature as shown on the TFT, °C.
    public var ambientC: Int?
    public var batteryVolts: Double?
    public var odometerKm: Int?
    public var rearWheelKmh: Double?
    /// Fuel gauge bars; 0x589 start 53, 3 bits.
    /// Read 5 with the tank about 60–70 % full — the scale (0…7?) still needs confirming.
    public var fuelBars: Int?
    /// Speed shown on the TFT speedometer (reads ~10 % above the wheel speed, like any speedo).
    public var dashboardSpeedKmh: Int?
    public var frontWheelKmh: Double?
    /// Throttle opening, 0…100.
    public var throttle: Int?
    public var fuelLitersPerHour: Double?
    /// Traction control (ASR) mode as reported by the ECU.
    public var asrMode: Int?
    /// Engine warning (MIL) lamp state, 0 = off.
    public var milLamp: Int?
    /// Clock shown on the TFT.
    public var clockHour: Int?
    public var clockMinute: Int?

    public init() {}

    public var engineRunning: Bool { (rpm ?? 0) > 300 }
}

/// Decodes the scooter's `C0 80 …` data frames.
///
/// The frame carries one value per signal of `Cobo.structuredSubscribePayload`, in the same order:
/// signals of ≤ 8 bits take one byte, 16-bit signals two bytes (big-endian), followed by the
/// four 2-byte analog inputs requested by the payload trailer. Verified on the owner's GTS 300
/// Supertech (2026-10-29): odometer and outside temperature match the TFT. Scales not marked as
/// verified in `docs/pmp2-cobo-protocol.md` are unconfirmed.
public enum CoboTelemetry {
    struct Signal: Equatable {
        let canID: Int
        let start: Int
        let length: Int
        var byteCount: Int { length > 8 ? 2 : 1 }
    }

    /// Signal list parsed from the subscription payload: 3-byte header, 8-byte entries
    /// `00 00 00 00 idHi idLo start length`, then a trailer of 3-byte analog requests.
    static let signals: [Signal] = {
        let p = Cobo.structuredSubscribePayload
        var out: [Signal] = []
        var i = 3
        while i + 8 <= p.count, p[i] == 0, p[i + 1] == 0, p[i + 2] == 0, p[i + 3] == 0 {
            out.append(Signal(canID: Int(p[i + 4]) << 8 | Int(p[i + 5]), start: Int(p[i + 6]), length: Int(p[i + 7])))
            i += 8
        }
        return out
    }()

    static let analogBytes = 8
    static var frameDataLength: Int { signals.reduce(0) { $0 + $1.byteCount } + analogBytes }

    public static func isDataFrame(_ b: [UInt8]) -> Bool {
        b.count >= 2 && b[0] == 0xC0 && b[1] == 0x80
    }

    /// Updates `base` with every value present in `frame`; nil if it is not a full data frame.
    public static func parse(_ frame: [UInt8], into base: ScooterTelemetry = ScooterTelemetry()) -> ScooterTelemetry? {
        guard isDataFrame(frame), frame.count - 2 >= frameDataLength - analogBytes else { return nil }
        var t = base
        var odo = [0, 0, 0]
        var offset = 2
        for s in signals {
            let raw: Int
            if s.byteCount == 2 {
                raw = Int(frame[offset]) << 8 | Int(frame[offset + 1])
            } else {
                raw = Int(frame[offset])
            }
            offset += s.byteCount
            switch (s.canID, s.start) {
            case (0x590, 48): t.batteryVolts = Double(raw) / 10
            case (0x590, 40): t.ambientC = raw - 20
            case (0x589, 48): t.clockHour = raw
            case (0x589, 40): t.clockMinute = raw
            case (0x589, 16): odo[1] = raw
            case (0x589, 8): odo[2] = raw
            case (0x589, 0): odo[0] = raw
            case (0x180, 24): t.intakeAirC = raw - 40
            case (0x180, 16): t.coolantC = raw - 40
            case (0x260, 24): t.rearWheelKmh = Double(raw) / 100
            case (0x220, 0): t.dashboardSpeedKmh = raw
            case (0x589, 53): t.fuelBars = raw
            case (0x260, 8): t.frontWheelKmh = Double(raw) / 100
            case (0x130, 61): t.asrMode = raw
            case (0x130, 24): t.fuelLitersPerHour = Double(raw) * 0.05
            case (0x100, 42): t.milLamp = raw
            case (0x120, 24): t.throttle = min(100, raw)
            case (0x120, 16): t.rpm = raw
            default: break
            }
        }
        // Odometer bytes: start 16 = low, 8 = mid, 0 = high.
        t.odometerKm = odo[0] * 65536 + odo[2] * 256 + odo[1]
        return t
    }
}
